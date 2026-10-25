package ru.bodro.music.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Задержка вывода звука для синхронизации фона с тем, что слышно: процессор ([SpectrumAudioProcessor]) получает
 * PCM раньше динамика — на буфер `AudioTrack` и задержку устройства (у Bluetooth 200+ мс).
 *
 * Медиавремя отсчёта, прошедшего через процессор, — позиция на момент сброса (перемотка, старт трека) плюс
 * обработанное с тех пор ([AudioSpectrum.processedUs]); позиция плеера (`currentPosition`) уже учитывает задержку
 * вывода. Разница двух — сколько звука ещё не прозвучало: на столько фон и отстаёт ([AudioSpectrum.outputLatencyMs]).
 * При переходе без паузы (gapless) сброса нет — точка отсчёта сдвигается на длительность прошлого трека.
 */
class OutputLatencyProbe(private val player: Player, private val scope: CoroutineScope) : Player.Listener {
    private var anchorPosMs: Long? = null
    private var anchorFlushes = -1
    private var pendingPosMs: Long? = null
    private var lastDurationMs = C.TIME_UNSET
    private var job: Job? = null

    fun start() {
        player.addListener(this)
        pendingPosMs = player.currentPosition
        job = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                if (AudioSpectrum.isListening && player.isPlaying) measure()
            }
        }
    }

    fun release() {
        job?.cancel()
        player.removeListener(this)
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) return // обработано в onMediaItemTransition
        pendingPosMs = newPosition.positionMs
        anchorPosMs = null
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            // Без сброса процессора — продолжаем отсчёт; со сбросом (другой формат) — новый трек с нуля.
            anchorPosMs = anchorPosMs?.let { if (lastDurationMs != C.TIME_UNSET) it - lastDurationMs else null }
            pendingPosMs = 0
        } else {
            anchorPosMs = null
            pendingPosMs = player.currentPosition
        }
    }

    private fun measure() {
        player.duration.takeIf { it != C.TIME_UNSET }?.let { lastDurationMs = it }
        val flushes = AudioSpectrum.flushes
        if (flushes != anchorFlushes) {
            val pending = pendingPosMs ?: return
            anchorFlushes = flushes
            anchorPosMs = pending
            pendingPosMs = null
            return // в первые полсекунды после сброса буфер ещё заполняется
        }
        val anchor = anchorPosMs ?: return
        val latency = AudioSpectrum.processedUs / 1000 - (player.currentPosition - anchor)
        if (latency in 0..MAX_LATENCY_MS) {
            AudioSpectrum.outputLatencyMs = (AudioSpectrum.outputLatencyMs * (1 - SMOOTHING) + latency * SMOOTHING).toLong()
        }
    }

    private companion object {
        const val TICK_MS = 500L
        const val MAX_LATENCY_MS = 3_000L
        const val SMOOTHING = 0.3
    }
}
