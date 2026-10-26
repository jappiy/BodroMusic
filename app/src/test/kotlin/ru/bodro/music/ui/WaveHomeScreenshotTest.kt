package ru.bodro.music.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import ru.bodro.music.R
import ru.bodro.music.core.domain.player.StreamQuality
import ru.bodro.music.core.downloads.DiscoverTrack
import ru.bodro.music.core.downloads.WaveModeInfo
import ru.bodro.music.core.downloads.WaveReport
import ru.bodro.music.core.downloads.WaveReportFailure
import ru.bodro.music.core.downloads.WaveReportMode
import ru.bodro.music.core.model.Artist
import ru.bodro.music.core.model.Playlist
import ru.bodro.music.core.model.QueueSource
import ru.bodro.music.core.model.Song
import ru.bodro.music.core.model.WaveSettings
import ru.bodro.music.data.reco.Section
import ru.bodro.music.data.repository.ReactionState
import ru.bodro.music.data.settings.AppSettings
import ru.bodro.music.data.settings.BackgroundAnimation
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
import ru.bodro.music.ui.screens.settings.WaveReportContent
import ru.bodro.music.ui.theme.BodroTheme
import ru.bodro.music.ui.theme.LocalPlayerColors
import ru.bodro.music.ui.theme.NeutralGlow
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

    /** Задание 034: длинные и написанные капсом имена — без наложения строк, сначала мельче, потом многоточие. */
    @Test
    fun `long artist names do not overlap`() {
        listOf(
            "artist-caps" to "Обе Две, ПАВЛОВА",
            "artist-short" to "Scorpions",
            "artist-three" to "Баста, Скриптонит, Ёлка и Йорш",
            "artist-allcaps" to "ЁЛКА Й ЙОРШ ÁNGEL ÉDITH",
            "artist-very-long" to "The Ultimate Very Long Latin Artist Name Orchestra Featuring Somebody Else",
        ).forEach { (name, artist) ->
            val track = song.copy(artist = artist)
            shoot("home-$name", "w411dp-h891dp-port-xxhdpi", playing.copy(currentSong = track, queue = listOf(track)), AppSettings())
        }
    }

    @Test
    fun `track from charts - home shows it and the wave by track`() = shoot(
        "home-outside-wave",
        "w411dp-h891dp-port-xxhdpi",
        playing.copy(source = QueueSource.CustomSource("Чарт Яндекса")),
        AppSettings(waveModeId = "energetic"),
    )

    @Test
    fun `loud mode without cover - background takes the mode color`() = shoot(
        "home-loud-no-cover",
        "w411dp-h891dp-port-xxhdpi",
        playing.copy(source = QueueSource.WaveSource(WaveSettings(preset = "loud"))),
        AppSettings(waveModeId = "loud"),
        colors = PlayerColors(Color(0xFF222222), Color(0xFFFFB627), Color.White, glow = NeutralGlow),
    )

    /** Задание 034: «Настройки → Волна» — отчёт словами, с причинами неудач и настроениями. */
    @Test
    fun `wave report - plain words`() = render("settings-wave-report", "w411dp-h891dp-port-xxhdpi") {
        val report = WaveReport(
            library = 8396,
            analyzed = 8389,
            failed = 7,
            excluded = 2,
            pending = 0,
            failures = listOf(WaveReportFailure("ffmpeg: Invalid data found when processing input", 5)),
            loudnessEstimated = 77,
            noMood = 2100,
            modeList = listOf(
                WaveReportMode("energetic", "Бодрое", 2400, mood = true),
                WaveReportMode("calm", "Спокойное", 1900, mood = true),
                WaveReportMode("happy", "Весёлое", 2200, mood = true),
                WaveReportMode("sad", "Грустное", 1500, mood = true),
                WaveReportMode("loud", "Пошумим", 600, mood = true),
                WaveReportMode("popular", "Популярное", 1300),
                WaveReportMode("new", "Новое", 0),
                WaveReportMode("instrumental", "Без слов", 520),
            ),
            features = mapOf("bpm" to 8389, "loudness" to 8389),
            languages = mapOf("ru" to 3108, "en" to 4911, "none" to 377),
        )
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { WaveReportContent(report) }
        }
    }

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
        colors: PlayerColors = coverGlow,
    ) = render(name, qualifiers, colors) {
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

    private fun render(name: String, qualifiers: String, colors: PlayerColors = coverGlow, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        // Как системная «Отключить анимацию»: мерцание скелетонов и фон статичны, иначе цикл кадров не кончается.
        android.provider.Settings.Global.putFloat(
            RuntimeEnvironment.getApplication().contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            0f,
        )
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { Providers(colors, content) }
        shadowOf(Looper.getMainLooper()).idle()
        val root = activity.window.decorView
        assertTrue(root.width > 0 && root.height > 0)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun Providers(colors: PlayerColors, content: @Composable () -> Unit) {
        BodroTheme {
            CompositionLocalProvider(
                LocalAppActions provides NoActions,
                LocalNavigator provides NoNavigator,
                LocalReactions provides ReactionState(),
                LocalCoverArtUrls provides mockk<ru.bodro.music.ui.image.CoverArtUrls>(relaxed = true) {
                    every { server } returns kotlinx.coroutines.flow.MutableStateFlow("test:4533")
                    every { request(any(), any()) } answers {
                        coil3.request.ImageRequest.Builder(firstArg<android.content.Context>()).data(null).build()
                    }
                },
                LocalPlayerColors provides colors,
                content = content,
            )
        }
    }

    private val coverGlow = PlayerColors(
        Color(0xFF3B2A6B),
        Color(0xFFFFB627),
        Color.White,
        glow = listOf(Color(0xFF13B5C8), Color(0xFF1F6FD1), Color(0xFF7AE0E8)),
    )

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
