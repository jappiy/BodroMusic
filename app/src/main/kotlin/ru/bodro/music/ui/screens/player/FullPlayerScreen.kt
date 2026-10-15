package ru.bodro.music.ui.screens.player

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.bodro.music.R
import ru.bodro.music.core.model.Reaction
import ru.bodro.music.core.model.RepeatMode
import ru.bodro.music.core.model.WaveSeed
import ru.bodro.music.core.model.WaveSettings
import ru.bodro.music.ui.app.LocalAppActions
import ru.bodro.music.ui.app.LocalNavigator
import ru.bodro.music.ui.app.LocalReactions
import ru.bodro.music.ui.components.CoverImage
import ru.bodro.music.ui.components.CoverKind
import ru.bodro.music.ui.theme.LocalPlayerColors
import ru.bodro.music.ui.util.formatMs

private enum class PlayerSheet { None, Queue, Lyrics, Sleep }

/** Полноэкранный плеер: фон и акцент в цвет обложки, waveform-сикбар, бейдж качества. */
@Composable
fun FullPlayerScreen(
    onCollapse: () -> Unit,
    sharedScope: SharedTransitionScope,
    visibilityScope: AnimatedVisibilityScope,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val song = state.currentSong ?: return
    val actions = LocalAppActions.current
    val navigator = LocalNavigator.current
    val reaction = LocalReactions.current.of(song)
    val colors = LocalPlayerColors.current
    val background = colors?.background ?: MaterialTheme.colorScheme.surfaceContainerHigh
    val content = colors?.onBackground ?: MaterialTheme.colorScheme.onSurface
    val accent = colors?.accent ?: MaterialTheme.colorScheme.primary
    var sheet by rememberSaveable { mutableStateOf(PlayerSheet.None) }
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    var coverDrag by remember { mutableFloatStateOf(0f) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(background, Color.Black.copy(alpha = 0.92f).compositeOver(background))))
            .pointerInput(Unit) {
                // Свайп вниз — свернуть.
                var total = 0f
                detectVerticalDragGestures(onDragStart = { total = 0f }, onDragEnd = {
                    if (total >
                        120.dp.toPx()
                    ) {
                        onCollapse()
                    }
                }) { _, amount ->
                    total += amount
                }
            }
            .testTag("full-player"),
    ) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCollapse, modifier = Modifier.testTag("collapse-player")) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.action_collapse), tint = content)
                }
                Text(
                    sourceTitle(state.source),
                    style = MaterialTheme.typography.labelLarge,
                    color = content.copy(alpha = 0.75f),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                IconButton(onClick = { actions.showSongMenu(song) }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.action_more), tint = content)
                }
            }
            Spacer(Modifier.weight(0.5f))
            with(sharedScope) {
                CoverImage(
                    song.coverArt,
                    Modifier
                        .sharedElement(rememberSharedContentState("player-cover"), visibilityScope)
                        .widthIn(max = if (wide) 420.dp else 560.dp)
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .shadow(24.dp, RoundedCornerShape(20.dp))
                        .pointerInput(song.id) {
                            detectHorizontalDragGestures(
                                onDragStart = { coverDrag = 0f },
                                onDragEnd = {
                                    val threshold = 64.dp.toPx()
                                    if (coverDrag < -threshold) {
                                        viewModel.next()
                                    } else if (coverDrag > threshold) {
                                        viewModel.previous()
                                    }
                                },
                            ) { _, amount -> coverDrag += amount }
                        }
                        .testTag("player-cover"),
                    sizeHint = 1000.dp,
                    shape = RoundedCornerShape(20.dp),
                    kind = CoverKind.Song,
                )
            }
            Spacer(Modifier.weight(0.5f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        song.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = content,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("player-title"),
                    )
                    Text(
                        song.artist.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        color = accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(enabled = song.artistId != null) { song.artistId?.let(navigator::openArtist) },
                    )
                    song.album?.let { album ->
                        Text(
                            album,
                            style = MaterialTheme.typography.bodyMedium,
                            color = content.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(enabled = song.albumId != null) { song.albumId?.let(navigator::openAlbum) },
                        )
                    }
                }
                IconButton(onClick = { actions.toggleDislike(song) }, modifier = Modifier.testTag("player-dislike")) {
                    Icon(
                        painterResource(R.drawable.ic_heart_broken),
                        contentDescription = stringResource(
                            if (reaction ==
                                Reaction.Disliked
                            ) {
                                R.string.action_undislike
                            } else {
                                R.string.action_dislike
                            },
                        ),
                        tint = if (reaction == Reaction.Disliked) accent else content.copy(alpha = 0.8f),
                        modifier = Modifier.testTag(if (reaction == Reaction.Disliked) "icon-disliked" else "icon-not-disliked"),
                    )
                }
                IconButton(onClick = { actions.toggleLike(song) }, modifier = Modifier.testTag("player-like")) {
                    Icon(
                        if (reaction == Reaction.Liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = stringResource(
                            if (reaction ==
                                Reaction.Liked
                            ) {
                                R.string.action_unlike
                            } else {
                                R.string.action_like
                            },
                        ),
                        tint = if (reaction == Reaction.Liked) accent else content,
                        modifier = Modifier.testTag(if (reaction == Reaction.Liked) "icon-liked" else "icon-not-liked"),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            WaveformSeekBar(
                songId = song.id,
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                onSeek = viewModel::seekTo,
                activeColor = accent,
                inactiveColor = content.copy(alpha = 0.22f),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(formatMs(state.positionMs), style = MaterialTheme.typography.labelMedium, color = content.copy(alpha = 0.75f))
                Spacer(Modifier.weight(1f))
                state.quality?.let { badge -> QualityChip(badge.label, badge.lossless, accent, content) }
                Spacer(Modifier.weight(1f))
                Text(
                    "−" + formatMs((state.durationMs - state.positionMs).coerceAtLeast(0)),
                    style = MaterialTheme.typography.labelMedium,
                    color = content.copy(alpha = 0.75f),
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = viewModel::cycleRepeat, modifier = Modifier.testTag("player-repeat")) {
                    Icon(
                        if (state.repeatMode == RepeatMode.One) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        contentDescription = stringResource(R.string.action_repeat),
                        tint = if (state.repeatMode == RepeatMode.Off) content.copy(alpha = 0.6f) else accent,
                    )
                }
                IconButton(onClick = viewModel::previous, modifier = Modifier.size(56.dp)) {
                    Icon(
                        Icons.Rounded.SkipPrevious,
                        contentDescription = stringResource(R.string.action_previous),
                        tint = content,
                        modifier = Modifier.size(40.dp),
                    )
                }
                Surface(
                    onClick = viewModel::togglePlay,
                    shape = RoundedCornerShape(28.dp),
                    color = accent,
                    modifier = Modifier.size(80.dp).testTag("player-play"),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (state.isBuffering) CircularProgressIndicator(Modifier.size(72.dp), color = background, strokeWidth = 2.dp)
                        Icon(
                            if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(if (state.isPlaying) R.string.action_pause else R.string.action_play),
                            tint = background,
                            modifier = Modifier.size(44.dp),
                        )
                    }
                }
                IconButton(onClick = viewModel::next, enabled = state.hasNext, modifier = Modifier.size(56.dp)) {
                    Icon(
                        Icons.Rounded.SkipNext,
                        contentDescription = stringResource(R.string.action_next),
                        tint = content,
                        modifier = Modifier.size(40.dp),
                    )
                }
                IconButton(onClick = viewModel::toggleShuffle, modifier = Modifier.testTag("player-shuffle")) {
                    Icon(
                        Icons.Rounded.Shuffle,
                        contentDescription = stringResource(R.string.action_shuffle),
                        tint = if (state.shuffle) accent else content.copy(alpha = 0.6f),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                BottomAction(
                    Icons.Rounded.Bedtime,
                    stringResource(R.string.player_sleep_timer),
                    content,
                    active = state.sleepTimer != null,
                ) {
                    sheet =
                        PlayerSheet.Sleep
                }
                BottomAction(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.player_queue), content, tag = "open-queue") {
                    sheet =
                        PlayerSheet.Queue
                }
                BottomAction(Icons.Rounded.Lyrics, stringResource(R.string.player_lyrics), content, tag = "open-lyrics") {
                    sheet =
                        PlayerSheet.Lyrics
                }
                BottomAction(Icons.Rounded.Waves, stringResource(R.string.player_wave_by_track), content) {
                    actions.startWave(WaveSettings(seed = WaveSeed.Track(song.id, song.title, song.artistId, song.genre)))
                }
            }
        }
    }

    when (sheet) {
        PlayerSheet.Queue -> QueueSheet(
            state = state,
            onDismiss = { sheet = PlayerSheet.None },
            onSkipTo = viewModel::skipTo,
            onMove = viewModel::moveItem,
            onRemove = viewModel::removeItem,
        )
        PlayerSheet.Lyrics -> LyricsSheet(viewModel, state.positionMs, onSeek = viewModel::seekTo, onDismiss = { sheet = PlayerSheet.None })
        PlayerSheet.Sleep -> SleepTimerDialog(
            current = state.sleepTimer,
            onSelect = {
                viewModel.setSleepTimer(it)
                sheet = PlayerSheet.None
            },
            onDismiss = { sheet = PlayerSheet.None },
        )
        PlayerSheet.None -> Unit
    }
}

