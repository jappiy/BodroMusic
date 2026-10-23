package ru.bodro.music.ui.screens.playlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import ru.bodro.music.R
import ru.bodro.music.core.model.Playlist
import ru.bodro.music.core.model.PlaylistAccess
import ru.bodro.music.core.model.PlaylistDetails
import ru.bodro.music.core.model.QueueSource
import ru.bodro.music.core.model.Song
import ru.bodro.music.data.repository.PlaylistRepository
import ru.bodro.music.ui.app.LocalAppActions
import ru.bodro.music.ui.app.LocalNavigator
import ru.bodro.music.ui.app.PlaylistRoute
import ru.bodro.music.ui.app.SongMenuContext
import ru.bodro.music.ui.components.BodroTopBar
import ru.bodro.music.ui.components.CoverImage
import ru.bodro.music.ui.components.CoverKind
import ru.bodro.music.ui.components.EmptyState
import ru.bodro.music.ui.components.ErrorState
import ru.bodro.music.ui.components.LoadState
import ru.bodro.music.ui.components.LoadingState
import ru.bodro.music.ui.components.PlaylistCollage
import ru.bodro.music.ui.components.SectionHeader
import ru.bodro.music.ui.components.SongRow
import ru.bodro.music.ui.components.heroCoverSize
import ru.bodro.music.ui.util.toUiError
import ru.bodro.music.ui.util.totalDurationText
import ru.bodro.music.ui.util.tracksText

/** Свои плейлисты и общие (чужие публичные). */
data class PlaylistsData(val mine: List<Playlist>, val shared: List<Playlist>) {
    val isEmpty: Boolean get() = mine.isEmpty() && shared.isEmpty()
}

/** Результат действия с плейлистом — для сообщения внизу экрана. */
enum class PlaylistEvent { Saved, Shared, Unshared, Failed }

@HiltViewModel
class PlaylistsViewModel @Inject constructor(private val repository: PlaylistRepository) : ViewModel() {
    private val _state = MutableStateFlow<LoadState<PlaylistsData>>(LoadState.Loading)
    val state: StateFlow<LoadState<PlaylistsData>> = _state.asStateFlow()

    private val _events = Channel<PlaylistEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = runCatching { repository.playlists() }.fold({ list ->
                LoadState.Content(
                    PlaylistsData(
                        mine = list.filter { repository.access(it) == PlaylistAccess.Own },
                        shared = list.filter { repository.access(it) == PlaylistAccess.Shared },
                    ),
                )
            }, { LoadState.Error(it.toUiError()) })
        }
    }

    fun create(name: String) = mutate(null) { repository.create(name) }

    fun rename(id: String, name: String) = mutate(null) { repository.rename(id, name) }

    fun delete(id: String) = mutate(null) { repository.delete(id) }

    fun setShared(id: String, shared: Boolean) =
        mutate(if (shared) PlaylistEvent.Shared else PlaylistEvent.Unshared) { repository.setShared(id, shared) }

    fun saveCopy(id: String) = mutate(PlaylistEvent.Saved) { repository.saveCopy(id) }

    private fun mutate(success: PlaylistEvent?, block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { success?.let { _events.send(it) } }
                .onFailure { _events.send(PlaylistEvent.Failed) }
            load()
        }
    }
}

@Composable
private fun PlaylistEventMessages(events: kotlinx.coroutines.flow.Flow<PlaylistEvent>) {
    val actions = LocalAppActions.current
    val saved = stringResource(R.string.playlist_saved_copy)
    val shared = stringResource(R.string.playlist_shared_message)
    val unshared = stringResource(R.string.playlist_unshared_message)
    val failed = stringResource(R.string.playlist_action_failed)
    androidx.compose.runtime.LaunchedEffect(events) {
        events.collect { event ->
            actions.showMessage(
                when (event) {
                    PlaylistEvent.Saved -> saved
                    PlaylistEvent.Shared -> shared
                    PlaylistEvent.Unshared -> unshared
                    PlaylistEvent.Failed -> failed
                },
            )
        }
    }
}

