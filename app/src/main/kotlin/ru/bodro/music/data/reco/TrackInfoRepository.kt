package ru.bodro.music.data.reco

import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.bodro.music.core.downloads.DiscoverTrack
import ru.bodro.music.core.model.Artist
import ru.bodro.music.core.model.Song
import ru.bodro.music.data.downloads.DownloadsRepository
import ru.bodro.music.data.repository.LibraryRepository
import ru.bodro.music.di.AppScope
import ru.bodro.music.playback.PlayerConnection
import ru.bodro.music.ui.util.UiError
import ru.bodro.music.ui.util.toUiError

/** Состояние догружаемого блока: пока грузится — скелетон, пусто — блок скрыт, ошибка — сообщение и «Повторить». */
sealed interface Section<out T> {
    data object Loading : Section<Nothing>

    data class Ready<T>(val value: T) : Section<T>

    data object Empty : Section<Nothing>

    data class Failed(val error: UiError) : Section<Nothing>

    val valueOrNull: T? get() = (this as? Ready<T>)?.value
}

/**
 * Дополнительная информация о треке: карточка исполнителя, похожие, популярные исполнителя и короткие тексты
 * для главной ([blurbs] — варианты от лучшего к запасным, все на русском).
 */
data class TrackInfo(
    val songId: String? = null,
    val artist: Section<Artist> = Section.Empty,
    val similar: Section<List<DiscoverTrack>> = Section.Empty,
    val popular: Section<List<DiscoverTrack>> = Section.Empty,
    val blurbs: Section<List<String>> = Section.Empty,
) {
    val isLoading: Boolean
        get() = listOf(artist, similar, popular).any { it is Section.Loading }
}

enum class TrackInfoPart { Artist, Similar, Popular, Blurbs }

/**
 * Догрузка информации о треке **сразу при его старте**, а не при открытии плеера: к моменту, когда пользователь
 * прокрутит плеер вниз, данные обычно уже есть. Результаты кэшируются на несколько последних треков; ошибка блока
 * не кэшируется навсегда — «Повторить» ([retry]) грузит его заново.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class TrackInfoRepository @Inject constructor(
    @AppScope private val scope: CoroutineScope,
    player: PlayerConnection,
    private val library: LibraryRepository,
    private val downloads: DownloadsRepository,
) {
    private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > MAX_CACHE
    }

    private class Entry(val song: Song, val state: MutableStateFlow<TrackInfo>)

    /** Информация о текущем треке плеера; загрузка начинается, как только трек сменился. */
    val current: StateFlow<TrackInfo> = player.state
        .map { it.currentSong }
        .distinctUntilChanged { a, b -> a?.id == b?.id }
        .flatMapLatest { song -> if (song == null) flowOf(TrackInfo()) else infoFor(song) }
        .stateIn(scope, SharingStarted.Eagerly, TrackInfo())

    fun infoFor(song: Song): StateFlow<TrackInfo> {
        val (entry, parts) = synchronized(cache) {
            val known = cache[song.id]
            if (known != null) {
                // Трек снова заиграл: то, что в прошлый раз не загрузилось, пробуем ещё раз.
                known to TrackInfoPart.entries.filter { known.state.value.part(it) is Section.Failed }
            } else {
                Entry(song, MutableStateFlow(TrackInfo(songId = song.id))).also { cache[song.id] = it } to TrackInfoPart.entries
            }
        }
        parts.forEach { load(entry, it) }
        return entry.state.asStateFlow()
    }

    fun retry(songId: String, part: TrackInfoPart) {
        val entry = synchronized(cache) { cache[songId] } ?: return
        load(entry, part)
    }

    private fun load(entry: Entry, part: TrackInfoPart) {
        val song = entry.song
        entry.state.update { it.with(part, Section.Loading) }
        scope.launch {
            val result = try {
                withTimeoutOrNull(TIMEOUT_MS) { fetch(song, part) } ?: Section.Failed(TimeoutException().toUiError())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Section.Failed(e.toUiError())
            }
            entry.state.update { it.with(part, result) }
        }
    }

    private suspend fun fetch(song: Song, part: TrackInfoPart): Section<*> {
        val artistName = song.artist?.takeIf { it.isNotBlank() }
        return when (part) {
            TrackInfoPart.Artist -> song.artistId?.let { Section.Ready(library.artist(it).first.artist) } ?: Section.Empty
            TrackInfoPart.Similar -> if (downloads.isConfigured && artistName != null) {
                downloads.similarTracks(artistName, song.title, song.id)
                    .filterNot { it.librarySongId == song.id }
                    .distinctBy { it.key }
                    .ifEmpty { null }
                    ?.let { Section.Ready(it) } ?: Section.Empty
            } else {
                Section.Empty
            }
            TrackInfoPart.Popular -> if (downloads.isConfigured && artistName != null) {
                downloads.artistPopular(artistName).tracks
                    .filterNot { it.librarySongId == song.id }
                    .distinctBy { it.key }
                    .take(POPULAR_SHOWN)
                    .ifEmpty { null }
                    ?.let { Section.Ready(it) } ?: Section.Empty
            } else {
                Section.Empty
            }
            TrackInfoPart.Blurbs -> if (downloads.isConfigured && artistName != null) {
                downloads.artistFacts(artistName, song.title, song.album, song.year, BLURB_MAX_CHARS).texts
                    .filter(::isRussian)
                    .ifEmpty { null }
                    ?.let { Section.Ready(it) } ?: Section.Empty
            } else {
                Section.Empty
            }
        }
    }

    private fun TrackInfo.part(part: TrackInfoPart): Section<*> = when (part) {
        TrackInfoPart.Artist -> artist
        TrackInfoPart.Similar -> similar
        TrackInfoPart.Popular -> popular
        TrackInfoPart.Blurbs -> blurbs
    }

    @Suppress("UNCHECKED_CAST")
    private fun TrackInfo.with(part: TrackInfoPart, section: Section<*>): TrackInfo = when (part) {
        TrackInfoPart.Artist -> copy(artist = section as Section<Artist>)
        TrackInfoPart.Similar -> copy(similar = section as Section<List<DiscoverTrack>>)
        TrackInfoPart.Popular -> copy(popular = section as Section<List<DiscoverTrack>>)
        TrackInfoPart.Blurbs -> copy(blurbs = section as Section<List<String>>)
    }

    companion object {
        private const val MAX_CACHE = 12
        private const val TIMEOUT_MS = 25_000L
        const val POPULAR_SHOWN = 10

        /** Лимит текста на главной: 2–3 строки. Не поместился при текущем шрифте — берётся следующий вариант. */
        const val BLURB_MAX_CHARS = 110

        /** Только русский: старый bodrod (до 0.21.0) присылал английские тексты Last.fm. */
        fun isRussian(text: String): Boolean {
            val cyrillic = text.count { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }
            val latin = text.count { it in 'a'..'z' || it in 'A'..'Z' }
            return cyrillic >= MIN_CYRILLIC && cyrillic >= latin
        }

        private const val MIN_CYRILLIC = 5
    }
}
