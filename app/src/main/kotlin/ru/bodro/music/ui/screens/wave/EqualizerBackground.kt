package ru.bodro.music.ui.screens.wave

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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import ru.bodro.music.playback.AudioSpectrum

/**
 * Фон «Моей волны»: мягкий эквалайзер в цветах обложки. Полосы реагируют на настоящий звук ([AudioSpectrum] —
 * спектр из аудиопроцессора плеера), под ними медленно плывут цветные пятна; снизу — затемнение, чтобы текст
 * читался. Цвета меняются плавно при смене трека.
 *
 * [animate] = false (выключено в настройках или волна на паузе) — статичный кадр без перерисовки. Анимация и анализ
 * звука останавливаются, когда экран не на переднем плане (приложение в фоне, экран погашен).
 */
@Composable
fun EqualizerBackground(accent: Color, base: Color, animate: Boolean, modifier: Modifier = Modifier) {
    val palette = remember(accent, base) { paletteFrom(accent, base) }
    val c0 by animateColorAsState(palette[0], tween(COLOR_MS), label = "eq0")
    val c1 by animateColorAsState(palette[1], tween(COLOR_MS), label = "eq1")
    val c2 by animateColorAsState(palette[2], tween(COLOR_MS), label = "eq2")
    val c3 by animateColorAsState(palette[3], tween(COLOR_MS), label = "eq3")
    val dark by animateColorAsState(palette[4], tween(COLOR_MS), label = "eqBase")

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    val running = animate && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    if (animate) {
        LifecycleResumeEffect(Unit) {
            AudioSpectrum.acquire()
            onPauseOrDispose { AudioSpectrum.release() }
        }
    }

    val levels = remember { FloatArray(AudioSpectrum.BANDS) }
    var loudness by remember { mutableFloatStateOf(0f) }
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.1f)
                last = now
                val frame = AudioSpectrum.frames.value
                for (i in levels.indices) {
                    val target = frame.bands.getOrElse(i) { 0f }
                    // Быстрый подъём, медленный спад — полосы «дышат», а не дёргаются.
                    val k = if (target > levels[i]) ATTACK else DECAY
                    levels[i] += (target - levels[i]) * k
                }
                loudness += (frame.level - loudness) * DECAY
                phase = (phase + dt * (0.12f + loudness * 0.35f)) % (2 * PI).toFloat()
            }
        }
    }

    Canvas(modifier) {
        val colors = listOf(c0, c1, c2, c3)
        drawRect(dark)
        val w = size.width
        val h = size.height
        // Цветные пятна — медленно плывут; при громкой музыке чуть крупнее.
        colors.forEachIndexed { index, color ->
            val angle = phase + index * (PI / 2).toFloat()
            val cx = w * (0.5f + 0.32f * cos(angle + index))
            val cy = h * (0.38f + 0.2f * sin(angle * (1 + index * 0.2f)))
            val r = maxOf(w, h) * (0.42f + 0.08f * index) * (0.9f + 0.25f * loudness)
            drawCircle(
                Brush.radialGradient(listOf(color.copy(alpha = 0.55f), color.copy(alpha = 0.18f), Color.Transparent), Offset(cx, cy), r),
                radius = r,
                center = Offset(cx, cy),
            )
        }
        // Эквалайзер: мягкие колонки снизу вверх, цвет — по палитре слева направо.
        val count = levels.size
        val slot = w / count
        for (i in 0 until count) {
            val level = levels[i]
            val barHeight = h * (0.12f + 0.5f * level)
            val t = i / (count - 1f)
            val color = when {
                t < 1f / 3 -> lerp(colors[0], colors[1], t * 3)
                t < 2f / 3 -> lerp(colors[1], colors[2], (t - 1f / 3) * 3)
                else -> lerp(colors[2], colors[3], (t - 2f / 3) * 3)
            }
            drawRoundRect(
                Brush.verticalGradient(
                    listOf(Color.Transparent, color.copy(alpha = 0.25f + 0.45f * level)),
                    startY = h - barHeight,
                    endY = h,
                ),
                topLeft = Offset(i * slot + slot * 0.12f, h - barHeight),
                size = Size(slot * 0.76f, barHeight),
                cornerRadius = CornerRadius(slot * 0.38f),
            )
        }
        // Затемнение снизу и чуть сверху — текст поверх фона читается всегда.
        drawRect(
            Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.25f),
                0.35f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.78f),
            ),
        )
    }
}

/** Четыре цвета из акцента и фона обложки (акцент и его соседи по кругу оттенков) + тёмная основа. */
private fun paletteFrom(accent: Color, base: Color): List<Color> {
    fun shifted(color: Color, hueShift: Float, lightness: Float? = null): Color {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color.toArgb(), hsl)
        hsl[0] = (hsl[0] + hueShift + 360f) % 360f
        hsl[1] = maxOf(hsl[1], 0.45f)
        lightness?.let { hsl[2] = it }
        return Color(ColorUtils.HSLToColor(hsl))
    }
    return listOf(
        shifted(accent, 0f, 0.55f),
        shifted(accent, 35f, 0.5f),
        shifted(base, 0f, 0.42f),
        shifted(accent, -40f, 0.45f),
        lerp(base, Color.Black, 0.82f),
    )
}

private const val COLOR_MS = 1200
private const val ATTACK = 0.45f
private const val DECAY = 0.08f
