package ru.bodro.music.core.domain.player

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Псевдо-waveform для сикбара: стабильный узор, детерминированно выведенный из id трека.
 * Настоящая форма волны — в планах (нужен анализ файла).
 */
object PseudoWaveform {
    fun generate(songId: String, bars: Int = DEFAULT_BARS): FloatArray {
        require(bars > 0)
        val random = Random(stableHash(songId))
        val raw = DoubleArray(bars) { random.nextDouble() }
        val phase = random.nextDouble() * PI
        val result = FloatArray(bars)
        for (i in 0 until bars) {
            // Сглаживание соседями + «огибающая» трека (тише в начале и конце).
            val prev = raw[(i - 1).coerceAtLeast(0)]
            val next = raw[(i + 1).coerceAtMost(bars - 1)]
            val smooth = (prev + 2 * raw[i] + next) / 4
            val x = i.toDouble() / bars
            val envelope = 0.55 + 0.45 * sin(PI * x).let { it * it }
            val wobble = 0.85 + 0.15 * sin(phase + x * 6 * PI)
            result[i] = (MIN_HEIGHT + (1 - MIN_HEIGHT) * smooth * envelope * wobble).toFloat().coerceIn(MIN_HEIGHT.toFloat(), 1f)
        }
        return result
    }

    /** FNV-1a 64 — стабилен между версиями JVM/Android. */
    fun stableHash(value: String): Long {
        var hash = -0x340d631b7bdddcdbL
        for (ch in value) {
            hash = hash xor ch.code.toLong()
            hash *= 0x100000001b3L
        }
        return hash
    }

    const val DEFAULT_BARS = 72
    private const val MIN_HEIGHT = 0.12
}
