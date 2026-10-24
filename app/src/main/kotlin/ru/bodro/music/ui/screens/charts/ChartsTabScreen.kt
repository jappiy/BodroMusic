package ru.bodro.music.ui.screens.charts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.bodro.music.R
import ru.bodro.music.core.downloads.Chart
import ru.bodro.music.core.model.QueueSource
import ru.bodro.music.data.reco.ChartsRepository
import ru.bodro.music.data.repository.AlbumSort
import ru.bodro.music.data.repository.HomeData
import ru.bodro.music.data.repository.LibraryRepository
import ru.bodro.music.ui.app.LocalAppActions
import ru.bodro.music.ui.app.LocalNavigator
import ru.bodro.music.ui.components.AlbumCard
import ru.bodro.music.ui.components.ArtistCircle
import ru.bodro.music.ui.components.BigAlbumCard
import ru.bodro.music.ui.components.EmptyState
import ru.bodro.music.ui.components.ErrorState
import ru.bodro.music.ui.components.LoadState
import ru.bodro.music.ui.components.LoadingState
import ru.bodro.music.ui.components.PlaylistCard
import ru.bodro.music.ui.components.SongCard
import ru.bodro.music.ui.components.carousel
import ru.bodro.music.ui.util.toUiError

/** Вкладка «Чарты»: чарты bodrod и подборки библиотеки, которые раньше были на главной под волной. */
@HiltViewModel
class ChartsTabViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val charts: ChartsRepository,
) : ViewModel() {
    private val _charts = MutableStateFlow<List<Chart>>(emptyList())
    val chartList: StateFlow<List<Chart>> = _charts.asStateFlow()

    private val _state = MutableStateFlow<LoadState<HomeData>>(LoadState.Loading)
    val state: StateFlow<LoadState<HomeData>> = _state.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch { _charts.value = charts.home() }
        viewModelScope.launch {
            if (_state.value !is LoadState.Content) _state.value = LoadState.Loading
            _state.value = runCatching { library.home() }
                .fold({ LoadState.Content(it) }, { LoadState.Error(it.toUiError()) })
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            launch { _charts.value = charts.home() }
            library.invalidate()
            runCatching { library.home() }
                .onSuccess { _state.value = LoadState.Content(it) }
                .onFailure { if (_state.value !is LoadState.Content) _state.value = LoadState.Error(it.toUiError()) }
            _refreshing.value = false
        }
    }
}

@Composable
fun ChartsTabScreen(
    onOpenWave: () -> Unit,
    onShowAllAlbums: (AlbumSort) -> Unit,
    onShowLiked: () -> Unit,
    onShowArtists: () -> Unit,
    onShowPlaylists: () -> Unit,
    onOpenChart: (Chart) -> Unit,
    viewModel: ChartsTabViewModel = hiltViewModel(),
) {
    val charts by viewModel.chartList.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val navigator = LocalNavigator.current

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize().statusBarsPadding().testTag("charts-tab"),
    ) {
        LazyColumn(Modifier.fillMaxSize().testTag("charts-list"), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "title") {
                Text(
                    stringResource(R.string.tab_charts),
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            carousel("charts", { stringResource(R.string.home_charts) }, charts, null, { it.id }) { chart ->
                ChartCard(chart, onClick = { onOpenChart(chart) })
            }
            item(key = "wave-more") {
                TextButton(onClick = onOpenWave, modifier = Modifier.padding(horizontal = 8.dp).testTag("wave-more")) {
                    Text(stringResource(R.string.wave_more_settings))
                }
            }
            when (val s = state) {
                LoadState.Loading -> item(key = "loading") { Box(Modifier.height(240.dp)) { LoadingState() } }
                is LoadState.Error -> item(key = "error") {
                    Box(Modifier.height(420.dp)) { ErrorState(s.error, onRetry = viewModel::load) }
                }
                is LoadState.Content -> {
                    val data: HomeData = s.data
                    if (data.isEmpty) {
                        item(key = "empty") {
                            Box(Modifier.height(320.dp)) {
                                EmptyState(stringResource(R.string.home_empty_title), stringResource(R.string.home_empty_message))
                            }
                        }
                    }
                    carousel(
                        key = "starred",
                        title = { stringResource(R.string.home_starred_tracks) },
                        items = data.starredSongs,
                        onShowAll = onShowLiked,
                        itemKey = { it.id },
                    ) { song ->
                        SongCard(
                            song,
                            onClick = { actions.play(data.starredSongs, data.starredSongs.indexOf(song), QueueSource.LikedSource) },
                            onLongClick = { actions.showSongMenu(song) },
                        )
                    }
                    carousel("newest", {
                        stringResource(R.string.home_newest_albums)
                    }, data.newestAlbums, { onShowAllAlbums(AlbumSort.Newest) }, { it.id }) { album ->
                        AlbumCard(album, onClick = { navigator.openAlbum(album.id) })
                    }
                    carousel("recent", {
                        stringResource(R.string.home_recent_albums)
                    }, data.recentAlbums, { onShowAllAlbums(AlbumSort.Recent) }, { it.id }) { album ->
                        BigAlbumCard(album, onClick = { navigator.openAlbum(album.id) })
                    }
                    carousel("artists", {
                        stringResource(R.string.home_random_artists)
                    }, data.randomArtists, onShowArtists, { it.id }) { artist ->
                        ArtistCircle(artist, onClick = { navigator.openArtist(artist.id) })
                    }
                    carousel("playlists", {
                        stringResource(R.string.home_playlists)
                    }, data.playlists, onShowPlaylists, { it.id }) { playlist ->
                        PlaylistCard(playlist, onClick = { navigator.openPlaylist(playlist.id) })
                    }
                }
            }
        }
    }
}
