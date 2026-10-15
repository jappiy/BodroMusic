package ru.bodro.music.core.subsonic

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import ru.bodro.music.core.model.ServerKind

/** Параметры подключения: выбранный адрес сервера + учётные данные. */
data class ConnectionConfig(
    val baseUrl: HttpUrl,
    val kind: ServerKind,
    val username: String,
    val password: String,
) {
    override fun toString(): String = "ConnectionConfig(baseUrl=$baseUrl, kind=$kind, username=$username, password=${SecretMasker.MASK})"

    companion object {
        fun of(baseUrl: String, kind: ServerKind, username: String, password: String): ConnectionConfig? =
            normalizeBaseUrl(baseUrl)?.let { ConnectionConfig(it, kind, username, password) }

        /** `nas.local:4533` → `http://nas.local:4533/`, отрезает хвостовой `/rest`. */
        fun normalizeBaseUrl(raw: String): HttpUrl? {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
            val url = withScheme.toHttpUrlOrNull() ?: return null
            val segments = url.pathSegments.filter { it.isNotEmpty() }.let {
                if (it.lastOrNull() == "rest") it.dropLast(1) else it
            }
            val builder = url.newBuilder().encodedPath("/").query(null).fragment(null)
            segments.forEach { builder.addPathSegment(it) }
            if (segments.isNotEmpty()) builder.addPathSegment("") // хвостовой «/»
            return builder.build()
        }
    }
}

/** Источник текущего подключения (активная учётка + выбранный адрес). */
fun interface ConnectionProvider {
    fun current(): ConnectionConfig?
}
