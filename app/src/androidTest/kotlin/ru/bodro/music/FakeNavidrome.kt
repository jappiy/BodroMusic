package ru.bodro.music

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import kotlin.math.PI
import kotlin.math.sin
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer

/**
 * Поддельный Navidrome для инструментальных тестов: отвечает на Subsonic API фикстурами
 * и отдаёт сгенерированный WAV (синус) как поток трека.
 */
class FakeNavidrome(private val trackSeconds: Int = 60) {
    val server = MockWebServer()
    val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
    private val starred = Collections.synchronizedSet(mutableSetOf<String>())
    private val ratings = Collections.synchronizedMap(mutableMapOf<String, Int>())
    private val wav: ByteArray by lazy { sineWav(trackSeconds) }

    val baseUrl: String get() = server.url("/").toString()

    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return respond(request)
            }
        }
        server.start()
    }

    fun shutdown() = server.close()

    fun calls(endpoint: String): List<RecordedRequest> = requests.filter { it.url.pathSegments.lastOrNull() == "$endpoint.view" }

    private fun respond(request: RecordedRequest): MockResponse {
        val url = request.url
        val endpoint = url.pathSegments.lastOrNull()?.removeSuffix(".view") ?: ""
        // Как настоящий Navidrome: авторизация проверяется на всех эндпоинтах, включая ping.
        if (url.queryParameter("u") != USER) return json(error(40, "Wrong username or password"))
        return when (endpoint) {
            "ping" -> json(ok(""))
            "getOpenSubsonicExtensions" -> json(ok(""","openSubsonicExtensions":[{"name":"songLyrics","versions":[1]}]"""))
            "getAlbumList2" -> json(ok(""","albumList2":{"album":[$ALBUM_SHORT]}"""))
            "getStarred2" -> json(ok(""","starred2":{"song":[${SONGS.filter { it.first in starred }.joinToString(",") { song(it) }}]}"""))
            "getArtists" -> json(
                ok(""","artists":{"index":[{"name":"Т","artist":[{"id":"ar-1","name":"Тестовый исполнитель","albumCount":1}]}]}"""),
            )
            "getArtist" -> json(ok(""","artist":{"id":"ar-1","name":"Тестовый исполнитель","albumCount":1,"album":[$ALBUM_SHORT]}"""))
            "getAlbum" -> json(
                ok(
                    ""","album":{"id":"al-1","name":"$ALBUM_NAME","artist":"Тестовый исполнитель","artistId":"ar-1","year":2024,"genre":"Rock","songCount":3,"duration":${trackSeconds * 3},"song":[${SONGS.joinToString(
                        ",",
                    ) {
                        song(it)
                    }}]}""",
                ),
            )
            "getSong" -> json(ok(""","song":${song(SONGS.first { it.first == url.queryParameter("id") })}"""))
            "getPlaylists" -> json(ok(""","playlists":{"playlist":[]}"""))
            "getGenres" -> json(ok(""","genres":{"genre":[{"value":"Rock","songCount":3,"albumCount":1}]}"""))
            "getRandomSongs", "getSongsByGenre", "getTopSongs", "getSimilarSongs2" -> {
                val key = when (endpoint) {
                    "getRandomSongs" -> "randomSongs"
                    "getSongsByGenre" -> "songsByGenre"
                    "getTopSongs" -> "topSongs"
                    else -> "similarSongs2"
                }
                json(ok(""","$key":{"song":[${SONGS.joinToString(",") { song(it) }}]}"""))
            }
            "search3" -> json(ok(""","searchResult3":{"song":[${SONGS.joinToString(",") { song(it) }}],"album":[$ALBUM_SHORT]}"""))
            "getLyricsBySongId" -> json(
                ok(
                    ""","lyricsList":{"structuredLyrics":[{"synced":true,"line":[""" +
                        """{"start":0,"value":"Первая строка"},{"start":3000,"value":"Вторая строка"}]}]}""",
                ),
            )
            "star" -> {
                url.queryParameter("id")?.let(starred::add)
                json(ok(""))
            }
            "unstar" -> {
                url.queryParameter("id")?.let(starred::remove)
                json(ok(""))
            }
            "setRating" -> {
                ratings[url.queryParameter("id")!!] = url.queryParameter("rating")!!.toInt()
                json(ok(""))
            }
            "scrobble" -> json(ok(""))
            "stream" -> MockResponse.Builder()
                .addHeader("Content-Type", "audio/wav")
                .body(Buffer().write(wav))
                .build()
            "getCoverArt" -> MockResponse.Builder().code(404).build()
            else -> json(error(0, "Not implemented in FakeNavidrome: $endpoint"))
        }
    }

    private fun song(entry: Pair<String, String>): String {
        val (id, title) = entry
        val star = if (id in starred) ""","starred":"2024-01-01T00:00:00Z"""" else ""
        val rating = ratings[id]?.takeIf { it > 0 }?.let { ""","userRating":$it""" } ?: ""
        return """{"id":"$id","title":"$title","artist":"Тестовый исполнитель","artistId":"ar-1",""" +
            """"album":"$ALBUM_NAME","albumId":"al-1",""" +
            """"track":${id.removePrefix(
                "s-",
            )},"duration":$trackSeconds,"suffix":"wav","bitRate":128,"genre":"Rock","year":2024$star$rating}"""
    }

    private fun ok(body: String) =
        """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome",""" +
            """"serverVersion":"0.58.0-test","openSubsonic":true$body}}"""

    private fun error(code: Int, message: String) =
        """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":$code,"message":"$message"}}}"""

    private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()

    companion object {
        const val USER = "bodro_test"
        const val PASSWORD = "s3cret-pass"
        const val ALBUM_NAME = "Тестовый альбом"
        val SONGS = listOf("s-1" to "Первый трек", "s-2" to "Второй трек", "s-3" to "Третий трек")
        private const val ALBUM_SHORT =
            """{"id":"al-1","name":"$ALBUM_NAME","artist":"Тестовый исполнитель","artistId":"ar-1",""" +
                """"year":2024,"songCount":3}"""

        /** PCM WAV 8 кГц, 16 бит, моно, синус 440 Гц. */
        fun sineWav(seconds: Int, sampleRate: Int = 8_000): ByteArray {
            val samples = seconds * sampleRate
            val data = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until samples) data.putShort((sin(2 * PI * 440 * i / sampleRate) * 8_000).toInt().toShort())
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray())
                putInt(36 + samples * 2)
                put("WAVE".toByteArray())
                put("fmt ".toByteArray())
                putInt(16)
                putShort(1)
                putShort(1)
                putInt(sampleRate)
                putInt(sampleRate * 2)
                putShort(2)
                putShort(16)
                put("data".toByteArray())
                putInt(samples * 2)
            }
            return header.array() + data.array()
        }
    }
}
