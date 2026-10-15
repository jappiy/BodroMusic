package ru.bodro.music.ui.screens.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ru.bodro.music.core.domain.player.SleepTimerOption
import ru.bodro.music.core.model.Lyrics
import ru.bodro.music.data.repository.LibraryRepository
import ru.bodro.music.playback.PlayerConnection
import ru.bodro.music.playback.PlayerUiState

sealed interface LyricsState {
    data object Loading : LyricsState

    data object Missing : LyricsState

    data class Loaded(val lyrics: Lyrics) : LyricsState
}

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val player: PlayerConnection,
    private val library: LibraryRepository,
) : ViewModel() {
    val state: StateFlow<PlayerUiState> = player.state

    private val _lyrics = MutableStateFlow<LyricsState>(LyricsState.Missing)
    val lyrics: StateFlow<LyricsState> = _lyrics.asStateFlow()

    private var lyricsJob: kotlinx.coroutines.Job? = null
    private var lyricsEnabled = false

    init {
        viewModelScope.launch {
            player.state.map { it.currentSong }.distinctUntilChanged { a, b -> a?.id == b?.id }.collect {
                if (lyricsEnabled) loadLyrics()
            }
        }
    }

    /** Текст грузим только когда открыта панель текста. */
    fun onLyricsVisible(visible: Boolean) {
        lyricsEnabled = visible
        if (visible) loadLyrics()
    }

    private fun loadLyrics() {
        val song = player.state.value.currentSong ?: return
        lyricsJob?.cancel()
        _lyrics.value = LyricsState.Loading
        lyricsJob = viewModelScope.launch {
            val result = runCatching { library.lyrics(song) }.getOrNull()
            _lyrics.value = result?.let { LyricsState.Loaded(it) } ?: LyricsState.Missing
        }
    }

    fun togglePlay() = player.togglePlay()

    fun next() = player.next()

    fun previous() = player.previous()

    fun seekTo(ms: Long) = player.seekTo(ms)

    fun cycleRepeat() = player.cycleRepeat()

    fun toggleShuffle() = player.toggleShuffle()

    fun skipTo(index: Int) = player.skipTo(index)

    fun moveItem(from: Int, to: Int) = player.moveItem(from, to)

    fun removeItem(index: Int) = player.removeItem(index)

    fun setSleepTimer(option: SleepTimerOption?) = player.setSleepTimer(option)
}
