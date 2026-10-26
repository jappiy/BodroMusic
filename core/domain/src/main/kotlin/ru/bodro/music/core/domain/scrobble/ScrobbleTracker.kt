package ru.bodro.music.core.domain.scrobble

/** Правила засчитывания прослушивания (как у Last.fm / ListenBrainz). */
data class ScrobblePolicy(
    /** Треки короче или равные этому не скробблятся. */
    val minTrackDurationMs: Long = 30_000,
    /** Доля длительности, после которой прослушивание засчитывается. */
    val fraction: Double = 0.5,
    /** Абсолютный порог: 4 минуты, даже если это меньше половины трека. */
    val maxThresholdMs: Long = 240_000,
    /** Устарело: ранний пропуск теперь по [ListenRules] (30 с или 20 % трека). */
    val earlySkipMs: Long = ListenRules.EARLY_SKIP_MS,
) {
    fun isEligible(durationMs: Long): Boolean = durationMs <= 0 || durationMs > minTrackDurationMs

    fun thresholdMs(durationMs: Long): Long =
        if (durationMs <= 0) maxThresholdMs else minOf((durationMs * fraction).toLong(), maxThresholdMs)
}

/** Итог проигрывания одного трека (для локальной истории и статистики пропусков). */
data class PlaySession(
    val songId: String,
    val startedAtMs: Long,
    val listenedMs: Long,
    val durationMs: Long,
    val submitted: Boolean,
    /** Итог для рекомендаций ([ListenRules]). */
    val outcome: PlayOutcome = PlayOutcome.Partial,
) {
    /** Ранний пропуск — слабый негативный сигнал для волны. */
    val skippedEarly: Boolean get() = outcome == PlayOutcome.EarlySkip
}

sealed interface ScrobbleEvent {
    data class NowPlaying(val songId: String) : ScrobbleEvent

    data class Submission(val songId: String, val startedAtMs: Long) : ScrobbleEvent
}

/**
 * Считает реально прослушанное время трека. Перемотка вперёд не засчитывается, пауза не считается,
 * повтор трека — новая сессия. Не зависит от Android: позиции и время передаются снаружи.
 */
class ScrobbleTracker(private val policy: ScrobblePolicy = ScrobblePolicy()) {
    private var songId: String? = null
    private var durationMs: Long = 0
    private var startedAtMs: Long = 0
    private var lastPositionMs: Long? = null
    private var lastTickMs: Long = 0
    private var submitted = false

    var listenedMs: Long = 0
        private set

    val currentSongId: String? get() = songId

    /** Новый трек начал играть. Возвращает итог предыдущего и событие «сейчас играет». */
    fun onTrackStarted(
        songId: String,
        durationMs: Long,
        nowMs: Long,
        previousSkippedByUser: Boolean = false,
    ): Pair<PlaySession?, ScrobbleEvent.NowPlaying> {
        val previous = finish(previousSkippedByUser)
        this.songId = songId
        this.durationMs = durationMs
        startedAtMs = nowMs
        lastPositionMs = 0
        lastTickMs = nowMs
        listenedMs = 0
        submitted = false
        return previous to ScrobbleEvent.NowPlaying(songId)
    }

    /** Длительность стала известна позже (после подготовки плеера). */
    fun onDurationKnown(durationMs: Long) {
        if (durationMs > 0) this.durationMs = durationMs
    }

    /**
     * Периодический тик (раз в ~1 с). Возвращает [ScrobbleEvent.Submission] ровно один раз за сессию,
     * когда набран порог.
     */
    fun onProgress(positionMs: Long, isPlaying: Boolean, nowMs: Long): ScrobbleEvent.Submission? {
        val id = songId ?: return null
        val last = lastPositionMs
        if (isPlaying && last != null) {
            val delta = positionMs - last
            val elapsed = (nowMs - lastTickMs).coerceAtLeast(0)
            if (delta > 0 && delta <= elapsed * MAX_PLAYBACK_SPEED + TOLERANCE_MS) {
                listenedMs += delta
            }
        }
        lastPositionMs = positionMs
        lastTickMs = nowMs
        if (!submitted && policy.isEligible(durationMs) && listenedMs >= policy.thresholdMs(durationMs)) {
            submitted = true
            return ScrobbleEvent.Submission(id, startedAtMs)
        }
        return null
    }

    /** Пользователь перемотал: новая точка отсчёта, перемотка не засчитывается. */
    fun onSeek(positionMs: Long, nowMs: Long) {
        lastPositionMs = positionMs
        lastTickMs = nowMs
    }

    /** Завершает текущую сессию (смена трека, стоп). */
    fun finish(skippedByUser: Boolean = false): PlaySession? {
        val id = songId ?: return null
        val session = PlaySession(
            songId = id,
            startedAtMs = startedAtMs,
            listenedMs = listenedMs,
            durationMs = durationMs,
            submitted = submitted,
            outcome = ListenRules.outcome(listenedMs, durationMs, skippedByUser),
        )
        songId = null
        lastPositionMs = null
        return session
    }

    private companion object {
        const val MAX_PLAYBACK_SPEED = 2.0
        const val TOLERANCE_MS = 1_500
    }
}
