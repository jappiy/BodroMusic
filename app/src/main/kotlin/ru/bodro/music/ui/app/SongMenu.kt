package ru.bodro.music.ui.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.bodro.music.R
import ru.bodro.music.core.domain.player.QualityBadges
import ru.bodro.music.core.model.Playlist
import ru.bodro.music.core.model.Reaction
import ru.bodro.music.core.model.Song
import ru.bodro.music.core.model.WaveSeed
import ru.bodro.music.core.model.WaveSettings
import ru.bodro.music.ui.components.CoverImage
import ru.bodro.music.ui.components.CoverKind
import ru.bodro.music.ui.util.formatBytes
import ru.bodro.music.ui.util.formatDuration

/** Контекстное меню трека (долгое нажатие / ⋮ в любом списке). */
@Composable
fun SongMenuSheet(
    song: Song,
    reaction: Reaction,
    onDismiss: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)?,
) {
    val actions = LocalAppActions.current
    val navigator = LocalNavigator.current
    var showInfo by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun act(block: () -> Unit) {
        block()
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = Modifier.testTag("song-menu")) {
        Column(Modifier.navigationBarsPadding()) {
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CoverImage(song.coverArt, Modifier.size(56.dp), sizeHint = 56.dp, shape = RoundedCornerShape(12.dp), kind = CoverKind.Song)
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        song.artist.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            MenuItem(Icons.Rounded.PlaylistPlay, stringResource(R.string.menu_play_next)) { act { actions.playNext(listOf(song)) } }
            MenuItem(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.menu_add_to_queue)) {
                act { actions.addToQueue(listOf(song)) }
            }
            MenuItem(Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.menu_add_to_playlist)) {
                act { actions.addToPlaylist(listOf(song)) }
            }
            MenuItem(Icons.Rounded.Waves, stringResource(R.string.menu_wave_by_track)) {
                act { actions.startWave(WaveSettings(seed = WaveSeed.Track(song.id, song.title, song.artistId, song.genre))) }
            }
            MenuItem(
                if (reaction == Reaction.Liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                stringResource(if (reaction == Reaction.Liked) R.string.action_unlike else R.string.action_like),
                tag = "menu-like",
            ) { act { actions.toggleLike(song) } }
            MenuItem(
                painterResource(R.drawable.ic_heart_broken),
                stringResource(if (reaction == Reaction.Disliked) R.string.action_undislike else R.string.action_dislike),
                tag = "menu-dislike",
            ) { act { actions.toggleDislike(song) } }
            song.albumId?.let { albumId ->
                MenuItem(Icons.Rounded.Album, stringResource(R.string.menu_go_album)) { act { navigator.openAlbum(albumId) } }
            }
            song.artistId?.let { artistId ->
                MenuItem(Icons.Rounded.Person, stringResource(R.string.menu_go_artist)) { act { navigator.openArtist(artistId) } }
            }
            if (onRemoveFromPlaylist != null) {
                MenuItem(Icons.Rounded.Delete, stringResource(R.string.menu_remove_from_playlist)) { act(onRemoveFromPlaylist) }
            }
            MenuItem(Icons.Rounded.Info, stringResource(R.string.menu_info)) { showInfo = true }
        }
    }
    if (showInfo) SongInfoDialog(song) { showInfo = false }
}

@Composable
private fun MenuItem(icon: ImageVector, text: String, tag: String? = null, onClick: () -> Unit) =
    MenuItem(rememberVectorPainter(icon), text, tag, onClick)

@Composable
private fun MenuItem(icon: Painter, text: String, tag: String? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(text) },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick).then(if (tag != null) Modifier.testTag(tag) else Modifier),
    )
}

@Composable
fun SongInfoDialog(song: Song, onDismiss: () -> Unit) {
    val rows = buildList {
        add(stringResource(R.string.info_title) to song.title)
        song.artist?.let { add(stringResource(R.string.info_artist) to it) }
        song.album?.let { add(stringResource(R.string.info_album) to it) }
        song.year?.let { add(stringResource(R.string.info_year) to it.toString()) }
        song.genre?.let { add(stringResource(R.string.info_genre) to it) }
        add(stringResource(R.string.info_duration) to formatDuration(song.duration.toLong()))
        QualityBadges.of(song)?.let { add(stringResource(R.string.info_quality) to it.label) }
        song.suffix?.let { add(stringResource(R.string.info_format) to it.uppercase()) }
        song.bitRate?.let { add(stringResource(R.string.info_bitrate) to "$it kbps") }
        song.bitDepth?.let { add(stringResource(R.string.info_bit_depth) to "$it bit") }
        song.samplingRate?.let { add(stringResource(R.string.info_sample_rate) to "$it Hz") }
        song.size?.let { add(stringResource(R.string.info_size) to formatBytes(it)) }
        song.playCount?.let { add(stringResource(R.string.info_play_count) to it.toString()) }
        add("ID" to song.id)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        title = { Text(stringResource(R.string.menu_info)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(rows) { (label, value) ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
    )
}

/** Выбор плейлиста для добавления треков (только свои плейлисты) + создание нового. */
@Composable
fun AddToPlaylistDialog(
    playlists: List<Playlist>?,
    onPick: (Playlist) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.menu_add_to_playlist)) },
        confirmButton = {
            if (creating) {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    onCreate(name.trim())
                }) { Text(stringResource(R.string.action_create)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        text = {
            if (creating) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.playlist_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 400.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    item {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.playlist_new)) },
                            leadingContent = { Icon(Icons.Rounded.Add, contentDescription = null) },
                            modifier = Modifier.clickable { creating = true },
                        )
                    }
                    if (playlists == null) {
                        item { Text(stringResource(R.string.loading), Modifier.padding(16.dp)) }
                    } else {
                        items(playlists, key = { it.id }) { playlist ->
                            ListItem(
                                headlineContent = { Text(playlist.name) },
                                supportingContent = {
                                    Text(
                                        androidx.compose.ui.res.pluralStringResource(
                                            R.plurals.tracks,
                                            playlist.songCount,
                                            playlist.songCount,
                                        ),
                                    )
                                },
                                leadingContent = {
                                    CoverImage(playlist.coverArt, Modifier.size(40.dp), sizeHint = 40.dp, kind = CoverKind.Playlist)
                                },
                                modifier = Modifier.clickable { onPick(playlist) },
                            )
                        }
                    }
                }
            }
        },
    )
}
