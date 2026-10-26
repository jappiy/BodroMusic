package ru.bodro.music.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.bodro.music.core.model.Lyrics
import ru.bodro.music.core.model.Song
import ru.bodro.music.data.account.ConnectionManager
import ru.bodro.music.di.AppScope
import ru.bodro.music.playback.PlayerConnection

/** Есть ли у трека текст: ищем / есть / нет. */
sealed interface LyricsAvailability {
    data object Loading : LyricsAvailability

    data object Missing : LyricsAvailability

    data class Available(val lyrics: Lyrics) : LyricsAvailability
}

/**
 * Текст песни заранее: при смене трека сразу проверяется текущий, затем следующий в очереди (предзагрузка) — иконка
 * текста в плеере показывает «есть / нет» до открытия панели. Ответы кэшируются по учётке и треку (последние
 * [CACHE_SIZE]); ошибка сети не кэшируется — при следующем открытии запрос повторится.
 */
@Singleton
class LyricsRepository @Inject constructor(
    private val library: LibraryRepository,
    private val connection: ConnectionManager,
    private val player: PlayerConnection,
    @AppScope private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val cache = object : LinkedHashMap<String, Lyrics?>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lyrics?>?) = size > CACHE_SIZE
    }
    private val _current = MutableStateFlow<LyricsAvailability>(LyricsAvailability.Missing)

    /** Состояние текста текущего трека. */
    val current: StateFlow<LyricsAvailability> = _current.asStateFlow()
    private var job: Job? = null
    private var started = false

    /** Начать следить за плеером (вызывается из UI; повторный вызов ничего не делает). */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            player.state
                .map { state -> state.currentSong to state.queue.getOrNull(state.currentIndex + 1) }
                .distinctUntilChanged { a, b -> a.first?.id == b.first?.id && a.second?.id == b.second?.id }
                .collect { (song, next) -> onTrack(song, next) }
        }
    }

    /** Повторить запрос для текущего трека (панель текста открыта, а прошлый запрос упал). */
    fun refresh() {
        val song = player.state.value.currentSong ?: return
        if (_current.value is LyricsAvailability.Missing && key(song) !in cache) onTrack(song, null)
    }

    private fun onTrack(song: Song?, next: Song?) {
        job?.cancel()
        if (song == null) {
            _current.value = LyricsAvailability.Missing
            return
        }
        val cachedKey = key(song)
        _current.value = if (cachedKey in cache) availability(cache[cachedKey]) else LyricsAvailability.Loading
        job = scope.launch {
            val lyrics = load(song)
            if (player.state.value.currentSong?.id == song.id) _current.value = availability(lyrics)
            next?.let { load(it) }
        }
    }

    private suspend fun load(song: Song): Lyrics? {
        val key = key(song)
        mutex.withLock { if (key in cache) return cache[key] }
        return try {
            val lyrics = library.lyrics(song)?.takeIf { !it.isEmpty }
            mutex.withLock { cache[key] = lyrics }
            lyrics
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null // нет сети — не кэшируем
        }
    }

    private fun availability(lyrics: Lyrics?): LyricsAvailability =
        lyrics?.let { LyricsAvailability.Available(it) } ?: LyricsAvailability.Missing

    private fun key(song: Song) = "${connection.activeAccountId}:${song.id}"

    private companion object {
        const val CACHE_SIZE = 64
    }
}
