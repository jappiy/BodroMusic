package ru.bodro.music.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.bodro.music.core.domain.wave.WaveCharacter
import ru.bodro.music.core.domain.wave.WaveMood
import ru.bodro.music.core.downloads.WaveModeInfo
import ru.bodro.music.data.downloads.DownloadsRepository
import ru.bodro.music.data.reco.TrackInfo
import ru.bodro.music.data.reco.TrackInfoRepository
import ru.bodro.music.data.repository.LibraryRepository
import ru.bodro.music.data.repository.WaveCoverage
import ru.bodro.music.data.repository.WaveRepository
import ru.bodro.music.data.settings.AppSettings
import ru.bodro.music.data.settings.SettingsRepository
import ru.bodro.music.playback.PlayerConnection
import ru.bodro.music.playback.PlayerUiState

/** «Главная» — «Моя волна»: плеер, режимы, короткий текст о треке. */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val connection: PlayerConnection,
    private val settingsRepository: SettingsRepository,
    private val library: LibraryRepository,
    private val wave: WaveRepository,
    private val downloads: DownloadsRepository,
    trackInfoRepository: TrackInfoRepository,
) : ViewModel() {
    private val _modes = MutableStateFlow<List<WaveModeInfo>?>(null)

    /** Режимы волны с сервера (`null` — сервер волну не подбирает, режимы — с телефона). */
    val modes: StateFlow<List<WaveModeInfo>?> = _modes.asStateFlow()

    private val _artistCovers = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Обложки исполнителей библиотеки по имени (нижний регистр) — аватары режимов «В стиле X». */
    val artistCovers: StateFlow<Map<String, String>> = _artistCovers.asStateFlow()

    /** Сколько треков под текущий режим по ответу сервера. */
    val coverage: StateFlow<WaveCoverage?> = wave.coverage

    val player: StateFlow<PlayerUiState> = connection.state

    val settings: StateFlow<AppSettings> = settingsRepository.settings

    /** Доп. информация о текущем треке (грузится при старте трека): отсюда — короткий текст под «таблеткой». */
    val trackInfo: StateFlow<TrackInfo> = trackInfoRepository.current

    init {
        viewModelScope.launch {
            val server = wave.serverModes()
            _modes.value = server
            if (server.orEmpty().any { it.id.startsWith("artist:") || it.id.startsWith("release:") }) {
                _artistCovers.value = runCatching {
                    library.artists().mapNotNull { a -> a.coverArt?.let { a.name.lowercase() to it } }.toMap()
                }.getOrDefault(emptyMap())
            }
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
