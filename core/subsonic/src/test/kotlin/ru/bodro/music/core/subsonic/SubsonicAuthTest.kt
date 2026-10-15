package ru.bodro.music.core.subsonic

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.bodro.music.core.model.ServerKind

class SubsonicAuthTest {
    @Test
    fun `token is md5 of password plus salt - example from Subsonic docs`() {
        // http://www.subsonic.org/pages/api.jsp: password "sesame", salt "c19b2d" → 26719a1196d2a940705a59634eb18eab
        assertEquals("26719a1196d2a940705a59634eb18eab", SubsonicAuth.token("sesame", "c19b2d"))
    }

    @Test
    fun `salt is random hex and differs between calls`() {
        val salts = List(50) { SubsonicAuth.randomSalt() }
        assertTrue(salts.all { it.matches(Regex("[0-9a-f]{16}")) })
        assertEquals(50, salts.toSet().size)
    }

    @Test
    fun `url contains auth params and never the password`() {
        val client = SubsonicClient(testHttp(), { null }, saltGenerator = { "c19b2d" })
        val config = ConnectionConfig("http://nas.local:4533/".toHttpUrl(), ServerKind.Local, "vasya", "sesame")
        val url = client.buildUrl("getAlbum", listOf("id" to "al 1"), config)
        assertEquals("/rest/getAlbum.view", url.encodedPath)
        assertEquals("vasya", url.queryParameter("u"))
        assertEquals("26719a1196d2a940705a59634eb18eab", url.queryParameter("t"))
        assertEquals("c19b2d", url.queryParameter("s"))
        assertEquals("1.16.1", url.queryParameter("v"))
        assertEquals("BodroMusic", url.queryParameter("c"))
        assertEquals("json", url.queryParameter("f"))
        assertEquals("al 1", url.queryParameter("id"))
        assertNull(url.queryParameter("p"))
        assertFalse(url.toString().contains("sesame"))
    }

    @Test
    fun `new salt for each request`() {
        val client = SubsonicClient(testHttp(), { null })
        val config = ConnectionConfig("http://nas.local:4533/".toHttpUrl(), ServerKind.Local, "vasya", "sesame")
        val a = client.buildUrl("ping", emptyList(), config)
        val b = client.buildUrl("ping", emptyList(), config)
        assertNotEquals(a.queryParameter("s"), b.queryParameter("s"))
        assertNotEquals(a.queryParameter("t"), b.queryParameter("t"))
    }

    @Test
    fun `stream url keeps base path of reverse proxy and supports original quality`() {
        val client = SubsonicClient(testHttp(), { null }, saltGenerator = { "x" })
        val config = ConnectionConfig.of("https://music.example.org/navidrome/rest/", ServerKind.External, "u", "p")!!
        val original = client.streamUrl("s-1", maxBitRate = null, format = "raw", config = config)
        assertEquals("/navidrome/rest/stream.view", original.encodedPath)
        assertEquals("0", original.queryParameter("maxBitRate"))
        assertEquals("raw", original.queryParameter("format"))
        val transcoded = client.streamUrl("s-1", maxBitRate = 192, format = "mp3", config = config)
        assertEquals("192", transcoded.queryParameter("maxBitRate"))
        assertEquals("mp3", transcoded.queryParameter("format"))
    }

    @Test
    fun `normalize base url`() {
        assertEquals("http://192.168.1.10:4533/", ConnectionConfig.normalizeBaseUrl("192.168.1.10:4533")?.toString())
        assertEquals("https://m.example.org/", ConnectionConfig.normalizeBaseUrl(" https://m.example.org/rest/ ")?.toString())
        assertEquals("https://m.example.org/nd/", ConnectionConfig.normalizeBaseUrl("https://m.example.org/nd?x=1")?.toString())
        assertNull(ConnectionConfig.normalizeBaseUrl("   "))
    }

    @Test
    fun `config toString hides password`() {
        val config = ConnectionConfig.of("http://h", ServerKind.Local, "u", "topsecret")!!
        assertFalse(config.toString().contains("topsecret"))
    }
}
