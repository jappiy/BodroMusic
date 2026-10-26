package ru.bodro.music.ui.screens.player

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import ru.bodro.music.R
import ru.bodro.music.core.model.Reaction
import ru.bodro.music.playback.PlayerUiState
import ru.bodro.music.ui.components.CoverImage
import ru.bodro.music.ui.components.CoverKind
import ru.bodro.music.ui.image.coverChain
import ru.bodro.music.ui.theme.Contrast
import ru.bodro.music.ui.theme.LocalPlayerColors

/**
 * Мини-плеер над нижней панелью: обложка, название, исполнитель, сердце, назад / play-pause / вперёд, тонкий прогресс.
 * Свайп вверх — полный плеер, влево/вправо — следующий/предыдущий трек.
 */
@Composable
fun MiniPlayer(
    state: PlayerUiState,
    reaction: Reaction?,
    onExpand: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleLike: () -> Unit,
    sharedScope: SharedTransitionScope,
    visibilityScope: AnimatedVisibilityScope,
) {
    val song = state.currentSong ?: return
    val colors = LocalPlayerColors.current
    val background = colors?.background ?: MaterialTheme.colorScheme.surfaceContainerHigh
    val surfaces = listOf(background)
    val content = Contrast.pick(colors?.onBackground ?: MaterialTheme.colorScheme.onSurface, surfaces, Contrast.TEXT)
    // Акцент (лайк, прогресс, индикатор) — не ниже 3:1 к фону мини-плеера, иначе цвет текста.
    val accent = (colors?.accent ?: MaterialTheme.colorScheme.primary).let {
        if (Contrast.worst(it, surfaces) >=
            Contrast.ICON
        ) {
            it
        } else {
            content
        }
    }
    val secondary = Contrast.muted(content, surfaces, Contrast.TEXT, 0.7f)
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }

    Column(
        Modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .clickable(onClick = onExpand)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        dragX = 0f
                        dragY = 0f
                    },
                    onDragEnd = {
                        val threshold = 48.dp.toPx()
                        when {
                            -dragY > threshold && abs(dragY) > abs(dragX) -> onExpand()
                            dragX < -threshold && abs(dragX) > abs(dragY) -> onNext()
                            dragX > threshold && abs(dragX) > abs(dragY) -> onPrevious()
                        }
                    },
                ) { change, amount ->
                    change.consume()
                    dragX += amount.x
                    dragY += amount.y
                }
            }
            .testTag("mini-player"),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            with(sharedScope) {
                CoverImage(
                    song.coverChain(),
                    Modifier
                        .sharedElement(rememberSharedContentState("player-cover"), visibilityScope)
                        .size(44.dp),
                    sizeHint = 300.dp,
                    shape = RoundedCornerShape(10.dp),
                    kind = CoverKind.Song,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    song.title,
                    color = content,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    song.artist.orEmpty(),
                    color = secondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onToggleLike, modifier = Modifier.size(40.dp).testTag("mini-like")) {
                Icon(
                    if (reaction == Reaction.Liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = stringResource(if (reaction == Reaction.Liked) R.string.action_unlike else R.string.action_like),
                    tint = if (reaction == Reaction.Liked) accent else content,
                )
            }
            IconButton(onClick = onPrevious, modifier = Modifier.size(40.dp).testTag("mini-previous")) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = stringResource(R.string.action_previous), tint = content)
            }
            Box(contentAlignment = Alignment.Center) {
                if (state.isBuffering) CircularProgressIndicator(Modifier.size(36.dp), color = accent, strokeWidth = 2.dp)
                IconButton(onClick = onTogglePlay, modifier = Modifier.size(40.dp).testTag("mini-play")) {
                    Icon(
                        if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(if (state.isPlaying) R.string.action_pause else R.string.action_play),
                        tint = content,
                    )
                }
            }
            IconButton(onClick = onNext, modifier = Modifier.size(40.dp).testTag("mini-next")) {
                if (state.skipPending) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = content, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.SkipNext, contentDescription = stringResource(R.string.action_next), tint = content)
                }
            }
        }
        val progress = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = accent,
            trackColor = content.copy(alpha = 0.15f),
            drawStopIndicator = {},
        )
    }
}
