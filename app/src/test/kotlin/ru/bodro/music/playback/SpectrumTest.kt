package ru.bodro.music.playback

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** БПФ и полосы спектра для фона «Моей волны» (задание 022). */
class SpectrumTest {
    @Test
    fun `fft finds the sine frequency`() {
        val n = SpectrumAudioProcessor.FFT_SIZE
        val bin = 37
        val re = FloatArray(n) { sin(2 * PI * bin * it / n).toFloat() }
        val im = FloatArray(n)
        SpectrumAudioProcessor.fft(re, im)
        val magnitudes = (0 until n / 2).map { sqrt(re[it] * re[it] + im[it] * im[it]) }
        assertEquals(bin, magnitudes.indices.maxBy { magnitudes[it] })
        assertEquals(n / 2f, magnitudes[bin], 1f)
    }

    @Test
    fun `band edges are increasing and stay below nyquist`() {
        for (rate in listOf(44_100, 48_000, 22_050)) {
            val edges = SpectrumAudioProcessor.edges(rate)
            assertEquals(AudioSpectrum.BANDS + 1, edges.size)
            assertTrue(edges.toList().zipWithNext().all { (a, b) -> a <= b })
            assertTrue(edges.last() <= SpectrumAudioProcessor.FFT_SIZE / 2)
        }
    }
}
