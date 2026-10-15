package ru.bodro.music.core.subsonic

import java.security.MessageDigest
import java.security.SecureRandom

/** Token-аутентификация Subsonic: `t = md5(password + salt)`, новый `s` на каждый запрос. */
object SubsonicAuth {
    private val random = SecureRandom()
    private const val SALT_BYTES = 8

    fun token(password: String, salt: String): String = md5Hex(password + salt)

    fun randomSalt(): String {
        val bytes = ByteArray(SALT_BYTES)
        random.nextBytes(bytes)
        return bytes.toHex()
    }

    fun md5Hex(input: String): String = MessageDigest.getInstance("MD5")
        .digest(input.toByteArray(Charsets.UTF_8))
        .toHex()

    private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }
}
