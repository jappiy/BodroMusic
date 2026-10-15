package ru.bodro.music

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.bodro.music.core.model.AlbumListType
import ru.bodro.music.core.model.ServerKind
import ru.bodro.music.core.subsonic.ConnectionConfig
import ru.bodro.music.core.subsonic.SubsonicClient

/**
 * Smoke-тест против настоящего Navidrome — ТОЛЬКО под тестовой учёткой (лайки, рейтинги, скробблы!).
 * Данные: local.properties → navidrome.test.url / user / password. Если их нет — тест пропускается.
 */
@RunWith(AndroidJUnit4::class)
class RealServerSmokeTest {
    private lateinit var client: SubsonicClient
    private lateinit var http: OkHttpClient

    @Before
    fun setUp() {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("navidromeUrl").orEmpty()
        val user = args.getString("navidromeUser").orEmpty()
        val password = args.getString("navidromePassword").orEmpty()
        assumeTrue("navidrome.test.* не заданы в local.properties", url.isNotBlank() && user.isNotBlank() && password.isNotBlank())
        val config = ConnectionConfig.of(url, ServerKind.External, user, password)!!
        http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
        client = SubsonicClient(http, { config })
    }

    @Test
    fun smoke() = runBlocking {
        val info = client.handshake()
        assertTrue(info.apiVersion.isNotBlank())

        val albums = client.getAlbumList2(AlbumListType.Newest, size = 5)
        assumeTrue("медиатека пуста", albums.isNotEmpty())
        val song = client.getAlbum(albums.first().id).songs.first()

        // Стрим первого трека ~5 секунд.
        val streamUrl = client.streamUrl(song.id, maxBitRate = 128, format = "mp3")
        val started = System.currentTimeMillis()
        var bytes = 0L
        http.newCall(Request.Builder().url(streamUrl).build()).execute().use { response ->
            assertEquals(200, response.code)
            val source = response.body.source()
            val buffer = okio.Buffer()
            while (System.currentTimeMillis() - started < 5_000) {
                val read = source.read(buffer, 64 * 1024)
                if (read < 0) break
                bytes += read
                buffer.clear()
            }
        }
        assertTrue("stream returned data", bytes > 0)

        client.star(songId = song.id)
        client.unstar(songId = song.id)
        client.setRating(song.id, 1)
        client.setRating(song.id, 0)
        client.scrobble(song.id, submission = false)
    }
}
