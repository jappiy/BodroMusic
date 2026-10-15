package ru.bodro.music.core.subsonic

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import ru.bodro.music.core.model.AlbumListType
import ru.bodro.music.core.model.Song

class SubsonicParsingTest {
    private val server = MockWebServer()
    private lateinit var client: SubsonicClient

    @Before
    fun setUp() {
        server.start()
        client = server.client()
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `handshake reads server info and open subsonic extensions`() = runTest {
        server.enqueue(json("ping_ok.json"))
        server.enqueue(json("extensions.json"))
        val info = client.handshake()
        assertEquals("1.16.1", info.apiVersion)
        assertEquals("navidrome", info.type)
        assertEquals("0.58.0 (abc123)", info.serverVersion)
        assertTrue(info.openSubsonic)
        assertTrue(info.supports("songLyrics"))
        assertEquals("ping", server.takeRequest().url.pathSegments.last().removeSuffix(".view"))
        assertEquals("getOpenSubsonicExtensions.view", server.takeRequest().url.pathSegments.last())
    }

    @Test
    fun `handshake on classic subsonic server skips extensions`() = runTest {
        server.enqueue(json("ping_classic.json"))
        val info = client.handshake()
        assertFalse(info.openSubsonic)
        assertTrue(info.extensions.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `album with songs, quality and user fields`() = runTest {
        server.enqueue(json("album.json"))
        val details = client.getAlbum("al-1")
        assertEquals("Кино — Группа крови", details.album.name)
        assertEquals(1988, details.album.year)
        assertTrue(details.album.starred)
        assertEquals(2, details.songs.size)
        val flac = details.songs[0]
        assertEquals("flac", flac.suffix)
        assertEquals(16, flac.bitDepth)
        assertEquals(44100, flac.samplingRate)
        assertTrue(flac.starred)
        assertEquals(5, flac.userRating)
        val disliked = details.songs[1]
        assertFalse(disliked.starred)
        assertTrue(disliked.isDislikedOnServer)
        assertNull(disliked.year)
        assertEquals("al-1", server.takeRequest().url.queryParameter("id"))
    }

    @Test
    fun `album with missing optional fields and numeric id`() = runTest {
        server.enqueue(json("album_minimal.json"))
        val details = client.getAlbum("42")
        assertEquals("42", details.album.id)
        assertEquals("Без треков", details.album.name)
        assertTrue(details.songs.isEmpty())
        assertEquals(0, details.album.songCount)
    }

    @Test
    fun `numeric ids are read as strings`() = runTest {
        server.enqueue(json("song_numeric_ids.json"))
        val song: Song = client.getSong("123")
        assertEquals("123", song.id)
        assertEquals("7", song.albumId)
        assertEquals("8", song.artistId)
        assertEquals("9", song.coverArt)
    }

    @Test
    fun `artists index is flattened`() = runTest {
        server.enqueue(json("artists.json"))
        val artists = client.getArtists()
        assertEquals(listOf("Aquarium", "Кино"), artists.map { it.name })
        assertTrue(artists[1].starred)
        assertEquals("https://example.org/kino.jpg", artists[1].artistImageUrl)
    }

    @Test
    fun `artist with albums`() = runTest {
        server.enqueue(json("artist.json"))
        val details = client.getArtist("ar-1")
        assertEquals("Кино", details.artist.name)
        assertEquals(2, details.albums.size)
    }

    @Test
    fun `album list passes type and handles empty object`() = runTest {
        server.enqueue(json("album_list.json"))
        server.enqueue(json("album_list_empty.json"))
        val albums = client.getAlbumList2(AlbumListType.Newest, size = 10)
        assertEquals(2, albums.size)
        assertNull("year 0 means unknown", albums[1].year)
        assertTrue(client.getAlbumList2(AlbumListType.Recent).isEmpty())
        val request = server.takeRequest().url
        assertEquals("newest", request.queryParameter("type"))
        assertEquals("10", request.queryParameter("size"))
        assertNull(request.queryParameter("fromYear"))
    }

    @Test
    fun `by year list sends year range`() = runTest {
        server.enqueue(json("album_list.json"))
        client.getAlbumList2(AlbumListType.ByYear, fromYear = 1980, toYear = 1989)
        val url = server.takeRequest().url
        assertEquals("1980", url.queryParameter("fromYear"))
        assertEquals("1989", url.queryParameter("toYear"))
    }

    @Test
    fun `genres skip blank names`() = runTest {
        server.enqueue(json("genres.json"))
        assertEquals(listOf("Rock", "Jazz"), client.getGenres().map { it.name })
    }

    @Test
    fun `random songs skip directories and pass filters`() = runTest {
        server.enqueue(json("random_songs.json"))
        val songs = client.getRandomSongs(size = 5, genre = "Rock", fromYear = 1980, toYear = 1989)
        assertEquals(listOf("s-9"), songs.map { it.id })
        val url = server.takeRequest().url
        assertEquals("Rock", url.queryParameter("genre"))
        assertEquals("1980", url.queryParameter("fromYear"))
        assertEquals("1989", url.queryParameter("toYear"))
    }

    @Test
    fun `search results and empty search`() = runTest {
        server.enqueue(json("search3.json"))
        server.enqueue(json("search3_empty.json"))
        val result = client.search3("кино")
        assertEquals(1, result.artists.size)
        assertEquals(1, result.albums.size)
        assertEquals(1, result.songs.size)
        assertTrue(client.search3("zzz").isEmpty)
        assertEquals("кино", server.takeRequest().url.queryParameter("query"))
    }

    @Test
    fun `starred2 lists`() = runTest {
        server.enqueue(json("starred2.json"))
        val starred = client.getStarred2()
        assertEquals(1, starred.songs.size)
        assertTrue(starred.songs.first().starred)
        assertTrue(starred.artists.isEmpty())
    }

    @Test
    fun `playlists and playlist entries`() = runTest {
        server.enqueue(json("playlists.json"))
        server.enqueue(json("playlist.json"))
        val playlists = client.getPlaylists()
        assertEquals(2, playlists.size)
        assertTrue(playlists[0].isEditableBy("Vasya"))
        assertFalse(playlists[1].isEditableBy("vasya"))
        val details = client.getPlaylist("pl-1")
        assertEquals(listOf("s-1", "s-2"), details.songs.map { it.id })
    }

    @Test
    fun `create and update playlist send repeated params`() = runTest {
        server.enqueue(json("playlist.json"))
        server.enqueue(json("empty_ok.json"))
        client.createPlaylist("В дорогу", listOf("s-1", "s-2"))
        client.updatePlaylist("pl-1", name = "Новое", songIdsToAdd = listOf("s-3"), songIndexesToRemove = listOf(0, 4))
        val create = server.takeRequest().url
        assertEquals(listOf("s-1", "s-2"), create.queryParameterValues("songId"))
        val update = server.takeRequest().url
        assertEquals("Новое", update.queryParameter("name"))
        assertEquals(listOf("s-3"), update.queryParameterValues("songIdToAdd"))
        assertEquals(listOf("0", "4"), update.queryParameterValues("songIndexToRemove"))
    }

    @Test
    fun `structured lyrics prefer synced and apply offset`() = runTest {
        server.enqueue(json("lyrics_structured.json"))
        val lyrics = client.getLyrics(Song(id = "s-1", title = "Группа крови", artist = "Кино"), useOpenSubsonic = true)!!
        assertTrue(lyrics.synced)
        assertEquals(900L, lyrics.lines[0].startMs)
        assertEquals(1, lyrics.activeLineIndex(6_000))
    }

    @Test
    fun `falls back to legacy lyrics with lrc`() = runTest {
        server.enqueue(json("lyrics_structured_empty.json"))
        server.enqueue(json("lyrics_legacy.json"))
        val lyrics = client.getLyrics(Song(id = "s-1", title = "Группа крови", artist = "Кино"), useOpenSubsonic = true)!!
        assertTrue(lyrics.synced)
        assertEquals(listOf(1000L, 5500L), lyrics.lines.map { it.startMs })
        val legacyRequest = server.takeRequest().let { server.takeRequest() }.url
        assertEquals("getLyrics.view", legacyRequest.pathSegments.last())
        assertEquals("Кино", legacyRequest.queryParameter("artist"))
    }

    @Test
    fun `optional endpoints return empty on api error`() = runTest {
        server.enqueue(json("error_not_found.json"))
        assertTrue(client.getTopSongs("Кино").isEmpty())
    }

    @Test
    fun `api error is mapped to typed error`() = runTest {
        server.enqueue(json("error_wrong_credentials.json"))
        try {
            client.ping()
            fail("expected exception")
        } catch (e: SubsonicException) {
            val error = e.error as SubsonicError.Api
            assertEquals(40, error.code)
            assertEquals(ApiErrorKind.WrongCredentials, error.kind)
            assertEquals("Wrong username or password", error.serverMessage)
        }
    }

    @Test
    fun `garbage body is invalid response`() = runTest {
        server.enqueue(mockwebserver3.MockResponse.Builder().body("<html>proxy error</html>").build())
        try {
            client.ping()
            fail("expected exception")
        } catch (e: SubsonicException) {
            assertTrue(e.error is SubsonicError.InvalidResponse)
        }
    }
}
