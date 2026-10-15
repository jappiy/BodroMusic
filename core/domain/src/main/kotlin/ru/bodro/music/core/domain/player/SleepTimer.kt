package ru.bodro.music.core.domain.player

/** Варианты таймера сна. */
sealed interface SleepTimerOption {
    data class Minutes(val minutes: Int) : SleepTimerOption

    data object EndOfTrack : SleepTimerOption

    companion object {
        val presets: List<SleepTimerOption> = listOf(Minutes(15), Minutes(30), Minutes(45), Minutes(60), EndOfTrack)
    }
}

/** Состояние таймера сна: либо момент остановки, либо «в конце трека». */
data class SleepTimerState(val option: SleepTimerOption, val startedAtMs: Long) {
    val deadlineMs: Long? = (option as? SleepTimerOption.Minutes)?.let { startedAtMs + it.minutes * 60_000L }

    fun remainingMs(nowMs: Long): Long? = deadlineMs?.let { (it - nowMs).coerceAtLeast(0) }

    fun isExpired(nowMs: Long): Boolean = deadlineMs?.let { nowMs >= it } ?: false
}
