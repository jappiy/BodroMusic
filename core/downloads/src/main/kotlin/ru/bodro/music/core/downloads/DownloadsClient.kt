package ru.bodro.music.core.downloads

import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.bodro.music.core.subsonic.ConnectionConfig
import ru.bodro.music.core.subsonic.SubsonicAuth

/** Адрес сервиса загрузок + учётка Navidrome (сервис проверяет её через Navidrome). */
data class DownloadsConfig(val baseUrl: HttpUrl, val username: String, val password: String) {
    override fun toString(): String = "DownloadsConfig(baseUrl=$baseUrl, username=$username)"

    companion object {
        /** Тот же формат адреса, что и у Navidrome: `nas.local:8790` → `http://nas.local:8790/`. */
        fun normalizeUrl(raw: String): HttpUrl? = ConnectionConfig.normalizeBaseUrl(raw)
    }
}

sealed interface DownloadsError {
    /** Адрес сервиса не задан. */
    data object NotConfigured : DownloadsError

    /** Navidrome не принял учётку. */
    data object Unauthorized : DownloadsError

    /** Действие доступно только администраторам Navidrome. */
    data object Forbidden : DownloadsError

    /** На сервере не настроен slskd / Lidarr. */
    data class Disabled(val details: String?) : DownloadsError

    data class Http(val status: Int, val details: String?) : DownloadsError

    data class Network(val details: String?) : DownloadsError

    data class InvalidResponse(val details: String?) : DownloadsError
}

class DownloadsException(val error: DownloadsError, cause: Throwable? = null) : Exception(error.toString(), cause)

