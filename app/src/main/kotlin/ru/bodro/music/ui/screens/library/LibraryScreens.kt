package ru.bodro.music.ui.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.bodro.music.R
import ru.bodro.music.core.model.Album
import ru.bodro.music.core.model.Artist
import ru.bodro.music.core.model.Genre
import ru.bodro.music.core.model.QueueSource
import ru.bodro.music.core.model.Song
import ru.bodro.music.core.model.WaveSeed
import ru.bodro.music.core.model.WaveSettings
import ru.bodro.music.data.repository.AlbumSort
import ru.bodro.music.data.repository.LibraryRepository
import ru.bodro.music.ui.app.GenreRoute
import ru.bodro.music.ui.app.LocalAppActions
import ru.bodro.music.ui.app.LocalNavigator
import ru.bodro.music.ui.components.AlbumCard
import ru.bodro.music.ui.components.ArtistAvatar
import ru.bodro.music.ui.components.BodroTopBar
import ru.bodro.music.ui.components.EmptyState
import ru.bodro.music.ui.components.ErrorState
import ru.bodro.music.ui.components.LoadState
import ru.bodro.music.ui.components.LoadingState
import ru.bodro.music.ui.components.SectionHeader
import ru.bodro.music.ui.components.SongRow
import ru.bodro.music.ui.util.UiError
import ru.bodro.music.ui.util.toUiError

/** Постраничный список: грузит следующую страницу, когда пользователь докрутил до конца. */
data class PagedState<T>(
    val items: List<T> = emptyList(),
    val loading: Boolean = false,
    val endReached: Boolean = false,
    val error: UiError? = null,
)

abstract class PagedViewModel<T> : ViewModel() {
    private val _paged = MutableStateFlow(PagedState<T>())
    val paged: StateFlow<PagedState<T>> = _paged.asStateFlow()
    private var job: Job? = null

    protected abstract suspend fun loadPage(offset: Int): List<T>

    protected open val pageSize: Int = LibraryRepository.PAGE

    fun reset() {
        job?.cancel()
        _paged.value = PagedState()
        loadMore()
    }

    fun loadMore() {
        val current = _paged.value
        if (current.loading || current.endReached) return
        _paged.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            runCatching { loadPage(current.items.size) }
                .onSuccess { page ->
                    _paged.update { it.copy(items = it.items + page, loading = false, endReached = page.size < pageSize) }
                }
                .onFailure { e -> _paged.update { it.copy(loading = false, error = e.toUiError()) } }
        }
    }
}

// region Исполнители

@HiltViewModel
class ArtistsViewModel @Inject constructor(private val library: LibraryRepository) : ViewModel() {
    private val _state = MutableStateFlow<LoadState<List<Artist>>>(LoadState.Loading)
    val state: StateFlow<LoadState<List<Artist>>> = _state.asStateFlow()

    fun load(starredOnly: Boolean) {
        viewModelScope.launch {
            _state.value = LoadState.Loading
            _state.value = runCatching {
                if (starredOnly) library.starred().artists else library.artists()
            }.fold({ LoadState.Content(it) }, { LoadState.Error(it.toUiError()) })
        }
    }
}

