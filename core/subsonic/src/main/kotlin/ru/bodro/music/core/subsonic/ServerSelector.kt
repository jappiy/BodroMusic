package ru.bodro.music.core.subsonic

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.bodro.music.core.model.ServerKind

data class ServerSelection(val baseUrl: HttpUrl, val kind: ServerKind, val reachable: Boolean)

/** Проверка доступности адреса сервера. */
fun interface ServerProbe {
    suspend fun isReachable(baseUrl: HttpUrl, timeoutMs: Long): Boolean
}

/**
 * Автовыбор адреса: сначала локальный с коротким таймаутом, иначе внешний.
 * Если не отвечает ни один — возвращает внешний (или локальный) с `reachable = false`.
 */
class ServerSelector(
    private val probe: ServerProbe,
    private val localTimeoutMs: Long = LOCAL_TIMEOUT_MS,
    private val externalTimeoutMs: Long = EXTERNAL_TIMEOUT_MS,
) {
    suspend fun select(localUrl: String?, externalUrl: String?): ServerSelection? {
        val local = localUrl?.let(ConnectionConfig::normalizeBaseUrl)
        val external = externalUrl?.let(ConnectionConfig::normalizeBaseUrl)
        if (local != null && probe.isReachable(local, localTimeoutMs)) {
            return ServerSelection(local, ServerKind.Local, reachable = true)
        }
        if (external != null && probe.isReachable(external, externalTimeoutMs)) {
            return ServerSelection(external, ServerKind.External, reachable = true)
        }
        return when {
            external != null -> ServerSelection(external, ServerKind.External, reachable = false)
            local != null -> ServerSelection(local, ServerKind.Local, reachable = false)
            else -> null
        }
    }

    companion object {
        const val LOCAL_TIMEOUT_MS = 1_500L
        const val EXTERNAL_TIMEOUT_MS = 8_000L
    }
}

/**
 * Проба через `ping.view` без учётных данных: любой корректный ответ `subsonic-response`
 * (даже с ошибкой авторизации) означает, что сервер доступен. Пароль по сети не уходит.
 */
class PingServerProbe(
    private val http: OkHttpClient,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ServerProbe {
    override suspend fun isReachable(baseUrl: HttpUrl, timeoutMs: Long): Boolean = withContext(dispatcher) {
        val client = http.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val url = baseUrl.newBuilder()
            .addPathSegment("rest")
            .addPathSegment("ping.view")
            .addQueryParameter("v", SubsonicClient.API_VERSION)
            .addQueryParameter("c", SubsonicClient.CLIENT_NAME)
            .addQueryParameter("f", "json")
            .build()
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                response.isSuccessful && response.body.string().contains("subsonic-response")
            }
        } catch (_: Exception) {
            false
        }
    }
}