/** Клиент сервиса загрузок bodrod (папка `server/` в репозитории). Один метод на эндпоинт API. */
@Suppress("TooManyFunctions")
class DownloadsClient(
    private val http: OkHttpClient,
    private val config: () -> DownloadsConfig?,
    private val saltGenerator: () -> String = SubsonicAuth::randomSalt,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun info(override: DownloadsConfig? = null): ServiceInfo = get(listOf("api", "v1", "info"), override = override)

    suspend fun discover(query: String): DiscoverResult = get(listOf("api", "v1", "discover"), listOf("q" to query))

    suspend fun artistTopTracks(artist: DiscoverArtist): List<DiscoverTrack> =
        get(listOf("api", "v1", "artists", "top"), listOf("source" to artist.source, "id" to artist.sourceId))

    /** Популярные треки исполнителя из каталога (Яндекс Музыка, запасные — Deezer и Last.fm) с отметкой «есть в библиотеке». */
    suspend fun artistPopular(name: String): ArtistPopular = get(listOf("api", "v1", "artists", "popular"), listOf("name" to name))

    /** Какие треки удалены из библиотеки или заменены другой копией (bodrod 0.4.0+). */
    suspend fun checkLibrary(ids: List<String>): LibraryCheck = post(listOf("api", "v1", "library", "check"), IdsBody(ids))

    suspend fun requests(limit: Int = 100): List<DownloadRequest> =
        get(listOf("api", "v1", "requests"), listOf("limit" to limit.toString()))

    suspend fun downloadTrack(track: DiscoverTrack): DownloadRequest = create(
        CreateRequestBody(
            kind = RequestKind.Track,
            artist = track.artist,
            title = track.title,
            album = track.album,
            durationMs = track.durationMs,
            coverUrl = track.coverUrl,
        ),
    )

    suspend fun downloadArtist(artist: DiscoverArtist): DownloadRequest =
        create(CreateRequestBody(kind = RequestKind.Artist, artist = artist.name, coverUrl = artist.coverUrl))

    /** Альбом целиком по цепочке источников (bodrod 0.7.0+). */
    suspend fun downloadAlbum(artist: String, album: String, coverUrl: String? = null): DownloadRequest =
        create(CreateRequestBody(kind = RequestKind.Album, artist = artist, album = album, coverUrl = coverUrl))

    /** Активную заявку отменяет, завершённую — убирает из списка. */
    suspend fun delete(id: String) {
        execute<Unit?>(Request.Builder().url(url(listOf("api", "v1", "requests", id))).delete(), null) { null }
    }

    // region «На удаление» и чёрный список (только администраторы)

    suspend fun cleanupUsers(): List<CleanupUser> = get(listOf("api", "v1", "cleanup", "users"))

    /** `username = null` — треки, которые не нравятся всем пользователям. */
    suspend fun disliked(username: String?): List<DislikedSong> =
        get(listOf("api", "v1", "cleanup", "disliked"), listOfNotNull(username?.let { "username" to it }))

    /** Удалить файлы с сервера окончательно и занести треки в чёрный список. */
    suspend fun deleteSongs(ids: List<String>): List<DeleteSongResult> {
        val payload = downloadsJson.encodeToString(DeleteSongsBody.serializer(), DeleteSongsBody(ids)).toRequestBody(JSON)
        return execute(Request.Builder().url(url(listOf("api", "v1", "cleanup", "delete"))).post(payload), null) {
            downloadsJson.decodeFromString(serializer<List<DeleteSongResult>>(), it)
        }
    }

    suspend fun blocklist(query: String?, limit: Int = 200, offset: Int = 0): BlocklistPage = get(
        listOf("api", "v1", "blocklist"),
        listOfNotNull(
            query?.takeIf { it.isNotBlank() }?.let { "q" to it },
            "limit" to limit.toString(),
            "offset" to offset.toString(),
        ),
    )

    suspend fun unblock(id: Long) {
        execute<Unit?>(Request.Builder().url(url(listOf("api", "v1", "blocklist", id.toString()))).delete(), null) { null }
    }

    // endregion

    // region Обслуживание библиотеки (только администраторы)

    suspend fun maintenanceStatus(): MaintenanceStatus = get(listOf("api", "v1", "maintenance"))

    suspend fun maintenanceActions(runId: Long?, kind: String?, limit: Int = 200, offset: Int = 0): MaintenanceActionsPage = get(
        listOf("api", "v1", "maintenance", "actions"),
        listOfNotNull(
            runId?.let { "runId" to it.toString() },
            kind?.let { "kind" to it },
            "limit" to limit.toString(),
            "offset" to offset.toString(),
        ),
    )

    suspend fun maintenanceRuns(limit: Int = 20): List<MaintenanceRun> =
        get(listOf("api", "v1", "maintenance", "runs"), listOf("limit" to limit.toString()))

    /** Запустить сейчас; 409 ([DownloadsError.Disabled]) — уже идёт. */
    suspend fun runMaintenance() {
        execute<Unit?>(Request.Builder().url(url(listOf("api", "v1", "maintenance", "run"))).post(EMPTY), null) { null }
    }

    suspend fun restoreFromQuarantine(id: Long) {
        val path = listOf("api", "v1", "maintenance", "quarantine", id.toString(), "restore")
        execute<Unit?>(Request.Builder().url(url(path)).post(EMPTY), null) { null }
    }

    // endregion

    // region Источники загрузки и выгрузка из Яндекса (только администраторы, bodrod 0.7.0+)

    suspend fun sources(): DownloadSources = get(listOf("api", "v1", "sources"))

    /** Новый порядок (все имена), переключатели источников и/или «Яндекс: метаданные»; `null` — не менять. */
    suspend fun updateSources(
        order: List<String>? = null,
        enabled: Map<String, Boolean>? = null,
        yandexMetadata: Boolean? = null,
    ): DownloadSources = send("PUT", listOf("api", "v1", "sources"), SourcesUpdateBody(order, enabled, yandexMetadata))

    suspend fun checkYandex(): DownloadSources = postEmpty(listOf("api", "v1", "sources", "yandex", "check"))

    suspend fun updateYtDlp(): DownloadSources = postEmpty(listOf("api", "v1", "sources", "youtube", "update"))

    suspend fun exportStatus(): ExportStatus = get(listOf("api", "v1", "yandex", "export"))

    suspend fun startExport(likes: Boolean, playlists: Boolean, artists: Boolean): ExportStatus =
        post(listOf("api", "v1", "yandex", "export", "start"), ExportStartBody(likes, playlists, artists))

    suspend fun pauseExport(): ExportStatus = postEmpty(listOf("api", "v1", "yandex", "export", "pause"))

    suspend fun exportArtists(): List<ExportArtist> = get(listOf("api", "v1", "yandex", "export", "artists"))

    suspend fun refreshExportArtists(): List<ExportArtist> = postEmpty(listOf("api", "v1", "yandex", "export", "artists", "refresh"))

    suspend fun selectExportArtists(ids: List<String>): List<ExportArtist> =
        send("PUT", listOf("api", "v1", "yandex", "export", "artists"), ExportSelectBody(ids))

    // endregion

    // region Пул «На пробу» и обратная связь (bodrod 0.9.0+)

    suspend fun feedback(events: List<FeedbackEvent>): FeedbackResult = post(listOf("api", "v1", "feedback"), FeedbackBody(events))

    suspend fun pool(): PoolList = get(listOf("api", "v1", "pool"))

    /** Статус в пуле для треков: в ответе только треки пула. */
    suspend fun poolStatus(ids: List<String>): Map<String, String> = post(listOf("api", "v1", "pool", "status"), IdsBody(ids))

    suspend fun poolHidden(): PoolHidden = get(listOf("api", "v1", "pool", "hidden"))

    suspend fun refillPool() {
        execute<Unit?>(Request.Builder().url(url(listOf("api", "v1", "pool", "refill"))).post(EMPTY), null) { null }
    }

    // endregion

    private suspend inline fun <reified B, reified T> send(method: String, path: List<String>, body: B): T {
        val payload = downloadsJson.encodeToString(serializer<B>(), body).toRequestBody(JSON)
        return execute(Request.Builder().url(url(path)).method(method, payload), null) {
            downloadsJson.decodeFromString(serializer<T>(), it)
        }
    }

    private suspend inline fun <reified T> postEmpty(path: List<String>): T =
        execute(Request.Builder().url(url(path)).post(EMPTY), null) { downloadsJson.decodeFromString(serializer<T>(), it) }

    private suspend inline fun <reified B, reified T> post(path: List<String>, body: B): T {
        val payload = downloadsJson.encodeToString(serializer<B>(), body).toRequestBody(JSON)
        return execute(Request.Builder().url(url(path)).post(payload), null) { downloadsJson.decodeFromString(serializer<T>(), it) }
    }

    private suspend fun create(body: CreateRequestBody): DownloadRequest {
        val payload = downloadsJson.encodeToString(CreateRequestBody.serializer(), body).toRequestBody(JSON)
        return execute(Request.Builder().url(url(listOf("api", "v1", "requests"))).post(payload), null) {
            downloadsJson.decodeFromString(DownloadRequest.serializer(), it)
        }
    }

    private suspend inline fun <reified T> get(
        path: List<String>,
        params: List<Pair<String, String>> = emptyList(),
        override: DownloadsConfig? = null,
    ): T {
        val request = Request.Builder().url(url(path, params, override)).get()
        return execute(request, override) { body -> downloadsJson.decodeFromString(serializer<T>(), body) }
    }

    private fun requireConfig(override: DownloadsConfig?): DownloadsConfig =
        override ?: config() ?: throw DownloadsException(DownloadsError.NotConfigured)

    private fun url(path: List<String>, params: List<Pair<String, String>> = emptyList(), override: DownloadsConfig? = null): HttpUrl {
        val builder = requireConfig(override).baseUrl.newBuilder()
        path.forEach { builder.addPathSegment(it) }
        params.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return builder.build()
    }

    private suspend fun <T> execute(builder: Request.Builder, override: DownloadsConfig?, parse: (String) -> T): T =
        withContext(dispatcher) {
            val cfg = requireConfig(override)
            val salt = saltGenerator()
            val request = builder
                .header("X-Subsonic-User", cfg.username)
                .header("X-Subsonic-Token", SubsonicAuth.token(cfg.password, salt))
                .header("X-Subsonic-Salt", salt)
                .header("Accept", "application/json")
                .build()
            val body = try {
                http.newCall(request).execute().use { response ->
                    val text = response.body.string()
                    if (!response.isSuccessful) throw DownloadsException(errorFor(response.code, detail(text)))
                    text
                }
            } catch (e: DownloadsException) {
                throw e
            } catch (e: IOException) {
                throw DownloadsException(DownloadsError.Network(e.message), e)
            }
            try {
                parse(body)
            } catch (e: IllegalArgumentException) {
                throw DownloadsException(DownloadsError.InvalidResponse(e.message), e)
            }
        }

    private fun errorFor(code: Int, details: String?): DownloadsError = when (code) {
        401 -> DownloadsError.Unauthorized
        403 -> DownloadsError.Forbidden
        409 -> DownloadsError.Disabled(details)
        else -> DownloadsError.Http(code, details)
    }

    /** FastAPI отдаёт ошибки как `{"detail": "..."}`. */
    private fun detail(body: String): String? = runCatching {
        downloadsJson.decodeFromString(ErrorBody.serializer(), body).detail
    }.getOrNull() ?: body.take(MAX_DETAIL).ifBlank { null }

    @Serializable
    private data class ErrorBody(val detail: String? = null)

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val EMPTY = ByteArray(0).toRequestBody(null)
        const val MAX_DETAIL = 200
    }
}

internal val downloadsJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}
