package ru.bodro.music.playback

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Задание 033: доли по басу с адаптивным порогом и AGC энергии. */
class BeatTrackerTest {
    private val rate = 44_100
    private val hop = rate / 60

    /** [seconds] звука: бочка 55 Гц каждые [beatEvery] с (если задано) поверх тихого шума и ровного тона 1 кГц. */
    private fun run(tracker: BeatTracker, seconds: Float, beatEvery: Float?, gain: Float = 1f) {
        val random = Random(1)
        val total = (seconds * rate).toInt()
        for (i in 0 until total) {
            val t = i.toFloat() / rate
            var x = 0.05f * sin(2 * PI * 1000 * t).toFloat() + 0.01f * (random.nextFloat() - 0.5f)
            if (beatEvery != null) {
                val since = t % beatEvery
                x += 0.6f * exp(-since / 0.06f) * sin(2 * PI * 55 * since).toFloat()
            }
            tracker.add(x * gain)
            if (i % hop == hop - 1) tracker.endHop()
        }
    }

    @Test
    fun `kick drum at 120 bpm gives about two beats per second`() {
        val tracker = BeatTracker(rate)
        run(tracker, 2f, beatEvery = 0.5f) // разгон истории порога
        val before = tracker.beats
        run(tracker, 8f, beatEvery = 0.5f)
        assertEquals(16f, (tracker.beats - before).toFloat(), 2f)
    }

    @Test
    fun `steady tone without kicks has no beats`() {
        val tracker = BeatTracker(rate)
        run(tracker, 6f, beatEvery = null)
        assertEquals(0, tracker.beats)
    }

    @Test
    fun `beats are never closer than 200 ms`() {
        val tracker = BeatTracker(rate)
        run(tracker, 2f, beatEvery = 0.5f)
        val before = tracker.beats
        run(tracker, 5f, beatEvery = 0.1f) // 10 ударов в секунду
        assertTrue("не больше 5 долей в секунду: ${tracker.beats - before}", tracker.beats - before <= 26)
    }

    @Test
    fun `energy is normalized so quiet and loud tracks move the background alike`() {
        val loud = BeatTracker(rate)
        val quiet = BeatTracker(rate)
        run(loud, 12f, beatEvery = 0.5f, gain = 1f)
        run(quiet, 12f, beatEvery = 0.5f, gain = 0.1f)
        assertEquals(loud.energy, quiet.energy, 0.15f)
    }
}
