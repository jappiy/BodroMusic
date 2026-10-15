package ru.bodro.music.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.bodro.music.data.network.CleartextPolicy

class CleartextPolicyTest {
    @Test
    fun `http allowed only for private addresses`() {
        listOf(
            "http://192.168.1.10:4533/",
            "http://10.0.0.5/",
            "http://172.16.3.4/",
            "http://127.0.0.1:4533/",
            "http://100.101.102.103/", // Tailscale
            "http://nas.local:4533/",
            "http://synology/",
            "http://[fd00::1]/",
        ).forEach { assertTrue(it, CleartextPolicy.isAllowed(it.toHttpUrl())) }
        listOf(
            "http://music.example.org/",
            "http://8.8.8.8/",
            "http://172.32.0.1/",
            "http://[2001:db8::1]/",
        ).forEach { assertFalse(it, CleartextPolicy.isAllowed(it.toHttpUrl())) }
        assertTrue(CleartextPolicy.isAllowed("https://music.example.org/".toHttpUrl()))
    }
}
