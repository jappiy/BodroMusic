package ru.bodro.music.core.subsonic

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.bodro.music.core.model.ServerKind

class ServerSelectorTest {
    private class FakeProbe(private val reachable: Set<String>) : ServerProbe {
        val probed = mutableListOf<Pair<String, Long>>()
        override suspend fun isReachable(baseUrl: HttpUrl, timeoutMs: Long): Boolean {
            probed += baseUrl.host to timeoutMs
            return baseUrl.host in reachable
        }
    }

    @Test
    fun `prefers local when it answers`() = runTest {
        val probe = FakeProbe(setOf("192.168.1.10", "music.example.org"))
        val selection = ServerSelector(probe).select("192.168.1.10:4533", "https://music.example.org")!!
        assertEquals(ServerKind.Local, selection.kind)
        assertTrue(selection.reachable)
        assertEquals(listOf("192.168.1.10" to ServerSelector.LOCAL_TIMEOUT_MS), probe.probed)
    }

    @Test
    fun `falls back to external`() = runTest {
        val probe = FakeProbe(setOf("music.example.org"))
        val selection = ServerSelector(probe).select("192.168.1.10:4533", "https://music.example.org")!!
        assertEquals(ServerKind.External, selection.kind)
        assertEquals("https://music.example.org/", selection.baseUrl.toString())
    }

    @Test
    fun `nothing reachable returns external as unreachable`() = runTest {
        val selection = ServerSelector(FakeProbe(emptySet())).select("192.168.1.10", "https://music.example.org")!!
        assertEquals(ServerKind.External, selection.kind)
        assertFalse(selection.reachable)
    }

    @Test
    fun `only local configured`() = runTest {
        val selection = ServerSelector(FakeProbe(emptySet())).select("192.168.1.10", null)!!
        assertEquals(ServerKind.Local, selection.kind)
        assertNull(ServerSelector(FakeProbe(emptySet())).select(null, " "))
    }

    @Test
    fun `ping probe against real http server`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(json("error_wrong_credentials.json"))
            val probe = PingServerProbe(testHttp())
            assertTrue("auth error still means reachable", probe.isReachable(server.url("/"), 1_000))
            val request = server.takeRequest().url
            assertEquals("ping.view", request.pathSegments.last())
            assertNull("probe never sends credentials", request.queryParameter("t"))
        }
    }

    @Test
    fun `ping probe on dead address`() = runTest {
        val server = MockWebServer()
        server.start()
        val url = server.url("/")
        server.close()
        assertFalse(PingServerProbe(testHttp()).isReachable(url, 300))
    }
}