@Composable
fun ArtistsScreen(onBack: () -> Unit, starredOnly: Boolean, viewModel: ArtistsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    LaunchedEffect(starredOnly) { viewModel.load(starredOnly) }
    Scaffold(topBar = {
        BodroTopBar(stringResource(if (starredOnly) R.string.collection_favorite_artists else R.string.library_artists), onBack)
    }) { padding ->
        when (val s = state) {
            LoadState.Loading -> LoadingState(Modifier.padding(padding))
            is LoadState.Error -> ErrorState(s.error, { viewModel.load(starredOnly) }, Modifier.padding(padding))
            is LoadState.Content -> if (s.data.isEmpty()) {
                EmptyState(stringResource(R.string.list_empty), modifier = Modifier.padding(padding))
            } else {
                val grouped = s.data.groupBy { it.name.firstOrNull()?.uppercaseChar() ?: '#' }
                LazyColumn(Modifier.padding(padding).testTag("artists-list")) {
                    grouped.forEach { (letter, artists) ->
                        item(key = "h-$letter") {
                            Text(
                                letter.toString(),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, top = 12.dp),
                            )
                        }
                        items(artists, key = { it.id }) { artist ->
                            ListItem(
                                headlineContent = { Text(artist.name) },
                                supportingContent = { Text(pluralStringResource(R.plurals.albums, artist.albumCount, artist.albumCount)) },
                                leadingContent = { ArtistAvatar(artist.coverArt, Modifier.size(48.dp), sizeHint = 48.dp) },
                                modifier = Modifier.clickable { navigator.openArtist(artist.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

// endregion

// region Альбомы

@HiltViewModel
class AlbumsViewModel @Inject constructor(private val library: LibraryRepository) : PagedViewModel<Album>() {
    var sort: AlbumSort = AlbumSort.Name
        private set

    fun setSort(value: AlbumSort) {
        if (value == sort && paged.value.items.isNotEmpty()) return
        sort = value
        reset()
    }

    override suspend fun loadPage(offset: Int): List<Album> = library.albums(sort, offset)
}

@Composable
fun AlbumsScreen(onBack: () -> Unit, initialSort: AlbumSort, viewModel: AlbumsViewModel = hiltViewModel()) {
    val state by viewModel.paged.collectAsStateWithLifecycle()
    var sort by rememberSaveable { mutableStateOf(initialSort) }
    var menu by rememberSaveable { mutableStateOf(false) }
    val navigator = LocalNavigator.current
    LaunchedEffect(sort) { viewModel.setSort(sort) }
    Scaffold(
        topBar = {
            BodroTopBar(
                stringResource(if (sort == AlbumSort.Starred) R.string.collection_favorite_albums else R.string.library_albums),
                onBack,
                actions = {
                    IconButton(onClick = {
                        menu = true
                    }) { Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = stringResource(R.string.action_sort)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        AlbumSort.entries.forEach { option ->
                            DropdownMenuItem(text = { Text(sortLabel(option)) }, onClick = {
                                sort = option
                                menu = false
                            })
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.items.isEmpty() && state.error != null -> ErrorState(state.error!!, viewModel::reset, Modifier.padding(padding))
            state.items.isEmpty() && state.loading -> LoadingState(Modifier.padding(padding))
            state.items.isEmpty() && state.endReached -> EmptyState(
                stringResource(R.string.list_empty),
                modifier = Modifier.padding(padding),
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                modifier = Modifier.padding(padding).testTag("albums-grid"),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(state.items, key = { index, album -> "$index-${album.id}" }) { index, album ->
                    if (index >= state.items.size - 6) LaunchedEffect(state.items.size) { viewModel.loadMore() }
                    AlbumCard(album, onClick = { navigator.openAlbum(album.id) }, width = null)
                }
                if (state.loading) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                }
            }
        }
    }
}

@Composable
private fun sortLabel(sort: AlbumSort): String = stringResource(
    when (sort) {
        AlbumSort.Name -> R.string.sort_name
        AlbumSort.Artist -> R.string.sort_artist
        AlbumSort.Newest -> R.string.sort_newest
        AlbumSort.Recent -> R.string.sort_recent
        AlbumSort.Frequent -> R.string.sort_frequent
        AlbumSort.Random -> R.string.sort_random
        AlbumSort.Year -> R.string.sort_year
        AlbumSort.Starred -> R.string.sort_starred
    },
)

// endregion

// region Треки

@HiltViewModel
class TracksViewModel @Inject constructor(private val library: LibraryRepository) : PagedViewModel<Song>() {
    init {
        reset()
    }

    override suspend fun loadPage(offset: Int): List<Song> = library.allSongs(offset)

    suspend fun random(): List<Song> = library.randomSongs(100)
}

@Composable
fun TracksScreen(onBack: () -> Unit, viewModel: TracksViewModel = hiltViewModel()) {
    val state by viewModel.paged.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    PagedSongList(
        title = stringResource(R.string.library_tracks),
        state = state,
        onBack = onBack,
        onRetry = viewModel::reset,
        onLoadMore = viewModel::loadMore,
        onPlay = { songs, index -> actions.play(songs, index, QueueSource.CustomSource()) },
        header = null,
    )
}

@Composable
fun PagedSongList(
    title: String,
    state: PagedState<Song>,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    header: (@Composable () -> Unit)?,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    Scaffold(topBar = { BodroTopBar(title, onBack, actions = actions) }) { padding ->
        when {
            state.items.isEmpty() && state.error != null -> ErrorState(state.error, onRetry, Modifier.padding(padding))
            state.items.isEmpty() && state.loading -> LoadingState(Modifier.padding(padding))
            state.items.isEmpty() && state.endReached -> EmptyState(
                stringResource(R.string.list_empty),
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                header?.let { item { Column { it() } } }
                itemsIndexed(state.items, key = { index, song -> "$index-${song.id}" }) { index, song ->
                    if (index >= state.items.size - 10) LaunchedEffect(state.items.size) { onLoadMore() }
                    SongRow(song, onClick = { onPlay(state.items, index) })
                }
                if (state.loading) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                }
            }
        }
    }
}

// endregion

// region Жанры

@HiltViewModel
class GenresViewModel @Inject constructor(private val library: LibraryRepository) : ViewModel() {
    private val _state = MutableStateFlow<LoadState<List<Genre>>>(LoadState.Loading)
    val state: StateFlow<LoadState<List<Genre>>> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = runCatching { library.genres() }.fold({ LoadState.Content(it) }, { LoadState.Error(it.toUiError()) })
        }
    }
}

@Composable
fun GenresScreen(onBack: () -> Unit, viewModel: GenresViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    Scaffold(topBar = { BodroTopBar(stringResource(R.string.library_genres), onBack) }) { padding ->
        when (val s = state) {
            LoadState.Loading -> LoadingState(Modifier.padding(padding))
            is LoadState.Error -> ErrorState(s.error, viewModel::load, Modifier.padding(padding))
            is LoadState.Content -> LazyColumn(Modifier.padding(padding)) {
                items(s.data, key = { it.name }) { genre ->
                    ListItem(
                        headlineContent = { Text(genre.name) },
                        supportingContent = {
                            Text(
                                pluralStringResource(R.plurals.tracks, genre.songCount, genre.songCount) + " · " +
                                    pluralStringResource(R.plurals.albums, genre.albumCount, genre.albumCount),
                            )
                        },
                        modifier = Modifier.clickable { navigator.openGenre(genre.name) },
                    )
                }
            }
        }
    }
}

@HiltViewModel
class GenreViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val library: LibraryRepository,
) : PagedViewModel<Song>() {
    val genre: String = savedState.toRoute<GenreRoute>().name
    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    init {
        reset()
        viewModelScope.launch {
            _albums.value =
                runCatching { library.albums(AlbumSort.Name, 0, size = 30, genre = genre) }.getOrDefault(emptyList())
        }
    }

    override suspend fun loadPage(offset: Int): List<Song> = library.songsByGenre(genre, offset)
}

@Composable
fun GenreScreen(onBack: () -> Unit, viewModel: GenreViewModel = hiltViewModel()) {
    val state by viewModel.paged.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val navigator = LocalNavigator.current
    PagedSongList(
        title = viewModel.genre,
        state = state,
        onBack = onBack,
        onRetry = viewModel::reset,
        onLoadMore = viewModel::loadMore,
        onPlay = { songs, index -> actions.play(songs, index, QueueSource.CustomSource(viewModel.genre)) },
        actions = {
            IconButton(onClick = { actions.startWave(WaveSettings(genre = viewModel.genre, seed = WaveSeed.Genre(viewModel.genre))) }) {
                Icon(Icons.Rounded.Shuffle, contentDescription = stringResource(R.string.genre_wave))
            }
        },
        header = if (albums.isEmpty()) {
            null
        } else {
            {
                SectionHeader(stringResource(R.string.library_albums))
                androidx.compose.foundation.lazy.LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(albums, key = { it.id }) { album -> AlbumCard(album, onClick = { navigator.openAlbum(album.id) }) }
                }
                SectionHeader(stringResource(R.string.library_tracks))
            }
        },
    )
}

// endregion
