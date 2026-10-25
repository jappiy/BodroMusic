package ru.bodro.music.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ритм из PCM на аудиопотоке (без UI): моно-отсчёты → громкость и доли.
 *
 * - Энергия — RMS окна, нормированная по скользящему максимуму за ~[AGC_S] с (AGC): тихий и громкий трек двигают
 *   фон одинаково.
 * - Бас — ФНЧ ~[BASS_HZ] Гц (biquad, RBJ, Q = 0,707). Доля — рост уровня баса (дБ) за окно выше адаптивного порога
 *   «среднее + [SIGMAS]σ» за последнюю ~1 с, не чаще раза в [REFRACTORY_S] с. Счётчик [beats] растёт на каждую долю.
 */
class BeatTracker(sampleRate: Int = 44_100) {
    var energy = 0f
        private set
    var beats = 0
        private set

    private var rate = sampleRate
    private var b0 = 0.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0
    private var sumSq = 0.0
    private var bassSq = 0.0
    private var count = 0
    private var peak = MIN_PEAK
    private var prevBassDb = Float.NaN
    private val flux = FloatArray(HISTORY)
    private var fluxPos = 0
    private var fluxFilled = 0
    private var sinceBeat = REFRACTORY_S

    init {
        configure(sampleRate)
    }

    fun configure(sampleRate: Int) {
        rate = sampleRate.coerceAtLeast(1)
        val w0 = 2 * PI * BASS_HZ / rate
        val alpha = sin(w0) / (2 * Q)
        val a0 = 1 + alpha
        b0 = (1 - cos(w0)) / 2 / a0
        b1 = (1 - cos(w0)) / a0
        b2 = b0
        a1 = -2 * cos(w0) / a0
        a2 = (1 - alpha) / a0
        reset()
    }

    /** Перемотка, новый поток: фильтр и история с нуля (счётчик долей не сбрасывается — его сравнивают с прошлым). */
    fun reset() {
        x1 = 0.0
        x2 = 0.0
        y1 = 0.0
        y2 = 0.0
        sumSq = 0.0
        bassSq = 0.0
        count = 0
        prevBassDb = Float.NaN
        fluxFilled = 0
        sinceBeat = REFRACTORY_S
        energy = 0f
    }

    fun add(sample: Float) {
        val x = sample.toDouble()
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        sumSq += x * x
        bassSq += y * y
        count++
    }

    /** Конец окна анализа (~1/60 с): обновить [energy] и, если была доля, [beats]. */
    fun endHop() {
        if (count == 0) return
        val hopS = count.toFloat() / rate
        val rms = sqrt(sumSq / count).toFloat()
        val bass = sqrt(bassSq / count).toFloat()
        sumSq = 0.0
        bassSq = 0.0
        count = 0

        peak = max(max(rms, peak * exp(-hopS / AGC_S)), MIN_PEAK)
        energy = (rms / peak).coerceIn(0f, 1f)

        val bassDb = 20f * log10(bass + 1e-6f)
        val rise = if (prevBassDb.isNaN()) 0f else (bassDb - prevBassDb).coerceAtLeast(0f)
        prevBassDb = bassDb
        sinceBeat += hopS
        val window = (1f / hopS).toInt().coerceIn(MIN_HISTORY, HISTORY)
        if (fluxFilled >= MIN_HISTORY) {
            val n = minOf(fluxFilled, window)
            var mean = 0f
            for (i in 1..n) mean += flux[(fluxPos - i + HISTORY) % HISTORY]
            mean /= n
            var variance = 0f
            for (i in 1..n) {
                val d = flux[(fluxPos - i + HISTORY) % HISTORY] - mean
                variance += d * d
            }
            val threshold = mean + SIGMAS * sqrt(variance / n)
            val audible = bass > peak * MIN_BASS_SHARE
            if (rise > threshold && rise > MIN_RISE_DB && audible && sinceBeat >= REFRACTORY_S) {
                beats++
                sinceBeat = 0f
            }
        }
        flux[fluxPos] = rise
        fluxPos = (fluxPos + 1) % HISTORY
        fluxFilled = minOf(fluxFilled + 1, HISTORY)
    }

    private companion object {
        const val BASS_HZ = 150.0
        const val Q = 0.7071
        const val AGC_S = 10f
        const val MIN_PEAK = 0.003f
        const val SIGMAS = 1.5f
        const val REFRACTORY_S = 0.2f
        const val MIN_RISE_DB = 1.5f
        const val MIN_BASS_SHARE = 0.05f
        const val HISTORY = 128
        const val MIN_HISTORY = 20
    }
}
