package ru.bodro.music.playback

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.bodro.music.core.domain.player.SleepTimerOption
import ru.bodro.music.core.domain.player.SleepTimerState
import ru.bodro.music.core.model.QueueSnapshot
import ru.bodro.music.core.model.QueueSource
import ru.bodro.music.data.account.ConnectionManager
import ru.bodro.music.data.local.BodroDatabase
import ru.bodro.music.data.local.QueueStateEntity
import ru.bodro.music.data.repository.AppJson
import ru.bodro.music.di.Clock

/** Источник текущей очереди (альбом, плейлист, волна…). Общий для UI и сервиса (один процесс). */
@Singleton
class QueueSourceHolder @Inject constructor() {
    private val _source = MutableStateFlow<QueueSource>(QueueSource.CustomSource())
    val source: StateFlow<QueueSource> = _source.asStateFlow()

    fun set(source: QueueSource) {
        _source.value = source
    }
}

/** Таймер сна: сервис следит за ним и ставит паузу. */
@Singleton
class SleepTimerController @Inject constructor(private val clock: Clock) {
    private val _state = MutableStateFlow<SleepTimerState?>(null)
    val state: StateFlow<SleepTimerState?> = _state.asStateFlow()

    fun start(option: SleepTimerOption) {
        _state.value = SleepTimerState(option, clock.now())
    }

    fun cancel() {
        _state.value = null
    }
}

/** Сохранение и восстановление очереди по учётке (Room). */
@Singleton
class QueuePersister @Inject constructor(
    database: BodroDatabase,
    private val connection: ConnectionManager,
    private val clock: Clock,
) {
    private val dao = database.queueDao()

    suspend fun save(accountId: Long, snapshot: QueueSnapshot) {
        if (snapshot.songs.isEmpty()) {
            dao.delete(accountId)
            return
        }
        dao.upsert(
            QueueStateEntity(
                accountId = accountId,
                snapshotJson = AppJson.encodeSnapshot(snapshot),
                currentIndex = snapshot.currentIndex,
                positionMs = snapshot.positionMs,
                updatedAt = clock.now(),
            ),
        )
    }

    suspend fun savePosition(accountId: Long, index: Int, positionMs: Long) =
        dao.updatePosition(accountId, index, positionMs, clock.now())

    suspend fun load(accountId: Long? = connection.activeAccountId): QueueSnapshot? {
        accountId ?: return null
        val entity = dao.get(accountId) ?: return null
        val snapshot = AppJson.decodeSnapshot(entity.snapshotJson) ?: return null
        return snapshot.copy(currentIndex = entity.currentIndex, positionMs = entity.positionMs).sanitized()
    }

    suspend fun clear(accountId: Long) = dao.delete(accountId)
}
