package ru.bodro.music.core.domain.wave

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.bodro.music.core.model.WaveMode
import ru.bodro.music.core.model.WaveSeed
import ru.bodro.music.core.model.WaveSettings

class LocalWaveSourceTest {
    private val fake = FakeWaveLibrary()
    private val source = LocalWaveSource(fake)
    private val profile = WaveProfileBuilder.build(
        liked = fake.starred,
        disliked = library.filter { it.artistId == "ar-3" },
        history = emptyList(),
        nowMs = NOW,
    )

    @Test
    fun `builds all four buckets`() = runTest {
        val candidates = source.candidates(request(profile))
        WaveBucket.entries.forEach { bucket -> assertTrue("$bucket empty", candidates.any { it.bucket == bucket }) }
        assertTrue(candidates.filter { it.bucket == WaveBucket.Liked }.all { it.song.starred })
    }

    @Test
    fun `full pipeline never returns disliked tracks`() = runTest {
        val engine = WaveEngine(listOf(source))
        repeat(30) { seed ->
            val result = engine.nextBatch(request(profile, seed = seed))
            assertEquals(20, result.size)
            assertTrue(result.none { it.id in profile.dislikedSongIds })
        }
    }

    @Test
    fun `artist seed asks for that artist and similar songs`() = runTest {
        fake.similarAvailable = true
        val candidates = source.candidates(request(profile, WaveSettings(seed = WaveSeed.Artist("ar-7", "Artist 7"))))
        assertTrue("artist:ar-7" in fake.calls)
        assertTrue("similar:ar-7" in fake.calls)
        assertTrue(candidates.any { it.song.artistId == "ar-49" && it.bucket == WaveBucket.Neighbors })
        assertTrue(candidates.filter { it.bucket == WaveBucket.Liked }.all { it.song.artistId == "ar-7" })
    }

    @Test
    fun `empty similar songs is fine`() = runTest {
        val candidates = source.candidates(request(profile, current = library[100]))
        assertTrue(candidates.isNotEmpty())
        assertTrue("similar:${library[100].artistId}" in fake.calls)
    }

    @Test
    fun `decade filter goes to random songs`() = runTest {
        source.candidates(request(profile, WaveSettings(mode = WaveMode.DecadeMix, decade = 1980)))
        assertTrue(fake.calls.any { it.startsWith("random:") && it.endsWith(":1980:1989") })
        assertFalse("no newest albums with decade", fake.calls.any { it.startsWith("albums:newest") })
    }

    @Test
    fun `discover mode skips familiar`() = runTest {
        val candidates = source.candidates(request(profile, WaveSettings(mode = WaveMode.Discover)))
        assertTrue(candidates.none { it.bucket == WaveBucket.Familiar })
    }

    @Test
    fun `song list seed uses the list as liked bucket`() = runTest {
        val ids = library.take(5).map { it.id }
        val candidates = source.candidates(request(profile, WaveSettings(seed = WaveSeed.SongList("Мне нравится", ids))))
        assertEquals(ids.toSet(), candidates.filter { it.bucket == WaveBucket.Liked }.map { it.song.id }.toSet())
    }
}
