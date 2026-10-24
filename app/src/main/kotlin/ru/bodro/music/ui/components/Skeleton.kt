package ru.bodro.music.ui.components

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Системная «Отключить анимацию» (и автотесты): бесконечные анимации не крутим — Compose должен простаивать. */
@Composable
fun rememberSystemAnimations(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
}

/** Мерцание скелетона: прозрачность плавно «дышит». Без системных анимаций — ровная. */
@Composable
fun shimmerAlpha(): Float {
    if (!rememberSystemAnimations()) return SHIMMER_MIN
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = SHIMMER_MIN,
        targetValue = SHIMMER_MAX,
        animationSpec = infiniteRepeatable(tween(SHIMMER_MS, easing = LinearEasing), RepeatMode.Reverse),
        label = "shimmer-alpha",
    )
    return alpha
}

/** Заглушка нужной формы на время загрузки. */
@Composable
fun SkeletonBox(modifier: Modifier, color: Color, alpha: Float, shape: Shape = RoundedCornerShape(8.dp)) {
    Box(modifier.graphicsLayer { this.alpha = alpha }.clip(shape).background(color))
}

/** Строки текста: последняя короче — как настоящий абзац. */
@Composable
fun SkeletonLines(lines: Int, color: Color, alpha: Float, modifier: Modifier = Modifier, lineHeight: Dp = 14.dp) {
    Column(modifier) {
        repeat(lines) { index ->
            SkeletonBox(
                Modifier.fillMaxWidth(if (index == lines - 1) 0.6f else 1f).height(lineHeight),
                color,
                alpha,
            )
            if (index < lines - 1) Spacer(Modifier.height(8.dp))
        }
    }
}

/** Строка трека: обложка и две строки. */
@Composable
fun SkeletonTrackRow(color: Color, alpha: Float, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        SkeletonBox(Modifier.size(48.dp), color, alpha, RoundedCornerShape(10.dp))
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            SkeletonBox(Modifier.fillMaxWidth(0.7f).height(14.dp), color, alpha)
            Spacer(Modifier.height(8.dp))
            SkeletonBox(Modifier.width(96.dp).height(12.dp), color, alpha)
        }
    }
}

/** Карточка исполнителя: круглое фото и две строки. */
@Composable
fun SkeletonArtistCard(color: Color, alpha: Float, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.06f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkeletonBox(Modifier.size(64.dp), color, alpha, CircleShape)
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            SkeletonBox(Modifier.fillMaxWidth(0.6f).height(16.dp), color, alpha)
            Spacer(Modifier.height(8.dp))
            SkeletonBox(Modifier.fillMaxWidth(0.85f).height(12.dp), color, alpha)
        }
    }
}

private const val SHIMMER_MIN = 0.10f
private const val SHIMMER_MAX = 0.22f
private const val SHIMMER_MS = 900
