package ru.bodro.music.core.subsonic

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.bodro.music.core.model.ServerKind

class NavidromeLogInterceptorTest {
    private val server = MockWebServer()
    private val entries = mutableListOf<NetworkLogEntry>()
    private var level = NetworkLogLevel.Normal

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun http() = OkHttpClient.Builder()
        .addInterceptor(NavidromeLogInterceptor({ entries += it }, { level }, { ServerKind.Local }))
        .build()

    @Test
    fun `logs api call with masked secrets and subsonic error`() = runTest {
        level = NetworkLogLevel.Verbose
        server.enqueue(json("error_wrong_credentials.json"))
        val client = SubsonicClient(http(), { server.config(password = "hunter2") }, saltGenerator = { "c19b2d" })
        runCatching { client.ping() }
        val entry = entries.single()
        assertEquals("ping", entry.endpoint)
        assertEquals(RequestCategory.Api, entry.category)
        assertEquals(ServerKind.Local, entry.serverKind)
        assertEquals(200, entry.httpCode)
        assertEquals("failed", entry.subsonicStatus)
        assertEquals(40, entry.errorCode)
        assertEquals("Wrong username or password", entry.errorMessage)
        assertTrue(entry.isError)
        assertTrue(entry.maskedUrl.contains("t=***"))
        assertTrue(entry.maskedUrl.contains("s=***"))
        assertFalse(entry.maskedUrl.contains("c19b2d"))
        assertFalse(entry.toString().contains("hunter2"))
        assertNotNull(entry.responseBody)
    }

    @Test
    fun `normal level does not keep bodies, errors only skips successes`() = runTest {
        server.enqueue(json("ping_ok.json"))
        server.enqueue(json("ping_ok.json"))
        val client = SubsonicClient(http(), { server.config() })
        client.ping()
        assertEquals("ok", entries.single().subsonicStatus)
        assertNull(entries.single().responseBody)
        level = NetworkLogLevel.ErrorsOnly
        client.ping()
        assertEquals(1, entries.size)
    }

    @Test
    fun `stream is categorized and body is not consumed`() = runTest {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "audio/flac").body("FLACDATA").build())
        val url = server.url("/rest/stream.view?id=1&t=abc&s=def")
        val body = http().newCall(Request.Builder().url(url).build()).execute().use { it.body.string() }
        assertEquals("FLACDATA", body)
        assertEquals(RequestCategory.Stream, entries.single().category)
        assertEquals("stream", entries.single().endpoint)
        assertFalse(entries.single().isError)
    }

    @Test
    fun `network failure is logged and rethrown`() = runTest {
        val url = server.url("/rest/getCoverArt.view?id=1")
        server.close()
        val result = runCatching { http().newCall(Request.Builder().url(url).build()).execute() }
        assertTrue(result.isFailure)
        val entry = entries.single()
        assertEquals(RequestCategory.Cover, entry.category)
        assertNull(entry.httpCode)
        assertTrue(entry.isError)
    }

    @Test
    fun `categories`() {
        assertEquals(RequestCategory.Scrobble, NavidromeLogInterceptor.categoryOf("scrobble"))
        assertEquals(RequestCategory.Api, NavidromeLogInterceptor.categoryOf("getAlbum"))
    }
}
