package ru.bodro.music.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Кадр спектра: [bands] — уровни полос 0..1 от низких частот к высоким, [level] — общая громкость 0..1. */
class SpectrumFrame(val bands: FloatArray, val level: Float) {
    companion object {
        val Silent = SpectrumFrame(FloatArray(AudioSpectrum.BANDS), 0f)
    }
}

/**
 * Спектр того, что сейчас играет, — для анимированного фона «Моей волны». Считается из PCM в аудиопроцессоре
 * плеера ([SpectrumAudioProcessor]), поэтому не нужно разрешение на микрофон и `Visualizer`.
 * Анализ идёт, только пока на экране есть подписчик ([acquire] / [release]): в фоне и при погашенном экране
 * процессор просто пропускает звук дальше.
 */
object AudioSpectrum {
    const val BANDS = 16
    private val listeners = AtomicInteger(0)
    private val _frames = MutableStateFlow(SpectrumFrame.Silent)
    val frames: StateFlow<SpectrumFrame> = _frames.asStateFlow()

    val isListening: Boolean get() = listeners.get() > 0

    fun acquire() {
        listeners.incrementAndGet()
    }

    fun release() {
        if (listeners.decrementAndGet() <= 0) {
            listeners.set(0)
            _frames.value = SpectrumFrame.Silent
        }
    }

    internal fun publish(frame: SpectrumFrame) {
        _frames.value = frame
    }
}

/**
 * Пропускает 16-битный PCM без изменений и, пока [AudioSpectrum] слушают, ~30 раз в секунду считает спектр:
 * БПФ на 1024 отсчёта (окно Ханна), 16 логарифмических полос 40 Гц – 16 кГц, уровни в дБ → 0..1.
 * Звук приходит в процессор раньше, чем в динамик (буфер вывода ~0,2–0,5 с); фон сглаживает уровни, задержка
 * на глаз не заметна. Другие форматы (float, многоканальный passthrough) процессор не берёт — плеер играет их мимо.
 */
@OptIn(UnstableApi::class)
class SpectrumAudioProcessor : BaseAudioProcessor() {
    private val ring = FloatArray(FFT_SIZE)
    private var ringPos = 0
    private var sinceFrame = 0
    private var hop = DEFAULT_RATE / FRAMES_PER_SECOND
    private var channels = 2
    private var sampleRate = DEFAULT_RATE
    private val re = FloatArray(FFT_SIZE)
    private val im = FloatArray(FFT_SIZE)
    private val window = FloatArray(FFT_SIZE) { (0.5 - 0.5 * cos(2 * PI * it / (FFT_SIZE - 1))).toFloat() }
    private var bandEdges = edges(DEFAULT_RATE)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw UnhandledAudioFormatException(inputAudioFormat)
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        sampleRate = inputAudioFormat.sampleRate
        hop = (sampleRate / FRAMES_PER_SECOND).coerceAtLeast(1)
        bandEdges = edges(sampleRate)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        if (AudioSpectrum.isListening) analyze(inputBuffer)
        replaceOutputBuffer(size).put(inputBuffer).flip()
    }

    override fun onFlush() {
        ringPos = 0
        sinceFrame = 0
        ring.fill(0f)
    }

    /** Читает отсчёты по абсолютным индексам — позиция буфера не меняется. */
    private fun analyze(buffer: ByteBuffer) {
        val start = buffer.position()
        val frameBytes = 2 * channels
        val frames = buffer.remaining() / frameBytes
        for (f in 0 until frames) {
            var sum = 0
            val base = start + f * frameBytes
            for (c in 0 until channels) sum += buffer.getShort(base + 2 * c).toInt()
            ring[ringPos] = sum / (channels * SHORT_SCALE)
            ringPos = (ringPos + 1) % FFT_SIZE
            if (++sinceFrame >= hop) {
                sinceFrame = 0
                AudioSpectrum.publish(spectrum())
            }
        }
    }

    private fun spectrum(): SpectrumFrame {
        var energy = 0f
        for (i in 0 until FFT_SIZE) {
            val sample = ring[(ringPos + i) % FFT_SIZE]
            energy += sample * sample
            re[i] = sample * window[i]
            im[i] = 0f
        }
        fft(re, im)
        val bands = FloatArray(AudioSpectrum.BANDS)
        for (b in 0 until AudioSpectrum.BANDS) {
            val from = bandEdges[b]
            val to = bandEdges[b + 1].coerceAtLeast(from + 1)
            var peak = 0f
            for (k in from until to) peak = maxOf(peak, sqrt(re[k] * re[k] + im[k] * im[k]))
            val db = 20f * log10(peak / (FFT_SIZE / 4f) + 1e-9f)
            bands[b] = ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)
        }
        val rmsDb = 10f * log10(energy / FFT_SIZE + 1e-12f)
        return SpectrumFrame(bands, ((rmsDb - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f))
    }

    companion object {
        const val FFT_SIZE = 1024
        const val FRAMES_PER_SECOND = 30
        private const val DEFAULT_RATE = 44_100
        private const val SHORT_SCALE = 32_768f
        private const val FLOOR_DB = -60f
        private const val LOW_HZ = 40.0
        private const val HIGH_HZ = 16_000.0

        /** Границы полос в номерах бинов БПФ: логарифмически от 40 Гц до 16 кГц (не выше Найквиста). */
        fun edges(sampleRate: Int): IntArray {
            val binHz = sampleRate.toDouble() / FFT_SIZE
            val high = minOf(HIGH_HZ, sampleRate / 2.0 - binHz)
            return IntArray(AudioSpectrum.BANDS + 1) { i ->
                val hz = LOW_HZ * (high / LOW_HZ).pow(i.toDouble() / AudioSpectrum.BANDS)
                (hz / binHz).toInt().coerceIn(1, FFT_SIZE / 2)
            }
        }

        /** БПФ на месте (радикс-2, N — степень двойки). */
        fun fft(re: FloatArray, im: FloatArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    re[i] = re[j].also { re[j] = re[i] }
                    im[i] = im[j].also { im[j] = im[i] }
                }
            }
            var len = 2
            while (len <= n) {
                val angle = -2 * PI / len
                val wRe = cos(angle).toFloat()
                val wIm = sin(angle).toFloat()
                var i = 0
                while (i < n) {
                    var curRe = 1f
                    var curIm = 0f
                    for (k in 0 until len / 2) {
                        val aRe = re[i + k]
                        val aIm = im[i + k]
                        val bRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                        val bIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                        re[i + k] = aRe + bRe
                        im[i + k] = aIm + bIm
                        re[i + k + len / 2] = aRe - bRe
                        im[i + k + len / 2] = aIm - bIm
                        val nextRe = curRe * wRe - curIm * wIm
                        curIm = curRe * wIm + curIm * wRe
                        curRe = nextRe
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }
}

/** Рендереры ExoPlayer с [SpectrumAudioProcessor] в аудиовыходе. */
@OptIn(UnstableApi::class)
class SpectrumRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(SpectrumAudioProcessor()))
            .build()
}
