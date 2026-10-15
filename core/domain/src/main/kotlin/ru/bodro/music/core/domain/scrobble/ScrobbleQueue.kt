package ru.bodro.music.core.domain.scrobble

/** Отложенный скроббл (лежит в Room, пока не отправлен). */
data class PendingScrobble(
    val id: Long,
    val accountId: Long,
    val songId: String,
    val timestampMs: Long,
    val attempts: Int = 0,
)

/** Хранилище очереди. Реализация обязана игнорировать дубли (accountId, songId, timestampMs). */
interface PendingScrobbleStore {
    /** Ожидающие отправки — строго по возрастанию времени прослушивания. */
    suspend fun pending(accountId: Long, limit: Int): List<PendingScrobble>

    suspend fun enqueue(accountId: Long, songId: String, timestampMs: Long)

    suspend fun remove(id: Long)

    suspend fun markAttempt(id: Long)

    suspend fun count(accountId: Long): Int
}

fun interface ScrobbleSender {
    suspend fun submit(songId: String, timestampMs: Long)
}

data class ScrobbleSyncResult(val sent: Int, val dropped: Int, val remaining: Int, val shouldRetry: Boolean)

/**
 * Отправка скробблов: если очередь не пуста — сначала она (хронология), новый скроббл встаёт в конец.
 * Сетевые ошибки останавливают отправку (повтор позже), «окончательные» ошибки (трек удалён) — выбрасывают запись.
 */
class ScrobbleQueue(
    private val store: PendingScrobbleStore,
    private val isPermanentError: (Throwable) -> Boolean,
    private val maxAttempts: Int = MAX_ATTEMPTS,
) {
    /** Засчитать прослушивание. Возвращает `true`, если оно ушло на сервер сразу. */
    suspend fun submit(accountId: Long, songId: String, timestampMs: Long, sender: ScrobbleSender): Boolean {
        if (store.count(accountId) > 0) {
            store.enqueue(accountId, songId, timestampMs)
            val result = flush(accountId, sender)
            return result.remaining == 0
        }
        return try {
            sender.submit(songId, timestampMs)
            true
        } catch (e: Exception) {
            if (!isPermanentError(e)) store.enqueue(accountId, songId, timestampMs)
            false
        }
    }

    /** Отправить всё накопленное по порядку. */
    suspend fun flush(accountId: Long, sender: ScrobbleSender): ScrobbleSyncResult {
        var sent = 0
        var dropped = 0
        while (true) {
            val batch = store.pending(accountId, BATCH)
            if (batch.isEmpty()) break
            for (item in batch) {
                try {
                    sender.submit(item.songId, item.timestampMs)
                    store.remove(item.id)
                    sent++
                } catch (e: Exception) {
                    if (isPermanentError(e) || item.attempts + 1 >= maxAttempts) {
                        store.remove(item.id)
                        dropped++
                        continue
                    }
                    store.markAttempt(item.id)
                    return ScrobbleSyncResult(sent, dropped, store.count(accountId), shouldRetry = true)
                }
            }
        }
        return ScrobbleSyncResult(sent, dropped, 0, shouldRetry = false)
    }

    private companion object {
        const val BATCH = 50
        const val MAX_ATTEMPTS = 50
    }
}
