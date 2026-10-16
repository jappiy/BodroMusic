package ru.bodro.music.core.downloads

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import ru.bodro.music.core.subsonic.SubsonicAuth

class DownloadsClientTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun client(configured: Boolean = true) = DownloadsClient(
        http,
        { if (configured) DownloadsConfig(server.url("/bodrod/"), "vasya", "sesame") else null },
        saltGenerator = { "abc123" },
    )

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    @Test
    fun `discover sends subsonic auth headers and parses camelCase`() = runTest {
        server.enqueue(
            json(
                """
                {"intent":"artist","source":"yandex","tracks":[],"artists":[],
                 "artist":{"name":"Трофим","coverUrl":null,"source":"yandex","sourceId":"77"},
                 "topTracks":[{"title":"Город Сочи","artist":"Трофим","album":"A","durationMs":200000,
                   "coverUrl":"https://c/1.jpg","source":"yandex","sourceId":"1","librarySongId":null},
                   {"title":"Снегири","artist":"Трофим","source":"yandex","sourceId":"3","librarySongId":"nd-1","extra":1}]}
                """.trimIndent(),
            ),
        )
        val result = client().discover("трофим")
        val request = server.takeRequest()
        assertEquals("/bodrod/api/v1/discover", request.url.encodedPath)
        assertEquals("трофим", request.url.queryParameter("q"))
        assertEquals("vasya", request.headers["X-Subsonic-User"])
        assertEquals("abc123", request.headers["X-Subsonic-Salt"])
        assertEquals(SubsonicAuth.token("sesame", "abc123"), request.headers["X-Subsonic-Token"])
        assertEquals(DiscoverIntent.Artist, result.intent)
        assertEquals("Трофим", result.artist?.name)
        assertEquals(200_000L, result.topTracks[0].durationMs)
        assertEquals("nd-1", result.topTracks[1].librarySongId)
    }

    @Test
    fun `download track posts body and parses request`() = runTest {
        server.enqueue(
            json(
                """{"id":"r1","kind":"track","artist":"Трофим","title":"Город Сочи","status":"queued",
                "progress":null,"createdAt":1,"updatedAt":1}""",
                code = 201,
            ),
        )
        val track = DiscoverTrack("Город Сочи", "Трофим", durationMs = 200_000, source = "yandex", sourceId = "1")
        val created = client().downloadTrack(track)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        val body = request.body!!.utf8()
        assertTrue(body, body.contains("\"kind\":\"track\""))
        assertTrue(body, body.contains("\"durationMs\":200000"))
        assertEquals(RequestStatus.Queued, created.status)
        assertEquals(track.key, created.key)
    }

    @Test
    fun `errors are mapped`() = runTest {
        server.enqueue(json("""{"detail":"Navidrome не принял учётные данные"}""", code = 401))
        server.enqueue(json("""{"detail":"Скачивание исполнителей не настроено (Lidarr)"}""", code = 409))
        expect(DownloadsError.Unauthorized) { client().requests() }
        expect(DownloadsError.Disabled("Скачивание исполнителей не настроено (Lidarr)")) {
            client().downloadArtist(DiscoverArtist("Трофим", null, "yandex", "77"))
        }
        expect(DownloadsError.NotConfigured) { client(configured = false).requests() }
    }

    @Test
    fun `delete accepts empty 204`() = runTest {
        server.enqueue(MockResponse.Builder().code(204).build())
        client().delete("r1")
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test
    fun `watch notifies only about transitions to finished`() {
        fun req(id: String, status: RequestStatus) = DownloadRequest(id, RequestKind.Track, "A", "T", status = status)
        val first = listOf(req("old", RequestStatus.Completed), req("a", RequestStatus.Downloading))
        assertTrue(DownloadWatch.finished(null, first).isEmpty())
        val known = DownloadWatch.snapshot(first)
        val next = listOf(
            req("old", RequestStatus.Completed),
            req("a", RequestStatus.Completed),
            req("fast", RequestStatus.Failed),
            req("new", RequestStatus.Queued),
        )
        assertEquals(listOf("a", "fast"), DownloadWatch.finished(known, next).map { it.id })
        assertTrue(DownloadWatch.hasActive(next))
    }

    @Test
    fun `keys ignore case and punctuation`() {
        assertEquals(requestKey("Трофим", "Город Сочи"), requestKey("трофим ", "Город  сочи!"))
        assertEquals(requestKey("Ёлка", "Прованс"), requestKey("Елка", "прованс"))
        assertNull(DownloadsConfig.normalizeUrl(""))
        assertEquals("http://nas.local:8790/", DownloadsConfig.normalizeUrl("nas.local:8790").toString())
    }

    private suspend fun expect(error: DownloadsError, block: suspend () -> Unit) {
        try {
            block()
            fail("ожидалась ошибка $error")
        } catch (e: DownloadsException) {
            assertEquals(error, e.error)
        }
    }
}
