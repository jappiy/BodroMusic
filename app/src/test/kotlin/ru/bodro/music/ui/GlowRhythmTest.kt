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
import ru.bodro.music.ui.screens.wave.BeatFollower

/** Задание 025: ритм свечения «Моей волны», подбор короткого текста под три строки, только русский текст. */
class GlowRhythmTest {
    private fun frame(level: Float, bass: Float) = SpectrumFrame(FloatArray(AudioSpectrum.BANDS), level, bass)

    @Test
    fun `energy rises fast and falls slowly`() {
        val beat = BeatFollower()
        repeat(6) { beat.update(frame(level = 0.9f, bass = 0.5f), 1 / 60f) } // 100 мс громко
        val peak = beat.energy
        assertTrue("за 100 мс подъём почти полный: $peak", peak > 0.6f)
        repeat(6) { beat.update(frame(level = 0f, bass = 0f), 1 / 60f) } // 100 мс тишины
        assertTrue("спад медленнее подъёма: ${beat.energy}", beat.energy > peak * 0.6f)
        repeat(120) { beat.update(frame(level = 0f, bass = 0f), 1 / 60f) } // 2 с паузы
        assertTrue("на паузе пульсация затухает", beat.energy < 0.01f)
    }

    @Test
    fun `bass kick above its average gives a breath, steady bass does not`() {
        val beat = BeatFollower()
        repeat(180) { beat.update(frame(level = 0.8f, bass = 0.5f), 1 / 60f) } // ровный бас 3 с
        assertTrue("ровный бас — без вдохов: ${beat.pulse}", beat.pulse < 0.05f)
        repeat(4) { beat.update(frame(level = 0.85f, bass = 0.75f), 1 / 60f) } // удар бочки
        assertTrue("удар — вдох: ${beat.pulse}", beat.pulse > 0.5f)
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
