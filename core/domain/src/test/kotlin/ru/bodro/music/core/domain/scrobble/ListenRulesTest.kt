package ru.bodro.music.core.domain.scrobble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ListenRulesTest {
    @Test
    fun `early skip - under 30 s or under 20 percent, only when user skipped`() {
        assertEquals(PlayOutcome.EarlySkip, ListenRules.outcome(10_000, 200_000, userSkipped = true))
        // 10-минутный трек: 1 мин — меньше 20 %, всё ещё ранний пропуск.
        assertEquals(PlayOutcome.EarlySkip, ListenRules.outcome(60_000, 600_000, userSkipped = true))
        // Не переключал (пауза, конец очереди) — нейтрально.
        assertEquals(PlayOutcome.Partial, ListenRules.outcome(10_000, 200_000, userSkipped = false))
    }

    @Test
    fun `partial - between 20 and 50 percent is neutral`() {
        assertEquals(PlayOutcome.Partial, ListenRules.outcome(60_000, 200_000, userSkipped = true))
        assertEquals(PlayOutcome.Partial, ListenRules.outcome(99_000, 200_000, userSkipped = true))
    }

    @Test
    fun `full - at least half or 4 minutes`() {
        assertEquals(PlayOutcome.Full, ListenRules.outcome(100_000, 200_000, userSkipped = true))
        assertEquals(PlayOutcome.Full, ListenRules.outcome(240_000, 900_000, userSkipped = true))
        // Длительность неизвестна: 4 минуты — полное, меньше — частичное (или ранний пропуск до 30 с).
        assertEquals(PlayOutcome.Full, ListenRules.outcome(240_000, 0, userSkipped = false))
        assertEquals(PlayOutcome.Partial, ListenRules.outcome(100_000, 0, userSkipped = true))
        assertEquals(PlayOutcome.EarlySkip, ListenRules.outcome(5_000, 0, userSkipped = true))
    }

    @Test
    fun `scrobble policy stays Last fm standard`() {
        val policy = ScrobblePolicy()
        assertFalse(policy.isEligible(30_000))
        assertEquals(100_000L, policy.thresholdMs(200_000))
        assertEquals(240_000L, policy.thresholdMs(900_000))
    }
}
