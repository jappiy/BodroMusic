package ru.bodro.music.ui.screens.player

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import ru.bodro.music.R
import ru.bodro.music.core.domain.player.StreamQuality
import ru.bodro.music.core.domain.reaction.ReactionTarget
import ru.bodro.music.core.downloads.DiscoverTrack
import ru.bodro.music.core.downloads.DownloadRequest
import ru.bodro.music.core.downloads.RequestStatus
import ru.bodro.music.core.model.Artist
import ru.bodro.music.core.model.Playlist
import ru.bodro.music.core.model.QueueSource
import ru.bodro.music.core.model.Song
import ru.bodro.music.data.downloads.DownloadsRepository
import ru.bodro.music.data.repository.LibraryRepository
import ru.bodro.music.data.repository.PlaylistRepository
import ru.bodro.music.data.repository.ReactionRepository
import ru.bodro.music.data.settings.AppSettings
import ru.bodro.music.data.settings.SettingsRepository
import ru.bodro.music.playback.PlayerConnection
import ru.bodro.music.ui.components.CoverImage
import ru.bodro.music.ui.components.CoverKind
import ru.bodro.music.ui.screens.search.DiscoverCallbacks
import ru.bodro.music.ui.screens.search.DiscoverTrackRow
import ru.bodro.music.ui.util.text
import ru.bodro.music.ui.util.toUiError

