package ru.bodro.music.core.domain.wave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.bodro.music.core.model.Song

class SoundArrangerTest {
    private fun song(id: String) = Song(id = id, title = id, artist = "A", duration = 200)

    @Test
    fun `mood filter keeps matching first and unknown after`() {
        val songs = listOf(song("calm"), song("loud"), song("unknown"), song("party"))
        val features = mapOf(
            "calm" to SoundFeatures(energy = 0.2, relaxed = 0.9),
            "loud" to SoundFeatures(energy = 0.8),
            "party" to SoundFeatures(energy = 0.95),
        )
        val energetic = SoundArranger.filterByMood(songs, features, WaveMood.Energetic, 10)
        assertEquals(listOf("party", "loud", "unknown"), energetic.map { it.id })
        val calm = SoundArranger.filterByMood(songs, features, WaveMood.Calm, 10)
        assertEquals(listOf("calm", "unknown"), calm.map { it.id })
        assertEquals(songs, SoundArranger.filterByMood(songs, features, WaveMood.Any, 10))
    }

    @Test
    fun `smooth orders by energy and tempo from current song`() {
        val songs = listOf(song("hi"), song("lo"), song("mid"), song("x"))
        val features = mapOf(
            "start" to SoundFeatures(energy = 0.1, bpm = 80.0),
            "hi" to SoundFeatures(energy = 0.9, bpm = 160.0),
            "lo" to SoundFeatures(energy = 0.15, bpm = 85.0),
            "mid" to SoundFeatures(energy = 0.5, bpm = 120.0),
        )
        val smoothed = SoundArranger.smooth(songs, features, start = song("start"))
        assertEquals(listOf("lo", "mid", "hi", "x"), smoothed.map { it.id })
        val jumps = smoothed.dropLast(1).zipWithNext { a, b -> SoundArranger.distance(features.getValue(a.id), features.getValue(b.id)) }
        assertTrue(jumps.all { it < 0.8 })
    }
}
