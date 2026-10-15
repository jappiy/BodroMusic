package ru.bodro.music.core.subsonic

/**
 * Маскирует секреты Subsonic (`p`, `t`, `s`, пароль) в URL, заголовках и произвольном тексте.
 * Используется для логов и их экспорта — секреты никогда не должны попадать в журнал.
 */
object SecretMasker {
    const val MASK = "***"

    private val secretParams = setOf("p", "t", "s", "password", "token", "salt", "apiKey")

    /** `?u=x&t=abc&s=def` → `?u=x&t=***&s=***`. Работает и для URL, и для строк query/form. */
    private val queryParam = Regex("""(?<=[?&;]|^)(${secretParams.joinToString("|")})=([^&#\s]*)""")

    private val authHeader = Regex("""(?i)^(authorization|cookie|set-cookie|x-nd-authorization)$""")

    private val jsonSecret = Regex(""""(password|token|salt)"\s*:\s*"[^"]*"""")

    fun maskUrl(url: String): String = queryParam.replace(url) { "${it.groupValues[1]}=$MASK" }

    fun maskHeader(name: String, value: String): String = if (authHeader.matches(name)) MASK else maskUrl(value)

    /** Маскирует секреты в произвольном тексте (URL внутри текста, JSON-поля). */
    fun maskText(text: String): String {
        val urlMasked = Regex("""https?://\S+""").replace(text) { maskUrl(it.value) }
        val queryMasked = Regex("""(?<=[?&])(${secretParams.joinToString("|")})=([^&#\s"]*)""")
            .replace(urlMasked) { "${it.groupValues[1]}=$MASK" }
        return jsonSecret.replace(queryMasked) { """"${it.groupValues[1]}":"$MASK"""" }
    }

    /** Дополнительно вычищает конкретное значение пароля, если оно встретилось как есть. */
    fun maskText(text: String, password: String?): String {
        val masked = maskText(text)
        return if (password.isNullOrEmpty() || password.length < 3) masked else masked.replace(password, MASK)
    }
}