@Composable
private fun BottomAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    active: Boolean = false,
    tag: String? = null,
    onClick: () -> Unit,
) {
    val accent = LocalPlayerColors.current?.accent ?: MaterialTheme.colorScheme.primary
    IconButton(onClick = onClick, modifier = if (tag != null) Modifier.testTag(tag) else Modifier) {
        Icon(icon, contentDescription = label, tint = if (active) accent else tint.copy(alpha = 0.85f))
    }
}

@Composable
fun QualityChip(label: String, lossless: Boolean, accent: Color, content: Color) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(content.copy(alpha = 0.10f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag("quality-badge"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (lossless) {
            Text(stringResource(R.string.quality_lossless), style = MaterialTheme.typography.labelSmall, color = accent)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = content.copy(alpha = 0.85f))
    }
}

private fun Color.compositeOver(background: Color): Color {
    val a = alpha
    return Color(
        red = red * a + background.red * (1 - a),
        green = green * a + background.green * (1 - a),
        blue = blue * a + background.blue * (1 - a),
        alpha = 1f,
    )
}

@Composable
private fun sourceTitle(source: ru.bodro.music.core.model.QueueSource): String = when (source) {
    is ru.bodro.music.core.model.QueueSource.AlbumSource -> source.title
    is ru.bodro.music.core.model.QueueSource.PlaylistSource -> source.title
    is ru.bodro.music.core.model.QueueSource.ArtistSource -> source.title
    ru.bodro.music.core.model.QueueSource.LikedSource -> stringResource(R.string.liked_title)
    is ru.bodro.music.core.model.QueueSource.WaveSource -> stringResource(R.string.wave_title)
    is ru.bodro.music.core.model.QueueSource.CustomSource -> source.title ?: stringResource(R.string.player_now_playing)
}