@Composable
fun PlaylistsScreen(onBack: () -> Unit, viewModel: PlaylistsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    var creating by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    PlaylistEventMessages(viewModel.events)
    Scaffold(
        topBar = { BodroTopBar(stringResource(R.string.playlists_title), onBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.playlist_new)) },
            )
        },
    ) { padding ->
        when (val s = state) {
            LoadState.Loading -> LoadingState(Modifier.padding(padding))
            is LoadState.Error -> ErrorState(s.error, viewModel::load, Modifier.padding(padding))
            is LoadState.Content -> if (s.data.isEmpty) {
                EmptyState(stringResource(R.string.playlists_empty), modifier = Modifier.padding(padding))
            } else {
                LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
                    item(key = "h-mine") { SectionHeader(stringResource(R.string.playlists_mine)) }
                    if (s.data.mine.isEmpty()) {
                        item(key = "mine-empty") {
                            Text(
                                stringResource(R.string.playlists_mine_empty),
                                Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(s.data.mine, key = { "m-${it.id}" }) { playlist ->
                        OwnPlaylistItem(
                            playlist,
                            onOpen = { navigator.openPlaylist(playlist.id) },
                            onRename = { renaming = playlist.id },
                            onDelete = { deleting = playlist.id },
                            onShare = { viewModel.setShared(playlist.id, !playlist.public) },
                        )
                    }
                    if (s.data.shared.isNotEmpty()) {
                        item(key = "h-shared") { SectionHeader(stringResource(R.string.playlists_shared)) }
                        items(s.data.shared, key = { "s-${it.id}" }) { playlist ->
                            SharedPlaylistItem(
                                playlist,
                                onOpen = { navigator.openPlaylist(playlist.id) },
                                onSave = { viewModel.saveCopy(playlist.id) },
                            )
                        }
                    }
                }
            }
        }
    }
    if (creating) {
        NameDialog(stringResource(R.string.playlist_new), "", onConfirm = {
            viewModel.create(it)
            creating = false
        }, onDismiss = { creating = false })
    }
    renaming?.let { id ->
        val current = (state as? LoadState.Content)?.data?.mine?.firstOrNull { it.id == id }?.name.orEmpty()
        NameDialog(stringResource(R.string.action_rename), current, onConfirm = {
            viewModel.rename(id, it)
            renaming = null
        }, onDismiss = { renaming = null })
    }
    deleting?.let { id ->
        DeleteDialog(onConfirm = {
            viewModel.delete(id)
            deleting = null
        }, onDismiss = { deleting = null })
    }
}

@Composable
private fun OwnPlaylistItem(playlist: Playlist, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onShare: () -> Unit) {
    var menu by rememberSaveable { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                listOf(
                    tracksText(playlist.songCount),
                    stringResource(if (playlist.public) R.string.playlist_public else R.string.playlist_private),
                ).joinToString(" · "),
            )
        },
        leadingContent = { CoverImage(playlist.coverArt, Modifier.size(56.dp), sizeHint = 56.dp, kind = CoverKind.Playlist) },
        trailingContent = {
            IconButton(onClick = {
                menu = true
            }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.action_more)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                OwnPlaylistMenuItems(playlist.public, onDismiss = { menu = false }, onRename, onShare, onDelete)
            }
        },
        modifier = Modifier.clickable(onClick = onOpen),
    )
}

@Composable
private fun OwnPlaylistMenuItems(
    shared: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onReorder: (() -> Unit)? = null,
) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.action_rename)) },
        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
        onClick = {
            onDismiss()
            onRename()
        },
    )
    if (onReorder != null) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.playlist_reorder)) },
            leadingIcon = { Icon(Icons.Rounded.SwapVert, contentDescription = null) },
            onClick = {
                onDismiss()
                onReorder()
            },
        )
    }
    DropdownMenuItem(
        text = { Text(stringResource(if (shared) R.string.playlist_unshare else R.string.playlist_share)) },
        leadingIcon = { Icon(if (shared) Icons.Rounded.Lock else Icons.Rounded.People, contentDescription = null) },
        onClick = {
            onDismiss()
            onShare()
        },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.action_delete)) },
        leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
        onClick = {
            onDismiss()
            onDelete()
        },
    )
}

