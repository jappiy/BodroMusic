package ru.bodro.music.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.bodro.music.core.domain.wave.StyleArtists
import ru.bodro.music.core.domain.wave.WaveCharacter
import ru.bodro.music.core.domain.wave.WaveMood
import ru.bodro.music.core.downloads.WaveModeInfo
import ru.bodro.music.core.model.Artist
import ru.bodro.music.data.downloads.DownloadsRepository
import ru.bodro.music.data.reco.TrackInfo
import ru.bodro.music.data.reco.TrackInfoRepository
import ru.bodro.music.data.repository.HistoryRepository
import ru.bodro.music.data.repository.LibraryRepository
import ru.bodro.music.data.repository.WaveCoverage
import ru.bodro.music.data.repository.WaveRepository
import ru.bodro.music.data.settings.AppSettings
import ru.bodro.music.data.settings.SettingsRepository
import ru.bodro.music.playback.PlayerConnection
import ru.bodro.music.playback.PlayerUiState
import ru.bodro.music.ui.screens.settings.discoveryPreset

/** «Главная» — «Моя волна»: плеер, режимы, короткий текст о треке. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val connection: PlayerConnection,
    private val history: HistoryRepository,
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

    /** Исполнители библиотеки по имени (нижний регистр): фото из Navidrome для карточек «В стиле X». */
    private var libraryArtists: Map<String, Artist>? = null

    /** Фото исполнителей не из Navidrome (Deezer → Last.fm через bodrod): имя → URL или `null` — нигде нет. */
    private val remotePhotos = mutableMapOf<String, String?>()

    /**
     * Карточки «В стиле X» (0.31.0): первая — артист текущего трека (смена — с задержкой [STYLE_DEBOUNCE_MS], чтобы
     * быстрое пролистывание не дёргало карусель; ничего не играет — последний прослушанный), вторая и третья —
     * топ за 2 недели с ротацией ([StyleArtists]). Пусто — остаются карточки сервера.
     */
    @OptIn(FlowPreview::class)
    val styleCards: StateFlow<List<StyleCard>> = connection.state
        .map { it.currentSong?.artist?.takeIf { a -> a.isNotBlank() } }
        .distinctUntilChanged()
        .debounce(STYLE_DEBOUNCE_MS)
        .mapLatest { current -> runCatching { styleCardsFor(current) }.getOrDefault(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private suspend fun styleCardsFor(current: String?): List<StyleCard> {
        val now = System.currentTimeMillis()
        val first = current ?: history.lastSong()?.artist
        val names = StyleArtists.pick(first, history.artistPlays(now - StyleArtists.WINDOW_MS), now)
        return names.map { name -> StyleCard(name, photoOf(name)) }
    }

    /** Фото исполнителя: Navidrome (есть своё фото) → Deezer → Last.fm (bodrod) → обложка Navidrome как есть. */
    private suspend fun photoOf(name: String): StylePhoto {
        val artists = libraryArtists ?: runCatching { library.artists().associateBy { it.name.lowercase() } }
            .getOrDefault(emptyMap()).also { libraryArtists = it }
        val candidates = listOf(name, mainArtist(name)).distinct()
        val inLibrary = candidates.firstNotNullOfOrNull { artists[it.lowercase()] }
        if (inLibrary?.artistImageUrl != null && inLibrary.coverArt != null) return StylePhoto(coverArt = inLibrary.coverArt)
        val remote = candidates.firstNotNullOfOrNull { candidate ->
            if (candidate !in remotePhotos) {
                remotePhotos[candidate] = runCatching { downloads.artistImage(candidate) }.getOrNull()
            }
            remotePhotos[candidate]
        }
        return StylePhoto(coverArt = inLibrary?.coverArt, url = remote)
    }

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
        viewModelScope.launch {
            settingsRepository.update { it.copy(waveCharacter = character, waveDiscoveryShare = character.discoveryPreset()) }
        }
    }

    fun setMood(mood: WaveMood) {
        viewModelScope.launch { settingsRepository.update { it.copy(waveMood = mood) } }
    }

    private companion object {
        const val STYLE_DEBOUNCE_MS = 1_500L
    }
}

/** Фото исполнителя карточки: обложка Navidrome ([coverArt]) и/или внешняя картинка ([url]). */
data class StylePhoto(val coverArt: String? = null, val url: String? = null)

/** Карточка «В стиле X». */
data class StyleCard(val artist: String, val photo: StylePhoto)

private val ARTIST_SEPARATORS = Regex("""\s*(?:,|&|\+|/|\s(?:feat\.?|ft\.?|x|vs\.?|и)\s)\s*""", RegexOption.IGNORE_CASE)

/** Основной исполнитель: «Заточка & Pizza» → «Заточка». */
internal fun mainArtist(name: String): String = name.split(ARTIST_SEPARATORS, limit = 2).first().trim().ifEmpty { name }
