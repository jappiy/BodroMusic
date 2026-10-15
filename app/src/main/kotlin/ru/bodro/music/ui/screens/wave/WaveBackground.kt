package ru.bodro.music.ui.screens.wave

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Анимированное размытое цветное «облако» (как на экране «Моей волны»). Рисуется радиальными
 * градиентами без blur-эффекта — дёшево по батарее. Анимация идёт только пока экран в композиции
 * и активен (Compose не рисует кадры для невидимого окна); [animate] = false — статичный кадр.
 */
@Composable
fun WaveBackground(colors: List<Color>, modifier: Modifier = Modifier, animate: Boolean = true, base: Color = Color(0xFF0B0B0C)) {
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by if (animate) {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(18_000, easing = LinearEasing), RepeatMode.Restart),
            label = "phase",
        )
    } else {
        transition.animateFloat(0f, 0f, infiniteRepeatable(tween(1)), label = "static")
    }
    val pulse by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = if (animate) 1.1f else 0.85f,
        animationSpec = infiniteRepeatable(tween(6_000), RepeatMode.Reverse),
        label = "pulse",
    )
    Canvas(modifier) {
        drawRect(base)
        val w = size.width
        val h = size.height
        val radius = maxOf(w, h) * 0.55f
        colors.take(4).forEachIndexed { index, color ->
            val angle = phase + index * (PI / 2).toFloat()
            val cx = w * (0.5f + 0.28f * cos(angle + index))
            val cy = h * (0.45f + 0.22f * sin(angle * (1 + index * 0.25f)))
            val r = radius * (if (index % 2 == 0) pulse else 2f - pulse) * (0.75f + index * 0.08f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = 0.75f), color.copy(alpha = 0.25f), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = r,
                ),
                radius = r,
                center = Offset(cx, cy),
            )
        }
    }
}
