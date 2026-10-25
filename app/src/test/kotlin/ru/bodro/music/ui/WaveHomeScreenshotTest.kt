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
import ru.bodro.music.data.settings.BackgroundAnimation
import ru.bodro.music.R
import ru.bodro.music.core.domain.player.StreamQuality
import ru.bodro.music.core.downloads.DiscoverTrack
import ru.bodro.music.core.downloads.WaveModeInfo
import ru.bodro.music.core.model.Artist
import ru.bodro.music.core.model.Playlist
import ru.bodro.music.core.model.QueueSource
import ru.bodro.music.core.model.Song
import ru.bodro.music.core.model.WaveSettings
import ru.bodro.music.data.reco.Section
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
import ru.bodro.music.ui.util.UiError

/**
 * Снимки главной «Моя волна» на размерах реальных экранов (задания 021–022, 025): телефон в портрете и ландшафте,
 * складной в разложенном виде; блоки под плеером — готовые и в загрузке. PNG — в `app/build/screenshots/`.
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
    private val blurbs: Section<List<String>> = Section.Ready(
        listOf(
            "Советская рок-группа, основанная в 1981 году в Ленинграде; одна из самых известных групп русского рока, " +
                "чьи песни поют до сих пор.",
            "Советская рок-группа, основанная в 1981 году.",
        ),
    )

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
    fun `phone portrait - text is still loading`() = shoot(
        "home-phone-blurb-loading",
        "w411dp-h891dp-port-xxhdpi",
        playing,
        AppSettings(),
        Section.Loading,
    )

    @Test
    fun `small phone - compact layout`() = shoot("home-small", "w360dp-h640dp-port-xhdpi", playing, AppSettings())

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
                    PlayerExtrasUi(
                        "s1",
                        Section.Ready(Artist("a1", "Кино")),
                        Section.Ready(similar),
                        Section.Ready(similar.take(2)),
                        Section.Ready(listOf("Советская рок-группа, основанная в 1981 году.")),
                        listOf(Playlist("p1", "Дорога")),
                    ),
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

    @Test
    fun `player extras - loading skeletons`() = render("player-extras-loading", "w411dp-h891dp-port-xxhdpi") {
        val palette = PlayerPalette(Color(0xFF3B2A6B), Color.White, Color(0xFFFFB627))
        val noop = DiscoverCallbacks({}, {}, {}, {}, {}, {})
        val actions = PlayerExtrasActions({}, { _, _ -> }, noop, {}, {}, {}, {}, {}, {})
        Surface(Modifier.fillMaxSize(), color = palette.background) {
            LazyColumn {
                playerExtras(
                    PlayerExtrasUi(
                        "s1",
                        Section.Loading,
                        Section.Loading,
                        Section.Failed(UiError(R.string.error_not_found)),
                        Section.Loading,
                    ),
                    emptyMap(),
                    canDownload = true,
                    artistLiked = false,
                    sleepActive = false,
                    quality = StreamQuality.Original,
                    palette = palette,
                    actions = actions,
                )
            }
        }
    }

    private fun shoot(
        name: String,
        qualifiers: String,
        player: PlayerUiState,
        settings: AppSettings,
        text: Section<List<String>> = blurbs,
    ) = render(name, qualifiers) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            WaveHome(
                player = player,
                settings = settings.copy(backgroundAnimation = BackgroundAnimation.Off),
                blurbs = text,
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

    private fun render(name: String, qualifiers: String, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        // Как системная «Отключить анимацию»: мерцание скелетонов и фон статичны, иначе цикл кадров не кончается.
        android.provider.Settings.Global.putFloat(
            RuntimeEnvironment.getApplication().contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            0f,
        )
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
                LocalPlayerColors provides PlayerColors(
                    Color(0xFF3B2A6B),
                    Color(0xFFFFB627),
                    Color.White,
                    glow = listOf(Color(0xFF13B5C8), Color(0xFF1F6FD1), Color(0xFF7AE0E8)),
                ),
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
