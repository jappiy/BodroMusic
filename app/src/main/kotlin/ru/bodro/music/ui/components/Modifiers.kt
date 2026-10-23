package ru.bodro.music.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Нажатие + долгое нажатие (контекстное меню). */
fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: (() -> Unit)? = null, role: Role? = null): Modifier =
    combinedClickable(onClick = onClick, onLongClick = onLongClick, role = role)

/**
 * Размер обложки в шапке экрана (альбом, артист, плейлист): не больше [max] и не больше 40 % высоты окна —
 * в ландшафте и на разложенном экране под обложкой остаются название и кнопки.
 */
@Composable
fun heroCoverSize(max: Dp): Dp {
    val height = LocalConfiguration.current.screenHeightDp
    return minOf(max, (height * HERO_HEIGHT_SHARE).toInt().dp).coerceAtLeast(HERO_MIN)
}

private const val HERO_HEIGHT_SHARE = 0.4f
private val HERO_MIN = 96.dp