@Composable
private fun SharedPlaylistItem(playlist: Playlist, onOpen: () -> Unit, onSave: () -> Unit) {
    ListItem(
        headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                listOfNotNull(
                    playlist.owner?.let { stringResource(R.string.playlist_owner, it) },
                    tracksText(playlist.songCount),
                ).joinToString(" · "),
            )
        },
        leadingContent = { CoverImage(playlist.coverArt, Modifier.size(56.dp), sizeHint = 56.dp, kind = CoverKind.Playlist) },
        trailingContent = {
            IconButton(onClick = onSave) {
                Icon(Icons.Rounded.LibraryAdd, contentDescription = stringResource(R.string.playlist_save_copy))
            }
        },
        modifier = Modifier.clickable(onClick = onOpen),
    )
}

@Composable
private fun DeleteDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playlist_delete_confirm)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun NameDialog(title: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(name, {
                name = it
            }, label = { Text(stringResource(R.string.playlist_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@HiltViewModel
class PlaylistViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: PlaylistRepository,
) : ViewModel() {
    private val playlistId = savedState.toRoute<PlaylistRoute>().id
    private val _state = MutableStateFlow<LoadState<PlaylistDetails>>(LoadState.Loading)
    val state: StateFlow<LoadState<PlaylistDetails>> = _state.asStateFlow()

    private val _events = Channel<PlaylistEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val _deleted = MutableStateFlow(false)

    /** Плейлист удалён — экран закрывается. */
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value =
                runCatching { repository.playlist(playlistId) }.fold({ LoadState.Content(it) }, { LoadState.Error(it.toUiError()) })
        }
    }

    fun access(playlist: Playlist) = repository.access(playlist)

    fun rename(name: String) = mutate(null) { repository.rename(playlistId, name) }

    fun setShared(shared: Boolean) =
        mutate(if (shared) PlaylistEvent.Shared else PlaylistEvent.Unshared) { repository.setShared(playlistId, shared) }

    fun saveCopy() = mutate(PlaylistEvent.Saved) { repository.saveCopy(playlistId) }

    fun reorder(songs: List<Song>) = mutate(null) { repository.reorder(playlistId, songs) }

    fun delete() {
        viewModelScope.launch {
            runCatching { repository.delete(playlistId) }
                .onSuccess { _deleted.value = true }
                .onFailure { _events.send(PlaylistEvent.Failed) }
        }
    }

    private fun mutate(success: PlaylistEvent?, block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { success?.let { _events.send(it) } }
                .onFailure { _events.send(PlaylistEvent.Failed) }
            load()
        }
    }
}

@Composable
fun PlaylistScreen(onBack: () -> Unit, viewModel: PlaylistViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    PlaylistEventMessages(viewModel.events)
    androidx.compose.runtime.LaunchedEffect(deleted) { if (deleted) onBack() }
    when (val s = state) {
        LoadState.Loading -> LoadingState()
        is LoadState.Error -> ErrorState(s.error, viewModel::load)
        is LoadState.Content -> PlaylistContent(s.data, viewModel, onBack)
    }
}

@Composable
private fun PlaylistContent(data: PlaylistDetails, viewModel: PlaylistViewModel, onBack: () -> Unit) {
    val actions = LocalAppActions.current
    val playlist = data.playlist
    val songs = data.songs
    val access = viewModel.access(playlist)
    val editable = access == PlaylistAccess.Own
    val source = QueueSource.PlaylistSource(playlist.id, playlist.name)
    var menu by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var reordering by rememberSaveable { mutableStateOf(false) }
    // Порядок в режиме перестановки — локально до «Готово».
    val order = remember(songs, reordering) { songs.toMutableStateList() }
    Scaffold(topBar = {
        BodroTopBar(playlist.name, onBack, actions = {
            when {
                reordering -> IconButton(onClick = {
                    reordering = false
                    if (order.toList() != songs) viewModel.reorder(order.toList())
                }) { Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.action_done)) }
                editable -> {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.action_more))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        OwnPlaylistMenuItems(
                            playlist.public,
                            onDismiss = { menu = false },
                            onRename = { renaming = true },
                            onShare = { viewModel.setShared(!playlist.public) },
                            onDelete = { deleting = true },
                            onReorder = if (songs.size > 1) ({ reordering = true }) else null,
                        )
                    }
                }
                access == PlaylistAccess.Shared -> IconButton(onClick = viewModel::saveCopy) {
                    Icon(Icons.Rounded.LibraryAdd, contentDescription = stringResource(R.string.playlist_save_copy))
                }
            }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "header") {
                PlaylistHeader(playlist, songs, editable, onPlay = { shuffle ->
                    actions.play(songs, 0, source, shuffle)
                }, onSave = viewModel::saveCopy)
            }
            if (songs.isEmpty()) {
                item { Text(pluralStringResource(R.plurals.tracks, 0, 0), Modifier.padding(16.dp)) }
            }
            if (reordering) {
                itemsIndexed(order, key = { index, song -> "r-$index-${song.id}" }) { index, song ->
                    ReorderRow(
                        song,
                        canUp = index > 0,
                        canDown = index < order.lastIndex,
                        onUp = { order.add(index - 1, order.removeAt(index)) },
                        onDown = { order.add(index + 1, order.removeAt(index)) },
                    )
                }
            } else {
                itemsIndexed(songs, key = { index, song -> "$index-${song.id}" }) { index, song ->
                    SongRow(
                        song,
                        onClick = { actions.play(songs, index, source) },
                        menuContext = if (editable) SongMenuContext(playlist.id, index, onRemoved = viewModel::load) else SongMenuContext(),
                    )
                }
            }
        }
    }
    if (renaming) {
        NameDialog(stringResource(R.string.action_rename), playlist.name, onConfirm = {
            viewModel.rename(it)
            renaming = false
        }, onDismiss = { renaming = false })
    }
    if (deleting) {
        DeleteDialog(onConfirm = {
            deleting = false
            viewModel.delete()
        }, onDismiss = { deleting = false })
    }
}

