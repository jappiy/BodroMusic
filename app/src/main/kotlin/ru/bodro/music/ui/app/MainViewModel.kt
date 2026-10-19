package ru.bodro.music.ui.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.bodro.music.R
import ru.bodro.music.core.model.Playlist
import ru.bodro.music.core.model.QueueSource
import ru.bodro.music.core.model.Reaction
import ru.bodro.music.core.model.Song
import ru.bodro.music.core.model.WaveSettings
import ru.bodro.music.data.account.ConnectionManager
import ru.bodro.music.data.account.SessionState
import ru.bodro.music.data.reco.SoundRepository
import ru.bodro.music.data.repository.LibraryRepository
import ru.bodro.music.data.repository.PlaylistRepository
import ru.bodro.music.data.repository.ReactionRepository
import ru.bodro.music.data.repository.ReactionState
import ru.bodro.music.data.settings.AppSettings
import ru.bodro.music.data.settings.SettingsRepository
import ru.bodro.music.playback.PlayerConnection
import ru.bodro.music.playback.PlayerUiState
import ru.bodro.music.ui.image.CoverColors
import ru.bodro.music.ui.image.CoverPaletteRepository
import ru.bodro.music.ui.util.toUiError

data class SongMenuRequest(val song: Song, val context: SongMenuContext)

data class PlaylistPickerState(val songs: List<Song>, val playlists: List<Playlist>? = null)

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    connection: ConnectionManager,
    settingsRepository: SettingsRepository,
    private val player: PlayerConnection,
    private val reactionRepository: ReactionRepository,
    private val playlistRepository: PlaylistRepository,
    private val palette: CoverPaletteRepository,
    private val library: LibraryRepository,
    private val sound: SoundRepository,
) : ViewModel(), AppActions {
    val session: StateFlow<SessionState> = connection.session
    val settings: StateFlow<AppSettings> = settingsRepository.settings
    val playerState: StateFlow<PlayerUiState> = player.state
    val reactions: StateFlow<ReactionState> = reactionRepository.state

    @OptIn(ExperimentalCoroutinesApi::class)
    val coverColors: StateFlow<CoverColors?> = player.state
        .map { it.currentSong?.coverArt }
        .distinctUntilChanged()
        .transformLatest { cover -> emit(runCatching { palette.colorsFor(cover) }.getOrNull()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _songMenu = MutableStateFlow<SongMenuRequest?>(null)
    val songMenu: StateFlow<SongMenuRequest?> = _songMenu.asStateFlow()

    private val _playlistPicker = MutableStateFlow<PlaylistPickerState?>(null)
    val playlistPicker: StateFlow<PlaylistPickerState?> = _playlistPicker.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun connectPlayer() = player.connect()

    override fun play(songs: List<Song>, index: Int, source: QueueSource, shuffle: Boolean) = player.play(songs, index, source, shuffle)

    override fun playNext(songs: List<Song>) {
        player.playNext(songs)
        showMessage(context.getString(R.string.message_play_next))
    }

    override fun addToQueue(songs: List<Song>) {
        player.addToQueue(songs)
        showMessage(context.getString(R.string.message_added_to_queue))
    }

    override fun toggleLike(song: Song) {
        viewModelScope.launch { reactionRepository.toggleLike(song) }
    }

    override fun toggleDislike(song: Song) {
        viewModelScope.launch {
            val result = reactionRepository.toggleDislike(song)
            if (result == Reaction.Disliked) showMessage(context.getString(R.string.message_disliked))
        }
    }

    override fun startWave(settings: WaveSettings) {
        player.startWave(settings) { error ->
            val text = if (error is IllegalStateException) {
                context.getString(R.string.wave_empty)
            } else {
                context.getString(error.toUiError().message)
            }
            showMessage(text)
        }
    }

    override fun playSimilar(song: Song) {
        viewModelScope.launch {
            val similar = if (sound.isSupported()) runCatching { sound.similar(song) }.getOrDefault(emptyList()) else null
            when {
                similar == null -> showMessage(context.getString(R.string.similar_sound_unsupported))
                similar.isEmpty() -> showMessage(context.getString(R.string.similar_sound_empty))
                else -> player.play(
                    listOf(song) + similar,
                    0,
                    QueueSource.CustomSource(context.getString(R.string.similar_sound_title, song.title)),
                    false,
                )
            }
        }
    }

    override fun showSongMenu(song: Song, context: SongMenuContext) {
        _songMenu.value = SongMenuRequest(song, context)
    }

    fun dismissSongMenu() {
        _songMenu.value = null
    }

    fun removeFromPlaylist(request: SongMenuRequest) {
        val playlistId = request.context.playlistId ?: return
        val index = request.context.indexInPlaylist ?: return
        viewModelScope.launch {
            runCatching { playlistRepository.removeAt(playlistId, index) }
                .onSuccess {
                    request.context.onRemoved?.invoke()
                    showMessage(context.getString(R.string.message_removed_from_playlist))
                }
                .onFailure { showMessage(context.getString(it.toUiError().message)) }
        }
    }

    override fun addToPlaylist(songs: List<Song>) {
        _playlistPicker.value = PlaylistPickerState(songs)
        viewModelScope.launch {
            val playlists = runCatching { playlistRepository.myPlaylists() }.getOrDefault(emptyList())
            _playlistPicker.update { it?.copy(playlists = playlists) }
        }
    }

    fun pickPlaylist(playlist: Playlist) {
        val songs = _playlistPicker.value?.songs ?: return
        _playlistPicker.value = null
        viewModelScope.launch {
            runCatching { playlistRepository.addSongs(playlist.id, songs) }
                .onSuccess { showMessage(context.getString(R.string.message_added_to_playlist, playlist.name)) }
                .onFailure { showMessage(context.getString(it.toUiError().message)) }
        }
    }

    fun createPlaylistWith(name: String) {
        val songs = _playlistPicker.value?.songs ?: return
        _playlistPicker.value = null
        viewModelScope.launch {
            runCatching { playlistRepository.create(name, songs) }
                .onSuccess { showMessage(context.getString(R.string.message_added_to_playlist, name)) }
                .onFailure { showMessage(context.getString(it.toUiError().message)) }
        }
    }

    fun dismissPlaylistPicker() {
        _playlistPicker.value = null
    }

    /** Скачанный трек из уведомления: загрузить по id и сразу включить. */
    fun playSongById(id: String) {
        viewModelScope.launch {
            runCatching { library.song(id) }
                .onSuccess { player.play(listOf(it), 0, QueueSource.CustomSource(), false) }
                .onFailure { showMessage(context.getString(it.toUiError().message)) }
        }
    }

    override fun showMessage(message: String) {
        _messages.trySend(message)
    }

    // Команды плеера для UI.
    fun togglePlay() = player.togglePlay()

    fun next() = player.next()

    fun previous() = player.previous()

    fun seekTo(ms: Long) = player.seekTo(ms)
}
