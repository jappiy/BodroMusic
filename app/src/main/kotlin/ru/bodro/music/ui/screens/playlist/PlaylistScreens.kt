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
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.bodro.music.R
import ru.bodro.music.core.model.Playlist
import ru.bodro.music.core.model.PlaylistDetails
import ru.bodro.music.core.model.QueueSource
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
import ru.bodro.music.ui.components.SongRow
import ru.bodro.music.ui.util.toUiError
import ru.bodro.music.ui.util.totalDurationText
import ru.bodro.music.ui.util.tracksText

@HiltViewModel
class PlaylistsViewModel @Inject constructor(private val repository: PlaylistRepository) : ViewModel() {
    private val _state = MutableStateFlow<LoadState<List<Playlist>>>(LoadState.Loading)
    val state: StateFlow<LoadState<List<Playlist>>> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = runCatching { repository.playlists() }.fold({ LoadState.Content(it) }, { LoadState.Error(it.toUiError()) })
        }
    }

    fun isEditable(playlist: Playlist) = repository.isEditable(playlist)

    fun create(name: String) = mutate { repository.create(name) }

    fun rename(id: String, name: String) = mutate { repository.rename(id, name) }

    fun delete(id: String) = mutate { repository.delete(id) }

    private fun mutate(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
            load()
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
            is LoadState.Content -> if (s.data.isEmpty()) {
                EmptyState(stringResource(R.string.playlists_empty), modifier = Modifier.padding(padding))
            } else {
                LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(s.data, key = { it.id }) { playlist ->
                        var menu by rememberSaveable { mutableStateOf(false) }
                        val editable = viewModel.isEditable(playlist)
                        ListItem(
                            headlineContent = { Text(playlist.name) },
                            supportingContent = {
                                Text(
                                    listOfNotNull(
                                        tracksText(playlist.songCount),
                                        playlist.owner?.takeIf { !editable }?.let { stringResource(R.string.playlist_owner, it) },
                                    ).joinToString(" · "),
                                )
                            },
                            leadingContent = {
                                CoverImage(playlist.coverArt, Modifier.size(56.dp), sizeHint = 56.dp, kind = CoverKind.Playlist)
                            },
                            trailingContent = {
                                if (editable) {
                                    IconButton(onClick = {
                                        menu = true
                                    }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.action_more)) }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.action_rename)) },
                                            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                            onClick = {
                                                menu = false
                                                renaming = playlist.id
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.action_delete)) },
                                            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                                            onClick = {
                                                menu = false
                                                deleting = playlist.id
                                            },
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.clickable { navigator.openPlaylist(playlist.id) },
                        )
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
        val current = (state as? LoadState.Content)?.data?.firstOrNull { it.id == id }?.name.orEmpty()
        NameDialog(stringResource(R.string.action_rename), current, onConfirm = {
            viewModel.rename(id, it)
            renaming = null
        }, onDismiss = { renaming = null })
    }
    deleting?.let { id ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.playlist_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(id)
                    deleting = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
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

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value =
                runCatching { repository.playlist(playlistId) }.fold({ LoadState.Content(it) }, { LoadState.Error(it.toUiError()) })
        }
    }

    fun isEditable(playlist: Playlist) = repository.isEditable(playlist)
}

@Composable
fun PlaylistScreen(onBack: () -> Unit, viewModel: PlaylistViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    when (val s = state) {
        LoadState.Loading -> LoadingState()
        is LoadState.Error -> ErrorState(s.error, viewModel::load)
        is LoadState.Content -> {
            val playlist = s.data.playlist
            val songs = s.data.songs
            val editable = viewModel.isEditable(playlist)
            val source = QueueSource.PlaylistSource(playlist.id, playlist.name)
            Scaffold(topBar = { BodroTopBar(playlist.name, onBack) }) { padding ->
                LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            PlaylistCollage(
                                songs.mapNotNull {
                                    it.coverArt
                                }.take(16),
                                Modifier.size(220.dp),
                                fallbackCoverId = playlist.coverArt,
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(playlist.name, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                            Text(
                                listOfNotNull(
                                    playlist.owner?.let { stringResource(R.string.playlist_owner, it) },
                                    tracksText(songs.size),
                                    totalDurationText(playlist.duration),
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                Button(onClick = { actions.play(songs, 0, source) }, enabled = songs.isNotEmpty()) {
                                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                    Text(stringResource(R.string.action_play))
                                }
                                FilledTonalButton(onClick = {
                                    actions.play(songs, 0, source, shuffle = true)
                                }, enabled = songs.isNotEmpty()) {
                                    Icon(Icons.Rounded.Shuffle, contentDescription = null)
                                    Text(stringResource(R.string.action_shuffle))
                                }
                            }
                        }
                    }
                    if (songs.isEmpty()) {
                        item { Text(pluralStringResource(R.plurals.tracks, 0, 0), Modifier.padding(16.dp)) }
                    }
                    itemsIndexed(songs, key = { index, song -> "$index-${song.id}" }) { index, song ->
                        SongRow(
                            song,
                            onClick = { actions.play(songs, index, source) },
                            menuContext = if (editable) {
                                SongMenuContext(
                                    playlist.id,
                                    index,
                                    onRemoved = viewModel::load,
                                )
                            } else {
                                SongMenuContext()
                            },
                        )
                    }
                }
            }
        }
    }
}
