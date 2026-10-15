package ru.bodro.music.core.model

/** Какой адрес сервера сейчас используется. */
enum class ServerKind { Local, External }

data class ServerInfo(
    val apiVersion: String,
    val type: String? = null,
    val serverVersion: String? = null,
    val openSubsonic: Boolean = false,
    val extensions: List<OpenSubsonicExtension> = emptyList(),
) {
    fun supports(extension: String): Boolean = extensions.any { it.name == extension }
}

data class OpenSubsonicExtension(val name: String, val versions: List<Int>)
