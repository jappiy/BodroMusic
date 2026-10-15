package ru.bodro.music.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import ru.bodro.music.R
import ru.bodro.music.core.domain.player.SleepTimerOption
import ru.bodro.music.core.domain.player.SleepTimerState
import ru.bodro.music.playback.PlayerUiState
import ru.bodro.music.ui.components.CoverImage
import ru.bodro.music.ui.components.CoverKind
import ru.bodro.music.ui.util.formatMs

/** Очередь: тап — перейти, перетаскивание за ручку (долгое нажатие) — порядок, крестик — удалить. */
@Composable
fun QueueSheet(
    state: PlayerUiState,
    onDismiss: () -> Unit,
    onSkipTo: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (state.currentIndex - 1).coerceAtLeast(0))
    // Локальная копия для плавного перетаскивания; синхронизируется с плеером по окончании.
    var items by remember(state.queue) { mutableStateOf(state.queue.withIndex().toList()) }
    var draggingKey by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dragFrom by remember { mutableStateOf(-1) }
    val itemHeightPx = remember { mutableFloatStateOf(1f) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = Modifier.testTag("queue-sheet")) {
        Text(
            stringResource(R.string.player_queue) + " · " + pluralStringResource(R.plurals.tracks, items.size, items.size),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(state = listState, modifier = Modifier.fillMaxHeight(0.85f), contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(items, key = { _, item -> item.index }) { position, (originalIndex, song) ->
                val dragging = draggingKey == originalIndex
                val isCurrent = originalIndex == state.currentIndex
                Row(
                    Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            translationY = if (dragging) dragOffset else 0f
                            shadowElevation = if (dragging) 8f else 0f
                        }
                        .background(
                            if (dragging) {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                        )
                        .clickable { onSkipTo(originalIndex) }
                        .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
                        .testTag("queue-item-$position"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Rounded.DragHandle,
                        contentDescription = stringResource(R.string.queue_drag),
                        modifier = Modifier
                            .size(32.dp)
                            .pointerInput(originalIndex) {
                                itemHeightPx.floatValue = size.height.toFloat().coerceAtLeast(1f) + 12.dp.toPx() * 2
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggingKey = originalIndex
                                        dragFrom = items.indexOfFirst { it.index == originalIndex }
                                        dragOffset = 0f
                                    },
                                    onDragEnd = {
                                        val to = items.indexOfFirst { it.index == originalIndex }
                                        if (dragFrom >= 0 && to >= 0 && dragFrom != to) onMove(dragFrom, to)
                                        draggingKey = null
                                        dragOffset = 0f
                                    },
                                    onDragCancel = {
                                        draggingKey = null
                                        dragOffset = 0f
                                    },
                                ) { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                    val current = items.indexOfFirst { it.index == originalIndex }
                                    val steps = (dragOffset / itemHeightPx.floatValue).roundToInt()
                                    val target = (current + steps).coerceIn(0, items.lastIndex)
                                    if (target != current) {
                                        items = items.toMutableList().apply { add(target, removeAt(current)) }
                                        dragOffset -= (target - current) * itemHeightPx.floatValue
                                    }
                                }
                            },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box {
                        CoverImage(
                            song.coverArt,
                            Modifier.size(44.dp),
                            sizeHint = 44.dp,
                            shape = RoundedCornerShape(8.dp),
                            kind = CoverKind.Song,
                        )
                        if (isCurrent) {
                            Icon(
                                Icons.Rounded.Equalizer,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            song.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            song.artist.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    if (!isCurrent) {
                        IconButton(onClick = { onRemove(originalIndex) }) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.queue_remove))
                        }
                    }
                }
            }
        }
    }
}

/** Текст песни: синхронизированный (LRC/OpenSubsonic) с автопрокруткой, иначе обычный. */
@Composable
fun LyricsSheet(viewModel: PlayerViewModel, positionMs: Long, onSeek: (Long) -> Unit, onDismiss: () -> Unit) {
    val lyricsState by viewModel.lyrics.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    DisposableEffect(Unit) {
        viewModel.onLyricsVisible(true)
        onDispose { viewModel.onLyricsVisible(false) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = Modifier.testTag("lyrics-sheet")) {
        Text(
            stringResource(R.string.player_lyrics),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        when (val s = lyricsState) {
            LyricsState.Loading -> Text(stringResource(R.string.loading), Modifier.padding(20.dp))
            LyricsState.Missing -> Text(
                stringResource(R.string.lyrics_missing),
                Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is LyricsState.Loaded -> {
                val lyrics = s.lyrics
                val listState = rememberLazyListState()
                val active = lyrics.activeLineIndex(positionMs)
                LaunchedEffect(active) {
                    if (active >= 0) listState.animateScrollToItem((active - 2).coerceAtLeast(0))
                }
                LazyColumn(state = listState, modifier = Modifier.fillMaxHeight(0.85f), contentPadding = PaddingValues(20.dp)) {
                    itemsIndexed(lyrics.lines) { index, line ->
                        val isActive = index == active
                        Text(
                            line.text.ifBlank { "♪" },
                            style = if (lyrics.synced) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Normal,
                            color = when {
                                !lyrics.synced -> MaterialTheme.colorScheme.onSurface
                                isActive -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = if (lyrics.synced) 6.dp else 2.dp)
                                .then(
                                    if (lyrics.synced &&
                                        line.startMs != null
                                    ) {
                                        Modifier.clickable { onSeek(line.startMs!!) }
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SleepTimerDialog(current: SleepTimerState?, onSelect: (SleepTimerOption?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.player_sleep_timer)) },
        text = {
            Column {
                current?.remainingMs(System.currentTimeMillis())?.let {
                    Text(
                        stringResource(R.string.sleep_remaining, formatMs(it)),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                SleepTimerOption.presets.forEach { option ->
                    val label = when (option) {
                        is SleepTimerOption.Minutes -> pluralStringResource(R.plurals.minutes, option.minutes, option.minutes)
                        SleepTimerOption.EndOfTrack -> stringResource(R.string.sleep_end_of_track)
                    }
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = current?.option == option, role = Role.RadioButton) {
                            onSelect(option)
                        }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = current?.option == option, onClick = null)
                        Text(label, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        dismissButton = {
            if (current != null) TextButton(onClick = { onSelect(null) }) { Text(stringResource(R.string.sleep_cancel)) }
        },
    )
}
