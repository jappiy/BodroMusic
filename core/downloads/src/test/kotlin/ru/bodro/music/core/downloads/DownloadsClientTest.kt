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
    fun `chart download is linked to position and entry carries request status`() = runTest {
        server.enqueue(
            json(
                """{"id":"r2","kind":"track","artist":"ADÉLA","title":"Nicole Kidman","status":"queued",
                "progress":null,"createdAt":1,"updatedAt":1}""",
                code = 201,
            ),
        )
        server.enqueue(
            json(
                """[{"position":1,"artist":"ADÉLA","title":"Nicole Kidman","movement":"new","state":"queued",
                "requestId":"r2","requestStatus":"queued"},
                {"position":2,"artist":"B","title":"2","movement":"same","state":"unavailable",
                "requestId":"r3","requestStatus":"completed"}]""",
            ),
        )
        val track = DiscoverTrack("Nicole Kidman", "ADÉLA", source = "chart", sourceId = "ADÉLA — Nicole Kidman")
        client().downloadTrack(track, chartId = "deezer_world", chartPosition = 1)
        val body = server.takeRequest().body!!.utf8()
        assertTrue(body, body.contains("\"chartId\":\"deezer_world\"") && body.contains("\"chartPosition\":1"))
        val entries = client().chart("deezer_world")
        assertEquals("queued", entries[0].requestStatus)
        assertTrue(entries[0].isPending)
        assertTrue(entries[1].isReady)
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
    fun `cleanup endpoints`() = runTest {
        server.enqueue(
            json(
                """[{"id":"s1","title":"Город Сочи","artist":"Трофим","albumId":"al1","duration":200,
                "path":"library/Трофим/x.mp3","dislikedBy":["masha","vasya"],"extra":1}]""",
            ),
        )
        server.enqueue(json("""[{"id":"s1","ok":true,"message":"Удалён"}]"""))
        server.enqueue(json("""{"total":1,"items":[{"id":7,"artist":"Трофим","title":"Город Сочи","createdBy":"vasya"}]}"""))
        server.enqueue(MockResponse.Builder().code(204).build())
        server.enqueue(json("""{"detail":"Доступно только администраторам"}""", code = 403))

        val songs = client().disliked(username = null)
        assertEquals(listOf("masha", "vasya"), songs.single().dislikedBy)
        assertEquals("/bodrod/api/v1/cleanup/disliked", server.takeRequest().url.encodedPath)

        assertTrue(client().deleteSongs(listOf("s1")).single().ok)
        val delete = server.takeRequest()
        assertEquals("POST", delete.method)
        assertEquals("""{"ids":["s1"]}""", delete.body!!.utf8())

        val page = client().blocklist("сочи")
        assertEquals(7L, page.items.single().id)
        assertEquals("сочи", server.takeRequest().url.queryParameter("q"))

        client().unblock(7)
        val unblock = server.takeRequest()
        assertEquals("DELETE", unblock.method)
        assertEquals("/bodrod/api/v1/blocklist/7", unblock.url.encodedPath)

        expect(DownloadsError.Forbidden) { client().cleanupUsers() }
    }

    @Test
    fun `library check, popular and maintenance endpoints`() = runTest {
        server.enqueue(json("""{"missing":["t1"],"replaced":{"s1":"a1"}}"""))
        server.enqueue(
            json(
                """{"source":"yandex","artist":{"name":"Трофим","source":"yandex","sourceId":"5"},
                "tracks":[{"title":"Город Сочи","artist":"Трофим","source":"yandex","sourceId":"1","librarySongId":"lib-1"}]}""",
            ),
        )
        server.enqueue(
            json(
                """{"enabled":true,"running":false,"dryRun":false,"schedule":"04:00","nextRunAt":5,"quarantineDays":30,
                "lastRun":{"id":3,"trigger":"schedule","status":"completed","dryRun":false,
                "stats":{"duplicates_removed":2},"startedAt":1,"finishedAt":2}}""",
            ),
        )
        server.enqueue(
            json(
                """{"total":1,"items":[{"id":9,"runId":3,"kind":"duplicate","applied":true,"title":"Песня",
                "details":"Дубль","createdAt":1,"quarantineId":4,"restorable":true}]}""",
            ),
        )
        server.enqueue(MockResponse.Builder().code(202).build())
        server.enqueue(MockResponse.Builder().code(204).build())

        val check = client().checkLibrary(listOf("s1", "t1"))
        assertEquals(listOf("t1"), check.missing)
        assertEquals(mapOf("s1" to "a1"), check.replaced)
        val checkRequest = server.takeRequest()
        assertEquals("/bodrod/api/v1/library/check", checkRequest.url.encodedPath)
        assertEquals("""{"ids":["s1","t1"]}""", checkRequest.body!!.utf8())

        assertEquals("lib-1", client().artistPopular("Трофим").tracks.single().librarySongId)
        assertEquals("Трофим", server.takeRequest().url.queryParameter("name"))

        assertEquals(2, client().maintenanceStatus().lastRun?.stats?.get("duplicates_removed"))
        server.takeRequest()
        val actions = client().maintenanceActions(runId = 3, kind = "duplicate")
        assertTrue(actions.items.single().restorable)
        assertEquals("3", server.takeRequest().url.queryParameter("runId"))

        client().runMaintenance()
        assertEquals("POST", server.takeRequest().method)
        client().restoreFromQuarantine(4)
        assertEquals("/bodrod/api/v1/maintenance/quarantine/4/restore", server.takeRequest().url.encodedPath)
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

    @Test
    fun `request with source chain and album kind is parsed`() = runTest {
        server.enqueue(
            json(
                """[{"id":"r1","kind":"album","artist":"Кино","album":"Группа крови","status":"completed",
                "source":"Яндекс Музыка, FLAC","attempts":[
                  {"source":"slskd","title":"Soulseek","result":"skip","reason":"Выключен","at":1},
                  {"source":"yandex","title":"Яндекс Музыка","result":"ok","reason":"Яндекс Музыка, FLAC","at":2}]}]""",
            ),
        )
        val request = client().requests().single()
        assertEquals(RequestKind.Album, request.kind)
        assertEquals("Яндекс Музыка, FLAC", request.source)
        assertEquals(listOf("skip", "ok"), request.attempts.map { it.result })
        assertEquals(albumKey("Кино", "Группа крови"), request.key)
    }

    @Test
    fun `update sources sends only changed fields with PUT`() = runTest {
        server.enqueue(
            json(
                """{"sources":[{"name":"yandex","title":"Яндекс Музыка","enabled":false,"state":"disabled",
                "kinds":["track","album","artist"]},{"name":"slskd","title":"Soulseek","enabled":true,"state":"unavailable",
                "reason":"Не заданы SLSKD_URL и SLSKD_API_KEY"}],"yandexMetadata":true}""",
            ),
        )
        val sources = client().updateSources(enabled = mapOf("yandex" to false))
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/bodrod/api/v1/sources", request.url.encodedPath)
        assertEquals("""{"enabled":{"yandex":false}}""", request.body!!.utf8())
        assertEquals("disabled", sources.sources[0].state)
        assertEquals("Не заданы SLSKD_URL и SLSKD_API_KEY", sources.sources[1].reason)
    }

    @Test
    fun `export pause posts empty body and parses status`() = runTest {
        server.enqueue(json("""{"state":"paused","counts":{"total":10,"done":3,"skipped":1,"failed":0,"pending":6}}"""))
        val status = client().pauseExport()
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/bodrod/api/v1/yandex/export/pause", request.url.encodedPath)
        assertEquals("paused", status.state)
        assertEquals(6, status.counts.pending)
        assertTrue(!status.isActive)
    }
}
