package ru.bodro.music.ui.screens.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import ru.bodro.music.core.domain.player.SleepTimerOption
import ru.bodro.music.core.model.Lyrics
import ru.bodro.music.data.reco.PoolRepository
import ru.bodro.music.data.repository.LyricsAvailability
import ru.bodro.music.data.repository.LyricsRepository
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
    private val lyricsRepository: LyricsRepository,
    pool: PoolRepository,
) : ViewModel() {
    val state: StateFlow<PlayerUiState> = player.state

    /** Треки пула «На пробу» — у них в плеере метка «Новое». */
    val poolSongIds: StateFlow<Set<String>> = pool.visibility
        .map { it.songIds }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), pool.visibility.value.songIds)

    /** Текст текущего трека: проверяется заранее при смене трека ([LyricsRepository]), не только при открытии панели. */
    val lyricsAvailability: StateFlow<LyricsAvailability> = lyricsRepository.current

    val lyrics: StateFlow<LyricsState> = lyricsRepository.current
        .map { it.toState() }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, lyricsRepository.current.value.toState())

    init {
        lyricsRepository.start()
    }

    /** Панель текста открыта — если прошлый запрос не удался, повторить. */
    fun onLyricsVisible(visible: Boolean) {
        if (visible) lyricsRepository.refresh()
    }

    private fun LyricsAvailability.toState(): LyricsState = when (this) {
        LyricsAvailability.Loading -> LyricsState.Loading
        LyricsAvailability.Missing -> LyricsState.Missing
        is LyricsAvailability.Available -> LyricsState.Loaded(lyrics)
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
