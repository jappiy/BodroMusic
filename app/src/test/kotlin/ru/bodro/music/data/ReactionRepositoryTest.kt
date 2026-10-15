package ru.bodro.music.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.bodro.music.core.model.Account
import ru.bodro.music.core.model.Reaction
import ru.bodro.music.core.model.ServerKind
import ru.bodro.music.core.model.Song
import ru.bodro.music.core.subsonic.ConnectionConfig
import ru.bodro.music.core.subsonic.SubsonicClient
import ru.bodro.music.data.account.ConnectionManager
import ru.bodro.music.data.account.SessionState
import ru.bodro.music.data.local.BodroDatabase
import ru.bodro.music.data.log.LogRepository
import ru.bodro.music.data.repository.ReactionRepository
import ru.bodro.music.sync.SyncScheduler

/** Лайк/дизлайк: взаимоисключение, отправка на сервер, офлайн-очередь, синхронизация рейтинга 1. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ReactionRepositoryTest {
    private val server = MockWebServer()
    private val requests = Collections.synchronizedList(mutableListOf<String>())

    @Volatile private var online = true
    private lateinit var db: BodroDatabase
    private val session = MutableStateFlow<SessionState>(SessionState.Active(Account(1, "vasya", "http://h", null)))
    private val connection = mockk<ConnectionManager>(relaxed = true)
    private val song = Song("s-1", "Группа крови", artistId = "ar-1")

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!online) return MockResponse.Builder().code(503).build()
                val url = request.url
                requests +=
                    url.pathSegments.last().removeSuffix(".view") + listOfNotNull(url.queryParameter("rating")).joinToString("") { ":$it" }
                return MockResponse.Builder().addHeader("Content-Type", "application/json")
                    .body("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}""").build()
            }
        }
        server.start()
        db = inMemoryDb()
        every { connection.activeAccountId } answers { (session.value as? SessionState.Active)?.account?.id }
        every { connection.session } returns session
    }

    private var repoScope: CoroutineScope? = null

    @After
    fun tearDown() {
        repoScope?.cancel()
        db.close()
        server.close()
    }

    /** HTTP-клиент работает на тестовом диспетчере — всё детерминировано через advanceUntilIdle(). */
    private fun TestScope.repository(): ReactionRepository {
        val config = ConnectionConfig(server.url("/"), ServerKind.Local, "vasya", "pw")
        val http = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()
        return ReactionRepository(
            database = db,
            client = SubsonicClient(http, { config }, dispatcher = StandardTestDispatcher(testScheduler)),
            connection = connection,
            log = mockk<LogRepository>(relaxed = true),
            syncScheduler = { mockk<SyncScheduler>(relaxed = true) },
            clock = { 1_000L },
            // Не backgroundScope: advanceUntilIdle() не выполняет фоновые корутины.
            scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job()).also { repoScope = it },
        )
    }

    @Test
    fun `like then dislike is mutually exclusive on server and locally`() = runTest {
        val repo = repository()
        advanceUntilIdle()
        assertEquals(Reaction.Liked, repo.toggleLike(song))
        advanceUntilIdle()
        assertEquals(Reaction.Disliked, repo.toggleDislike(song))
        advanceUntilIdle()
        assertEquals(listOf("star", "unstar", "setRating:1"), requests.toList())
        assertEquals(Reaction.Disliked, repo.reactionOf(song))
        assertTrue(db.reactionDao().getLiked(1).isEmpty())
        assertEquals(listOf("s-1"), db.reactionDao().getDisliked(1).map { it.songId })
        assertTrue("pending queue drained", db.reactionDao().getPending(1).isEmpty())
    }

    @Test
    fun `offline changes are queued, collapsed and sent later`() = runTest {
        val repo = repository()
        advanceUntilIdle()
        online = false
        repo.toggleLike(song)
        advanceUntilIdle()
        repo.toggleDislike(song)
        advanceUntilIdle()
        val pending = db.reactionDao().getPending(1).single()
        assertEquals(Reaction.None.name, pending.serverState)
        assertEquals(Reaction.Disliked.name, pending.desiredState)
        assertEquals("UI is optimistic", Reaction.Disliked, repo.reactionOf(song))
        online = true
        requests.clear()
        assertTrue(repo.syncPending())
        assertEquals("only the collapsed change is sent", listOf("setRating:1"), requests.toList())
        assertTrue(db.reactionDao().getPending(1).isEmpty())
    }

    @Test
    fun `server rating 1 is ingested as dislike and removal is synced back`() = runTest {
        val repo = repository()
        advanceUntilIdle()
        repo.ingest(listOf(song.copy(userRating = 1)))
        assertEquals(Reaction.Disliked, repo.reactionOf(song))
        assertEquals(1, db.reactionDao().getDisliked(1).size)
        repo.ingest(listOf(song.copy(userRating = null)))
        assertEquals(Reaction.None, repo.reactionOf(song))
        assertTrue(db.reactionDao().getDisliked(1).isEmpty())
    }

    @Test
    fun `dislikes are isolated per account`() = runTest {
        val repo = repository()
        advanceUntilIdle()
        repo.toggleDislike(song)
        advanceUntilIdle()
        session.value = SessionState.Active(Account(2, "petya", "http://h", null))
        advanceUntilIdle()
        assertEquals(Reaction.None, repo.reactionOf(song))
        assertTrue(repo.dislikedSongs().isEmpty())
        session.value = SessionState.Active(Account(1, "vasya", "http://h", null))
        advanceUntilIdle()
        assertEquals(Reaction.Disliked, repo.reactionOf(song))
    }
}
