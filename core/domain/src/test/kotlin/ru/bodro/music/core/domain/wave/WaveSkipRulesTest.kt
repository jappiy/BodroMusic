package ru.bodro.music.core.domain.wave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.bodro.music.core.domain.scrobble.PlayContext
import ru.bodro.music.core.domain.scrobble.PlayOutcome

class WaveSkipRulesTest {
    private val song = library.first { it.artistId == "ar-3" }
    private val engine = WaveEngine(emptyList())

    private fun skip(at: Long, context: PlayContext = PlayContext.Wave) =
        PlayHistoryItem(song.id, song.artistId, song.genre, at, skippedEarly = true, context = context)

    @Test
    fun `skipped track returns to the wave after cooldown`() {
        val profile = WaveProfileBuilder.build(emptyList(), emptyList(), listOf(skip(NOW - DAY)), NOW, skipCooldownMs = 7 * DAY)
        assertFalse(engine.isAllowed(song, request(profile = profile)))
        assertTrue(engine.isAllowed(song, request(profile = profile).copy(nowMs = NOW + 7 * DAY)))
    }

    @Test
    fun `weight drops only after three early skips without a full play`() {
        val twice = WaveProfileBuilder.build(emptyList(), emptyList(), listOf(skip(NOW - 3 * DAY), skip(NOW - 2 * DAY)), NOW)
        val three = listOf(skip(NOW - 3 * DAY), skip(NOW - 2 * DAY), skip(NOW - DAY))
        val thrice = WaveProfileBuilder.build(emptyList(), emptyList(), three, NOW)
        val full = PlayHistoryItem(song.id, song.artistId, song.genre, NOW - DAY / 2, skippedEarly = false)
        val healed = WaveProfileBuilder.build(emptyList(), emptyList(), three + full, NOW)
        val candidate = WaveCandidate(song, WaveBucket.Neighbors)
        val base = engine.adjustedWeight(candidate, twice.copy(artistSkips = emptyMap(), genreSkips = emptyMap()))
        val low = engine.adjustedWeight(candidate, thrice.copy(artistSkips = emptyMap(), genreSkips = emptyMap()))
        assertEquals(1.0, base, 1e-9)
        assertEquals(WaveProfile.REPEATED_SKIP_FACTOR, low, 1e-9)
        assertEquals(0, healed.songEarlySkips[song.id] ?: 0)
    }

    @Test
    fun `skips in own playlist barely count`() {
        val profile = WaveProfileBuilder.build(emptyList(), emptyList(), listOf(skip(NOW - DAY, PlayContext.Own)), NOW)
        assertEquals(0.1, profile.artistSkips.getValue(song.artistId!!), 1e-9)
        assertTrue("no cooldown for own playlist skips", song.id !in profile.lastSkippedAt)
    }

    @Test
    fun `partial play is neutral for taste`() {
        val partial = PlayHistoryItem(song.id, song.artistId, song.genre, NOW - DAY, skippedEarly = false, outcome = PlayOutcome.Partial)
        val profile = WaveProfileBuilder.build(emptyList(), emptyList(), listOf(partial), NOW)
        assertTrue(profile.topArtistIds.isEmpty())
        assertTrue(profile.artistSkips.isEmpty())
        assertEquals(NOW - DAY, profile.lastPlayedAt[song.id])
    }
}
