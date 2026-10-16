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

/** Клиент сервиса загрузок bodrod (папка `server/` в репозитории). */
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
        const val MAX_DETAIL = 200
    }
}

internal val downloadsJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}
