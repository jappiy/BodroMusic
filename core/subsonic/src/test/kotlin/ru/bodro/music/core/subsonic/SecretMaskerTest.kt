package ru.bodro.music.core.subsonic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretMaskerTest {
    @Test
    fun `masks t s p in url`() {
        val url = "https://h/rest/stream.view?u=vasya&t=26719a1196d2a940705a59634eb18eab&s=c19b2d&p=enc:736573616d65&v=1.16.1&id=1"
        val masked = SecretMasker.maskUrl(url)
        assertEquals("https://h/rest/stream.view?u=vasya&t=***&s=***&p=***&v=1.16.1&id=1", masked)
    }

    @Test
    fun `does not touch params that only end with secret names`() {
        val url = "https://h/rest/search3.view?query=bass&songs=3&ids=1"
        assertEquals(url, SecretMasker.maskUrl(url))
    }

    @Test
    fun `masks query string without leading question mark`() {
        assertEquals("t=***&s=***&u=a", SecretMasker.maskUrl("t=abc&s=def&u=a"))
    }

    @Test
    fun `masks auth headers`() {
        assertEquals("***", SecretMasker.maskHeader("Authorization", "Basic dXNlcjpwYXNz"))
        assertEquals("***", SecretMasker.maskHeader("x-nd-authorization", "Bearer abc"))
        assertEquals("***", SecretMasker.maskHeader("X-Subsonic-Token", "26719a1196d2a940705a59634eb18eab"))
        assertEquals("audio/flac", SecretMasker.maskHeader("Content-Type", "audio/flac"))
    }

    @Test
    fun `masks secrets inside free text and json`() {
        val text = """Error loading http://h/rest/stream.view?u=a&t=deadbeef&s=1234 {"password":"qwerty","user":"a"} p=hunter2"""
        val masked = SecretMasker.maskText(text, password = "hunter2")
        assertFalse(masked.contains("deadbeef"))
        assertFalse(masked.contains("1234"))
        assertFalse(masked.contains("qwerty"))
        assertFalse(masked.contains("hunter2"))
        assertTrue(masked.contains("u=a"))
        assertTrue(masked.contains(""""user":"a""""))
    }
}
