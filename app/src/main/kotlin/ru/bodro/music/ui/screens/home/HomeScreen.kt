package ru.bodro.music.ui.screens.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.bodro.music.R
import ru.bodro.music.core.model.QueueSource
import ru.bodro.music.core.model.WaveMode
import ru.bodro.music.core.model.WaveSettings
import ru.bodro.music.data.repository.AlbumSort
import ru.bodro.music.data.repository.HomeData
import ru.bodro.music.playback.PlayerUiState
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
import ru.bodro.music.ui.screens.wave.TrackCapsule
import ru.bodro.music.ui.screens.wave.WaveBackground
import ru.bodro.music.ui.screens.wave.WaveChip
import ru.bodro.music.ui.screens.wave.WaveModes
import ru.bodro.music.ui.screens.wave.WavePlayButton
import ru.bodro.music.ui.screens.wave.waveColors
import ru.bodro.music.ui.screens.wave.waveModeLabel
import ru.bodro.music.ui.theme.WaveTitleStyle

@Composable
fun HomeScreen(
    onOpenWave: () -> Unit,
    onShowAllAlbums: (AlbumSort) -> Unit,
    onShowLiked: () -> Unit,
    onShowArtists: () -> Unit,
    onShowPlaylists: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val player by viewModel.player.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val navigator = LocalNavigator.current

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize().testTag("home-screen")) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "hero") { WaveHero(player, onOpenWave, onTogglePlay = viewModel::togglePlay) }
            item(key = "mixes") { MixesRow() }
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

/** Hero-блок «Моя волна» (в стиле Яндекса): облако, большая кнопка, текущий трек волны, ленточка режимов. */
@Composable
private fun WaveHero(player: PlayerUiState, onOpenWave: () -> Unit, onTogglePlay: () -> Unit) {
    val actions = LocalAppActions.current
    val wavePlaying = player.source.isWave && player.hasMedia
    Box(
        Modifier
            .fillMaxWidth()
            // Блок не должен заходить под системную строку состояния (edge-to-edge).
            .statusBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 12.dp)
            .clip(RoundedCornerShape(28.dp))
            .clickable(onClick = onOpenWave)
            .testTag("wave-hero"),
    ) {
        WaveBackground(waveColors(), Modifier.matchParentSize(), animate = !wavePlaying || player.isPlaying)
        Column(Modifier.padding(top = 28.dp, bottom = 16.dp)) {
            Text(
                stringResource(R.string.wave_title),
                style = WaveTitleStyle,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            if (wavePlaying) {
                Text(
                    player.currentSong?.artist.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                WavePlayButton(
                    playing = wavePlaying && player.isPlaying,
                    loading = player.waveLoading,
                    onClick = { if (wavePlaying) onTogglePlay() else actions.startWave(WaveSettings()) },
                )
                Spacer(Modifier.size(16.dp))
                if (wavePlaying) TrackCapsule(player)
            }
            Spacer(Modifier.height(20.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(WaveModes.drop(1)) { mode ->
                    WaveChip(waveModeLabel(mode), selected = false) { actions.startWave(WaveSettings(mode = mode)) }
                }
            }
        }
    }
}

@Composable
private fun MixesRow() {
    val actions = LocalAppActions.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MixButton(Icons.Rounded.Shuffle, stringResource(R.string.mix_tracks), Modifier.weight(1f)) {
            actions.startWave(WaveSettings(mode = WaveMode.TrackMix))
        }
        MixButton(Icons.Rounded.Album, stringResource(R.string.mix_albums), Modifier.weight(1f)) {
            actions.startWave(WaveSettings(mode = WaveMode.AlbumMix))
        }
        MixButton(Icons.Rounded.CalendarMonth, stringResource(R.string.mix_decade), Modifier.weight(1f)) {
            actions.startWave(WaveSettings(mode = WaveMode.DecadeMix))
        }
    }
}

@Composable
private fun MixButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Column(Modifier.padding(vertical = 14.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
