package ru.bodro.music.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.bodro.music.core.model.QueueSnapshot
import ru.bodro.music.core.model.QueueSource
import ru.bodro.music.core.model.RepeatMode
import ru.bodro.music.core.model.Song
import ru.bodro.music.core.model.WaveSettings
import ru.bodro.music.data.account.ConnectionManager
import ru.bodro.music.data.local.BodroDatabase
import ru.bodro.music.data.local.LogEntryEntity
import ru.bodro.music.data.local.PlayHistoryEntity
import ru.bodro.music.data.local.SearchHistoryEntity
import ru.bodro.music.data.repository.RoomPendingScrobbleStore
import ru.bodro.music.playback.QueuePersister

/** Room: сохранение/восстановление очереди, очередь скробблов, изоляция данных учёток. */
@RunWith(AndroidJUnit4::class)
class LocalStorageTest {
    private lateinit var db: BodroDatabase
    private val connection = mockk<ConnectionManager>(relaxed = true)
    private val songs = listOf(Song("a", "A", duration = 200), Song("b", "B", duration = 180))

    @Before
    fun setUp() {
        db = inMemoryDb()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `queue snapshot round trip with position update`() = runTest {
        val persister = QueuePersister(db, connection) { 1_000L }
        val snapshot = QueueSnapshot(
            songs = songs,
            currentIndex = 1,
            positionMs = 30_000,
            repeatMode = RepeatMode.All.name,
            shuffle = true,
            source = QueueSource.WaveSource(WaveSettings()),
        )
        persister.save(1, snapshot)
        persister.savePosition(1, 0, 12_345)
        val loaded = persister.load(1)!!
        assertEquals(songs, loaded.songs)
        assertEquals(0, loaded.currentIndex)
        assertEquals(12_345L, loaded.positionMs)
        assertEquals(RepeatMode.All.name, loaded.repeatMode)
        assertEquals(true, loaded.shuffle)
        assertEquals(QueueSource.WaveSource(WaveSettings()), loaded.source)
    }

    @Test
    fun `queues are per account`() = runTest {
        val persister = QueuePersister(db, connection) { 0L }
        persister.save(1, QueueSnapshot(songs.take(1), 0, 1))
        persister.save(2, QueueSnapshot(songs.drop(1), 0, 2))
        assertEquals("a", persister.load(1)!!.currentSong!!.id)
        assertEquals("b", persister.load(2)!!.currentSong!!.id)
        every { connection.activeAccountId } returns 2L
        assertEquals("b", persister.load()!!.currentSong!!.id)
        persister.save(1, QueueSnapshot(emptyList(), 0, 0))
        assertNull(persister.load(1))
    }

    @Test
    fun `pending scrobbles keep order and ignore duplicates`() = runTest {
        val store = RoomPendingScrobbleStore(db)
        store.enqueue(1, "b", 200)
        store.enqueue(1, "a", 100)
        store.enqueue(1, "a", 100)
        store.enqueue(2, "z", 50)
        assertEquals(listOf("a", "b"), store.pending(1, 10).map { it.songId })
        assertEquals(2, store.count(1))
        store.markAttempt(store.pending(1, 1).single().id)
        assertEquals(1, store.pending(1, 1).single().attempts)
    }

    @Test
    fun `logout removes only that account's data`() = runTest {
        val persister = QueuePersister(db, connection) { 0L }
        for (account in listOf(1L, 2L)) {
            persister.save(account, QueueSnapshot(songs, 0, 0))
            RoomPendingScrobbleStore(db).enqueue(account, "a", 1)
            db.searchHistoryDao().upsert(SearchHistoryEntity(account, "кино", 1))
            db.historyDao().insert(
                PlayHistoryEntity(
                    accountId = account,
                    songId = "a",
                    artistId = null,
                    albumId = null,
                    genre = null,
                    playedAt = 1,
                    listenedMs = 1000,
                    skippedEarly = false,
                    songJson = "{}",
                ),
            )
            db.logDao().insertAll(listOf(log(account)))
        }
        db.clearAccountData(1)
        assertNull(persister.load(1))
        assertEquals(0, db.scrobbleDao().count(1))
        assertEquals(1, db.scrobbleDao().count(2))
        assertEquals(0, db.historyDao().since(1, 0).size)
        assertEquals(1, db.historyDao().since(2, 0).size)
        assertEquals(1, db.logDao().export(2, 10).size)
    }

    private fun log(account: Long) = LogEntryEntity(
        accountId = account, timestamp = 1, type = "Network", category = "Api", endpoint = "ping", serverKind = null, method = "GET",
        url = null, httpCode = 200, subsonicStatus = "ok", errorCode = null, message = null, durationMs = 1, size = 1,
        requestHeaders = null, responseHeaders = null, body = null, isError = false,
    )
}
