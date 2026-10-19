package ru.bodro.music.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.bodro.music.core.downloads.Chart
import ru.bodro.music.data.reco.ChartsRepository
import ru.bodro.music.data.repository.HomeData
import ru.bodro.music.data.repository.LibraryRepository
import ru.bodro.music.playback.PlayerConnection
import ru.bodro.music.playback.PlayerUiState
import ru.bodro.music.ui.components.LoadState
import ru.bodro.music.ui.util.toUiError

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val connection: PlayerConnection,
    private val charts: ChartsRepository,
) : ViewModel() {
    private val _charts = MutableStateFlow<List<Chart>>(emptyList())

    /** Чарты bodrod для раздела под «Моей волной». */
    val chartList: StateFlow<List<Chart>> = _charts.asStateFlow()

    private val _state = MutableStateFlow<LoadState<HomeData>>(LoadState.Loading)
    val state: StateFlow<LoadState<HomeData>> = _state.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    val player: StateFlow<PlayerUiState> = connection.state

    init {
        load()
    }

    private fun loadCharts() {
        viewModelScope.launch { _charts.value = charts.home() }
    }

    fun load() {
        loadCharts()
        viewModelScope.launch {
            if (_state.value !is LoadState.Content) _state.value = LoadState.Loading
            _state.value = runCatching { library.home() }
                .fold({ LoadState.Content(it) }, { LoadState.Error(it.toUiError()) })
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            loadCharts()
            library.invalidate()
            runCatching { library.home() }
                .onSuccess { _state.value = LoadState.Content(it) }
                .onFailure { if (_state.value !is LoadState.Content) _state.value = LoadState.Error(it.toUiError()) }
            _refreshing.value = false
        }
    }

    fun togglePlay() = connection.togglePlay()
}
