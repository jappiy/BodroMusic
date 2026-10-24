package ru.bodro.music

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.bodro.music.core.model.QueueSource
import ru.bodro.music.core.model.Song
import ru.bodro.music.playback.PlaybackService

/** Музыка продолжает играть в фоне, уведомление медиа на месте; восстановление очереди и позиции. */
@RunWith(AndroidJUnit4::class)
class BackgroundPlaybackTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val server = FakeNavidrome(trackSeconds = 120)
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private lateinit var scenario: ActivityScenario<MainActivity>

    private val songs = FakeNavidrome.SONGS.map { (id, title) ->
        Song(id = id, title = title, artist = "Тестовый исполнитель", albumId = "al-1", duration = 120, suffix = "wav")
    }

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
    fun keepsPlayingInBackgroundWithNotification() {
        val player = entryPoint.player()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            player.play(songs, 0, QueueSource.AlbumSource("al-1", FakeNavidrome.ALBUM_NAME))
        }
        compose.waitUntil(15_000) { player.state.value.isPlaying }

        device.pressHome()
        Thread.sleep(10_000)
        assertTrue("still playing 10 s after Home", player.state.value.isPlaying)
        assertTrue("position advanced", player.state.value.positionMs > 5_000)

        // Медиа-уведомление проверяем через систему, а не через шторку: на Android 13+ оно рисуется
        // плеером в быстрых настройках, и поиск по тексту в UI ненадёжен.
        val notifications = appContext.getSystemService(NotificationManager::class.java).activeNotifications
        val media = notifications.firstOrNull {
            it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ==
                songs[0].title
        }
        assertTrue(
            "media notification with track title, got: ${notifications.map {
                it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)
            }}",
            media != null,
        )
        // Сервис плеера — foreground (иначе система убьёт воспроизведение в фоне). Флаг ongoing Media3 не ставит.
        assertTrue(
            "notification belongs to a foreground service",
            media!!.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
        )
    }

    @Test
    fun restoresTrackAndPositionAfterServiceAndActivityRestart() {
        val player = entryPoint.player()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { player.play(songs, 1, QueueSource.AlbumSource("al-1", FakeNavidrome.ALBUM_NAME)) }
        compose.waitUntil(15_000) { player.state.value.isPlaying }
        instrumentation.runOnMainSync { player.seekTo(30_000) }
        compose.waitUntil(5_000) { player.state.value.positionMs >= 30_000 }
        instrumentation.runOnMainSync { player.togglePlay() }
        compose.waitUntil(5_000) { !player.state.value.isPlaying }
        val savedPosition = player.state.value.positionMs

        // «Убиваем» плеер: закрываем Activity, отключаем контроллер и останавливаем сервис (он сохраняет очередь в onDestroy).
        scenario.close()
        instrumentation.runOnMainSync { player.disconnect() }
        appContext.stopService(Intent(appContext, PlaybackService::class.java))
        Thread.sleep(2_000)

        scenario = ActivityScenario.launch(MainActivity::class.java)
        // На «Главной» мини-плеера нет (волна на весь экран) — ждём восстановленную очередь в самом плеере.
        compose.waitForTag("home-screen", timeoutMs = 20_000)
        compose.waitUntil(20_000) { player.state.value.currentSong?.id == "s-2" }
        val restored = player.state.value
        assertTrue("same track: ${restored.currentSong?.id}", restored.currentSong?.id == "s-2")
        assertTrue("position ±2 s: ${restored.positionMs} vs $savedPosition", kotlin.math.abs(restored.positionMs - savedPosition) <= 2_000)
    }
}
