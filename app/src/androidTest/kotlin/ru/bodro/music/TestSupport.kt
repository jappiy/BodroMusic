package ru.bodro.music

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import ru.bodro.music.data.settings.AppSettings
import ru.bodro.music.data.settings.BackgroundAnimation
import ru.bodro.music.di.AppEntryPoint

val appContext: Context get() = ApplicationProvider.getApplicationContext()

val entryPoint: AppEntryPoint get() = EntryPointAccessors.fromApplication(appContext, AppEntryPoint::class.java)

/**
 * Чистое состояние: без учёток, очередей, логов; настройки по умолчанию, но фон главной неподвижен (туманность
 * рисует кадры и при системной «Отключить анимацию», а Compose-тесты ждут простоя). Разрешение на уведомления — заранее.
 */
fun resetApp() = runBlocking {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(appContext.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }
    onMain { entryPoint.player().disconnect() }
    entryPoint.database().clearAllTables()
    entryPoint.settings().update { AppSettings(backgroundAnimation = BackgroundAnimation.Off) }
}

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
fun ComposeTestRule.waitForTag(tag: String, timeoutMs: Long = 15_000) =
    waitUntil(timeoutMs) { onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

fun ComposeTestRule.waitForText(text: String, timeoutMs: Long = 15_000) =
    waitUntil(timeoutMs) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

/** MediaController и плеер — только из главного потока. */
fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

/** Вход через экран логина в поддельный Navidrome. */
fun ComposeTestRule.login(server: FakeNavidrome, user: String = FakeNavidrome.USER, password: String = FakeNavidrome.PASSWORD) {
    waitForTag("login-screen")
    onNodeWithTag("login-local").performTextInput(server.baseUrl)
    onNodeWithTag("login-username").performTextInput(user)
    onNodeWithTag("login-password").performTextInput(password)
    onNodeWithTag("login-submit").performClick()
}
