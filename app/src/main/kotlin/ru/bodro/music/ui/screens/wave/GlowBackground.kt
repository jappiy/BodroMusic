package ru.bodro.music.ui.screens.wave

import android.graphics.RuntimeShader
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import ru.bodro.music.playback.AudioSpectrum
import ru.bodro.music.playback.SpectrumFrame

/**
 * Фон «Моей волны» — живое свечение в цветах обложки (ориентир — «Моя волна» Яндекс Музыки): размытые световые
 * потоки без чётких форм медленно перетекают и закручиваются, книзу уходят в чёрный.
 *
 * - Цвета — [colors] (доминантный цвет обложки и 1–2 акцента); при смене трека перетекают за ~1 с.
 * - Ритм — из PCM плеера ([AudioSpectrum], без микрофона и `Visualizer`): громкость (RMS) усиливает яркость и
 *   ускоряет течение, удар баса даёт короткий «вдох» (масштаб и вспышка). Сглаживание: подъём ~50 мс,
 *   спад ~400 мс — без дёрганья и стробоскопа. [playing] = false — пульсация затухает, остаётся спокойный дрейф.
 * - Android 13+ — AGSL-шейдер ([RuntimeShader]); раньше — несколько размытых радиальных градиентов.
 * - [animate] = false (выключено в настройках) или системная «Отключить анимацию» — статичный кадр. Когда экран
 *   не на переднем плане (другая вкладка, приложение свёрнуто, экран погашен), кадры и анализ звука не идут.
 */
@Composable
fun GlowBackground(colors: List<Color>, animate: Boolean, playing: Boolean, modifier: Modifier = Modifier) {
    val tones = remember(colors) { glowTones(colors) }
    val c0 by animateColorAsState(tones[0], tween(COLOR_MS), label = "glow0")
    val c1 by animateColorAsState(tones[1], tween(COLOR_MS), label = "glow1")
    val c2 by animateColorAsState(tones[2], tween(COLOR_MS), label = "glow2")

    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    // Системная «Отключить анимацию» (и автотесты) — статичный кадр, как у остальных анимаций Compose.
    val context = LocalContext.current
    val systemAnimations = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    val running = animate && systemAnimations && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    if (animate && systemAnimations && playing) {
        LifecycleResumeEffect(Unit) {
            AudioSpectrum.acquire()
            onPauseOrDispose { AudioSpectrum.release() }
        }
    }

    var time by remember { mutableFloatStateOf(START_TIME) }
    var energy by remember { mutableFloatStateOf(0f) }
    var pulse by remember { mutableFloatStateOf(0f) }
    val beat = remember { BeatFollower() }
    LaunchedEffect(running, playing) {
        if (!running) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(MAX_DT)
                last = now
                val frame = if (playing) AudioSpectrum.frameAt() else SpectrumFrame.Silent
                beat.update(frame, dt)
                energy = beat.energy
                pulse = beat.pulse
                time += dt * (DRIFT_SPEED + ENERGY_SPEED * energy)
            }
        }
    }

    val shader = remember { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) glowShader() else null }
    Canvas(modifier) {
        drawRect(Color.Black)
        // Шейдер рисуется только аппаратно; программный холст (снимок экрана, тесты) — градиенты.
        val hardware = drawContext.canvas.nativeCanvas.isHardwareAccelerated
        if (shader != null && hardware && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            drawShaderGlow(shader, c0, c1, c2, time, energy, pulse)
        } else {
            drawGradientGlow(listOf(c0, c1, c2), time, energy, pulse)
        }
    }
}

/**
 * Огибающие для фона из кадров спектра: [energy] — громкость, [pulse] — «вдох» на сильной доле (бас выше своего
 * среднего за ~1 с). Обе — подъём ~50 мс, спад ~400 мс.
 */
internal class BeatFollower {
    var energy = 0f
        private set
    var pulse = 0f
        private set
    private var bassAverage = 0f

    fun update(frame: SpectrumFrame, dt: Float) {
        if (dt <= 0f) return
        // Громкость музыки обычно −20…−6 дБ (0,65–0,9 в шкале спектра): этот диапазон и раскладываем на 0..1.
        val loud = ((frame.level - LEVEL_FLOOR) / (1f - LEVEL_FLOOR)).coerceIn(0f, 1f)
        energy = follow(energy, loud, dt)
        // После тишины среднее сразу берёт текущий бас — иначе ровный бас в начале трека казался бы ударами.
        if (bassAverage <= 0f) bassAverage = frame.bass
        bassAverage += (frame.bass - bassAverage) * smoothing(dt, BASS_AVERAGE_S)
        val onset = ((frame.bass - bassAverage - ONSET_MARGIN) * ONSET_GAIN).coerceIn(0f, 1f)
        pulse = follow(pulse, onset, dt)
    }

    private fun follow(current: Float, target: Float, dt: Float): Float {
        val tau = if (target > current) ATTACK_S else RELEASE_S
        return current + (target - current) * smoothing(dt, tau)
    }

    private fun smoothing(dt: Float, tau: Float): Float = 1f - exp(-dt / tau)

    private companion object {
        const val ATTACK_S = 0.05f
        const val RELEASE_S = 0.4f
        const val BASS_AVERAGE_S = 1.2f
        const val LEVEL_FLOOR = 0.55f
        const val ONSET_MARGIN = 0.03f
        const val ONSET_GAIN = 6f
    }
}

