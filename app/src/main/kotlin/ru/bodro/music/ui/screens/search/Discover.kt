package ru.bodro.music.ui.screens.search

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.SubcomposeAsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import ru.bodro.music.R
import ru.bodro.music.core.downloads.DiscoverArtist
import ru.bodro.music.core.downloads.DiscoverIntent
import ru.bodro.music.core.downloads.DiscoverResult
import ru.bodro.music.core.downloads.DiscoverTrack
import ru.bodro.music.core.downloads.DownloadRequest
import ru.bodro.music.core.downloads.RequestStatus
import ru.bodro.music.core.downloads.ServiceInfo
import ru.bodro.music.core.downloads.artistKey
import ru.bodro.music.core.model.Song
import ru.bodro.music.data.downloads.DownloadsRepository
import ru.bodro.music.data.repository.LibraryRepository
import ru.bodro.music.ui.components.CoverImage
import ru.bodro.music.ui.components.CoverKind
import ru.bodro.music.ui.components.ErrorState
import ru.bodro.music.ui.components.LoadState
import ru.bodro.music.ui.components.SectionHeader
import ru.bodro.music.ui.image.librarySongCover
import ru.bodro.music.ui.util.formatDuration
import ru.bodro.music.ui.util.text
import ru.bodro.music.ui.util.toUiError

/** Состояние глобального поиска для запроса [query]. [selected] — исполнитель, выбранный из списка. */
data class DiscoverUi(
    val query: String,
    val result: LoadState<DiscoverResult>,
    val selected: DiscoverArtist? = null,
    val selectedTracks: LoadState<List<DiscoverTrack>>? = null,
)

sealed interface DiscoverEvent {
    data class Message(val text: String) : DiscoverEvent

