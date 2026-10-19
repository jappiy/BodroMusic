package ru.bodro.music.ui.screens.collection

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.bodro.music.R
import ru.bodro.music.core.model.Album
import ru.bodro.music.core.model.Artist
import ru.bodro.music.core.model.QueueSource
import ru.bodro.music.core.model.Song
import ru.bodro.music.core.model.WaveSeed
import ru.bodro.music.core.model.WaveSettings
import ru.bodro.music.data.downloads.LikedCheck
import ru.bodro.music.data.repository.LibraryRepository
import ru.bodro.music.data.repository.ListeningStats
import ru.bodro.music.data.repository.ReactionRepository
import ru.bodro.music.ui.app.LocalAppActions
import ru.bodro.music.ui.app.LocalNavigator
import ru.bodro.music.ui.components.AlbumCard
import ru.bodro.music.ui.components.ArtistCircle
import ru.bodro.music.ui.components.BodroTopBar
import ru.bodro.music.ui.components.EmptyState
import ru.bodro.music.ui.components.SectionHeader
import ru.bodro.music.ui.components.SongRow
import ru.bodro.music.ui.screens.pool.PoolCountViewModel
import ru.bodro.music.ui.screens.wave.WaveBackground
import ru.bodro.music.ui.screens.wave.waveColors
import ru.bodro.music.ui.util.UiError
import ru.bodro.music.ui.util.toUiError

data class CollectionData(
    val likedSongs: List<Song> = emptyList(),
    val starredArtists: List<Artist> = emptyList(),
    val starredAlbums: List<Album> = emptyList(),
    /** Статистика прослушиваний (null — ещё не загружена или сервер недоступен). */
    val stats: ListeningStats? = null,
    val error: UiError? = null,
)

@HiltViewModel
class CollectionViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val likedCheck: LikedCheck,
    reactions: ReactionRepository,
) : ViewModel() {
    private val remote = MutableStateFlow(CollectionData())

    /** Лайки — из кэша Room (работает без сети), исполнители/альбомы — с сервера. */
    val state: StateFlow<CollectionData> = combine(remote, reactions.observeLikedCache()) { r, liked ->
        r.copy(likedSongs = liked)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CollectionData())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { library.starred() }
                .onSuccess { starred ->
                    remote.update { it.copy(starredArtists = starred.artists, starredAlbums = starred.albums, error = null) }
                    likedCheck.run()
                }
                .onFailure { e -> remote.update { it.copy(error = e.toUiError()) } }
        }
        viewModelScope.launch {
            runCatching { library.listeningStats() }.onSuccess { stats -> remote.update { it.copy(stats = stats) } }
        }
    }
}

@Composable
fun CollectionScreen(
    onOpenLiked: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onOpenArtists: () -> Unit,
    onOpenStarredArtists: () -> Unit,
    onOpenAlbums: () -> Unit,
    onOpenStarredAlbums: () -> Unit,
    onOpenGenres: () -> Unit,
    onOpenTracks: () -> Unit,
    onOpenPool: () -> Unit = {},
    viewModel: CollectionViewModel = hiltViewModel(),
    poolCount: PoolCountViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val inPool by poolCount.count.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val navigator = LocalNavigator.current
    val favoriteTracksTitle = stringResource(R.string.collection_favorite_tracks)
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().testTag("collection-screen"), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text(
                stringResource(R.string.tab_collection),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = if (state.stats == null) 12.dp else 0.dp),
            )
            state.stats?.let { stats ->
                Text(
                    stringResource(R.string.collection_total_plays, formatCount(stats.totalPlays)),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 12.dp).testTag("collection-total-plays"),
                )
            }
        }
        item {
            // «Моя волна по коллекции».
            Box(
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .height(96.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .clickable { actions.startWave(WaveSettings(seed = WaveSeed.Collection)) }
                    .testTag("collection-wave"),
            ) {
                WaveBackground(waveColors(), Modifier.matchParentSize(), animate = false)
                Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Waves, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.size(12.dp))
                    Text(stringResource(R.string.collection_wave), style = MaterialTheme.typography.titleLarge, color = Color.White)
                }
            }
        }
        item {
            Column {
                Spacer(Modifier.height(8.dp))
                CollectionEntry(
                    Icons.Rounded.Favorite,
                    stringResource(R.string.liked_title),
                    pluralStringResource(R.plurals.tracks, state.likedSongs.size, state.likedSongs.size),
                    onOpenLiked,
                    "entry-liked",
                )
                CollectionEntry(
                    Icons.AutoMirrored.Rounded.QueueMusic,
                    stringResource(R.string.playlists_title),
                    null,
                    onOpenPlaylists,
                    "entry-playlists",
                )
                if (inPool > 0) {
                    CollectionEntry(
                        Icons.Rounded.NewReleases,
                        stringResource(R.string.pool_title),
                        pluralStringResource(R.plurals.tracks, inPool, inPool),
                        onOpenPool,
                        "entry-pool",
                    )
                }
            }
        }
        val topArtists = state.stats?.topArtists.orEmpty()
        if (topArtists.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.collection_favorite_artists)) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(topArtists, key = { it.artist.id }) { (artist, plays) ->
                        ArtistCircle(
                            artist,
                            onClick = { navigator.openArtist(artist.id) },
                            subtitle = pluralStringResource(R.plurals.plays, plays.toInt(), plays.toInt()),
                        )
                    }
                }
            }
        }
        val topSongs = state.stats?.topSongs.orEmpty()
        if (topSongs.isNotEmpty()) {
            item { SectionHeader(favoriteTracksTitle) }
            itemsIndexed(topSongs.take(FAVORITE_TRACKS_SHOWN), key = { _, song -> "top-${song.id}" }) { index, song ->
                SongRow(
                    song,
                    onClick = { actions.play(topSongs, index, QueueSource.CustomSource(favoriteTracksTitle)) },
                    showPlays = true,
                )
            }
        }
        if (state.starredArtists.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.collection_starred_artists), onShowAll = onOpenStarredArtists) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.starredArtists, key = { it.id }) { ArtistCircle(it, onClick = { navigator.openArtist(it.id) }) }
                }
            }
        }
        if (state.starredAlbums.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.collection_favorite_albums), onShowAll = onOpenStarredAlbums) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.starredAlbums, key = { it.id }) { AlbumCard(it, onClick = { navigator.openAlbum(it.id) }) }
                }
            }
        }
        item { SectionHeader(stringResource(R.string.collection_library)) }
        item {
            Column {
                CollectionEntry(Icons.Rounded.Person, stringResource(R.string.library_artists), null, onOpenArtists, "entry-artists")
                CollectionEntry(Icons.Rounded.Album, stringResource(R.string.library_albums), null, onOpenAlbums, "entry-albums")
                CollectionEntry(Icons.Rounded.Category, stringResource(R.string.library_genres), null, onOpenGenres, "entry-genres")
                CollectionEntry(Icons.Rounded.MusicNote, stringResource(R.string.library_tracks), null, onOpenTracks, "entry-tracks")
            }
        }
    }
}

