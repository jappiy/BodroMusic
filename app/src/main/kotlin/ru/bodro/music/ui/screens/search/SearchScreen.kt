package ru.bodro.music.ui.screens.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import ru.bodro.music.R
import ru.bodro.music.core.model.Playlist
import ru.bodro.music.core.model.QueueSource
import ru.bodro.music.core.model.SearchResult
import ru.bodro.music.data.repository.LibraryRepository
import ru.bodro.music.data.repository.PlaylistRepository
import ru.bodro.music.data.repository.SearchHistoryRepository
import ru.bodro.music.ui.app.LocalAppActions
import ru.bodro.music.ui.app.LocalNavigator
import ru.bodro.music.ui.components.AlbumCard
import ru.bodro.music.ui.components.ArtistCircle
import ru.bodro.music.ui.components.CoverImage
import ru.bodro.music.ui.components.CoverKind
import ru.bodro.music.ui.components.EmptyState
import ru.bodro.music.ui.components.ErrorState
import ru.bodro.music.ui.components.LoadState
import ru.bodro.music.ui.components.LoadingState
import ru.bodro.music.ui.components.SectionHeader
import ru.bodro.music.ui.components.SongRow
import ru.bodro.music.ui.util.toUiError
import ru.bodro.music.ui.util.tracksText

data class SearchResults(val result: SearchResult, val playlists: List<Playlist>, val query: String = "") {
    val isEmpty: Boolean get() = result.isEmpty && playlists.isEmpty()
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val playlists: PlaylistRepository,
    private val historyRepository: SearchHistoryRepository,
) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val history: StateFlow<List<String>> = historyRepository.observe().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    private var allPlaylists: List<Playlist>? = null
    private val retryTick = MutableStateFlow(0)

    /** Дебаунс 300 мс, отмена устаревших запросов. `null` — запрос пуст (показываем историю). */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val results: StateFlow<LoadState<SearchResults>?> = combine(
        _query.map { it.trim() }.debounce(DEBOUNCE_MS).distinctUntilChanged(),
        retryTick,
    ) { q, tick -> q to tick }
        .distinctUntilChanged()
        .transformLatest { (q, _) ->
            if (q.length < MIN_QUERY) {
                emit(null)
                return@transformLatest
            }
            emit(LoadState.Loading)
            val result = runCatching {
                val found = library.search(q)
                val lists = allPlaylists ?: playlists.playlists().also { allPlaylists = it }
                SearchResults(found, lists.filter { it.name.contains(q, ignoreCase = true) }, q)
            }
            emit(result.fold({ LoadState.Content(it) }, { LoadState.Error(it.toUiError()) }))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setQuery(value: String) {
        _query.value = value
    }

    fun commit() {
        viewModelScope.launch { historyRepository.add(_query.value) }
    }

    fun removeHistory(query: String) {
        viewModelScope.launch { historyRepository.remove(query) }
    }

    fun clearHistory() {
        viewModelScope.launch { historyRepository.clear() }
    }

    fun retry() {
        retryTick.value++
    }

    companion object {
        const val DEBOUNCE_MS = 300L
        const val MIN_QUERY = 2
    }
}