/** Три насыщенных тона средней яркости: свечение не «грязнит» тёмные обложки и не выбеливает светлые. */
private fun glowTones(colors: List<Color>): List<Color> {
    val source = colors.ifEmpty { listOf(Color(0xFFC0702E)) }
    fun tone(color: Color, hueShift: Float = 0f): Color {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color.toArgb(), hsl)
        hsl[0] = (hsl[0] + hueShift + 360f) % 360f
        // Почти серые обложки — мягкий цвет, а не кислотный: насыщенность поднимаем, но не до максимума.
        hsl[1] = if (hsl[1] < 0.12f) 0.35f else hsl[1].coerceIn(0.55f, 0.95f)
        hsl[2] = hsl[2].coerceIn(0.4f, 0.58f)
        return Color(ColorUtils.HSLToColor(hsl))
    }
    return listOf(
        tone(source[0]),
        tone(source.getOrElse(1) { source[0] }, if (source.size > 1) 0f else 28f),
        tone(source.getOrElse(2) { source.getOrElse(1) { source[0] } }, if (source.size > 2) 0f else -32f),
    )
}

// region Android 13+: AGSL

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun glowShader(): RuntimeShader? = runCatching { RuntimeShader(GLOW_AGSL) }.getOrNull()

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun DrawScope.drawShaderGlow(shader: RuntimeShader, c0: Color, c1: Color, c2: Color, time: Float, energy: Float, pulse: Float) {
    shader.setFloatUniform("uSize", size.width, size.height)
    shader.setFloatUniform("uTime", time)
    shader.setFloatUniform("uEnergy", energy)
    shader.setFloatUniform("uPulse", pulse)
    shader.setFloatUniform("uC0", c0.red, c0.green, c0.blue)
    shader.setFloatUniform("uC1", c1.red, c1.green, c1.blue)
    shader.setFloatUniform("uC2", c2.red, c2.green, c2.blue)
    drawRect(ShaderBrush(shader))
}

/**
 * Плазма из «завихрённых» синусов: координаты несколько раз сдвигаются синусами друг друга — получаются мягкие
 * перетекающие потоки без резких краёв. Яркие прожилки — где два поля совпадают. Удар баса чуть приближает
 * картину и подсвечивает прожилки. Книзу (40–80 % высоты) — в чёрный, у самого верха — немного темнее.
 */
private const val GLOW_AGSL = """
uniform float2 uSize;
uniform float uTime;
uniform float uEnergy;
uniform float uPulse;
uniform float3 uC0;
uniform float3 uC1;
uniform float3 uC2;

half4 main(float2 fc) {
    float2 uv = fc / uSize;
    float aspect = uSize.x / uSize.y;
    float2 p = float2((uv.x - 0.5) * aspect, uv.y - 0.36);
    p *= 2.4 * (1.0 - 0.07 * uPulse - 0.03 * uEnergy);
    float t = uTime;
    for (int i = 1; i < 6; i++) {
        float fi = float(i);
        p.x += 0.6 / fi * sin(fi * p.y + t * 0.9 + 0.3 * fi);
        p.y += 0.6 / fi * cos(fi * p.x - t * 0.7 + 0.5 * fi);
    }
    float f1 = 0.5 + 0.5 * sin(p.x + 0.5 * p.y);
    float f2 = 0.5 + 0.5 * cos(1.3 * p.y - 0.4 * p.x + 0.2 * t);
    float3 col = mix(uC0, uC1, smoothstep(0.15, 0.85, f1));
    col = mix(col, uC2, 0.75 * smoothstep(0.35, 0.95, f2));
    float streak = pow(f1 * f2, 3.0);
    float light = 0.5 + 0.35 * f1 * f2 + 0.22 * uEnergy + 0.15 * uPulse;
    col = col * light + streak * (0.3 + 0.3 * uPulse) * mix(col, float3(1.0), 0.55);
    float fade = 1.0 - smoothstep(0.40, 0.80, uv.y);
    fade *= 0.8 + 0.2 * smoothstep(0.0, 0.14, uv.y);
    return half4(half3(col * fade), 1.0);
}
"""

// endregion

/** Android < 13: несколько больших размытых пятен по кругу, затем то же затемнение книзу. */
private fun DrawScope.drawGradientGlow(colors: List<Color>, time: Float, energy: Float, pulse: Float) {
    val w = size.width
    val h = size.height
    val scale = 1f + 0.06f * pulse + 0.05f * energy
    val spots = colors + colors.first()
    spots.forEachIndexed { index, color ->
        val angle = time * (0.5f + 0.13f * index) + index * 1.7f
        val cx = w * (0.5f + 0.34f * cos(angle))
        val cy = h * (0.3f + 0.14f * sin(angle * 1.3f + index))
        val r = maxOf(w, h) * (0.38f + 0.05f * index) * scale
        val alpha = (0.75f + 0.2f * energy).coerceAtMost(1f)
        drawCircle(
            Brush.radialGradient(
                listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * 0.35f), Color.Transparent),
                Offset(cx, cy),
                r,
            ),
            radius = r,
            center = Offset(cx, cy),
        )
    }
    drawRect(
        Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.2f),
            0.14f to Color.Transparent,
            0.4f to Color.Transparent,
            0.8f to Color.Black,
            1f to Color.Black,
        ),
    )
}

private const val COLOR_MS = 1000
private const val MAX_DT = 0.1f
private const val START_TIME = 7f
private const val DRIFT_SPEED = 0.18f
private const val ENERGY_SPEED = 0.35f