private const val FAVORITE_TRACKS_SHOWN = 10

/** 12345 → «12 345». */
private fun formatCount(value: Long): String = "%,d".format(java.util.Locale.forLanguageTag("ru"), value)

@Composable
private fun CollectionEntry(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit, tag: String) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick).testTag(tag),
    )
}

// region «Мне нравится»

data class LikedUi(
    val all: List<Song> = emptyList(),
    val query: String = "",
    val genre: String? = null,
    val refreshing: Boolean = false,
) {
    val genres: List<String> = all.mapNotNull {
        it.genre
    }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(12)
    val visible: List<Song> = all.filter { song ->
        (genre == null || song.genre == genre) &&
            (query.isBlank() || listOfNotNull(song.title, song.artist, song.album).any { it.contains(query.trim(), ignoreCase = true) })
    }
}

@HiltViewModel
class LikedViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val likedCheck: LikedCheck,
    reactions: ReactionRepository,
) : ViewModel() {
    private val filters = MutableStateFlow(LikedUi())

    // Кэш лайков в Room обновляется сразу при снятии/постановке лайка.
    val state: StateFlow<LikedUi> = combine(filters, reactions.observeLikedCache()) { f, liked -> f.copy(all = liked) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LikedUi())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            filters.update { it.copy(refreshing = true) }
            runCatching { library.starred() }
            runCatching { likedCheck.run() } // удалённые с сервера треки не показываем
            filters.update { it.copy(refreshing = false) }
        }
    }

    fun setQuery(query: String) = filters.update { it.copy(query = query) }

    fun setGenre(genre: String?) = filters.update { it.copy(genre = if (it.genre == genre) null else genre) }
}

@Composable
fun LikedScreen(onBack: () -> Unit, viewModel: LikedViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val songs = state.visible
    Scaffold(topBar = { BodroTopBar(stringResource(R.string.liked_title), onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("liked-screen"), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        pluralStringResource(R.plurals.tracks, state.all.size, state.all.size),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    val waveTitle = listTitle(state)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            onClick = {
                                actions.startWave(
                                    WaveSettings(
                                        genre = state.genre,
                                        seed = WaveSeed.SongList(
                                            waveTitle,
                                            songs.map {
                                                it.id
                                            },
                                        ),
                                    ),
                                )
                            },
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f).testTag("liked-wave"),
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Waves, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    stringResource(R.string.liked_wave),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                        Surface(onClick = {
                            actions.play(songs, 0, QueueSource.LikedSource)
                        }, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Icon(
                                Icons.Rounded.PlayArrow,
                                contentDescription = stringResource(R.string.action_play),
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                        Surface(onClick = {
                            actions.play(songs, 0, QueueSource.LikedSource, shuffle = true)
                        }, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Icon(
                                Icons.Rounded.Shuffle,
                                contentDescription = stringResource(R.string.action_shuffle),
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::setQuery,
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        placeholder = { Text(stringResource(R.string.liked_search_hint)) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (state.genres.isNotEmpty()) {
                item {
                    LazyRow(contentPadding = PaddingValues(16.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.genres) { genre ->
                            FilterChip(selected = state.genre == genre, onClick = { viewModel.setGenre(genre) }, label = { Text(genre) })
                        }
                    }
                }
            }
            if (songs.isEmpty()) {
                item { Box(Modifier.height(300.dp)) { EmptyState(stringResource(R.string.liked_empty)) } }
            }
            itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                SongRow(song, onClick = { actions.play(songs, index, QueueSource.LikedSource) })
            }
        }
    }
}

@Composable
private fun listTitle(state: LikedUi): String = state.genre ?: stringResource(R.string.liked_title)

// endregion
