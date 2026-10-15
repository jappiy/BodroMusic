package ru.bodro.music.core.domain.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.bodro.music.core.model.QueueSnapshot
import ru.bodro.music.core.model.Song

class PlayerLogicTest {
    @Test
    fun `quality badges`() {
        val flac = Song("1", "t", suffix = "flac", bitRate = 900, bitDepth = 16, samplingRate = 44100)
        assertEquals(QualityBadge("FLAC 16/44", lossless = true), QualityBadges.of(flac))
        val hires = flac.copy(bitDepth = 24, samplingRate = 96000)
        assertEquals("FLAC 24/96", QualityBadges.of(hires)!!.label)
        assertEquals(QualityBadge("MP3 320", lossless = false), QualityBadges.of(Song("2", "t", suffix = "mp3", bitRate = 320)))
        assertEquals("FLAC", QualityBadges.of(flac.copy(bitDepth = null))!!.label)
        assertNull(QualityBadges.of(Song("3", "t")))
    }

    @Test
    fun `transcoded badge`() {
        val flac = Song("1", "t", suffix = "flac", bitRate = 900)
        val badge = QualityBadges.of(flac, StreamQuality.Kbps192, TranscodeCodec.Opus)!!
        assertEquals("OPUS 192", badge.label)
        assertTrue(badge.transcoded)
        assertFalse(badge.lossless)
        // 128 kbps mp3 при лимите 320 не транскодируется.
        assertEquals("MP3 128", QualityBadges.of(Song("2", "t", suffix = "mp3", bitRate = 128), StreamQuality.Kbps320)!!.label)
    }

    @Test
    fun `stream params`() {
        assertEquals(StreamParams(0, "raw"), StreamParamsResolver.resolve(StreamQuality.Original, TranscodeCodec.Mp3))
        assertEquals(StreamParams(128, "opus"), StreamParamsResolver.resolve(StreamQuality.Kbps128, TranscodeCodec.Opus))
    }

    @Test
    fun `pseudo waveform is stable and bounded`() {
        val a = PseudoWaveform.generate("song-1")
        assertArrayEquals(a, PseudoWaveform.generate("song-1"), 0f)
        assertFalse(a.contentEquals(PseudoWaveform.generate("song-2")))
        assertEquals(PseudoWaveform.DEFAULT_BARS, a.size)
        assertTrue(a.all { it in 0.1f..1f })
        assertEquals(-0x340d631b7bdddcdbL, PseudoWaveform.stableHash(""))
    }

    @Test
    fun `sleep timer`() {
        val timer = SleepTimerState(SleepTimerOption.Minutes(15), startedAtMs = 1_000)
        assertEquals(15 * 60_000L + 1_000, timer.deadlineMs)
        assertEquals(60_000L, timer.remainingMs(14 * 60_000L + 1_000))
        assertTrue(timer.isExpired(16 * 60_000L))
        val endOfTrack = SleepTimerState(SleepTimerOption.EndOfTrack, 0)
        assertNull(endOfTrack.deadlineMs)
        assertFalse(endOfTrack.isExpired(Long.MAX_VALUE))
    }

    @Test
    fun `queue snapshot sanitizing`() {
        val songs = listOf(Song("a", "A", duration = 100), Song("b", "B", duration = 200))
        assertEquals(1, QueueSnapshot(songs, currentIndex = 7, positionMs = 1).sanitized().currentIndex)
        assertEquals(200_000L, QueueSnapshot(songs, currentIndex = 1, positionMs = 999_999).sanitized().positionMs)
        assertEquals(0L, QueueSnapshot(songs, currentIndex = 0, positionMs = -5).sanitized().positionMs)
        assertNull(QueueSnapshot(emptyList(), 3, 10).sanitized().currentSong)
    }
}
