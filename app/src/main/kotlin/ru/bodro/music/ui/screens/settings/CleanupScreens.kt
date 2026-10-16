package ru.bodro.music.ui.screens.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.bodro.music.R
import ru.bodro.music.core.downloads.BlockEntry
import ru.bodro.music.core.downloads.DislikedSong
import ru.bodro.music.core.model.Reaction
import ru.bodro.music.core.model.Song
import ru.bodro.music.data.account.ConnectionManager
import ru.bodro.music.data.downloads.DownloadsRepository
import ru.bodro.music.data.repository.ReactionRepository
import ru.bodro.music.ui.app.LocalAppActions
import ru.bodro.music.ui.components.BodroTopBar
import ru.bodro.music.ui.components.EmptyState
import ru.bodro.music.ui.components.ErrorState
import ru.bodro.music.ui.components.LoadingState
import ru.bodro.music.ui.components.SongRow
import ru.bodro.music.ui.util.UiError
import ru.bodro.music.ui.util.formatTime
import ru.bodro.music.ui.util.text
import ru.bodro.music.ui.util.toUiError

// region «На удаление»

/** Группа треков: все люди сразу, я или другой пользователь. */
sealed interface CleanupGroup {
    data object Everyone : CleanupGroup

    data object Mine : CleanupGroup

    data class User(val username: String) : CleanupGroup
}

data class CleanupUi(
    val loading: Boolean = true,
    /** Удаление доступно: сервис настроен и пользователь — администратор Navidrome. */
    val canDelete: Boolean = false,
    val groups: List<CleanupGroup> = listOf(CleanupGroup.Mine),
    val group: CleanupGroup = CleanupGroup.Mine,
    val songs: List<Song> = emptyList(),
    val dislikedBy: Map<String, List<String>> = emptyMap(),
    val busy: Boolean = false,
    val error: UiError? = null,
)

/**
 * Без прав администратора (или без сервиса загрузок) экран показывает только свои дизлайки из кэша приложения
 * с кнопкой «Вернуть». Администратор видит списки с сервера и может удалять треки окончательно.
 */
@HiltViewModel
class CleanupViewModel @Inject constructor(
    private val downloads: DownloadsRepository,
    private val reactions: ReactionRepository,
    private val connection: ConnectionManager,
) : ViewModel() {
    private val _state = MutableStateFlow(CleanupUi())
    val state: StateFlow<CleanupUi> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val info = if (downloads.isConfigured) runCatching { downloads.info() }.getOrNull() else null
            if (info?.cleanup == true && info.admin) {
                val me = connection.current()?.username
                val others = runCatching { downloads.cleanupUsers() }.getOrDefault(emptyList())
                    .filter { it.username != me }
                    .map { CleanupGroup.User(it.username) }
                _state.update {
                    it.copy(canDelete = true, groups = listOf(CleanupGroup.Everyone, CleanupGroup.Mine) + others)
                }
                select(CleanupGroup.Everyone)
            } else {
                observeLocal()
            }
        }
    }

    private fun observeLocal() {
        viewModelScope.launch {
            reactions.observeDisliked().collect { songs -> _state.update { it.copy(loading = false, songs = songs) } }
        }
    }

    fun select(group: CleanupGroup) {
        _state.update { it.copy(group = group, loading = true, error = null, songs = emptyList()) }
        load()
    }

    fun load() {
        val group = _state.value.group
        viewModelScope.launch {
            val username = when (group) {
                CleanupGroup.Everyone -> null
                CleanupGroup.Mine -> connection.current()?.username
                is CleanupGroup.User -> group.username
            }
            runCatching { downloads.disliked(username) }
                .onSuccess { list ->
                    if (_state.value.group != group) return@onSuccess
                    _state.update {
                        it.copy(
                            loading = false,
                            songs = list.map(DislikedSong::toSong),
                            dislikedBy = list.associate { s -> s.id to s.dislikedBy },
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.toUiError()) } }
        }
    }

    /** Снять свой дизлайк («Вернуть»). */
    fun undislike(song: Song) {
        viewModelScope.launch {
            reactions.setReaction(song, Reaction.None)
            if (_state.value.canDelete) _state.update { s -> s.copy(songs = s.songs.filterNot { it.id == song.id }) }
        }
    }

    /** Удалить с сервера окончательно. Возвращает (удалено, ошибок) через [onDone]. */
    fun delete(songs: List<Song>, onDone: (deleted: Int, failed: Int, details: String?) -> Unit) {
        if (songs.isEmpty()) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching { downloads.deleteSongs(songs.map { it.id }) }
                .onSuccess { results ->
                    val removed = results.filter { it.ok }.map { it.id }.toSet()
                    reactions.forget(removed) // удалённый трек не должен остаться в «Любимом»
                    _state.update { s -> s.copy(busy = false, songs = s.songs.filterNot { it.id in removed }) }
                    val failed = results.filterNot { it.ok }
                    onDone(removed.size, failed.size, failed.firstOrNull()?.message)
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = false) }
                    onDone(0, songs.size, e.message)
                }
        }
    }
}

