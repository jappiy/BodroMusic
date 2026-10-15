package ru.bodro.music.core.subsonic

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** star/unstar, setRating, scrobble, обработка ошибок и таймаутов через MockWebServer. */
class SubsonicActionsTest {
    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    @Test
    fun `star and unstar song, album, artist`() = runTest {
        val client = server.client()
        repeat(3) { server.enqueue(json("empty_ok.json")) }
        client.star(songId = "s-1")
        client.unstar(albumId = "al-1")
        client.star(artistId = "ar-1")
        server.takeRequest().url.let {
            assertEquals("star.view", it.pathSegments.last())
            assertEquals("s-1", it.queryParameter("id"))
        }
        server.takeRequest().url.let {
            assertEquals("unstar.view", it.pathSegments.last())
            assertEquals("al-1", it.queryParameter("albumId"))
            assertNull(it.queryParameter("id"))
        }
        assertEquals("ar-1", server.takeRequest().url.queryParameter("artistId"))
    }

    @Test
    fun `set rating 1 is dislike, 0 removes it`() = runTest {
        val client = server.client()
        repeat(2) { server.enqueue(json("empty_ok.json")) }
        client.setRating("s-1", 1)
        client.setRating("s-1", 0)
        assertEquals("1", server.takeRequest().url.queryParameter("rating"))
        assertEquals("0", server.takeRequest().url.queryParameter("rating"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rating out of range is rejected locally`() = runTest {
        server.client().setRating("s-1", 6)
    }

    @Test
    fun `scrobble now playing and submission`() = runTest {
        val client = server.client()
        repeat(2) { server.enqueue(json("empty_ok.json")) }
        client.scrobble("s-1", submission = false)
        client.scrobble("s-1", timeMs = 1_700_000_000_000, submission = true)
        server.takeRequest().url.let {
            assertEquals("false", it.queryParameter("submission"))
            assertNull(it.queryParameter("time"))
        }
        server.takeRequest().url.let {
            assertEquals("true", it.queryParameter("submission"))
            assertEquals("1700000000000", it.queryParameter("time"))
        }
    }

    @Test
    fun `http error is mapped`() = runTest {
        server.enqueue(MockResponse.Builder().code(502).body("Bad gateway").build())
        try {
            server.client().star(songId = "s-1")
            fail()
        } catch (e: SubsonicException) {
            assertEquals(SubsonicError.Http(502), e.error)
        }
    }

    @Test
    fun `timeout is mapped to network timeout`() = runTest {
        server.enqueue(json("empty_ok.json").newBuilder().headersDelay(2, TimeUnit.SECONDS).build())
        try {
            server.client(http = testHttp(timeoutMs = 300)).scrobble("s-1", submission = false)
            fail()
        } catch (e: SubsonicException) {
            val error = e.error as SubsonicError.Network
            assertEquals(NetworkErrorKind.Timeout, error.kind)
        }
    }

    @Test
    fun `unreachable server is mapped`() = runTest {
        val config = server.config()
        server.close()
        val client = SubsonicClient(testHttp(500), { config })
        try {
            client.ping()
            fail()
        } catch (e: SubsonicException) {
            val error = e.error as SubsonicError.Network
            assertTrue(error.kind == NetworkErrorKind.Unreachable || error.kind == NetworkErrorKind.Timeout)
        }
    }

    @Test
    fun `no account configured`() = runTest {
        try {
            SubsonicClient(testHttp(), { null }).ping()
            fail()
        } catch (e: SubsonicException) {
            assertEquals(SubsonicError.NotConfigured, e.error)
        }
    }
}
