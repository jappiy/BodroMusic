package ru.bodro.music.playback

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import ru.bodro.music.data.log.LogRepository
import ru.bodro.music.data.log.PlayerEvent

/**
 * Перемотка, когда у потока нет карты перемотки (`isCurrentMediaItemSeekable == false`). Так бывает, если плеер
 * открыл трек, не зная длины файла: FLAC без SEEKTABLE, Opus, MP3 без Xing ищут позицию по длине. MediaController
 * в этом состоянии молча игнорирует `seekTo` (команда перемотки недоступна) — поэтому приложение шлёт отдельную
 * команду [COMMAND], а сервис:
 * 1. узнаёт длину файла ([StreamDataSourceFactory.ensureContentLength]) и переоткрывает поток с нужной позиции;
 * 2. если и это не помогло (сервер не отдаёт длину) — докачивает трек в кэш целиком и переоткрывает ещё раз.
 * Каждый шаг — в журнал (`Stream`). Пользователь не остаётся с неработающей перемоткой без объяснения.
 */
@OptIn(UnstableApi::class)
class SeekRescue(
    private val player: ExoPlayer,
    private val sources: StreamDataSourceFactory,
    private val scope: CoroutineScope,
    private val log: LogRepository,
) {
    private var reopenCount = 0

    fun seek(args: Bundle): ListenableFuture<SessionResult> {
        val positionMs = args.getLong(EXTRA_POSITION_MS, C.TIME_UNSET)
        if (positionMs == C.TIME_UNSET) return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
        if (player.isCurrentMediaItemSeekable) {
            player.seekTo(positionMs)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
        val item = player.currentMediaItem ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
        val result = SettableFuture.create<SessionResult>()
        scope.launch {
            log.event(
                PlayerEvent.Stream,
                "Перемотка на ${positionMs / 1000} с недоступна (${item.mediaId}): длина файла, переоткрываем поток",
            )
            var ok = reopen(item, positionMs, full = false)
            if (!ok && player.currentMediaItem?.mediaId == item.mediaId) {
                log.event(PlayerEvent.Stream, "Перемотка всё ещё недоступна (${item.mediaId}): докачиваем трек в кэш целиком")
                ok = reopen(item, positionMs, full = true)
            }
            val message = if (ok) {
                "Перемотка восстановлена (${item.mediaId}), позиция ${positionMs / 1000} с"
            } else {
                "Перемотка не удалась (${item.mediaId})"
            }
            log.event(PlayerEvent.Stream, message, isError = !ok)
            result.set(SessionResult(if (ok) SessionResult.RESULT_SUCCESS else SessionError.ERROR_NOT_SUPPORTED))
        }
        return result
    }

    private suspend fun reopen(item: MediaItem, positionMs: Long, full: Boolean): Boolean {
        val length = sources.ensureContentLength(item.mediaId, full)
        if (player.currentMediaItem?.mediaId != item.mediaId) return false
        val index = player.currentMediaItemIndex
        // Другой URI — ExoPlayer не может «обновить» источник и открывает поток заново (уже с известной длиной).
        // Трек тот же: PlaybackCoordinator не считает это переходом на новый трек.
        val uri = MediaItemFactory.songUri(item.mediaId).buildUpon()
            .appendQueryParameter(MediaItemFactory.REOPEN_PARAM, (++reopenCount).toString())
            .build()
        player.replaceMediaItem(index, item.buildUpon().setUri(uri).build())
        player.seekTo(index, positionMs)
        player.prepare()
        val ready = awaitReady()
        val seekable = ready && player.isCurrentMediaItemSeekable
        log.event(
            PlayerEvent.Stream,
            "Поток ${item.mediaId} переоткрыт${if (full) " после полной загрузки" else ""}: длина " +
                (if (length == C.LENGTH_UNSET.toLong()) "неизвестна" else "${length / 1024} КБ") +
                ", перемотка ${if (seekable) "есть" else "нет"}",
        )
        return seekable
    }

    private suspend fun awaitReady(): Boolean = withTimeoutOrNull(READY_TIMEOUT_MS) {
        if (player.playbackState == Player.STATE_READY) return@withTimeoutOrNull true
        suspendCancellableCoroutine { cont ->
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY && cont.isActive) {
                        player.removeListener(this)
                        cont.resume(true)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (cont.isActive) {
                        player.removeListener(this)
                        cont.resume(false)
                    }
                }
            }
            player.addListener(listener)
            cont.invokeOnCancellation { player.removeListener(listener) }
        }
    } ?: false

    companion object {
        const val ACTION = "ru.bodro.music.SEEK"
        const val EXTRA_POSITION_MS = "positionMs"
        val COMMAND = SessionCommand(ACTION, Bundle.EMPTY)
        private const val READY_TIMEOUT_MS = 15_000L
    }
}
