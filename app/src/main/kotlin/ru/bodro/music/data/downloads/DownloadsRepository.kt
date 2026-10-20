package ru.bodro.music.data.downloads

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.OkHttpClient
import ru.bodro.music.core.downloads.AnalysisStatus
import ru.bodro.music.core.downloads.ArtistPopular
import ru.bodro.music.core.downloads.BlocklistPage
import ru.bodro.music.core.downloads.Chart
import ru.bodro.music.core.downloads.ChartEntry
import ru.bodro.music.core.downloads.CleanupUser
import ru.bodro.music.core.downloads.DeleteSongResult
import ru.bodro.music.core.downloads.DiscoverArtist
import ru.bodro.music.core.downloads.DiscoverResult
import ru.bodro.music.core.downloads.DiscoverTrack
import ru.bodro.music.core.downloads.DislikedSong
import ru.bodro.music.core.downloads.DownloadRequest
import ru.bodro.music.core.downloads.DownloadSources
import ru.bodro.music.core.downloads.DownloadsClient
import ru.bodro.music.core.downloads.DownloadsConfig
import ru.bodro.music.core.downloads.DownloadsError
import ru.bodro.music.core.downloads.DownloadsException
import ru.bodro.music.core.downloads.EngineStatus
import ru.bodro.music.core.downloads.ExportArtist
import ru.bodro.music.core.downloads.ExportStatus
import ru.bodro.music.core.downloads.FeedbackEvent
import ru.bodro.music.core.downloads.FeedbackResult
import ru.bodro.music.core.downloads.HarvestStatus
import ru.bodro.music.core.downloads.LibraryCheck
import ru.bodro.music.core.downloads.MaintenanceActionsPage
import ru.bodro.music.core.downloads.MaintenanceStatus
import ru.bodro.music.core.downloads.PoolHidden
import ru.bodro.music.core.downloads.PoolList
import ru.bodro.music.core.downloads.RequestStatus
import ru.bodro.music.core.downloads.ServiceInfo
import ru.bodro.music.core.downloads.TrackFeatures
import ru.bodro.music.data.account.ConnectionManager
import ru.bodro.music.data.account.SessionState
import ru.bodro.music.data.repository.AppJson
import ru.bodro.music.di.AppScope

private val Context.downloadsStore: DataStore<Preferences> by preferencesDataStore(name = "downloads")

/**
 * Сервис загрузок (bodrod): глобальный поиск, заявки на скачивание по цепочке источников.
 * Адрес сервиса хранится отдельно для каждой учётки; авторизация — учёткой Navidrome.
 */
