package ru.bodro.music.core.subsonic

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import ru.bodro.music.core.model.ServerKind

/** Тип запроса для фильтров экрана логов. */
enum class RequestCategory { Api, Stream, Cover, Scrobble }

enum class NetworkLogLevel { ErrorsOnly, Normal, Verbose }

/** Запись журнала обмена с Navidrome. Все секреты уже замаскированы. */
data class NetworkLogEntry(
    val timestampMs: Long,
    val category: RequestCategory,
    val endpoint: String,
    val serverKind: ServerKind?,
    val method: String,
    val maskedUrl: String,
    val httpCode: Int?,
    val subsonicStatus: String?,
    val errorCode: Int?,
    val errorMessage: String?,
    val durationMs: Long,
    val responseSize: Long?,
    val requestHeaders: String?,
    val responseHeaders: String?,
    val responseBody: String?,
) {
    val isError: Boolean
        get() = httpCode == null || httpCode !in 200..399 || subsonicStatus == "failed" || errorMessage != null
}

fun interface NetworkLogSink {
    fun log(entry: NetworkLogEntry)
}

/**
 * Перехватчик OkHttp: пишет каждый запрос к серверу в журнал. Тело ответа не читается целиком —
 * только `peekBody` до [MAX_BODY_BYTES], поэтому стримы и обложки не задерживаются.
 */
class NavidromeLogInterceptor(
    private val sink: NetworkLogSink,
    private val level: () -> NetworkLogLevel,
    private val serverKindOf: (HttpUrl) -> ServerKind?,
    private val clock: () -> Long = System::currentTimeMillis,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val endpoint = endpointOf(url)
        val category = categoryOf(endpoint)
        val started = clock()
        val startedNs = System.nanoTime()
        val currentLevel = level()
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            sink.log(
                NetworkLogEntry(
                    timestampMs = started,
                    category = category,
                    endpoint = endpoint,
                    serverKind = serverKindOf(url),
                    method = request.method,
                    maskedUrl = SecretMasker.maskUrl(url.toString()),
                    httpCode = null,
                    subsonicStatus = null,
                    errorCode = null,
                    errorMessage = "${e.javaClass.simpleName}: ${e.message.orEmpty()}",
                    durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs),
                    responseSize = null,
                    requestHeaders = request.headers.masked(),
                    responseHeaders = null,
                    responseBody = null,
                ),
            )
            throw e
        }
        val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs)
        val contentType = response.header("Content-Type").orEmpty()
        val isJson = contentType.contains("json", ignoreCase = true)
        val peek = if (isJson) runCatching { response.peekBody(MAX_BODY_BYTES).string() }.getOrNull() else null
        val status = peek?.let { statusRegex.find(it)?.groupValues?.get(1) }
        val errorCode = peek?.takeIf { status == "failed" }?.let { errorCodeRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val errorMessage = peek?.takeIf { status == "failed" }?.let { errorMessageRegex.find(it)?.groupValues?.get(1) }
            ?: if (!response.isSuccessful) "HTTP ${response.code} ${response.message}".trim() else null
        val entry = NetworkLogEntry(
            timestampMs = started,
            category = category,
            endpoint = endpoint,
            serverKind = serverKindOf(url),
            method = request.method,
            maskedUrl = SecretMasker.maskUrl(url.toString()),
            httpCode = response.code,
            subsonicStatus = status,
            errorCode = errorCode,
            errorMessage = errorMessage,
            durationMs = durationMs,
            responseSize = response.body.contentLength().takeIf { it >= 0 } ?: peek?.length?.toLong(),
            requestHeaders = request.headers.masked(),
            responseHeaders = response.headers.masked(),
            responseBody = if (currentLevel == NetworkLogLevel.Verbose) peek?.let { SecretMasker.maskText(it) } else null,
        )
        if (currentLevel != NetworkLogLevel.ErrorsOnly || entry.isError) sink.log(entry)
        return response
    }

    private fun Headers.masked(): String = joinToString("\n") { (name, value) -> "$name: ${SecretMasker.maskHeader(name, value)}" }

    companion object {
        const val MAX_BODY_BYTES = 64L * 1024

        private val statusRegex = Regex(""""status"\s*:\s*"(\w+)"""")
        private val errorCodeRegex = Regex(""""error"\s*:\s*\{[^}]*?"code"\s*:\s*(\d+)""")
        private val errorMessageRegex = Regex(""""error"\s*:\s*\{[^}]*?"message"\s*:\s*"((?:[^"\\]|\\.)*)"""")

        fun endpointOf(url: HttpUrl): String = url.pathSegments.lastOrNull { it.isNotEmpty() }
            ?.removeSuffix(".view")
            ?: url.encodedPath

        fun categoryOf(endpoint: String): RequestCategory = when (endpoint) {
            "stream", "download" -> RequestCategory.Stream
            "getCoverArt" -> RequestCategory.Cover
            "scrobble" -> RequestCategory.Scrobble
            else -> RequestCategory.Api
        }
    }
}
