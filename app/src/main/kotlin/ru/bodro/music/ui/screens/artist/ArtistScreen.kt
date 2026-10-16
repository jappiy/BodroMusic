package ru.bodro.music.ui.screens.artist

import android.content.Context
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import ru.bodro.music.R
import ru.bodro.music.core.domain.reaction.ReactionTarget
import ru.bodro.music.core.downloads.DiscoverTrack
import ru.bodro.music.core.downloads.DownloadRequest
import ru.bodro.music.core.downloads.ServiceInfo
import ru.bodro.music.core.model.ArtistDetails
import ru.bodro.music.core.model.QueueSource
import ru.bodro.music.core.model.Song
import ru.bodro.music.core.model.WaveSeed
import ru.bodro.music.core.model.WaveSettings
import ru.bodro.music.data.downloads.DownloadsRepository
import ru.bodro.music.data.repository.LibraryRepository
import ru.bodro.music.data.repository.ReactionRepository
import ru.bodro.music.ui.app.ArtistRoute
import ru.bodro.music.ui.app.LocalAppActions
import ru.bodro.music.ui.app.LocalNavigator
import ru.bodro.music.ui.app.LocalReactions
import ru.bodro.music.ui.components.AlbumCard
import ru.bodro.music.ui.components.ArtistAvatar
import ru.bodro.music.ui.components.ErrorState
import ru.bodro.music.ui.components.LoadState
import ru.bodro.music.ui.components.LoadingState
import ru.bodro.music.ui.components.SectionHeader
import ru.bodro.music.ui.components.SongRow
import ru.bodro.music.ui.screens.search.DiscoverCallbacks
import ru.bodro.music.ui.screens.search.DiscoverTrackRow
import ru.bodro.music.ui.util.text
import ru.bodro.music.ui.util.toUiError

/**
 * Популярное по каталогу (Яндекс Музыка через bodrod): [inLibrary] — уже скачанные, в порядке популярности;
 * [missing] — ещё не скачанные, их можно поставить в загрузку.
 */
