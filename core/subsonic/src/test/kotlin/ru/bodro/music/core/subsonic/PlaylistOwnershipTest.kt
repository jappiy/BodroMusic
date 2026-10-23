package ru.bodro.music.core.subsonic

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import ru.bodro.music.core.model.Playlist
import ru.bodro.music.core.model.PlaylistAccess

/** Задание 020: чей плейлист, «Поделиться» = public, порядок треков — заменой состава. */
class PlaylistOwnershipTest {
    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    @Test
    fun `own, shared and hidden playlists`() {
        assertEquals(PlaylistAccess.Own, Playlist("1", "Моё", owner = "Vasya").accessFor("vasya"))
        assertEquals(PlaylistAccess.Shared, Playlist("2", "Общее", owner = "masha", public = true).accessFor("vasya"))
        // Администратор Navidrome получает и чужие приватные — приложение их скрывает.
        assertEquals(PlaylistAccess.Hidden, Playlist("3", "Чужое", owner = "masha", public = false).accessFor("vasya"))
        assertEquals(PlaylistAccess.Own, Playlist("4", "Без владельца").accessFor("vasya"))
    }

    @Test
    fun `share sets public and reorder replaces songs`() = runTest {
        val client = server.client()
        repeat(3) { server.enqueue(json("empty_ok.json")) }
        client.updatePlaylist("p1", public = true)
        client.updatePlaylist("p1", public = false)
        client.replacePlaylistSongs("p1", listOf("s3", "s1", "s2"))
        server.takeRequest().url.let {
            assertEquals("updatePlaylist.view", it.pathSegments.last())
            assertEquals("true", it.queryParameter("public"))
        }
        assertEquals("false", server.takeRequest().url.queryParameter("public"))
        server.takeRequest().url.let {
            assertEquals("createPlaylist.view", it.pathSegments.last())
            assertEquals("p1", it.queryParameter("playlistId"))
            assertEquals(listOf("s3", "s1", "s2"), it.queryParameterValues("songId"))
        }
    }
}
