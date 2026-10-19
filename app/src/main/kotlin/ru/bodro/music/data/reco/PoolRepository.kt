package ru.bodro.music.data.reco

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.bodro.music.core.downloads.DownloadsError
import ru.bodro.music.core.downloads.DownloadsException
import ru.bodro.music.core.downloads.FeedbackEvent
import ru.bodro.music.core.downloads.PoolItem
import ru.bodro.music.core.model.Song
import ru.bodro.music.core.subsonic.SubsonicClient
import ru.bodro.music.data.account.ConnectionManager
import ru.bodro.music.data.account.SessionState
import ru.bodro.music.data.downloads.DownloadsRepository
import ru.bodro.music.data.local.BodroDatabase
import ru.bodro.music.data.local.PendingFeedbackEntity
import ru.bodro.music.data.log.LogRepository
import ru.bodro.music.data.log.PlayerEvent
import ru.bodro.music.data.repository.AppJson
import ru.bodro.music.di.AppScope
import ru.bodro.music.di.Clock

/** Что скрыто из исполнителей, альбомов и поиска: треки пула и альбомы/исполнители, у которых все треки в пуле. */
data class PoolVisibility(
    val songIds: Set<String> = emptySet(),
    val albumIds: Set<String> = emptySet(),
    val artistIds: Set<String> = emptySet(),
) {
    fun hides(song: Song): Boolean = song.id in songIds

    val isEmpty: Boolean get() = songIds.isEmpty() && albumIds.isEmpty() && artistIds.isEmpty()
}

/**
 * Discovery-пул «На пробу» (bodrod 0.9.0+) и события обратной связи.
 *
 * События (началось, пропущено, дослушано, лайк, дизлайк, в плейлист, показано в волне) копятся в Room
 * (`pending_feedback`) и уходят в bodrod пачками — сразу, если есть сеть, иначе вместе с синхронизацией.
 * Без bodrod или без поддержки пула события отбрасываются.
 */
@Singleton
class PoolRepository @Inject constructor(
    database: BodroDatabase,
    private val downloads: DownloadsRepository,
    private val client: SubsonicClient,
    private val connection: ConnectionManager,
    private val log: LogRepository,
    private val clock: Clock,
    @AppScope private val scope: CoroutineScope,
) {
    private val dao = database.feedbackDao()
    private val flushMutex = Mutex()
    private var flushJob: Job? = null
    private var supported: Pair<Long, Boolean>? = null
    private val songCache = HashMap<String, Song>()

    private val _visibility = MutableStateFlow(PoolVisibility())

    /** Скрытое из исполнителей, альбомов и поиска. */
    val visibility: StateFlow<PoolVisibility> = _visibility.asStateFlow()

    private val _items = MutableStateFlow<List<PoolItem>>(emptyList())

    /** Треки пула пользователя (статус `in_pool`). */
    val items: StateFlow<List<PoolItem>> = _items.asStateFlow()

    init {
        scope.launch {
            connection.session.map { (it as? SessionState.Active)?.account?.id }.distinctUntilChanged().collect { id ->
                _visibility.value = PoolVisibility()
                _items.value = emptyList()
                songCache.clear()
                supported = null
                if (id != null) {
                    refresh()
                    flush()
                }
            }
        }
        scope.launch {
            while (true) {
                delay(REFRESH_MS)
                refresh()
            }
        }
    }

    /** Поддерживает ли bodrod активной учётки пул (запрос `info` — раз на учётку). */
    suspend fun isSupported(): Boolean {
        val accountId = connection.activeAccountId ?: return false
        supported?.takeIf { it.first == accountId }?.let { return it.second }
        if (!downloads.isConfigured) return false
        // Сеть недоступна — спросим в следующий раз (ответ не запоминается).
        val value = runCatching { downloads.info().pool }.onFailure { if (it is CancellationException) throw it }.getOrNull()
        value?.let { supported = accountId to it }
        return value == true
    }

    suspend fun refresh() {
        if (!isSupported()) return
        runCatching { downloads.poolHidden() }.onSuccess { hidden ->
            _visibility.value = PoolVisibility(hidden.songIds.toSet(), hidden.albumIds.toSet(), hidden.artistIds.toSet())
        }
        runCatching { downloads.pool() }.onSuccess { list -> _items.value = list.items.filter { it.status == "in_pool" } }
    }

    /** Треки пула как `Song` (для волны и экрана «На пробу»), без недоступных. */
    suspend fun songs(): List<Song> = coroutineScope {
        val ids = _items.value.mapNotNull { it.songId }
        ids.map { id ->
            async {
                songCache[id] ?: runCatching { client.getSong(id) }.getOrNull()?.also { songCache[id] = it }
            }
        }.mapNotNull { it.await() }
    }

    fun isPoolSong(songId: String): Boolean = songId in _visibility.value.songIds

    // region События

    fun track(song: Song, event: String, context: String, positionMs: Long? = null, durationMs: Long? = null) {
        val accountId = connection.activeAccountId ?: return
        val payload = FeedbackEvent(
            songId = song.id,
            event = event,
            positionMs = positionMs,
            durationMs = durationMs ?: song.durationMs.takeIf { it > 0 },
            context = context,
            at = clock.now(),
        )
        scope.launch {
            val json = AppJson.json.encodeToString(FeedbackEvent.serializer(), payload)
            dao.insert(PendingFeedbackEntity(accountId = accountId, payload = json, createdAt = clock.now()))
            dao.trim(accountId, MAX_QUEUE)
            scheduleFlush(if (event in URGENT) 0 else FLUSH_DELAY_MS)
        }
    }

    private fun scheduleFlush(delayMs: Long) {
        if (delayMs > 0 && flushJob?.isActive == true) return
        flushJob?.cancel()
        flushJob = scope.launch {
            delay(delayMs)
            flush()
        }
    }

    /** Отправить накопленное. `true` — очередь пуста (или отправлять некуда). */
    suspend fun flush(): Boolean = flushMutex.withLock {
        val accountId = connection.activeAccountId ?: return true
        if (dao.count(accountId) == 0) return true
        if (!isSupported()) {
            if (supported?.second == false || !downloads.isConfigured) dao.trim(accountId, 0)
            return supported?.second == false || !downloads.isConfigured
        }
        var changed = false
        while (true) {
            val batch = dao.pending(accountId, BATCH)
            if (batch.isEmpty()) break
            val events = batch.mapNotNull {
                runCatching { AppJson.json.decodeFromString(FeedbackEvent.serializer(), it.payload) }.getOrNull()
            }
            try {
                val result = downloads.feedback(events)
                changed = changed || result.changed.isNotEmpty()
                if (result.changed.isNotEmpty()) {
                    log.event(PlayerEvent.Sync, "Пул: ${result.changed.entries.joinToString { "${it.key} → ${it.value}" }}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: DownloadsException) {
                if (e.error is DownloadsError.Network) return false
                // bodrod без пула или отклонил пачку — не держим её вечно.
                log.event(PlayerEvent.Sync, "События рекомендаций не приняты: ${e.error}", isError = true)
            } catch (_: Exception) {
                return false
            }
            dao.delete(batch.map { it.id })
        }
        if (changed) refresh()
        true
    }

    // endregion

    private companion object {
        const val BATCH = 200
        const val MAX_QUEUE = 5_000
        const val FLUSH_DELAY_MS = 15_000L
        const val REFRESH_MS = 15L * 60 * 1000
        val URGENT = setOf("liked", "disliked", "playlisted")
    }
}