@Composable
private fun PlaylistHeader(playlist: Playlist, songs: List<Song>, editable: Boolean, onPlay: (Boolean) -> Unit, onSave: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        PlaylistCollage(
            songs.mapNotNull {
                it.coverArt
            }.take(16),
            Modifier.size(heroCoverSize(220.dp)),
            fallbackCoverId = playlist.coverArt,
        )
        Spacer(Modifier.height(16.dp))
        Text(playlist.name, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text(
            listOfNotNull(
                if (editable) {
                    stringResource(if (playlist.public) R.string.playlist_public else R.string.playlist_private)
                } else {
                    playlist.owner?.let { stringResource(R.string.playlist_owner, it) }
                },
                tracksText(songs.size),
                totalDurationText(playlist.duration),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (!editable) {
            Text(
                stringResource(R.string.playlist_read_only),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { onPlay(false) }, enabled = songs.isNotEmpty()) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                Text(stringResource(R.string.action_play))
            }
            FilledTonalButton(onClick = { onPlay(true) }, enabled = songs.isNotEmpty()) {
                Icon(Icons.Rounded.Shuffle, contentDescription = null)
                Text(stringResource(R.string.action_shuffle))
            }
        }
        if (!editable) {
            TextButton(onClick = onSave) {
                Icon(Icons.Rounded.LibraryAdd, contentDescription = null)
                Text(stringResource(R.string.playlist_save_copy), Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun ReorderRow(song: Song, canUp: Boolean, canDown: Boolean, onUp: () -> Unit, onDown: () -> Unit) {
    ListItem(
        headlineContent = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(song.artist.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { CoverImage(song.coverArt, Modifier.size(40.dp), sizeHint = 40.dp) },
        trailingContent = {
            Row {
                IconButton(onClick = onUp, enabled = canUp) {
                    Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = stringResource(R.string.playlist_move_up))
                }
                IconButton(onClick = onDown, enabled = canDown) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.playlist_move_down))
                }
            }
        },
    )
}
