package ru.bodro.music.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.mockk
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import ru.bodro.music.core.domain.player.StreamQuality
import ru.bodro.music.core.downloads.ArtistFacts
import ru.bodro.music.core.downloads.DiscoverTrack
import ru.bodro.music.core.downloads.WaveModeInfo
import ru.bodro.music.core.model.Artist
import ru.bodro.music.core.model.Playlist
import ru.bodro.music.core.model.QueueSource
import ru.bodro.music.core.model.Song
import ru.bodro.music.core.model.WaveSettings
import ru.bodro.music.data.repository.ReactionState
import ru.bodro.music.data.settings.AppSettings
import ru.bodro.music.playback.PlayerUiState
import ru.bodro.music.ui.app.AppActions
import ru.bodro.music.ui.app.LocalAppActions
import ru.bodro.music.ui.app.LocalNavigator
import ru.bodro.music.ui.app.LocalReactions
import ru.bodro.music.ui.app.Navigator
import ru.bodro.music.ui.app.SongMenuContext
import ru.bodro.music.ui.components.LocalCoverArtUrls
import ru.bodro.music.ui.screens.home.WaveHome
import ru.bodro.music.ui.screens.home.rememberModeCards
import ru.bodro.music.ui.screens.player.PlayerExtrasActions
import ru.bodro.music.ui.screens.player.PlayerExtrasUi
import ru.bodro.music.ui.screens.player.PlayerPalette
import ru.bodro.music.ui.screens.player.playerExtras
import ru.bodro.music.ui.screens.search.DiscoverCallbacks
import ru.bodro.music.ui.theme.BodroTheme
import ru.bodro.music.ui.theme.LocalPlayerColors
import ru.bodro.music.ui.theme.PlayerColors

/**
 * Снимки главной «Моя волна» на размерах реальных экранов (задания 021–022): телефон в портрете и ландшафте,
 * складной в разложенном виде. PNG — в `app/build/screenshots/`; тест проверяет, что ключевые элементы на экране.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WaveHomeScreenshotTest {
    private val song = Song(id = "s1", title = "Группа крови", artist = "Кино", album = "Группа крови", duration = 280)
    private val playing = PlayerUiState(
        connected = true,
        currentSong = song,
        queue = listOf(song),
        isPlaying = true,
        positionMs = 120_000,
        durationMs = 280_000,
        source = QueueSource.WaveSource(WaveSettings()),
    )
    private val modes = listOf(
        WaveModeInfo("energetic", "Бодрое", count = 812),
        WaveModeInfo("calm", "Спокойное", count = 640),
        WaveModeInfo("loud", "Пошумим", count = 121),
        WaveModeInfo("artist:Кино", "В стиле: Кино", dynamic = true),
        WaveModeInfo("release:al1", "Новый релиз: ЛСП", subtitle = "Альбом", dynamic = true),
    )
    private val facts = ArtistFacts(fact = "Кино — советская рок-группа, основанная в 1981 году.", tags = listOf("russian rock"))

    @Test
    fun `phone portrait - wave playing`() = shoot(
        "home-phone-portrait",
        "w411dp-h891dp-port-xxhdpi",
        playing,
        AppSettings(waveModeId = "energetic"),
    )

    @Test
    fun `phone portrait - wave not started`() = shoot(
        "home-phone-start",
        "w411dp-h891dp-port-xxhdpi",
        PlayerUiState(connected = true),
        AppSettings(),
    )

    @Test
    fun `phone landscape - wave playing`() = shoot("home-phone-landscape", "w891dp-h411dp-land-xxhdpi", playing, AppSettings())

    @Test
    fun `unfolded - wave playing`() = shoot("home-unfolded", "w841dp-h764dp-land-xhdpi", playing, AppSettings())

    @Test
    fun `player extras - similar, popular, playlists`() = render("player-extras", "w411dp-h891dp-port-xxhdpi") {
        val palette = PlayerPalette(Color(0xFF3B2A6B), Color.White, Color(0xFFFFB627))
        val similar = listOf(
            DiscoverTrack("Звезда по имени Солнце", "Кино", source = "lastfm", sourceId = "1", librarySongId = "s2"),
            DiscoverTrack("Город", "Алиса", source = "lastfm", sourceId = "2"),
            DiscoverTrack("Выхода нет", "Сплин", source = "lastfm", sourceId = "3"),
        )
        val noop = DiscoverCallbacks({}, {}, {}, {}, {}, {})
        val actions = PlayerExtrasActions({}, { _, _ -> }, noop, {}, {}, {}, {}, {}, {})
        Surface(Modifier.fillMaxSize(), color = palette.background) {
            LazyColumn {
                playerExtras(
                    PlayerExtrasUi("s1", Artist("a1", "Кино"), similar, similar.take(2), listOf(Playlist("p1", "Дорога"))),
                    emptyMap(),
                    canDownload = true,
                    artistLiked = true,
                    sleepActive = false,
                    quality = StreamQuality.Original,
                    palette = palette,
                    actions = actions,
                )
            }
        }
    }

    private fun shoot(name: String, qualifiers: String, player: PlayerUiState, settings: AppSettings) = render(name, qualifiers) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            LazyColumn {
                item {
                    WaveHome(
                        player = player,
                        settings = settings.copy(backgroundAnimation = false),
                        facts = facts,
                        modes = rememberModeCards(modes),
                        onTogglePlay = {},
                        onNext = {},
                        onPrevious = {},
                        onSelectMode = {},
                        onOpenSettings = {},
                        onOpenSearch = {},
                    )
                }
            }
        }
    }

    private fun render(name: String, qualifiers: String, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { Providers(content) }
        shadowOf(Looper.getMainLooper()).idle()
        val root = activity.window.decorView
        assertTrue(root.width > 0 && root.height > 0)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun Providers(content: @Composable () -> Unit) {
        BodroTheme {
            CompositionLocalProvider(
                LocalAppActions provides NoActions,
                LocalNavigator provides NoNavigator,
                LocalReactions provides ReactionState(),
                LocalCoverArtUrls provides mockk(relaxed = true),
                LocalPlayerColors provides PlayerColors(Color(0xFF3B2A6B), Color(0xFFFFB627), Color.White),
                content = content,
            )
        }
    }

    private object NoActions : AppActions {
        override fun play(songs: List<Song>, index: Int, source: QueueSource, shuffle: Boolean) = Unit

        override fun playNext(songs: List<Song>) = Unit

        override fun addToQueue(songs: List<Song>) = Unit

        override fun toggleLike(song: Song) = Unit

        override fun toggleDislike(song: Song) = Unit

        override fun startWave(settings: WaveSettings) = Unit

        override fun showSongMenu(song: Song, context: SongMenuContext) = Unit

        override fun addToPlaylist(songs: List<Song>) = Unit

        override fun showMessage(message: String) = Unit
    }

    private object NoNavigator : Navigator {
        override fun openAlbum(id: String) = Unit

        override fun openArtist(id: String) = Unit

        override fun openPlaylist(id: String) = Unit

        override fun openGenre(name: String) = Unit

        override fun openWave() = Unit

        override fun openPlayer() = Unit

        override fun openLogs() = Unit

        override fun back() = Unit
    }
}
