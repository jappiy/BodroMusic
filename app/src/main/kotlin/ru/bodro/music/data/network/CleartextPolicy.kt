package ru.bodro.music.data.network

import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * HTTP без шифрования разрешён только для приватных адресов (домашняя сеть).
 * Network Security Config не умеет диапазоны IP, поэтому правило применяется здесь.
 */
object CleartextPolicy {
    private val privateSuffixes = listOf(".local", ".lan", ".home", ".home.arpa", ".internal", ".localdomain")

    fun isAllowed(url: HttpUrl): Boolean = url.isHttps || isPrivateHost(url.host)

    fun isPrivateHost(host: String): Boolean {
        val lower = host.lowercase().trimEnd('.')
        if (lower == "localhost" || privateSuffixes.any { lower.endsWith(it) }) return true
        if (!lower.contains('.') && !lower.contains(':')) return true // «nas», «synology» — имя в домашней сети
        val address = literalAddress(lower) ?: return false
        return isPrivateAddress(address)
    }

    fun isPrivateAddress(address: InetAddress): Boolean = when (address) {
        is Inet4Address -> address.isSiteLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || isCgnat(address)
        is Inet6Address ->
            address.isLoopbackAddress ||
                address.isLinkLocalAddress ||
                address.isSiteLocalAddress ||
                (address.address[0].toInt() and 0xfe) == 0xfc // fc00::/7 ULA
        else -> false
    }

    /** 100.64.0.0/10 — Tailscale / CGNAT. */
    private fun isCgnat(address: Inet4Address): Boolean {
        val bytes = address.address
        return (bytes[0].toInt() and 0xff) == 100 && (bytes[1].toInt() and 0xc0) == 64
    }

    /** Разбирает только IP-литерал (без DNS-запроса). */
    private fun literalAddress(host: String): InetAddress? {
        val ipv4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
        val isLiteral = ipv4.matches(host) || host.contains(':')
        return if (isLiteral) runCatching { InetAddress.getByName(host.trim('[', ']')) }.getOrNull() else null
    }
}

class CleartextNotAllowedException(host: String) :
    IOException("HTTP без шифрования разрешён только для адресов домашней сети: $host")

class CleartextPolicyInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (!CleartextPolicy.isAllowed(url)) throw CleartextNotAllowedException(url.host)
        return chain.proceed(chain.request())
    }
}