data class ArtistPopularUi(val inLibrary: List<Song> = emptyList(), val missing: List<DiscoverTrack> = emptyList())

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ArtistViewModel @Inject constructor(
    savedState: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val library: LibraryRepository,
    private val reactions: ReactionRepository,
    private val downloads: DownloadsRepository,
) : ViewModel() {
    private val artistId = savedState.toRoute<ArtistRoute>().id
    private val _state = MutableStateFlow<LoadState<Pair<ArtistDetails, List<Song>>>>(LoadState.Loading)
    val state: StateFlow<LoadState<Pair<ArtistDetails, List<Song>>>> = _state.asStateFlow()

    private val _popular = MutableStateFlow<ArtistPopularUi?>(null)
    val popular: StateFlow<ArtistPopularUi?> = _popular.asStateFlow()

    /** Заявки на загрузку по ключу «исполнитель|название»; пока экран открыт — с опросом сервиса. */
    val requests: StateFlow<Map<String, DownloadRequest>> = combine(
        downloads.requests,
        downloads.serviceUrl.flatMapLatest { url -> if (url != null) downloads.observe() else emptyFlow() }.onStart { emit(emptyList()) },
    ) { list, _ -> list.sortedBy { it.createdAt }.associateBy { it.key } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val info: StateFlow<ServiceInfo?> = downloads.serviceUrl
        .transformLatest { url -> emit(if (url != null) runCatching { downloads.info() }.getOrNull() else null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = LoadState.Loading
            val result = runCatching { library.artist(artistId) }
            _state.value = result.fold({ LoadState.Content(it) }, { LoadState.Error(it.toUiError()) })
            result.getOrNull()?.let { loadPopular(it.first.artist.name) }
        }
    }

    private suspend fun loadPopular(name: String) {
        if (!downloads.isConfigured) return
        val popular = runCatching { downloads.artistPopular(name) }.getOrNull() ?: return
        if (popular.tracks.isEmpty()) return
        val ids = popular.tracks.mapNotNull { it.librarySongId }.distinct()
        val songs = coroutineScope {
            ids.map { id -> async { runCatching { library.song(id) }.getOrNull() } }.awaitAll().filterNotNull()
        }
        reactions.ingest(songs)
        _popular.value = ArtistPopularUi(
            inLibrary = songs,
            missing = popular.tracks.filter { it.librarySongId == null }.distinctBy { it.key },
        )
    }

    fun download(track: DiscoverTrack) {
        viewModelScope.launch {
            runCatching { downloads.downloadTrack(track) }
                .onSuccess { _messages.send(context.getString(R.string.discover_queued_track, "${track.artist} — ${track.title}")) }
                .onFailure { _messages.send(it.toUiError().text(context)) }
        }
    }

    fun playLibrarySong(songId: String, play: (Song) -> Unit) {
        viewModelScope.launch { runCatching { library.song(songId) }.onSuccess(play) }
    }

    suspend fun allSongs(): List<Song> = library.artistSongs(artistId)

    fun toggleLike(current: Boolean) {
        viewModelScope.launch { reactions.setStarred(ReactionTarget.Artist, artistId, !current) }
    }

    fun loadAllSongs(play: (List<Song>) -> Unit) {
        viewModelScope.launch { runCatching { allSongs() }.onSuccess(play) }
    }
}

@Composable
fun ArtistScreen(onBack: () -> Unit, viewModel: ArtistViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    LaunchedEffect(viewModel) { viewModel.messages.collect { actions.showMessage(it) } }
    when (val s = state) {
        LoadState.Loading -> LoadingState()
        is LoadState.Error -> ErrorState(s.error, onRetry = viewModel::load)
        is LoadState.Content -> ArtistContent(s.data.first, s.data.second, onBack, viewModel)
    }
}

@Composable
private fun ArtistContent(details: ArtistDetails, topSongs: List<Song>, onBack: () -> Unit, viewModel: ArtistViewModel) {
    val actions = LocalAppActions.current
    val navigator = LocalNavigator.current
    val artist = details.artist
    val liked = LocalReactions.current.artistLiked(artist.id, artist.starred)
    var showAllTop by rememberSaveable { mutableStateOf(false) }
    var showAllMissing by rememberSaveable { mutableStateOf(false) }
    val popular by viewModel.popular.collectAsStateWithLifecycle()
    val requests by viewModel.requests.collectAsStateWithLifecycle()
    val info by viewModel.info.collectAsStateWithLifecycle()
    val source = QueueSource.ArtistSource(artist.id, artist.name)
    // Популярное из каталога, если bodrod его отдал; иначе — своя история и getTopSongs Navidrome.
    val top = popular?.inLibrary?.takeIf { it.isNotEmpty() } ?: topSongs
    val visibleTop = if (showAllTop) top else top.take(TOP_SHOWN)
    val missing = popular?.missing.orEmpty()
    val visibleMissing = if (showAllMissing) missing else missing.take(TOP_SHOWN)
    val callbacks = remember(viewModel, source) {
        DiscoverCallbacks(
            onDownload = viewModel::download,
            onDownloadArtist = {},
            onSelectArtist = {},
            onPlay = { id -> viewModel.playLibrarySong(id) { actions.play(listOf(it), 0, source) } },
            onRetry = {},
            onShowMore = {},
        )
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize().testTag("artist-screen"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth()) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
                ArtistAvatar(artist.coverArt, Modifier.size(180.dp), sizeHint = 600.dp)
                Spacer(Modifier.height(16.dp))
                Text(artist.name, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
                Text(
                    pluralStringResource(R.plurals.albums, details.albums.size, details.albums.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.toggleLike(liked) }) {
                        Icon(
                            if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = stringResource(if (liked) R.string.action_unlike else R.string.action_like),
                            tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Button(onClick = {
                        actions.startWave(WaveSettings(seed = WaveSeed.Artist(artist.id, artist.name)))
                    }, modifier = Modifier.testTag("artist-wave")) {
                        Icon(Icons.Rounded.Waves, contentDescription = null)
                        Text(stringResource(R.string.artist_wave))
                    }
                    FilledTonalButton(onClick = { viewModel.loadAllSongs { actions.play(it, 0, source, shuffle = true) } }) {
                        Icon(Icons.Rounded.Shuffle, contentDescription = null)
                        Text(stringResource(R.string.action_shuffle))
                    }
                }
            }
        }
        if (top.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(stringResource(R.string.artist_top_tracks)) }
            visibleTop.forEachIndexed { index, song ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "top-${song.id}") {
                    SongRow(song, onClick = { actions.play(top, index, source) }, modifier = Modifier.padding(horizontal = 0.dp))
                }
            }
            if (top.size > TOP_SHOWN) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    TextButton(onClick = { showAllTop = !showAllTop }) {
                        Text(stringResource(if (showAllTop) R.string.action_show_less else R.string.action_show_more))
                    }
                }
            }
        }
        if (missing.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "missing-header") {
                Column(Modifier.testTag("artist-missing")) {
                    SectionHeader(stringResource(R.string.artist_popular_missing))
                    Text(
                        stringResource(R.string.artist_popular_missing_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            val canDownload = info?.trackDownloads != false
            visibleMissing.forEach { track ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "missing-${track.source}-${track.sourceId}") {
                    DiscoverTrackRow(track, requests[track.key], canDownload, callbacks)
                }
            }
            if (missing.size > TOP_SHOWN) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    TextButton(onClick = { showAllMissing = !showAllMissing }) {
                        Text(stringResource(if (showAllMissing) R.string.action_show_less else R.string.action_show_more))
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(stringResource(R.string.artist_albums)) }
        items(details.albums, key = { it.id }) { album ->
            AlbumCard(album, onClick = { navigator.openAlbum(album.id) }, width = null)
        }
    }
}

private const val TOP_SHOWN = 5