private fun DislikedSong.toSong() = Song(
    id = id,
    title = title,
    artist = artist,
    album = album,
    albumId = albumId,
    coverArt = id,
    duration = duration ?: 0,
    suffix = suffix,
    bitRate = bitRate,
    size = size,
    userRating = 1,
)

private sealed interface PendingDelete {
    data class One(val song: Song) : PendingDelete

    data class All(val songs: List<Song>) : PendingDelete
}

@Composable
fun CleanupScreen(onBack: () -> Unit, viewModel: CleanupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    var pending by remember { mutableStateOf<PendingDelete?>(null) }
    val deletedText = stringResource(R.string.cleanup_deleted)
    val failedText = stringResource(R.string.cleanup_failed)
    val done: (Int, Int, String?) -> Unit = { deleted, failed, details ->
        val suffix = if (failed == 0) "" else ", $failedText: $failed" + (details?.let { " ($it)" } ?: "")
        actions.showMessage("$deletedText: $deleted$suffix")
    }

    Scaffold(topBar = { BodroTopBar(stringResource(R.string.cleanup_title), onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).testTag("cleanup-screen")) {
            if (state.groups.size > 1) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.groups.forEach { group ->
                        FilterChip(
                            selected = state.group == group,
                            onClick = { viewModel.select(group) },
                            label = { Text(groupLabel(group)) },
                        )
                    }
                }
            }
            if (state.canDelete && state.songs.isNotEmpty()) {
                Button(
                    onClick = { pending = PendingDelete.All(state.songs) },
                    enabled = !state.busy,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("cleanup-delete-all"),
                ) {
                    Icon(Icons.Rounded.DeleteForever, contentDescription = null)
                    Text(
                        stringResource(R.string.cleanup_delete_all, state.songs.size),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            val error = state.error
            when {
                state.loading -> LoadingState()
                error != null -> ErrorState(error, onRetry = viewModel::load)
                state.songs.isEmpty() -> EmptyState(
                    stringResource(R.string.disliked_empty),
                    stringResource(if (state.canDelete) R.string.cleanup_hint else R.string.cleanup_hint_readonly),
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Text(
                            stringResource(if (state.canDelete) R.string.cleanup_hint else R.string.cleanup_hint_readonly),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    itemsIndexed(state.songs, key = { _, song -> song.id }) { index, song ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { SongRow(song, onClick = { actions.play(state.songs, index) }) }
                            if (state.group == CleanupGroup.Mine) {
                                TextButton(
                                    onClick = { viewModel.undislike(song) },
                                    modifier = Modifier.testTag("undislike-${song.id}"),
                                ) { Text(stringResource(R.string.action_undislike_short)) }
                            }
                            if (state.canDelete) {
                                IconButton(
                                    onClick = { pending = PendingDelete.One(song) },
                                    enabled = !state.busy,
                                    modifier = Modifier.padding(end = 4.dp).testTag("cleanup-delete-${song.id}"),
                                ) {
                                    Icon(
                                        Icons.Rounded.DeleteForever,
                                        contentDescription = stringResource(R.string.cleanup_delete),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    when (val p = pending) {
        is PendingDelete.One -> ConfirmDialog(
            title = stringResource(R.string.cleanup_delete_one_title),
            text = stringResource(R.string.cleanup_delete_one_text, "${p.song.artist.orEmpty()} — ${p.song.title}"),
            confirm = stringResource(R.string.cleanup_delete),
            onConfirm = { viewModel.delete(listOf(p.song), done) },
            onDismiss = { pending = null },
        )
        is PendingDelete.All -> ConfirmDialog(
            title = stringResource(R.string.cleanup_delete_all_title),
            text = pluralStringResource(R.plurals.cleanup_delete_all_text, p.songs.size, p.songs.size, groupLabel(state.group)),
            confirm = stringResource(R.string.cleanup_delete),
            onConfirm = { viewModel.delete(p.songs, done) },
            onDismiss = { pending = null },
        )
        null -> Unit
    }
}

@Composable
private fun groupLabel(group: CleanupGroup): String = when (group) {
    CleanupGroup.Everyone -> stringResource(R.string.cleanup_group_everyone)
    CleanupGroup.Mine -> stringResource(R.string.cleanup_group_mine)
    is CleanupGroup.User -> stringResource(R.string.cleanup_group_user, group.username)
}

@Composable
private fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }, modifier = Modifier.testTag("confirm")) {
                Text(confirm, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// endregion

// region Чёрный список

data class BlocklistUi(
    val loading: Boolean = true,
    val query: String = "",
    val total: Int = 0,
    val items: List<BlockEntry> = emptyList(),
    val error: UiError? = null,
)

@HiltViewModel
class BlocklistViewModel @Inject constructor(private val downloads: DownloadsRepository) : ViewModel() {
    private val _state = MutableStateFlow(BlocklistUi())
    val state: StateFlow<BlocklistUi> = _state.asStateFlow()
    private var searchJob: Job? = null

    init {
        load()
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            load()
        }
    }

    fun load() {
        val query = _state.value.query
        viewModelScope.launch {
            runCatching { downloads.blocklist(query.trim(), PAGE_SIZE, 0) }
                .onSuccess { page ->
                    if (_state.value.query == query) {
                        _state.update { it.copy(loading = false, total = page.total, items = page.items, error = null) }
                    }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.toUiError()) } }
        }
    }

    /** Следующая страница, когда список долистали до конца. */
    fun loadMore() {
        val current = _state.value
        if (current.items.size >= current.total) return
        viewModelScope.launch {
            runCatching { downloads.blocklist(current.query.trim(), PAGE_SIZE, current.items.size) }
                .onSuccess { page ->
                    _state.update { s ->
                        if (s.query != current.query) s else s.copy(items = (s.items + page.items).distinctBy { it.id }, total = page.total)
                    }
                }
        }
    }

    fun unblock(entry: BlockEntry, onError: (UiError) -> Unit) {
        viewModelScope.launch {
            runCatching { downloads.unblock(entry.id) }
                .onSuccess {
                    _state.update { s -> s.copy(items = s.items.filterNot { it.id == entry.id }, total = (s.total - 1).coerceAtLeast(0)) }
                }
                .onFailure { e -> onError(e.toUiError()) }
        }
    }

    private companion object {
        const val PAGE_SIZE = 200
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}

@Composable
fun BlocklistScreen(onBack: () -> Unit, viewModel: BlocklistViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    var pending by remember { mutableStateOf<BlockEntry?>(null) }
    val context = LocalContext.current

    Scaffold(topBar = { BodroTopBar(stringResource(R.string.blocklist_title), onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).testTag("blocklist-screen")) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.blocklist_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("blocklist-search"),
            )
            val error = state.error
            when {
                state.loading -> LoadingState()
                error != null -> ErrorState(error, onRetry = viewModel::load)
                state.items.isEmpty() -> EmptyState(
                    stringResource(if (state.query.isBlank()) R.string.blocklist_empty else R.string.blocklist_not_found),
                    stringResource(R.string.blocklist_hint),
                    icon = Icons.Rounded.Block,
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Text(
                            pluralStringResource(R.plurals.tracks, state.total, state.total),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    items(state.items, key = { it.id }) { entry ->
                        if (entry == state.items.last()) LaunchedEffect(entry.id) { viewModel.loadMore() }
                        ListItem(
                            headlineContent = { Text("${entry.artist} — ${entry.title}") },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        R.string.blocklist_entry_summary,
                                        entry.createdBy,
                                        formatTime(entry.createdAt, "dd.MM.yyyy"),
                                        entry.removedCount,
                                    ),
                                )
                            },
                            trailingContent = {
                                TextButton(
                                    onClick = { pending = entry },
                                    modifier = Modifier.testTag("unblock-${entry.id}"),
                                ) { Text(stringResource(R.string.blocklist_unblock)) }
                            },
                        )
                    }
                }
            }
        }
    }

    pending?.let { entry ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.blocklist_unblock_title)) },
            text = { Text(stringResource(R.string.blocklist_unblock_text, "${entry.artist} — ${entry.title}")) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.unblock(entry) { actions.showMessage(it.text(context)) }
                    pending = null
                }, modifier = Modifier.testTag("confirm")) { Text(stringResource(R.string.blocklist_unblock)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

// endregion