@Suppress("TooManyFunctions")
@Singleton
class DownloadsRepository @Inject constructor(
    @ApplicationContext context: Context,
    http: OkHttpClient,
    private val connection: ConnectionManager,
    private val scheduler: DownloadWatchScheduler,
    @AppScope scope: CoroutineScope,
) {
    private val store = context.downloadsStore

    private val urls: StateFlow<Map<Long, String>> = store.data
        .map { it.urls() }
        .stateIn(scope, SharingStarted.Eagerly, runBlocking { store.data.first().urls() })

    private val client = DownloadsClient(http, ::config)

    private val _requests = MutableStateFlow<List<DownloadRequest>>(emptyList())

    /** Последний известный список заявок активной учётки. */
    val requests: StateFlow<List<DownloadRequest>> = _requests.asStateFlow()

    /** Адрес сервиса для активной учётки (`null` — не настроен). */
    val serviceUrl: StateFlow<String?> = combine(connection.session, urls) { session, map ->
        (session as? SessionState.Active)?.account?.id?.let { map[it] }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    val isConfigured: Boolean get() = config() != null

    private fun config(): DownloadsConfig? {
        val base = connection.activeAccountId
            ?.let { urls.value[it] }
            ?.let(DownloadsConfig::normalizeUrl)
        val navidrome = connection.current()
        return if (base != null && navidrome != null) DownloadsConfig(base, navidrome.username, navidrome.password) else null
    }

    /** Проверяет адрес (не сохраняя его): сервис отвечает и принимает учётку. */
    suspend fun check(rawUrl: String): ServiceInfo {
        val base = DownloadsConfig.normalizeUrl(rawUrl) ?: throw DownloadsException(DownloadsError.NotConfigured)
        val navidrome = connection.current() ?: throw DownloadsException(DownloadsError.NotConfigured)
        return client.info(DownloadsConfig(base, navidrome.username, navidrome.password))
    }

    suspend fun setServiceUrl(rawUrl: String?) {
        val accountId = connection.activeAccountId ?: return
        val normalized = rawUrl?.let(DownloadsConfig::normalizeUrl)?.toString()
        store.edit { prefs ->
            val key = urlKey(accountId)
            if (normalized == null) prefs.remove(key) else prefs[key] = normalized
        }
        _requests.value = emptyList()
        if (normalized != null) scheduler.watch()
    }

    suspend fun info(): ServiceInfo = client.info()

    suspend fun discover(query: String): DiscoverResult = client.discover(query)

    suspend fun artistTopTracks(artist: DiscoverArtist): List<DiscoverTrack> = client.artistTopTracks(artist)

    suspend fun artistPopular(name: String): ArtistPopular = client.artistPopular(name)

    suspend fun checkLibrary(ids: List<String>): LibraryCheck = client.checkLibrary(ids)

    suspend fun downloadTrack(track: DiscoverTrack, chartId: String? = null, chartPosition: Int? = null): DownloadRequest =
        remember(client.downloadTrack(track, chartId, chartPosition))

    suspend fun downloadArtist(artist: DiscoverArtist): DownloadRequest = remember(client.downloadArtist(artist))

    /** Активную заявку отменяет, завершённую убирает из списка. */
    suspend fun delete(request: DownloadRequest) {
        client.delete(request.id)
        refresh()
    }

    // region «На удаление» и чёрный список

    suspend fun cleanupUsers(): List<CleanupUser> = client.cleanupUsers()

    suspend fun disliked(username: String?): List<DislikedSong> = client.disliked(username)

    suspend fun deleteSongs(ids: List<String>): List<DeleteSongResult> = client.deleteSongs(ids)

    suspend fun blocklist(query: String?, limit: Int, offset: Int): BlocklistPage = client.blocklist(query, limit, offset)

    suspend fun unblock(id: Long) = client.unblock(id)

    // endregion

    // region Обслуживание библиотеки

    suspend fun maintenanceStatus(): MaintenanceStatus = client.maintenanceStatus()

    suspend fun maintenanceActions(runId: Long?, kind: String?, limit: Int, offset: Int): MaintenanceActionsPage =
        client.maintenanceActions(runId, kind, limit, offset)

    suspend fun runMaintenance() = client.runMaintenance()

    suspend fun restoreFromQuarantine(id: Long) = client.restoreFromQuarantine(id)

    // endregion

    // region Источники загрузки и выгрузка из Яндекса

    suspend fun sources(): DownloadSources = client.sources()

    suspend fun updateSources(
        order: List<String>? = null,
        enabled: Map<String, Boolean>? = null,
        yandexMetadata: Boolean? = null,
    ): DownloadSources = client.updateSources(order, enabled, yandexMetadata)

    suspend fun checkYandex(): DownloadSources = client.checkYandex()

    suspend fun updateYtDlp(): DownloadSources = client.updateYtDlp()

    suspend fun exportStatus(): ExportStatus = client.exportStatus()

    suspend fun startExport(likes: Boolean, playlists: Boolean, artists: Boolean): ExportStatus =
        client.startExport(likes, playlists, artists)

    suspend fun pauseExport(): ExportStatus = client.pauseExport()

    suspend fun exportArtists(): List<ExportArtist> = client.exportArtists()

    suspend fun refreshExportArtists(): List<ExportArtist> = client.refreshExportArtists()

    suspend fun selectExportArtists(ids: List<String>): List<ExportArtist> = client.selectExportArtists(ids)

    // endregion

    // region Пул «На пробу» и обратная связь

    suspend fun feedback(events: List<FeedbackEvent>): FeedbackResult = client.feedback(events)

    suspend fun pool(): PoolList = client.pool()

    suspend fun poolHidden(): PoolHidden = client.poolHidden()

    suspend fun refillPool() = client.refillPool()

    suspend fun trackFeatures(ids: List<String>): Map<String, TrackFeatures> = client.trackFeatures(ids)

    suspend fun similarBySound(songId: String): List<String> = client.similarBySound(songId).ids

    suspend fun analysisStatus(): AnalysisStatus = client.analysisStatus()

    suspend fun charts(): List<Chart> = client.charts()

    suspend fun chart(id: String): List<ChartEntry> = client.chart(id)

    suspend fun engine(): EngineStatus = client.engine()

    suspend fun updateEngine(
        enabled: Map<String, Boolean>? = null,
        weights: Map<String, Float>? = null,
        newArtistShare: Float? = null,
    ): EngineStatus = client.updateEngine(enabled, weights, newArtistShare)

    suspend fun harvest(): HarvestStatus = client.harvest()

    suspend fun runHarvest(): HarvestStatus = client.runHarvest()

    // endregion

    suspend fun refresh(): List<DownloadRequest> = client.requests().also { _requests.value = it }

    /**
     * Список заявок с автообновлением: часто, пока есть активные, иначе редко.
     * Ошибки сети не прерывают поток — остаётся последний известный список.
     */
    fun observe(): Flow<List<DownloadRequest>> = flow {
        while (true) {
            val list = runCatching { refresh() }.getOrElse { _requests.value }
            emit(list)
            delay(if (list.any { it.status.isActive }) ACTIVE_POLL_MS else IDLE_POLL_MS)
        }
    }

    private suspend fun remember(request: DownloadRequest): DownloadRequest {
        _requests.value = listOf(request) + _requests.value.filterNot { it.id == request.id }
        // Заявка попадает в снимок статусов сразу: так уведомление придёт, даже если она завершится до опроса.
        val known = knownStatuses().orEmpty()
        if (request.id !in known) saveKnownStatuses(known + (request.id to request.status))
        scheduler.watch()
        return request
    }

    // region Снимок статусов для уведомлений (на учётку)

    suspend fun knownStatuses(): Map<String, RequestStatus>? {
        val accountId = connection.activeAccountId ?: return null
        val raw = store.data.first()[knownKey(accountId)] ?: return null
        return runCatching { AppJson.json.decodeFromString(knownSerializer, raw) }.getOrNull()
    }

    suspend fun saveKnownStatuses(statuses: Map<String, RequestStatus>) {
        val accountId = connection.activeAccountId ?: return
        store.edit { it[knownKey(accountId)] = AppJson.json.encodeToString(knownSerializer, statuses) }
    }

    // endregion

    private fun Preferences.urls(): Map<Long, String> = asMap().mapNotNull { (key, value) ->
        key.name.removePrefix(URL_PREFIX).takeIf { key.name.startsWith(URL_PREFIX) }?.toLongOrNull()?.let { id ->
            (value as? String)?.let { id to it }
        }
    }.toMap()

    private fun urlKey(accountId: Long) = stringPreferencesKey("$URL_PREFIX$accountId")

    private fun knownKey(accountId: Long) = stringPreferencesKey("known_$accountId")

    private companion object {
        const val URL_PREFIX = "url_"
        const val ACTIVE_POLL_MS = 4_000L
        const val IDLE_POLL_MS = 30_000L
        val knownSerializer = MapSerializer(String.serializer(), RequestStatus.serializer())
    }
}
