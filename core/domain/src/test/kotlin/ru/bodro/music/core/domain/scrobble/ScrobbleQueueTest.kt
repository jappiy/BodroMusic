package ru.bodro.music.core.domain.scrobble

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeScrobbleStore : PendingScrobbleStore {
    val items = mutableListOf<PendingScrobble>()
    private var nextId = 1L

    override suspend fun pending(accountId: Long, limit: Int) =
        items.filter { it.accountId == accountId }.sortedWith(compareBy({ it.timestampMs }, { it.id })).take(limit)

    override suspend fun enqueue(accountId: Long, songId: String, timestampMs: Long) {
        if (items.none { it.accountId == accountId && it.songId == songId && it.timestampMs == timestampMs }) {
            items += PendingScrobble(nextId++, accountId, songId, timestampMs)
        }
    }

    override suspend fun remove(id: Long) {
        items.removeAll { it.id == id }
    }

    override suspend fun markAttempt(id: Long) {
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) items[index] = items[index].copy(attempts = items[index].attempts + 1)
    }

    override suspend fun count(accountId: Long) = items.count { it.accountId == accountId }
}

class PermanentError : Exception()

class ScrobbleQueueTest {
    private val store = FakeScrobbleStore()
    private val queue = ScrobbleQueue(store, isPermanentError = { it is PermanentError })
    private val sent = mutableListOf<Pair<String, Long>>()
    private var online = true
    private val sender = ScrobbleSender { songId, ts ->
        if (!online) throw IOException("offline")
        sent += songId to ts
    }

    @Test
    fun `online scrobble goes straight to server`() = runTest {
        assertTrue(queue.submit(1, "a", 100, sender))
        assertEquals(listOf("a" to 100L), sent)
        assertEquals(0, store.count(1))
    }

    @Test
    fun `offline scrobbles are queued and sent later in chronological order`() = runTest {
        online = false
        assertFalse(queue.submit(1, "b", 200, sender))
        assertFalse(queue.submit(1, "a", 100, sender))
        assertFalse(queue.submit(1, "c", 300, sender))
        assertEquals(3, store.count(1))
        online = true
        val result = queue.flush(1, sender)
        assertEquals(3, result.sent)
        assertFalse(result.shouldRetry)
        assertEquals(listOf("a" to 100L, "b" to 200L, "c" to 300L), sent)
        assertEquals(0, store.count(1))
    }

    @Test
    fun `new scrobble waits behind queued ones`() = runTest {
        online = false
        queue.submit(1, "old", 100, sender)
        online = true
        assertTrue(queue.submit(1, "new", 200, sender))
        assertEquals(listOf("old" to 100L, "new" to 200L), sent)
    }

    @Test
    fun `no duplicates when the same scrobble is enqueued twice`() = runTest {
        online = false
        queue.submit(1, "a", 100, sender)
        queue.submit(1, "a", 100, sender)
        assertEquals(1, store.count(1))
        online = true
        queue.flush(1, sender)
        queue.flush(1, sender)
        assertEquals(listOf("a" to 100L), sent)
    }

    @Test
    fun `failure in the middle keeps the rest and asks for retry`() = runTest {
        online = false
        queue.submit(1, "a", 100, sender)
        queue.submit(1, "b", 200, sender)
        var calls = 0
        val flaky = ScrobbleSender { songId, ts ->
            if (calls++ == 1) throw IOException("lost")
            sent += songId to ts
        }
        val result = queue.flush(1, flaky)
        assertTrue(result.shouldRetry)
        assertEquals(1, result.remaining)
        assertEquals(1, store.items.single().attempts)
        online = true
        queue.flush(1, sender)
        assertEquals(listOf("a" to 100L, "b" to 200L), sent)
    }

    @Test
    fun `permanent errors drop the entry and do not block the queue`() = runTest {
        online = false
        queue.submit(1, "deleted", 100, sender)
        queue.submit(1, "ok", 200, sender)
        val result = queue.flush(1) { songId, ts ->
            if (songId == "deleted") throw PermanentError()
            sent += songId to ts
        }
        assertEquals(1, result.dropped)
        assertEquals(listOf("ok" to 200L), sent)
    }

    @Test
    fun `accounts are isolated`() = runTest {
        online = false
        queue.submit(1, "mine", 100, sender)
        queue.submit(2, "theirs", 100, sender)
        online = true
        queue.flush(1, sender)
        assertEquals(listOf("mine" to 100L), sent)
        assertEquals(1, store.count(2))
    }

    @Test
    fun `gives up after max attempts`() = runTest {
        val limited = ScrobbleQueue(store, { false }, maxAttempts = 2)
        online = false
        limited.submit(1, "a", 100, sender)
        assertTrue(limited.flush(1, sender).shouldRetry)
        val result = limited.flush(1, sender)
        assertEquals(1, result.dropped)
        assertEquals(0, store.count(1))
    }
}
