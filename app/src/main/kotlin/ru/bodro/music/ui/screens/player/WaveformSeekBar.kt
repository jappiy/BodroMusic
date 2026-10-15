package ru.bodro.music.ui.screens.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import ru.bodro.music.core.domain.player.PseudoWaveform

/**
 * Сикбар в виде псевдо-waveform: узор стабилен для трека (из id), прогресс заполняется плавно.
 * Перетаскивание показывает новую позицию, отпускание — перемотка.
 */
@Composable
fun WaveformSeekBar(
    songId: String,
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier,
) {
    val bars = remember(songId) { PseudoWaveform.generate(songId) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val rawFraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val animated by animateFloatAsState(rawFraction, tween(250), label = "waveProgress")
    val fraction = dragFraction ?: animated

    Canvas(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .testTag("waveform-seekbar")
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                setProgress { value ->
                    onSeek((value * durationMs).toLong())
                    true
                }
            }
            .pointerInput(durationMs) {
                detectTapGestures { offset -> onSeek((offset.x / size.width * durationMs).toLong().coerceIn(0, durationMs)) }
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> dragFraction = (offset.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = {
                        dragFraction?.let { onSeek((it * durationMs).toLong()) }
                        dragFraction = null
                    },
                    onDragCancel = { dragFraction = null },
                ) { change, _ ->
                    dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            },
    ) {
        val count = bars.size
        val gap = 2.dp.toPx()
        val barWidth = ((size.width - gap * (count - 1)) / count).coerceAtLeast(1f)
        val progressX = size.width * fraction
        for (i in 0 until count) {
            val x = i * (barWidth + gap)
            val h = size.height * bars[i]
            val top = (size.height - h) / 2
            val color = when {
                x + barWidth <= progressX -> activeColor
                x >= progressX -> inactiveColor
                else -> activeColor
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(x, top),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2, barWidth / 2),
            )
        }
    }
}
