package ru.bodro.music

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoginNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val server = FakeNavidrome()
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() {
        resetApp()
        server.start()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
        server.shutdown()
    }

    @Test
    fun loginAndNavigateAllTabs() {
        compose.login(server)
        compose.waitForTag("home-screen")
        compose.onNodeWithTag("tab-collection").performClick()
        compose.waitForTag("collection-screen")
        compose.onNodeWithTag("tab-search").performClick()
        compose.waitForTag("search-screen")
        compose.onNodeWithTag("tab-settings").performClick()
        compose.waitForTag("settings-screen")
        compose.onNodeWithTag("tab-home").performClick()
        compose.waitForTag("home-screen")

        val ping = server.calls("ping").first().url
        assertEquals(FakeNavidrome.USER, ping.queryParameter("u"))
        assertTrue("token auth", ping.queryParameter("t")!!.matches(Regex("[0-9a-f]{32}")))
        assertFalse("password never sent", ping.toString().contains(FakeNavidrome.PASSWORD))
    }

    @Test
    fun unknownUserIsRejected() {
        compose.login(server, user = "nobody")
        compose.waitForTag("login-error")
    }
}
