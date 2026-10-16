package ru.bodro.music

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LogsAndVersionsTest {
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
        compose.onNodeWithTag("tab-settings").performClick()
        compose.waitForTag("settings-screen")
    }

    @After
    fun tearDown() {
        scenario.close()
        server.shutdown()
    }

    @Test
    fun logsShowRequestsWithMaskedSecrets() {
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("settings-logs"))
        compose.onNodeWithTag("settings-logs").performClick()
        compose.waitForTag("logs-list")
        compose.waitForText("getAlbumList2")
        compose.onAllNodesWithText("getAlbumList2", substring = true)[0].performClick()
        compose.waitForTag("log-detail")
        compose.waitForText("t=***")
        compose.waitForText("s=***")
        val leaked = compose.onAllNodesWithText(FakeNavidrome.PASSWORD, substring = true).fetchSemanticsNodes()
        assertTrue("password must never be shown", leaked.isEmpty())
    }

    @Test
    fun versionsShowCurrentVersion() {
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("settings-versions"))
        compose.onNodeWithTag("settings-versions").performClick()
        compose.waitForTag("versions-screen")
        compose.onNodeWithTag("current-version").assertTextContains(BuildConfig.VERSION_NAME, substring = true)
        compose.waitForText(BuildConfig.VERSION_NAME.substringBefore("-"))
        compose.onNodeWithText(BuildConfig.GIT_HASH, substring = true).assertExists()
    }
}
