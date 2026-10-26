package ru.bodro.music.core.domain.wave

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackWavePlannerTest {
    private val seed = library.first { it.artistId == "ar-1" }

    private fun of(artist: String, count: Int, source: TrackWaveSource, score: (Int) -> Double = { 1.0 }) =
        library.filter { it.artistId == artist }.take(count).mapIndexed { i, s -> TrackWaveCandidate(s, source, score(i)) }

    private val genreFiller = library.filter { it.genre == seed.genre && it.artistId !in setOf("ar-1", "ar-2", "ar-3") }
        .take(30)
        .map { TrackWaveCandidate(it, TrackWaveSource.Genre, 1.0) }

    private fun candidates(): List<TrackWaveCandidate> =
        of("ar-2", 10, TrackWaveSource.Sound) { 1.0 - it * 0.05 } +
            of("ar-3", 10, TrackWaveSource.LastFm) { 0.8 } +
            of("ar-1", 15, TrackWaveSource.SameArtist) + genreFiller

    private fun select(c: List<TrackWaveCandidate> = candidates(), profile: WaveProfile = WaveProfile(), seedValue: Int = 1) =
        TrackWavePlanner.select(seed, c, profile, listOf(seed), 20, NOW, Random(seedValue))

    @Test
    fun `only similar tracks, seed excluded, no genre filler when similar suffice`() {
        val result = select()
        assertEquals(20, result.picks.size)
        assertTrue(result.picks.none { it.song.id == seed.id })
        assertTrue(result.picks.none { it.source == TrackWaveSource.Genre })
        assertFalse(result.expanded)
    }

    @Test
    fun `same artist limited to share of batch and never three in a row`() {
        repeat(20) { s ->
            val songs = select(seedValue = s).songs
            assertTrue(songs.count { it.artistId == seed.artistId } <= (20 * TrackWavePlanner.SAME_ARTIST_SHARE).toInt())
            songs.windowed(3).forEach { w -> assertFalse(w.all { it.artistId == w[0].artistId }) }
        }
    }

    @Test
    fun `few similar - honest expansion by genre, flagged`() {
        val result = select(of("ar-2", 3, TrackWaveSource.Sound) + genreFiller)
        assertTrue(result.expanded)
        assertEquals(3, result.picks.count { it.source == TrackWaveSource.Sound })
        assertEquals(TrackWaveSource.Sound, result.picks.first().source)
    }

    @Test
    fun `disliked never, recently skipped only when nothing else`() {
        val sound = of("ar-2", 10, TrackWaveSource.Sound)
        val disliked = sound[0].song.id
        val skipped = sound[1].song.id
        val profile = WaveProfile(dislikedSongIds = setOf(disliked), lastSkippedAt = mapOf(skipped to NOW - DAY))
        val result = select(profile = profile)
        assertTrue(result.picks.none { it.song.id == disliked || it.song.id == skipped })
        // Похожих почти нет — кулдаун ослабляется, но дизлайк — нет.
        val tiny = select(sound.take(3), profile)
        assertTrue(tiny.cooldownRelaxed)
        assertTrue(tiny.picks.any { it.song.id == skipped })
        assertTrue(tiny.picks.none { it.song.id == disliked })
    }

    @Test
    fun `nothing similar - empty, never random`() {
        assertTrue(select(emptyList()).picks.isEmpty())
    }
}
