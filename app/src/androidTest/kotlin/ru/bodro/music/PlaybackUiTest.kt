package ru.bodro.music

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Альбом → трек → мини-плеер → полный плеер → лайк/дизлайк. */
@RunWith(AndroidJUnit4::class)
class PlaybackUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val server = FakeNavidrome()
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() {
        resetApp()
        server.start()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.login(server)
        compose.waitForTag("home-screen")
    }

    @After
    fun tearDown() {
        onMain { entryPoint.player().clearQueue() }
        scenario.close()
        server.shutdown()
    }

    @Test
    fun openAlbumPlayTrackAndReact() {
        // Блок «Моей волны» занимает почти весь первый экран — подборки ниже, к ним нужно прокрутить.
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("album-al-1")) }.isSuccess
        }
        compose.waitForTag("album-al-1")
        compose.onAllNodesWithTag("album-al-1")[0].performClick()
        compose.waitForTag("album-screen")
        compose.onNodeWithTag("song-s-2").performClick()

        compose.waitForTag("mini-player")
        compose.waitUntil(15_000) { entryPoint.player().state.value.isPlaying }
        assertEquals("s-2", entryPoint.player().state.value.currentSong?.id)
        compose.waitUntil(10_000) { server.calls("stream").isNotEmpty() }
        compose.waitUntil(10_000) { server.calls("scrobble").any { it.url.queryParameter("submission") == "false" } }

        compose.onNodeWithTag("mini-player").performClick()
        compose.waitForTag("full-player")
        compose.waitForTag("player-bottom-bar")

        compose.onNodeWithTag("player-like").performClick()
        compose.waitForTag("icon-liked")
        compose.waitUntil(10_000) { server.calls("star").any { it.url.queryParameter("id") == "s-2" } }

        compose.onNodeWithTag("player-dislike").performClick()
        compose.waitForTag("icon-disliked")
        compose.waitForTag("icon-not-liked")
        compose.waitUntil(10_000) { server.calls("setRating").any { it.url.queryParameter("rating") == "1" } }
        assertTrue("like removed on dislike", server.calls("unstar").any { it.url.queryParameter("id") == "s-2" })

        compose.onNodeWithTag("collapse-player").performClick()
        compose.waitForTag("mini-player")
    }
}