@Composable
fun SearchScreen(
    onOpenDownloads: () -> Unit = {},
    viewModel: SearchViewModel = hiltViewModel(),
    discover: DiscoverViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val discoverUi by discover.state.collectAsStateWithLifecycle()
    val discoverConfigured by discover.configured.collectAsStateWithLifecycle()
    val discoverRequests by discover.requests.collectAsStateWithLifecycle()
    val discoverInfo by discover.info.collectAsStateWithLifecycle()
    var discoverExpanded by rememberSaveable(discoverUi?.query) { mutableStateOf(false) }
    val actions = LocalAppActions.current
    val navigator = LocalNavigator.current
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        discover.events.collect { event ->
            when (event) {
                is DiscoverEvent.Message -> actions.showMessage(event.text)
                is DiscoverEvent.Play -> actions.play(listOf(event.song), 0, QueueSource.CustomSource(query.trim()))
            }
        }
    }
    // Сначала ищем в своей библиотеке; если треков там нет — сразу предлагаем скачать.
    LaunchedEffect(results, discoverConfigured) {
        val content = (results as? LoadState.Content)?.data
        when {
            results == null -> discover.reset()
            content == null -> Unit
            content.result.songs.isEmpty() && discoverConfigured -> discover.search(content.query)
            discoverUi?.query != content.query -> discover.reset()
        }
    }
    val discoverCallbacks = remember(discover, discoverUi?.query) {
        DiscoverCallbacks(
            onDownload = discover::download,
            onDownloadArtist = discover::downloadArtist,
            onSelectArtist = discover::selectArtist,
            onPlay = discover::play,
            onRetry = discover::retry,
            onShowMore = { discoverExpanded = true },
        )
    }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().testTag("search-screen"), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column {
                Text(
                    stringResource(R.string.tab_search),
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 12.dp),
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("search-field"),
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = {
                                viewModel.setQuery("")
                            }) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_clear)) }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        viewModel.commit()
                        keyboard?.hide()
                    }),
                )
            }
        }
        when (val r = results) {
            null -> {
                if (history.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.search_history)) }
                    items(history, key = { "h-$it" }) { q ->
                        ListItem(
                            headlineContent = { Text(q) },
                            leadingContent = { Icon(Icons.Rounded.History, contentDescription = null) },
                            trailingContent = {
                                IconButton(onClick = {
                                    viewModel.removeHistory(q)
                                }) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_delete)) }
                            },
                            modifier = Modifier.clickable { viewModel.setQuery(q) },
                        )
                    }
                    item {
                        TextButton(onClick = viewModel::clearHistory, modifier = Modifier.padding(horizontal = 8.dp)) {
                            Text(stringResource(R.string.search_clear_history))
                        }
                    }
                }
            }
            LoadState.Loading -> item { Box(Modifier.height(200.dp)) { LoadingState() } }
            is LoadState.Error -> item { Box(Modifier.height(400.dp)) { ErrorState(r.error, viewModel::retry) } }
            is LoadState.Content -> {
                val data = r.data
                if (data.isEmpty && !discoverConfigured) {
                    item { Box(Modifier.height(300.dp)) { EmptyState(stringResource(R.string.search_nothing, query.trim())) } }
                    item { DiscoverSetupHint(onOpenDownloads) }
                } else if (data.isEmpty) {
                    item {
                        Text(
                            stringResource(R.string.search_library_empty, data.query),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
                if (data.result.songs.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.search_tracks)) }
                    itemsIndexed(data.result.songs, key = { _, s -> "s-${s.id}" }) { index, song ->
                        SongRow(song, onClick = {
                            viewModel.commit()
                            actions.play(data.result.songs, index, QueueSource.CustomSource(query.trim()))
                        })
                    }
                }
                if (data.result.albums.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.search_albums)) }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(data.result.albums, key = { it.id }) { album ->
                                AlbumCard(album, onClick = {
                                    viewModel.commit()
                                    navigator.openAlbum(album.id)
                                })
                            }
                        }
                    }
                }
                if (data.result.artists.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.search_artists)) }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(data.result.artists, key = { it.id }) { artist ->
                                ArtistCircle(artist, onClick = {
                                    viewModel.commit()
                                    navigator.openArtist(artist.id)
                                })
                            }
                        }
                    }
                }
                if (data.playlists.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.search_playlists)) }
                    items(data.playlists, key = { "p-${it.id}" }) { playlist ->
                        ListItem(
                            headlineContent = { Text(playlist.name) },
                            supportingContent = { Text(tracksText(playlist.songCount)) },
                            leadingContent = {
                                CoverImage(playlist.coverArt, Modifier.size(48.dp), sizeHint = 48.dp, kind = CoverKind.Playlist)
                            },
                            modifier = Modifier.clickable {
                                viewModel.commit()
                                navigator.openPlaylist(playlist.id)
                            },
                        )
                    }
                }
                val ui = discoverUi
                if (ui != null && ui.query == data.query) {
                    discoverSection(ui, discoverRequests, discoverInfo, discoverExpanded, discoverCallbacks)
                } else if (discoverConfigured && data.result.songs.isNotEmpty()) {
                    item(key = "discover-offer") {
                        TextButton(
                            onClick = { discover.search(data.query) },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp).testTag("discover-offer"),
                        ) { Text(stringResource(R.string.search_discover_button, data.query)) }
                    }
                }
            }
        }
    }
}
