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
    private fun frame(energy: Float, beats: Int = 0) =
        SpectrumFrame(FloatArray(AudioSpectrum.BANDS), energy, energy = energy, beats = beats)

    @Test
    fun `energy rises smoothly and falls slower`() {
        val rhythm = NebulaRhythm()
        repeat(6) { rhythm.update(frame(0.9f), 1 / 60f, playing = true) } // 100 мс громко
        assertTrue("за 100 мс — без скачка: ${rhythm.energy}", rhythm.energy < 0.45f)
        repeat(24) { rhythm.update(frame(0.9f), 1 / 60f, playing = true) } // ещё 400 мс
        val peak = rhythm.energy
        assertTrue("за 0,5 с подъём заметный: $peak", peak > 0.7f)
        repeat(6) { rhythm.update(frame(0f), 1 / 60f, playing = true) } // 100 мс тишины
        assertTrue("спад медленнее подъёма: ${rhythm.energy}", rhythm.energy > peak * 0.8f)
        repeat(300) { rhythm.update(frame(0f), 1 / 60f, playing = false) } // 5 с паузы
        assertTrue("на паузе отклик затухает", rhythm.energy < 0.01f)
    }

    @Test
    fun `beat pulse rises without a jump, decays and is rate limited`() {
        val rhythm = NebulaRhythm()
        rhythm.update(frame(0.5f, beats = 3), 1 / 60f, playing = true)
        assertTrue("первый кадр — только запоминаем счётчик", rhythm.beat == 0f)
        rhythm.update(frame(0.5f, beats = 4), 1 / 60f, playing = true)
        assertTrue("без скачка за кадр: ${rhythm.beat}", rhythm.beat in 0.05f..0.4f)
        var max = rhythm.beat
        repeat(30) {
            rhythm.update(frame(0.5f, beats = 4), 1 / 60f, playing = true)
            max = maxOf(max, rhythm.beat)
        }
        assertTrue("импульс мягкий: $max", max < 0.8f)
        assertTrue("за 0,5 с гаснет: ${rhythm.beat}", rhythm.beat < max * 0.6f)
        // Доли каждые 100 мс (10 в секунду) — импульсов не больше 3 в секунду (WCAG 2.3.1).
        val limited = NebulaRhythm()
        var pulses = 0
        var last = 0f
        var rising = false
        for (frameIndex in 0 until 60) {
            limited.update(frame(0.5f, beats = 1 + frameIndex / 6), 1 / 60f, playing = true)
            val up = limited.beat > last + 1e-4f
            if (up && !rising) pulses++
            rising = up
            last = limited.beat
        }
        assertTrue("импульсов за секунду: $pulses", pulses <= 3)
        rhythm.update(frame(0.5f, beats = 5), 1 / 60f, playing = true, pulse = false)
        assertTrue("спокойный режим — без пульсации", rhythm.beat < max)
    }

    @Test
    fun `flow is faster when loud and slow on pause`() {
        val loud = NebulaRhythm()
        val paused = NebulaRhythm()
        repeat(300) {
            loud.update(frame(1f), 1 / 60f, playing = true)
            paused.update(frame(0f), 1 / 60f, playing = false)
        }
        assertTrue("громко: ${loud.phase}, пауза: ${paused.phase}", loud.phase - 7f > (paused.phase - 7f) * 3)
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
