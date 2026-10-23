package ru.bodro.music.ui.screens.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.bodro.music.R
import ru.bodro.music.core.downloads.Chart
import ru.bodro.music.core.model.QueueSource
import ru.bodro.music.data.repository.AlbumSort
import ru.bodro.music.data.repository.HomeData
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
import ru.bodro.music.ui.screens.charts.ChartCard

@Composable
fun HomeScreen(
    onOpenWave: () -> Unit,
    onShowAllAlbums: (AlbumSort) -> Unit,
    onShowLiked: () -> Unit,
    onShowArtists: () -> Unit,
    onShowPlaylists: () -> Unit,
    onOpenChart: (Chart) -> Unit = {},
    onOpenSearch: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val charts by viewModel.chartList.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val player by viewModel.player.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val facts by viewModel.facts.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val navigator = LocalNavigator.current
    var waveSettings by rememberSaveable { mutableStateOf(false) }

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize().testTag("home-screen")) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "wave") {
                WaveHome(
                    player = player,
                    settings = settings,
                    facts = facts,
                    onTogglePlay = viewModel::togglePlay,
                    onNext = viewModel::next,
                    onPrevious = viewModel::previous,
                    onSelectPreset = viewModel::setPreset,
                    onOpenSettings = { waveSettings = true },
                    onOpenSearch = onOpenSearch,
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
    if (waveSettings) {
        WaveSettingsSheet(
            settings,
            onCharacter = viewModel::setCharacter,
            onMood = viewModel::setMood,
            onDismiss = { waveSettings = false },
        )
    }
}
