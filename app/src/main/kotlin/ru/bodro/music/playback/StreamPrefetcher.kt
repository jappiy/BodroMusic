package ru.bodro.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Предзагрузка: пока играет трек, начало следующих [TRACKS] треков очереди скачивается в кэш стрима.
 * Переключение «вперёд» (и листание волны) тогда начинается мгновенно, без ожидания сервера.
 * Работает с плеером в главном потоке, скачивает в фоне; при смене очереди прежняя загрузка отменяется.
 */
@OptIn(UnstableApi::class)
class StreamPrefetcher(
    private val player: Player,
    private val sources: StreamDataSourceFactory,
    private val scope: CoroutineScope,
) : Player.Listener {
    private var job: Job? = null
    private var planned: List<String> = emptyList()

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = schedule()

    override fun onTimelineChanged(timeline: Timeline, reason: Int) = schedule()

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = schedule()

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY) schedule()
    }

    fun release() {
        player.removeListener(this)
        job?.cancel()
    }

    private fun schedule() {
        val upcoming = upcomingIds()
        if (upcoming == planned) return
        planned = upcoming
        job?.cancel()
        if (upcoming.isEmpty()) return
        job = scope.launch {
            delay(START_DELAY_MS) // сначала сеть отдаётся текущему треку
            upcoming.forEach { sources.prefetch(it, BYTES) }
        }
    }

    private fun upcomingIds(): List<String> {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val ids = mutableListOf<String>()
        var index = player.currentMediaItemIndex
        repeat(TRACKS) {
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            if (index == C.INDEX_UNSET) return ids
            ids += player.getMediaItemAt(index).mediaId
        }
        return ids
    }

    private companion object {
        const val TRACKS = 2

        /** ~10 с FLAC или больше минуты в 128 кбит/с — хватает на мгновенный старт. */
        const val BYTES = 1_536L * 1024
        const val START_DELAY_MS = 2_000L
    }
}