/** Блоки под плеером для текущего трека: исполнитель, похожие, популярные исполнителя, свои плейлисты. */
data class PlayerExtrasUi(
    val songId: String? = null,
    val artist: Artist? = null,
    val similar: List<DiscoverTrack> = emptyList(),
    val popular: List<DiscoverTrack> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerExtrasViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    player: PlayerConnection,
    private val library: LibraryRepository,
    private val downloads: DownloadsRepository,
    private val playlists: PlaylistRepository,
    private val reactions: ReactionRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    /** Нет сети, bodrod или Last.fm — блоки пустые и не показываются. */
    val extras: StateFlow<PlayerExtrasUi> = player.state
        .map { it.currentSong }
        .distinctUntilChanged { a, b -> a?.id == b?.id }
        .transformLatest { song ->
            if (song == null) {
                emit(PlayerExtrasUi())
                return@transformLatest
            }
            emit(PlayerExtrasUi(songId = song.id))
            emit(load(song))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerExtrasUi())

    /** Заявки на загрузку по ключу «исполнитель|название»; пока плеер открыт — с опросом сервиса. */
    val requests: StateFlow<Map<String, DownloadRequest>> = combine(
        downloads.requests,
        downloads.serviceUrl.flatMapLatest { url -> if (url != null) downloads.observe() else emptyFlow() }.onStart { emit(emptyList()) },
    ) { list, _ -> list.sortedBy { it.createdAt }.associateBy { it.key } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val canDownload: StateFlow<Boolean> = downloads.serviceUrl
        .transformLatest { url -> emit(url != null && runCatching { downloads.info().trackDownloads }.getOrDefault(false)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val settings: StateFlow<AppSettings> = settingsRepository.settings

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private suspend fun load(song: Song): PlayerExtrasUi = coroutineScope {
        val artistName = song.artist.orEmpty()
        val artist = async { song.artistId?.let { id -> runCatching { library.artist(id).first.artist }.getOrNull() } }
        val similar = async {
            if (downloads.isConfigured && artistName.isNotBlank()) {
                runCatching { downloads.similarTracks(artistName, song.title, song.id) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
        }
        val popular = async {
            if (downloads.isConfigured && artistName.isNotBlank()) {
                runCatching { downloads.artistPopular(artistName).tracks }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
        }
        val mine = async { runCatching { playlists.myPlaylists() }.getOrDefault(emptyList()) }
        PlayerExtrasUi(
            songId = song.id,
            artist = artist.await(),
            similar = similar.await().filterNot { it.librarySongId == song.id }.distinctBy { it.key },
            popular = popular.await().filterNot { it.librarySongId == song.id }.distinctBy { it.key }.take(POPULAR_SHOWN),
            playlists = mine.await(),
        )
    }

    /** ♡ исполнителя: звезда в Navidrome для текущей учётки (liked2lidarr по ней подписывает на новые релизы в Lidarr). */
    fun toggleArtistLike(artist: Artist, current: Boolean) {
        viewModelScope.launch { reactions.setStarred(ReactionTarget.Artist, artist.id, !current) }
    }

    fun download(track: DiscoverTrack) {
        viewModelScope.launch {
            runCatching { downloads.downloadTrack(track) }
                .onSuccess { _messages.send(context.getString(R.string.discover_queued_track, "${track.artist} — ${track.title}")) }
                .onFailure { _messages.send(it.toUiError().text(context)) }
        }
    }

    /** «Скачать все недостающие»: в очередь bodrod (порядок источников — из настроек сервера) всё, чего нет и что не качается. */
    fun downloadAll(tracks: List<DiscoverTrack>) {
        val active = requests.value
        val missing = tracks.filter { t ->
            t.librarySongId == null && active[t.key]?.let { it.status.isActive || it.status == RequestStatus.Completed } != true
        }
        if (missing.isEmpty()) return
        viewModelScope.launch {
            val queued = missing.count { runCatching { downloads.downloadTrack(it) }.isSuccess }
            _messages.send(context.getString(R.string.player_download_all_queued, queued))
        }
    }

    fun withSong(songId: String, block: (Song) -> Unit) {
        viewModelScope.launch { runCatching { library.song(songId) }.onSuccess(block) }
    }

    fun addTo(playlist: Playlist, song: Song) {
        viewModelScope.launch {
            runCatching { playlists.addSongs(playlist.id, listOf(song)) }
                .onSuccess { _messages.send(context.getString(R.string.message_added_to_playlist, playlist.name)) }
                .onFailure { _messages.send(it.toUiError().text(context)) }
        }
    }

    fun setQuality(quality: StreamQuality) {
        viewModelScope.launch { settingsRepository.update { it.copy(quality = quality) } }
    }

    private companion object {
        const val POPULAR_SHOWN = 10
    }
}

/** Действия блоков под плеером (из экрана плеера). */
class PlayerExtrasActions(
    val onOpenArtist: (String) -> Unit,
    val onToggleArtistLike: (Artist, Boolean) -> Unit,
    val callbacks: DiscoverCallbacks,
    val onTrackMenu: (DiscoverTrack) -> Unit,
    val onDownloadAll: (List<DiscoverTrack>) -> Unit,
    val onAddTo: (Playlist) -> Unit,
    val onAllPlaylists: () -> Unit,
    val onSleepTimer: () -> Unit,
    val onQuality: (StreamQuality) -> Unit,
)

/** Блоки под основным плеером (прокрутка вниз). Нет данных — блок не показывается. */
fun LazyListScope.playerExtras(
    extras: PlayerExtrasUi,
    requests: Map<String, DownloadRequest>,
    canDownload: Boolean,
    artistLiked: Boolean,
    sleepActive: Boolean,
    quality: StreamQuality,
    palette: PlayerPalette,
    actions: PlayerExtrasActions,
) {
    extras.artist?.let { artist ->
        item(key = "artist") { ArtistCard(artist, artistLiked, palette, actions) }
    }
    trackSection("similar", R.string.player_similar, extras.similar, requests, canDownload, palette, actions)
    trackSection("popular", R.string.player_popular, extras.popular, requests, canDownload, palette, actions)
    item(key = "playlists-header") { SectionTitle(stringResource(R.string.menu_add_to_playlist), palette) }
    items(extras.playlists.take(PLAYLISTS_SHOWN), key = { "pl-${it.id}" }) { playlist ->
        Row(
            Modifier.fillMaxWidth().clickable { actions.onAddTo(playlist) }.padding(horizontal = 24.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverImage(playlist.coverArt, Modifier.size(44.dp), sizeHint = 44.dp, kind = CoverKind.Playlist)
            Text(
                playlist.name,
                color = palette.content,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.player_add_here), tint = palette.accent)
        }
    }
    item(key = "playlists-all") {
        TextButton(onClick = actions.onAllPlaylists, modifier = Modifier.padding(horizontal = 12.dp)) {
            Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null, tint = palette.accent)
            Text(stringResource(R.string.player_all_playlists), color = palette.accent, modifier = Modifier.padding(start = 8.dp))
        }
    }
    item(key = "bottom") { BottomSettings(sleepActive, quality, palette, actions) }
}

@Composable
private fun SectionTitle(text: String, palette: PlayerPalette, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge, color = palette.content, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun ArtistCard(artist: Artist, liked: Boolean, palette: PlayerPalette, actions: PlayerExtrasActions) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(palette.content.copy(alpha = 0.08f))
            .clickable { actions.onOpenArtist(artist.id) }
            .padding(12.dp)
            .testTag("player-artist-card"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(artist.coverArt, Modifier.size(64.dp), sizeHint = 128.dp, shape = CircleShape, kind = CoverKind.Artist)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(
                artist.name,
                style = MaterialTheme.typography.titleMedium,
                color = palette.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.player_artist_card_hint),
                style = MaterialTheme.typography.bodySmall,
                color = palette.content.copy(alpha = 0.7f),
            )
        }
        IconButton(onClick = { actions.onToggleArtistLike(artist, liked) }, modifier = Modifier.testTag("player-artist-like")) {
            Icon(
                if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = stringResource(if (liked) R.string.action_unlike else R.string.player_artist_favorite),
                tint = if (liked) palette.accent else palette.content,
            )
        }
    }
}

private fun LazyListScope.trackSection(
    key: String,
    title: Int,
    tracks: List<DiscoverTrack>,
    requests: Map<String, DownloadRequest>,
    canDownload: Boolean,
    palette: PlayerPalette,
    actions: PlayerExtrasActions,
) {
    if (tracks.isEmpty()) return
    item(key = "$key-header") {
        val missing = tracks.count { t ->
            t.librarySongId == null &&
                requests[t.key]?.status?.let { it.isActive || it == RequestStatus.Completed } != true
        }
        SectionTitle(stringResource(title), palette) {
            if (canDownload && missing > 0) {
                TextButton(onClick = { actions.onDownloadAll(tracks) }, modifier = Modifier.testTag("player-$key-download-all")) {
                    Icon(Icons.Rounded.CloudDownload, contentDescription = null, tint = palette.accent, modifier = Modifier.size(18.dp))
                    Text(
                        stringResource(R.string.player_download_all, missing),
                        color = palette.accent,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }
    items(tracks, key = { "$key-${it.key}" }) { track ->
        Row(Modifier.padding(start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                OnPlayerColors(palette) { DiscoverTrackRow(track, requests[track.key], canDownload, actions.callbacks) }
            }
            val inLibrary =
                track.librarySongId ?: requests[track.key]?.songId?.takeIf { requests[track.key]?.status == RequestStatus.Completed }
            if (inLibrary != null) {
                IconButton(onClick = { actions.onTrackMenu(track) }) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = stringResource(R.string.action_more),
                        tint = palette.content.copy(alpha = 0.7f),
                    )
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BottomSettings(sleepActive: Boolean, quality: StreamQuality, palette: PlayerPalette, actions: PlayerExtrasActions) {
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 32.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(
                RoundedCornerShape(16.dp),
            ).clickable(onClick = actions.onSleepTimer).padding(vertical = 12.dp).testTag("player-sleep-row"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Bedtime, contentDescription = null, tint = if (sleepActive) palette.accent else palette.content)
            Text(
                stringResource(if (sleepActive) R.string.player_sleep_timer_on else R.string.player_sleep_timer),
                color = palette.content,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        Text(
            stringResource(R.string.player_sound),
            style = MaterialTheme.typography.titleSmall,
            color = palette.content,
            modifier = Modifier.padding(top = 12.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppSettings.QUALITIES.forEach { option ->
                FilterChip(
                    selected = quality == option,
                    onClick = { actions.onQuality(option) },
                    label = {
                        Text(
                            if (option.isOriginal) {
                                stringResource(R.string.quality_original)
                            } else {
                                stringResource(R.string.quality_slow, option.maxBitRate ?: 0)
                            },
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        labelColor = palette.content,
                        selectedContainerColor = palette.accent,
                        selectedLabelColor = palette.background,
                        containerColor = Color.Transparent,
                    ),
                )
            }
        }
    }
}

/** Общие строки (поиск, загрузки) в цветах плеера: светлый текст на фоне обложки в любой теме. */
@Composable
private fun OnPlayerColors(palette: PlayerPalette, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    MaterialTheme(
        colorScheme = scheme.copy(
            onSurface = palette.content,
            onSurfaceVariant = palette.content.copy(alpha = 0.7f),
            primary = palette.accent,
            onBackground = palette.content,
        ),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides palette.content,
            content = content,
        )
    }
}

private const val PLAYLISTS_SHOWN = 5

/** Источник очереди для трека, запущенного из блоков плеера. */
fun extrasSource(title: String) = QueueSource.CustomSource(title)
