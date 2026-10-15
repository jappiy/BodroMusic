package ru.bodro.music.core.domain.scrobble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrobbleTrackerTest {
    private val tracker = ScrobbleTracker()

    /** Проигрывает [seconds] секунд с тиком 1 с. Возвращает событие отправки, если было. */
    private fun play(fromSec: Long, seconds: Long, startClockMs: Long): List<ScrobbleEvent.Submission> {
        val events = mutableListOf<ScrobbleEvent.Submission>()
        for (i in 1..seconds) {
            tracker.onProgress((fromSec + i) * 1000, isPlaying = true, nowMs = startClockMs + i * 1000)?.let { events += it }
        }
        return events
    }

    @Test
    fun `half of a 3 minute track is enough`() {
        val (_, nowPlaying) = tracker.onTrackStarted("s", durationMs = 180_000, nowMs = 0)
        assertEquals("s", nowPlaying.songId)
        assertTrue(play(0, 89, 0).isEmpty())
        val events = play(89, 1, 89_000)
        assertEquals(listOf(ScrobbleEvent.Submission("s", 0)), events)
    }

    @Test
    fun `four minutes for a long track`() {
        tracker.onTrackStarted("long", durationMs = 20 * 60_000L, nowMs = 1_000)
        assertTrue(play(0, 239, 1_000).isEmpty())
        assertEquals(1, play(239, 1, 240_000).size)
    }

    @Test
    fun `short tracks are never scrobbled`() {
        tracker.onTrackStarted("jingle", durationMs = 30_000, nowMs = 0)
        assertTrue(play(0, 30, 0).isEmpty())
        val session = tracker.finish()!!
        assertFalse(session.submitted)
    }

    @Test
    fun `submission happens only once`() {
        tracker.onTrackStarted("s", durationMs = 60_000, nowMs = 0)
        assertEquals(1, play(0, 60, 0).size)
    }

    @Test
    fun `seeking forward does not count`() {
        tracker.onTrackStarted("s", durationMs = 200_000, nowMs = 0)
        play(0, 10, 0)
        tracker.onSeek(150_000, nowMs = 10_000)
        assertTrue(play(150, 40, 10_000).isEmpty())
        assertEquals(50_000, tracker.listenedMs)
    }

    @Test
    fun `seek jump reported as progress is ignored`() {
        tracker.onTrackStarted("s", durationMs = 200_000, nowMs = 0)
        play(0, 5, 0)
        // Тик пришёл после перемотки без onSeek: позиция прыгнула на 100 с за 1 с.
        assertNull(tracker.onProgress(105_000, isPlaying = true, nowMs = 6_000))
        assertEquals(5_000, tracker.listenedMs)
    }

    @Test
    fun `pause is not counted`() {
        tracker.onTrackStarted("s", durationMs = 100_000, nowMs = 0)
        play(0, 20, 0)
        // 10 минут на паузе.
        tracker.onProgress(20_000, isPlaying = false, nowMs = 620_000)
        assertNull(tracker.onProgress(20_000, isPlaying = false, nowMs = 621_000))
        assertEquals(20_000, tracker.listenedMs)
        val events = play(20, 30, 621_000)
        assertEquals(1, events.size)
        assertEquals(0L, events.single().startedAtMs)
    }

    @Test
    fun `seeking back and replaying counts real listening`() {
        tracker.onTrackStarted("s", durationMs = 100_000, nowMs = 0)
        play(0, 30, 0)
        tracker.onSeek(0, nowMs = 30_000)
        assertNotNull(play(0, 20, 30_000).singleOrNull())
    }

    @Test
    fun `unknown duration uses four minutes`() {
        tracker.onTrackStarted("radio", durationMs = 0, nowMs = 0)
        assertTrue(play(0, 239, 0).isEmpty())
        assertEquals(1, play(239, 1, 239_000).size)
    }

    @Test
    fun `duration known later`() {
        tracker.onTrackStarted("s", durationMs = 0, nowMs = 0)
        tracker.onDurationKnown(60_000)
        assertEquals(1, play(0, 30, 0).size)
    }

    @Test
    fun `early user skip is reported, next track starts new session`() {
        tracker.onTrackStarted("a", durationMs = 200_000, nowMs = 0)
        play(0, 10, 0)
        val (previous, nowPlaying) = tracker.onTrackStarted("b", durationMs = 200_000, nowMs = 10_000, previousSkippedByUser = true)
        assertEquals("a", previous!!.songId)
        assertTrue(previous.skippedEarly)
        assertEquals("b", nowPlaying.songId)
        assertEquals(0, tracker.listenedMs)
    }

    @Test
    fun `late skip is not an early skip`() {
        tracker.onTrackStarted("a", durationMs = 200_000, nowMs = 0)
        play(0, 40, 0)
        assertFalse(tracker.finish(skippedByUser = true)!!.skippedEarly)
    }

    @Test
    fun `faster playback speed still counts`() {
        tracker.onTrackStarted("s", durationMs = 100_000, nowMs = 0)
        var clock = 0L
        var position = 0L
        var submitted = 0
        repeat(40) {
            clock += 1000
            position += 1500
            if (tracker.onProgress(position, true, clock) != null) submitted++
        }
        assertEquals(1, submitted)
    }
}
