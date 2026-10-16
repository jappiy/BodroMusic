package ru.bodro.music.ui.screens.wave

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.bodro.music.core.model.WaveMode
import ru.bodro.music.core.model.WaveSettings
import ru.bodro.music.data.repository.WaveRepository
import ru.bodro.music.playback.PlayerConnection
import ru.bodro.music.playback.PlayerUiState

data class WaveFilters(
    val mode: WaveMode = WaveMode.Default,
    val genre: String? = null,
    val decade: Int? = null,
    val genres: List<String> = emptyList(),
    val decades: List<Int> = emptyList(),
) {
    fun toSettings() = WaveSettings(mode = mode, genre = genre, decade = decade)
}

@HiltViewModel
class WaveViewModel @Inject constructor(
    private val wave: WaveRepository,
    private val connection: PlayerConnection,
) : ViewModel() {
    val player: StateFlow<PlayerUiState> = connection.state

    private val _filters = MutableStateFlow(WaveFilters())
    val filters: StateFlow<WaveFilters> = _filters.asStateFlow()

    init {
        viewModelScope.launch {
            val genres = runCatching { wave.topGenres(limit = GENRES_LOADED) }.getOrDefault(emptyList())
            val decades = runCatching { wave.favoriteDecades() }.getOrDefault(emptyList()).take(6).sorted()
            _filters.update { it.copy(genres = genres, decades = decades.ifEmpty { listOf(1970, 1980, 1990, 2000, 2010, 2020) }) }
        }
    }

    fun togglePlay() = connection.togglePlay()

    fun setMode(mode: WaveMode) = _filters.update { it.copy(mode = mode) }

    fun setGenre(genre: String?) = _filters.update { it.copy(genre = if (it.genre == genre) null else genre) }

    fun setDecade(decade: Int?) = _filters.update { it.copy(decade = if (it.decade == decade) null else decade) }
}

private const val GENRES_LOADED = 24
