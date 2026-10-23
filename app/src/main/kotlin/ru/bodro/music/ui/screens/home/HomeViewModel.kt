package ru.bodro.music.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import ru.bodro.music.core.domain.wave.WaveCharacter
import ru.bodro.music.core.domain.wave.WaveMood
import ru.bodro.music.core.downloads.ArtistFacts
import ru.bodro.music.core.downloads.Chart
import ru.bodro.music.core.downloads.WaveModeInfo
import ru.bodro.music.data.downloads.DownloadsRepository
import ru.bodro.music.data.reco.ChartsRepository
import ru.bodro.music.data.reco.FactsRepository
import ru.bodro.music.data.repository.HomeData
import ru.bodro.music.data.repository.LibraryRepository
import ru.bodro.music.data.repository.WaveCoverage
import ru.bodro.music.data.repository.WaveRepository
import ru.bodro.music.data.settings.AppSettings
import ru.bodro.music.data.settings.SettingsRepository
import ru.bodro.music.playback.PlayerConnection
import ru.bodro.music.playback.PlayerUiState
import ru.bodro.music.ui.components.LoadState
import ru.bodro.music.ui.util.toUiError

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val connection: PlayerConnection,
    private val charts: ChartsRepository,
    private val settingsRepository: SettingsRepository,
    facts: FactsRepository,
    private val wave: WaveRepository,
    private val downloads: DownloadsRepository,
) : ViewModel() {
    private val _modes = MutableStateFlow<List<WaveModeInfo>?>(null)

    /** Режимы волны с сервера (`null` — сервер волну не подбирает, режимы — с телефона). */
    val modes: StateFlow<List<WaveModeInfo>?> = _modes.asStateFlow()

    /** Сколько треков под текущий режим по ответу сервера. */
    val coverage: StateFlow<WaveCoverage?> = wave.coverage

    private val _charts = MutableStateFlow<List<Chart>>(emptyList())

    /** Чарты bodrod для раздела под «Моей волной». */
    val chartList: StateFlow<List<Chart>> = _charts.asStateFlow()

    private val _state = MutableStateFlow<LoadState<HomeData>>(LoadState.Loading)
    val state: StateFlow<LoadState<HomeData>> = _state.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    val player: StateFlow<PlayerUiState> = connection.state

    val settings: StateFlow<AppSettings> = settingsRepository.settings

    /** Факт о текущем треке волны (Last.fm); `null` — строки нет. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val facts: StateFlow<ArtistFacts?> = connection.state
        .map { state -> state.currentSong?.takeIf { state.source.isWave } }
        .distinctUntilChanged { a, b -> a?.id == b?.id }
        .transformLatest { song ->
            emit(null)
            if (song != null) emit(facts.factsFor(song))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        load()
    }

    private fun loadCharts() {
        viewModelScope.launch { _charts.value = charts.home() }
        viewModelScope.launch { _modes.value = wave.serverModes() }
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

    fun next() = connection.next()

    fun previous() = connection.previous()

    /** Выбранный режим волны (свой у каждой учётки); `null` — обычная «Моя волна». */
    fun setMode(id: String?) {
        viewModelScope.launch { settingsRepository.update { it.copy(waveModeId = id.orEmpty()) } }
    }

    fun setLanguage(code: String) {
        viewModelScope.launch { settingsRepository.update { it.copy(waveLanguage = code) } }
    }

    /** Под режим мало треков: движок рекомендаций подбирает и докачивает похожее в пул «На пробу». */
    fun findMore(onDone: (Boolean) -> Unit) {
        viewModelScope.launch { onDone(runCatching { downloads.refillPool() }.isSuccess) }
    }

    fun setCharacter(character: WaveCharacter) {
        viewModelScope.launch { settingsRepository.update { it.copy(waveCharacter = character) } }
    }

    fun setMood(mood: WaveMood) {
        viewModelScope.launch { settingsRepository.update { it.copy(waveMood = mood) } }
    }
}
