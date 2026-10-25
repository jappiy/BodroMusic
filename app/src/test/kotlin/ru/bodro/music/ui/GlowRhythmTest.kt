package ru.bodro.music.ui

import androidx.compose.ui.text.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.bodro.music.data.reco.TrackInfoRepository
import ru.bodro.music.playback.AudioSpectrum
import ru.bodro.music.playback.SpectrumFrame
import ru.bodro.music.ui.screens.home.firstFitting
import ru.bodro.music.ui.screens.wave.NebulaRhythm

/** Задания 025, 033: ритм туманности на главной, подбор короткого текста под три строки, только русский текст. */
class GlowRhythmTest {
    private fun frame(energy: Float, beats: Int = 0) = SpectrumFrame(FloatArray(AudioSpectrum.BANDS), energy, energy = energy, beats = beats)

    @Test
    fun `energy rises fast and falls slowly`() {
        val rhythm = NebulaRhythm()
        repeat(6) { rhythm.update(frame(0.9f), 1 / 60f, playing = true) } // 100 мс громко
        val peak = rhythm.energy
        assertTrue("за 100 мс подъём почти полный: $peak", peak > 0.6f)
        repeat(6) { rhythm.update(frame(0f), 1 / 60f, playing = true) } // 100 мс тишины
        assertTrue("спад медленнее подъёма: ${rhythm.energy}", rhythm.energy > peak * 0.6f)
        repeat(120) { rhythm.update(frame(0f), 1 / 60f, playing = false) } // 2 с паузы
        assertTrue("на паузе пульсация затухает", rhythm.energy < 0.01f)
    }

    @Test
    fun `beat pulse jumps on a new beat and decays with tau 180 ms`() {
        val rhythm = NebulaRhythm()
        rhythm.update(frame(0.5f, beats = 3), 1 / 60f, playing = true)
        assertTrue("первый кадр — только запоминаем счётчик", rhythm.beat == 0f)
        rhythm.update(frame(0.5f, beats = 4), 1 / 60f, playing = true)
        assertEquals(1f, rhythm.beat, 0.001f)
        repeat(11) { rhythm.update(frame(0.5f, beats = 4), 1 / 60f, playing = true) } // ~183 мс
        assertEquals(0.37f, rhythm.beat, 0.05f)
        rhythm.update(frame(0.5f, beats = 5), 1 / 60f, playing = true, pulse = false)
        assertTrue("экономный режим — без пульсации", rhythm.beat < 0.37f)
    }

    @Test
    fun `flow is faster when loud and slow on pause`() {
        val loud = NebulaRhythm()
        val paused = NebulaRhythm()
        repeat(300) {
            loud.update(frame(1f), 1 / 60f, playing = true)
            paused.update(frame(0f), 1 / 60f, playing = false)
        }
        assertTrue("громко: ${loud.phase}, пауза: ${paused.phase}", loud.phase - 7f > (paused.phase - 7f) * 5)
    }

    @Test
    fun `blurb takes the first variant that fits three lines`() {
        // Подделка измерения: 30 символов на строку.
        val measure: (String, Int, TextStyle) -> Pair<Int, Boolean> = { text, _, _ ->
            val lines = (text.length + 29) / 30
            lines to (lines > 3)
        }
        val long = "а".repeat(100)
        val short = "б".repeat(60)
        assertEquals(short, firstFitting(listOf(long, short), 300, TextStyle.Default, measure))
        assertNull(firstFitting(listOf(long), 300, TextStyle.Default, measure))
    }

    @Test
    fun `only russian texts are shown`() {
        assertTrue(TrackInfoRepository.isRussian("Шведская рок-группа, основанная в 2006 году в Линчёпинге."))
        assertTrue(TrackInfoRepository.isRussian("Трек из альбома «Meliora» (2015)"))
        assertFalse(TrackInfoRepository.isRussian("This is Ghost's leading single for the album \"Meliora\", release 05/31/2015."))
    }
}
