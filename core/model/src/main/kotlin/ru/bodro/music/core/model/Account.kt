package ru.bodro.music.core.model

/** Учётная запись Navidrome на устройстве. Пароль здесь не хранится. */
data class Account(
    val id: Long,
    val username: String,
    val localUrl: String?,
    val externalUrl: String?,
    val serverVersion: String? = null,
    val openSubsonic: Boolean = false,
) {
    val title: String get() = username
}
