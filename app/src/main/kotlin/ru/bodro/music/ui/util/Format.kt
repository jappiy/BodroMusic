package ru.bodro.music.ui.util

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ru.bodro.music.R
import ru.bodro.music.core.downloads.DownloadsError
import ru.bodro.music.core.downloads.DownloadsException
import ru.bodro.music.core.subsonic.ApiErrorKind
import ru.bodro.music.core.subsonic.NetworkErrorKind
import ru.bodro.music.core.subsonic.SubsonicError
import ru.bodro.music.core.subsonic.SubsonicException
import ru.bodro.music.data.network.CleartextNotAllowedException

/** 245 → «4:05», 3725 → «1:02:05». */
fun formatDuration(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun formatMs(ms: Long): String = formatDuration(ms / 1000)

/** Длительность альбома/плейлиста: «48 мин», «1 ч 12 мин». */
@Composable
fun totalDurationText(seconds: Int): String {
    val minutes = seconds / 60
    val hours = minutes / 60
    return if (hours > 0) {
        pluralStringResource(R.plurals.hours, hours, hours) + " " +
            pluralStringResource(R.plurals.minutes_short, minutes % 60, minutes % 60)
    } else {
        pluralStringResource(R.plurals.minutes_short, minutes, minutes)
    }
}

@Composable
fun tracksText(count: Int): String = pluralStringResource(R.plurals.tracks, count, count)

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f ГБ".format(Locale("ru"), bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f МБ".format(Locale("ru"), bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.1f КБ".format(Locale("ru"), bytes / (1L shl 10).toDouble())
    else -> "$bytes Б"
}

fun formatTime(ms: Long, pattern: String = "dd.MM HH:mm:ss"): String = SimpleDateFormat(pattern, Locale("ru")).format(Date(ms))

/** Понятная пользователю ошибка (текст — из strings.xml). */
data class UiError(@StringRes val message: Int, val details: String? = null, val isOffline: Boolean = false)

fun Throwable.toUiError(): UiError = when (this) {
    is SubsonicException -> error.toUiError()
    is DownloadsException -> error.toUiError()
    is CleartextNotAllowedException -> UiError(R.string.error_cleartext)
    else -> UiError(R.string.error_unknown, message)
}

fun SubsonicError.toUiError(): UiError = when (this) {
    is SubsonicError.Api -> when (kind) {
        ApiErrorKind.WrongCredentials -> UiError(R.string.error_wrong_credentials)
        ApiErrorKind.TokenAuthNotSupported, ApiErrorKind.AuthMechanismNotSupported -> UiError(R.string.error_token_not_supported)
        ApiErrorKind.NotAuthorized -> UiError(R.string.error_not_authorized)
        ApiErrorKind.NotFound -> UiError(R.string.error_not_found)
        ApiErrorKind.MissingParameter -> UiError(R.string.error_missing_parameter, serverMessage)
        ApiErrorKind.ClientTooOld, ApiErrorKind.ServerTooOld -> UiError(R.string.error_version_mismatch, serverMessage)
        else -> UiError(R.string.error_server_generic, serverMessage)
    }
    is SubsonicError.Http -> when (status) {
        401, 403 -> UiError(R.string.error_not_authorized, "HTTP $status")
        404 -> UiError(R.string.error_not_subsonic, "HTTP 404")
        in 500..599 -> UiError(R.string.error_server_down, "HTTP $status")
        else -> UiError(R.string.error_server_generic, "HTTP $status")
    }
    is SubsonicError.Network -> when (kind) {
        NetworkErrorKind.Timeout -> UiError(R.string.error_timeout, details, isOffline = true)
        NetworkErrorKind.Unreachable -> UiError(R.string.error_unreachable, details, isOffline = true)
        NetworkErrorKind.Ssl -> UiError(R.string.error_ssl, details)
        NetworkErrorKind.Other -> UiError(R.string.error_network, details, isOffline = true)
    }
    is SubsonicError.InvalidResponse -> UiError(R.string.error_invalid_response, details)
    SubsonicError.NotConfigured -> UiError(R.string.error_not_configured)
}

fun DownloadsError.toUiError(): UiError = when (this) {
    DownloadsError.NotConfigured -> UiError(R.string.downloads_error_not_configured)
    DownloadsError.Unauthorized -> UiError(R.string.downloads_error_unauthorized)
    is DownloadsError.Disabled -> UiError(R.string.downloads_error_disabled, details)
    is DownloadsError.Network -> UiError(R.string.downloads_error_unreachable, details, isOffline = true)
    is DownloadsError.Http -> UiError(R.string.error_server_generic, "HTTP $status" + (details?.let { ": $it" } ?: ""))
    is DownloadsError.InvalidResponse -> UiError(R.string.error_invalid_response, details)
}

fun UiError.text(context: Context): String = context.getString(message)

@Composable
fun UiError.text(): String = LocalContext.current.getString(message)
