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
import ru.bodro.music.core.downloads.DiscoverArtist
import ru.bodro.music.core.downloads.DiscoverResult
import ru.bodro.music.core.downloads.DiscoverTrack
import ru.bodro.music.core.downloads.DownloadRequest
import ru.bodro.music.core.downloads.DownloadsClient
import ru.bodro.music.core.downloads.DownloadsConfig
import ru.bodro.music.core.downloads.DownloadsError
import ru.bodro.music.core.downloads.DownloadsException
import ru.bodro.music.core.downloads.RequestStatus
import ru.bodro.music.core.downloads.ServiceInfo
import ru.bodro.music.data.account.ConnectionManager
import ru.bodro.music.data.account.SessionState
import ru.bodro.music.data.repository.AppJson
import ru.bodro.music.di.AppScope

private val Context.downloadsStore: DataStore<Preferences> by preferencesDataStore(name = "downloads")

/**
 * Сервис загрузок (bodrod): глобальный поиск, заявки на скачивание через slskd/Lidarr.
 * Адрес сервиса хранится отдельно для каждой учётки; авторизация — учёткой Navidrome.
 */
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
        val accountId = connection.activeAccountId ?: return null
        val raw = urls.value[accountId] ?: return null
        val base = DownloadsConfig.normalizeUrl(raw) ?: return null
        val navidrome = connection.current() ?: return null
        return DownloadsConfig(base, navidrome.username, navidrome.password)
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

    suspend fun downloadTrack(track: DiscoverTrack): DownloadRequest = remember(client.downloadTrack(track))

    suspend fun downloadArtist(artist: DiscoverArtist): DownloadRequest = remember(client.downloadArtist(artist))

    /** Активную заявку отменяет, завершённую убирает из списка. */
    suspend fun delete(request: DownloadRequest) {
        client.delete(request.id)
        refresh()
    }

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
