package ru.bodro.music.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import ru.bodro.music.core.domain.scrobble.PendingScrobble
import ru.bodro.music.core.domain.scrobble.PendingScrobbleStore
import ru.bodro.music.core.domain.scrobble.ScrobbleQueue
import ru.bodro.music.core.domain.scrobble.ScrobbleSender
import ru.bodro.music.core.domain.scrobble.ScrobbleSyncResult
import ru.bodro.music.core.subsonic.ApiErrorKind
import ru.bodro.music.core.subsonic.SubsonicClient
import ru.bodro.music.core.subsonic.SubsonicError
import ru.bodro.music.core.subsonic.SubsonicException
import ru.bodro.music.data.account.ConnectionManager
import ru.bodro.music.data.account.SessionState
import ru.bodro.music.data.local.BodroDatabase
import ru.bodro.music.data.local.PendingScrobbleEntity
import ru.bodro.music.data.log.LogRepository
import ru.bodro.music.data.log.PlayerEvent
import ru.bodro.music.sync.SyncScheduler

/** Room-реализация очереди скробблов (уникальность (accountId, songId, timestamp) — индексом). */
class RoomPendingScrobbleStore(database: BodroDatabase) : PendingScrobbleStore {
    private val dao = database.scrobbleDao()

    override suspend fun pending(accountId: Long, limit: Int): List<PendingScrobble> =
        dao.pending(accountId, limit).map { PendingScrobble(it.id, it.accountId, it.songId, it.timestamp, it.attempts) }

    override suspend fun enqueue(accountId: Long, songId: String, timestampMs: Long) {
        dao.insert(PendingScrobbleEntity(accountId = accountId, songId = songId, timestamp = timestampMs))
    }

    override suspend fun remove(id: Long) = dao.delete(id)

    override suspend fun markAttempt(id: Long) = dao.incrementAttempts(id)

    override suspend fun count(accountId: Long): Int = dao.count(accountId)
}

/** Скробблы в Navidrome: «сейчас играет» и засчитанные прослушивания (с офлайн-очередью). */
@Singleton
class ScrobbleRepository @Inject constructor(
    private val database: BodroDatabase,
    private val client: SubsonicClient,
    private val connection: ConnectionManager,
    private val log: LogRepository,
    private val syncScheduler: dagger.Lazy<SyncScheduler>,
) {
    private val queue = ScrobbleQueue(RoomPendingScrobbleStore(database), ::isPermanent)
    private val sender = ScrobbleSender { songId, ts -> client.scrobble(songId, ts, submission = true) }

    suspend fun nowPlaying(songId: String) {
        runCatching { client.scrobble(songId, submission = false) }
    }

    suspend fun submit(accountId: Long, songId: String, startedAtMs: Long) {
        if (connection.activeAccountId != accountId) {
            // Учётку переключили — сохраняем в очередь той учётки, отправим позже.
            RoomPendingScrobbleStore(database).enqueue(accountId, songId, startedAtMs)
            return
        }
        val sent = queue.submit(accountId, songId, startedAtMs, sender)
        if (sent) {
            log.event(PlayerEvent.ScrobbleSent, "Прослушивание засчитано: $songId")
        } else {
            log.event(PlayerEvent.ScrobbleQueued, "Нет связи — скроббл $songId в очереди")
            syncScheduler.get().requestSync()
        }
    }

    /** Отправить очередь активной учётки. */
    suspend fun flush(): ScrobbleSyncResult? {
        val accountId = connection.activeAccountId ?: return null
        val result = queue.flush(accountId, sender)
        if (result.sent > 0 || result.dropped > 0) {
            log.event(
                PlayerEvent.ScrobbleSent,
                "Из очереди отправлено: ${result.sent}, отброшено: ${result.dropped}, осталось: ${result.remaining}",
            )
        }
        return result
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observePendingCount(): Flow<Int> = connection.session.flatMapLatest { session ->
        val id = (session as? SessionState.Active)?.account?.id ?: return@flatMapLatest flowOf(0)
        database.scrobbleDao().observeCount(id)
    }

    /** Трек удалён с сервера или запрос некорректен — повторять бессмысленно. */
    private fun isPermanent(error: Throwable): Boolean {
        val api = (error as? SubsonicException)?.error as? SubsonicError.Api ?: return false
        return api.kind == ApiErrorKind.NotFound || api.kind == ApiErrorKind.MissingParameter
    }
}