    data class Play(val song: Song) : DiscoverEvent
}

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloads: DownloadsRepository,
    private val library: LibraryRepository,
) : ViewModel() {
    val configured: StateFlow<Boolean> = downloads.serviceUrl
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, downloads.serviceUrl.value != null)

    /** Возможности сервиса (есть ли slskd и Lidarr). `null` — неизвестно, кнопки показываем. */
    val info: StateFlow<ServiceInfo?> = configured
        .transformLatest { on -> emit(if (on) runCatching { downloads.info() }.getOrNull() else null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Заявки по ключу «исполнитель|название»; пока экран открыт, список обновляется опросом сервиса. */
    val requests: StateFlow<Map<String, DownloadRequest>> = combine(
        downloads.requests,
        configured.flatMapLatest { on -> if (on) downloads.observe() else emptyFlow() }.onStart { emit(emptyList()) },
    ) { list, _ -> list.sortedBy { it.createdAt }.associateBy { it.key } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _state = MutableStateFlow<DiscoverUi?>(null)
    val state: StateFlow<DiscoverUi?> = _state.asStateFlow()

    private val _events = Channel<DiscoverEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var searchJob: Job? = null
    private var artistJob: Job? = null

    fun search(query: String) {
        val current = _state.value
        if (current != null && current.query == query && current.result !is LoadState.Error) return
        searchJob?.cancel()
        artistJob?.cancel()
        _state.value = DiscoverUi(query, LoadState.Loading)
        searchJob = viewModelScope.launch {
            val result = runCatching { downloads.discover(query) }
                .fold({ LoadState.Content(it) }, { LoadState.Error(it.toUiError()) })
            _state.value = _state.value?.takeIf { it.query == query }?.copy(result = result)
        }
    }

    fun reset() {
        searchJob?.cancel()
        artistJob?.cancel()
        _state.value = null
    }

    fun retry() {
        val query = _state.value?.query ?: return
        _state.value = null
        search(query)
    }

    fun selectArtist(artist: DiscoverArtist) {
        val current = _state.value ?: return
        artistJob?.cancel()
        val main = (current.result as? LoadState.Content)?.data?.artist
        if (artist == main || artist == current.selected) {
            _state.value = current.copy(selected = null, selectedTracks = null)
            return
        }
        _state.value = current.copy(selected = artist, selectedTracks = LoadState.Loading)
        artistJob = viewModelScope.launch {
            val tracks = runCatching { downloads.artistTopTracks(artist) }
                .fold({ LoadState.Content(it) }, { LoadState.Error(it.toUiError()) })
            _state.value = _state.value?.takeIf { it.selected == artist }?.copy(selectedTracks = tracks)
        }
    }

    fun download(track: DiscoverTrack) {
        viewModelScope.launch {
            runCatching { downloads.downloadTrack(track) }
                .onSuccess { message(context.getString(R.string.discover_queued_track, "${track.artist} — ${track.title}")) }
                .onFailure { message(it.toUiError().text(context)) }
        }
    }

    fun downloadArtist(artist: DiscoverArtist) {
        viewModelScope.launch {
            runCatching { downloads.downloadArtist(artist) }
                .onSuccess { message(context.getString(R.string.discover_queued_artist, artist.name)) }
                .onFailure { message(it.toUiError().text(context)) }
        }
    }

    fun cancel(request: DownloadRequest) {
        viewModelScope.launch {
            runCatching { downloads.delete(request) }
                .onSuccess { message(context.getString(R.string.discover_cancelled, request.artist)) }
                .onFailure { message(it.toUiError().text(context)) }
        }
    }

    fun play(songId: String) {
        viewModelScope.launch {
            runCatching { library.song(songId) }
                .onSuccess { _events.send(DiscoverEvent.Play(it)) }
                .onFailure { message(it.toUiError().text(context)) }
        }
    }

    private suspend fun message(text: String) = _events.send(DiscoverEvent.Message(text))
}

/** Колбэки блока «Найти и скачать». */
class DiscoverCallbacks(
    val onDownload: (DiscoverTrack) -> Unit,
    val onDownloadArtist: (DiscoverArtist) -> Unit,
    val onSelectArtist: (DiscoverArtist) -> Unit,
    val onPlay: (String) -> Unit,
    val onRetry: () -> Unit,
    val onShowMore: () -> Unit,
    /** Отменить активную заявку (для исполнителя — Lidarr перестаёт его отслеживать). */
    val onCancel: (DownloadRequest) -> Unit = {},
)

private const val COLLAPSED_TRACKS = 8

/** Блок глобального поиска в списке результатов. */
fun LazyListScope.discoverSection(
    ui: DiscoverUi,
    requests: Map<String, DownloadRequest>,
    info: ServiceInfo?,
    expanded: Boolean,
    callbacks: DiscoverCallbacks,
) {
    item(key = "discover-header") {
        val source = (ui.result as? LoadState.Content)?.data?.source
        Column {
            SectionHeader(stringResource(R.string.search_discover_title))
            if (source != null) {
                Text(
                    stringResource(R.string.search_discover_source, sourceName(source)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
                )
            }
        }
    }
    when (val result = ui.result) {
        LoadState.Loading -> item(key = "discover-loading") { InlineLoading() }
        is LoadState.Error -> item(key = "discover-error") { Box(Modifier.height(320.dp)) { ErrorState(result.error, callbacks.onRetry) } }
        is LoadState.Content -> discoverContent(ui, result.data, requests, info, expanded, callbacks)
    }
}

private fun LazyListScope.discoverContent(
    ui: DiscoverUi,
    data: DiscoverResult,
    requests: Map<String, DownloadRequest>,
    info: ServiceInfo?,
    expanded: Boolean,
    callbacks: DiscoverCallbacks,
) {
    if (data.isEmpty) {
        item(key = "discover-empty") {
            Text(
                stringResource(R.string.search_discover_nothing),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        return
    }
    val canTracks = info?.trackDownloads != false
    val canArtists = info?.artistDownloads != false
    val selected = ui.selected
    val mainArtist = data.artist.takeIf { data.intent == DiscoverIntent.Artist }
    val headerArtist = selected ?: mainArtist
    if (headerArtist != null) {
        item(key = "discover-artist-${headerArtist.source}-${headerArtist.sourceId}") {
            ArtistHeader(
                headerArtist,
                requests[artistKey(headerArtist.name)],
                canArtists,
                onDownloadAll = { callbacks.onDownloadArtist(headerArtist) },
                onCancel = callbacks.onCancel,
            )
        }
    }
    val (titleRes, tracks) = when {
        selected != null -> R.string.discover_popular to (ui.selectedTracks as? LoadState.Content)?.data.orEmpty()
        mainArtist != null -> R.string.discover_popular to data.topTracks.ifEmpty { data.tracks }
        else -> R.string.discover_tracks to data.tracks
    }
    if (selected != null && ui.selectedTracks is LoadState.Loading) {
        item(key = "discover-artist-loading") { InlineLoading() }
    }
    if (tracks.isNotEmpty()) {
        item(key = "discover-tracks-title") { SubHeader(stringResource(titleRes)) }
        val visible = if (expanded) tracks else tracks.take(COLLAPSED_TRACKS)
        items(visible, key = { "dt-${it.source}-${it.sourceId}" }) { track ->
            DiscoverTrackRow(track, requests[track.key], canTracks, callbacks)
        }
        if (!expanded && tracks.size > COLLAPSED_TRACKS) {
            item(key = "discover-more") {
                TextButton(onClick = callbacks.onShowMore, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(stringResource(R.string.discover_show_more))
                }
            }
        }
    }
    val otherArtists = data.artists.filter { it != headerArtist }
    if (otherArtists.isNotEmpty()) {
        item(key = "discover-artists") {
            Column {
                SubHeader(stringResource(R.string.discover_other_artists))
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(otherArtists, key = { "da-${it.source}-${it.sourceId}" }) { artist ->
                        ArtistBubble(artist) { callbacks.onSelectArtist(artist) }
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineLoading() {
    Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun SubHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

/** Название внешнего каталога bodrod для подписи «откуда данные». */
internal fun sourceName(source: String): String = when (source) {
    "yandex" -> "Яндекс Музыка"
    "deezer" -> "Deezer"
    "lastfm" -> "Last.fm"
    else -> source
}

@Composable
private fun ArtistHeader(
    artist: DiscoverArtist,
    request: DownloadRequest?,
    canDownload: Boolean,
    onDownloadAll: () -> Unit,
    onCancel: (DownloadRequest) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("discover-artist"),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RemoteImage(artist.coverUrl, Modifier.size(72.dp), CircleShape, Icons.Rounded.Person)
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(artist.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    request?.let { statusText(it) } ?: stringResource(R.string.discover_download_all_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when {
                request?.status?.isActive == true -> Row(verticalAlignment = Alignment.CenterVertically) {
                    RequestProgress(request)
                    IconButton(onClick = { onCancel(request) }, modifier = Modifier.testTag("discover-cancel-artist")) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.discover_cancel_artist))
                    }
                }
                request?.status == RequestStatus.Completed -> Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = stringResource(R.string.download_status_completed),
                    tint = MaterialTheme.colorScheme.primary,
                )
                canDownload -> FilledTonalButton(onClick = onDownloadAll, modifier = Modifier.testTag("discover-download-all")) {
                    Text(stringResource(R.string.discover_download_all))
                }
            }
        }
    }
}

@Composable
private fun ArtistBubble(artist: DiscoverArtist, onClick: () -> Unit) {
    Column(
        Modifier.width(88.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RemoteImage(artist.coverUrl, Modifier.size(72.dp), CircleShape, Icons.Rounded.Person)
        Text(
            artist.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
fun DiscoverTrackRow(track: DiscoverTrack, request: DownloadRequest?, canDownload: Boolean, callbacks: DiscoverCallbacks) {
    val librarySongId = track.librarySongId ?: request?.songId?.takeIf { request.status == RequestStatus.Completed }
    val activeOrDone = request != null && (request.status.isActive || request.status == RequestStatus.Completed)
    val onClick: (() -> Unit)? = when {
        librarySongId != null -> {
            { callbacks.onPlay(librarySongId) }
        }
        canDownload && !activeOrDone -> {
            { callbacks.onDownload(track) }
        }
        else -> null
    }
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
            .testTag("discover-track-${track.sourceId}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Трек есть в библиотеке — обложка с сервера (своя → альбома), иначе внешняя (Яндекс, Last.fm). Раньше у
        // похожих из Last.fm и по звучанию внешней обложки не было, и показывалась заглушка, хотя обложка есть.
        if (librarySongId != null) {
            CoverImage(
                listOf(librarySongCover(librarySongId)),
                Modifier.size(48.dp),
                sizeHint = 48.dp,
                shape = RoundedCornerShape(10.dp),
                kind = CoverKind.Song,
                remoteUrl = track.coverUrl,
            )
        } else {
            RemoteImage(track.coverUrl, Modifier.size(48.dp), RoundedCornerShape(10.dp), Icons.Rounded.MusicNote)
        }
        Column(Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val details = when {
                track.librarySongId != null -> stringResource(R.string.similar_artist_in_library, track.artist)
                request != null && request.status != RequestStatus.Cancelled -> statusText(request)
                else -> listOfNotNull(
                    track.artist,
                    track.album?.takeIf { it.isNotBlank() },
                    track.durationMs?.let { formatDuration(it / 1000) },
                ).joinToString(" · ")
            }
            Text(
                details,
                style = MaterialTheme.typography.bodySmall,
                color = if (request?.status == RequestStatus.Failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            when {
                librarySongId != null -> IconButton(onClick = { callbacks.onPlay(librarySongId) }) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.discover_in_library))
                }
                request?.status?.isActive == true -> RequestProgress(request)
                request?.status == RequestStatus.Completed -> Icon(
                    Icons.Rounded.LibraryMusic,
                    contentDescription = stringResource(R.string.download_status_completed),
                    tint = MaterialTheme.colorScheme.primary,
                )
                canDownload -> IconButton(onClick = { callbacks.onDownload(track) }) {
                    Icon(
                        if (request?.status == RequestStatus.Failed) Icons.Rounded.ErrorOutline else Icons.Rounded.Download,
                        contentDescription = stringResource(R.string.discover_download),
                        tint = if (request?.status == RequestStatus.Failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun RequestProgress(request: DownloadRequest, size: Dp = 24.dp) {
    val progress = request.progress
    if (request.status == RequestStatus.Downloading && progress != null && progress > 0f) {
        CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(size), strokeWidth = 3.dp)
    } else {
        CircularProgressIndicator(modifier = Modifier.size(size), strokeWidth = 3.dp)
    }
}

@Composable
fun statusText(request: DownloadRequest): String {
    val label = stringResource(
        when (request.status) {
            RequestStatus.Queued -> R.string.download_status_queued
            RequestStatus.Searching -> R.string.download_status_searching
            RequestStatus.Downloading -> R.string.download_status_downloading
            RequestStatus.Importing -> R.string.download_status_importing
            RequestStatus.Completed -> R.string.download_status_completed
            RequestStatus.Failed -> R.string.download_status_failed
            RequestStatus.Cancelled -> R.string.download_status_cancelled
        },
    )
    val percent = request.progress?.takeIf { request.status == RequestStatus.Downloading && it > 0f }?.let { " ${(it * 100).toInt()}%" }
    val message = request.message?.takeIf { it.isNotBlank() && it != label }
    return listOfNotNull(label + percent.orEmpty(), message).joinToString(" · ")
}

/** Обложка по внешнему URL (каталоги) с плейсхолдером. */
@Composable
fun RemoteImage(url: String?, modifier: Modifier, shape: Shape, icon: ImageVector) {
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        val placeholder = @Composable {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            }
        }
        if (url == null) {
            placeholder()
        } else {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { placeholder() },
                error = { placeholder() },
            )
        }
    }
}

/** Подсказка, когда сервис загрузок не настроен. */
@Composable
fun DiscoverSetupHint(onSetup: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(R.string.search_discover_not_configured),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onSetup, modifier = Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.search_discover_setup)) }
    }
}
