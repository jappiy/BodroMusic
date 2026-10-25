package ru.bodro.music.ui.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Leaderboard
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlin.reflect.KClass
import ru.bodro.music.ui.screens.home.HomeBackdrop
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.NavigationRailItemColors
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationBarItemColors
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.animation.animateColorAsState
import android.app.Activity
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarDuration
import kotlinx.coroutines.flow.Flow
import ru.bodro.music.R
import ru.bodro.music.data.account.SessionState
import ru.bodro.music.ui.components.LocalCoverArtUrls
import ru.bodro.music.ui.components.LocalOpenLogs
import ru.bodro.music.ui.image.CoverArtUrls
import ru.bodro.music.ui.screens.login.LoginScreen
import ru.bodro.music.ui.screens.player.FullPlayerScreen
import ru.bodro.music.ui.screens.player.MiniPlayer
import ru.bodro.music.ui.theme.BodroTheme
import ru.bodro.music.ui.theme.ProvidePlayerColors
import ru.bodro.music.ui.theme.rememberPlayerColors

enum class TopLevel(val route: Any, val routeClass: KClass<*>, val icon: ImageVector, val label: Int, val tag: String) {
    Home(HomeRoute, HomeRoute::class, Icons.Rounded.Home, R.string.tab_home, "tab-home"),
    Charts(ChartsRoute, ChartsRoute::class, Icons.Rounded.Leaderboard, R.string.tab_charts, "tab-charts"),
    Collection(CollectionRoute, CollectionRoute::class, Icons.Rounded.LibraryMusic, R.string.tab_collection, "tab-collection"),
    Search(SearchRoute, SearchRoute::class, Icons.Rounded.Search, R.string.tab_search, "tab-search"),
    Settings(SettingsRoute, SettingsRoute::class, Icons.Rounded.Settings, R.string.tab_settings, "tab-settings"),
}

@Composable
fun BodroRoot(viewModel: MainViewModel, coverArtUrls: CoverArtUrls, externalRequests: Flow<ExternalRequest>) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    BodroTheme(settings.themeMode, settings.dynamicColor) {
        CompositionLocalProvider(LocalCoverArtUrls provides coverArtUrls) {
            val session by viewModel.session.collectAsStateWithLifecycle()
            when (val s = session) {
                SessionState.Loading -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                SessionState.NoAccount -> LoginScreen(prefill = null)
                is SessionState.NeedsLogin -> LoginScreen(prefill = s.account)
                // Смена учётки — новый граф навигации и новые ViewModel (данные другой учётки).
                is SessionState.Active -> key(s.account.id) { MainShell(viewModel, externalRequests) }
            }
        }
    }
}

@Composable
private fun MainShell(viewModel: MainViewModel, externalRequests: Flow<ExternalRequest>) {
    val navController = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val player by viewModel.playerState.collectAsStateWithLifecycle()
    val reactions by viewModel.reactions.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val cover by viewModel.coverColors.collectAsStateWithLifecycle()
    val songMenu by viewModel.songMenu.collectAsStateWithLifecycle()
    val picker by viewModel.playlistPicker.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableStateOf(TopLevel.Home) }
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val playerColors = rememberPlayerColors(cover, settings.coverColors)

    LaunchedEffect(Unit) { viewModel.connectPlayer() }
    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            if (message.actionLabel == null) {
                snackbar.showSnackbar(message.text)
            } else {
                // Снекбар с действием держится UNDO_SNACKBAR_MS (Short — 4 с, Long — 10 с).
                val result = withTimeoutOrNull(UNDO_SNACKBAR_MS) {
                    snackbar.showSnackbar(message.text, message.actionLabel, duration = SnackbarDuration.Indefinite)
                }
                if (result == SnackbarResult.ActionPerformed) message.onAction?.invoke()
            }
        }
    }
    LaunchedEffect(Unit) {
        externalRequests.collect { request ->
            when (request) {
                ExternalRequest.OpenPlayer -> if (player.hasMedia) expanded = true
                is ExternalRequest.PlaySong -> {
                    viewModel.playSongById(request.songId)
                    expanded = true
                }
                ExternalRequest.OpenDownloads -> navController.navigate(DownloadsRoute) { launchSingleTop = true }
            }
        }
    }
    BackHandler(enabled = expanded) { expanded = false }

    val backStackEntry by navController.currentBackStackEntryAsState()
    // На «Главной» мини-плеер не нужен: трек, лайк и управление — в самой волне, плеер открывается тапом по «таблетке».
    val onHome = backStackEntry?.destination?.hasRoute(HomeRoute::class) == true
    LaunchedEffect(backStackEntry) {
        val destination = backStackEntry?.destination ?: return@LaunchedEffect
        TopLevel.entries.firstOrNull { destination.hasRoute(it.routeClass) }?.let { selectedTab = it }
    }

    val navigator = remember(navController) { NavControllerNavigator(navController) { expanded = it } }
    val bars = homeBarColors(onHome)
    SystemBarIcons(lightIcons = onHome && !expanded)

    CompositionLocalProvider(
        LocalAppActions provides viewModel,
        LocalNavigator provides navigator,
        LocalReactions provides reactions,
        LocalNowPlayingId provides player.currentSong?.id,
        LocalOpenLogs provides { navigator.openLogs() },
    ) {
        ProvidePlayerColors(playerColors) {
            SharedTransitionLayout {
                Box(Modifier.fillMaxSize()) {
                    // Фон главной — на весь экран: под нижним меню, левой панелью, статус-баром и панелью жестов.
                    AnimatedVisibility(visible = onHome, enter = fadeIn(tween(BARS_MS)), exit = fadeOut(tween(BARS_MS))) {
                        HomeBackdrop(player, settings, Modifier.fillMaxSize())
                    }
                    // Слева и справа — вырез камеры и панель навигации в ландшафте: содержимое под них не заходит.
                    Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
                        if (wide) {
                            NavigationRail(containerColor = bars.railContainer) {
                                TopLevel.entries.forEach { tab ->
                                    NavigationRailItem(
                                        selected = selectedTab == tab,
                                        onClick = { navController.onTabClick(tab, selectedTab) },
                                        icon = { Icon(tab.icon, contentDescription = null) },
                                        label = { Text(stringResource(tab.label)) },
                                        colors = bars.railItem,
                                        modifier = Modifier.testTag(tab.tag),
                                    )
                                }
                            }
                        }
                        Scaffold(
                            modifier = Modifier.weight(1f),
                            containerColor = bars.content,
                            // Отступы под системные панели экраны делают сами (edge-to-edge).
                            contentWindowInsets = WindowInsets(0, 0, 0, 0),
                            snackbarHost = { SnackbarHost(snackbar) },
                            bottomBar = {
                                Column(if (wide) Modifier.navigationBarsPadding() else Modifier) {
                                    // Появляется и исчезает плавно вместе с высотой: отступ содержимого меняется без скачка.
                                    AnimatedVisibility(
                                        visible = player.hasMedia && !expanded && !onHome,
                                        enter = fadeIn(tween(MINI_PLAYER_MS)) + expandVertically(tween(MINI_PLAYER_MS)),
                                        exit = fadeOut(tween(MINI_PLAYER_MS)) + shrinkVertically(tween(MINI_PLAYER_MS)),
                                    ) {
                                        MiniPlayer(
                                            state = player,
                                            reaction = player.currentSong?.let(reactions::of),
                                            onExpand = { expanded = true },
                                            onTogglePlay = viewModel::togglePlay,
                                            onNext = viewModel::next,
                                            onPrevious = viewModel::previous,
                                            onToggleLike = { player.currentSong?.let(viewModel::toggleLike) },
                                            sharedScope = this@SharedTransitionLayout,
                                            visibilityScope = this,
                                        )
                                    }
                                    if (!wide) {
                                        NavigationBar(containerColor = bars.barContainer) {
                                            TopLevel.entries.forEach { tab ->
                                                NavigationBarItem(
                                                    selected = selectedTab == tab,
                                                    onClick = { navController.onTabClick(tab, selectedTab) },
                                                    icon = { Icon(tab.icon, contentDescription = null) },
                                                    label = { Text(stringResource(tab.label)) },
                                                    colors = bars.barItem,
                                                    modifier = Modifier.testTag(tab.tag),
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                        ) { padding ->
                            BodroNavHost(navController, Modifier.padding(padding))
                        }
                    }
                    AnimatedVisibility(
                        visible = expanded && player.hasMedia,
                        enter = slideInVertically(tween(350)) { it / 3 } + fadeIn(tween(250)),
                        exit = slideOutVertically(tween(300)) { it / 3 } + fadeOut(tween(200)),
                    ) {
                        FullPlayerScreen(
                            onCollapse = { expanded = false },
                            sharedScope = this@SharedTransitionLayout,
                            visibilityScope = this,
                        )
                    }
                }
            }
        }

        songMenu?.let { request ->
            SongMenuSheet(
                song = request.song,
                reaction = reactions.of(request.song),
                onDismiss = viewModel::dismissSongMenu,
                onRemoveFromPlaylist = if (request.context.playlistId != null) {
                    { viewModel.removeFromPlaylist(request) }
                } else {
                    null
                },
            )
        }
        picker?.let { state ->
            AddToPlaylistDialog(
                playlists = state.playlists,
                onPick = viewModel::pickPlaylist,
                onCreate = viewModel::createPlaylistWith,
                onDismiss = viewModel::dismissPlaylistPicker,
            )
        }
    }
}

/** Цвета нижнего меню / левой панели: на «Главной» — прозрачные поверх туманности, на остальных — как в теме. */
private class BarColors(
    val content: Color,
    val barContainer: Color,
    val railContainer: Color,
    val barItem: NavigationBarItemColors,
    val railItem: NavigationRailItemColors,
)

@Composable
private fun animatedBarColor(onHome: Boolean, home: Color, other: Color, label: String): Color =
    animateColorAsState(if (onHome) home else other, tween(BARS_MS), label = label).value

@Composable
private fun homeBarColors(onHome: Boolean): BarColors {
    val scheme = MaterialTheme.colorScheme
    val selected = animatedBarColor(onHome, Color.White, scheme.onSecondaryContainer, "bar-selected")
    val unselected = animatedBarColor(onHome, Color.White.copy(alpha = 0.6f), scheme.onSurfaceVariant, "bar-unselected")
    val selectedText = animatedBarColor(onHome, Color.White, scheme.onSurface, "bar-selected-text")
    val indicator = animatedBarColor(onHome, Color.White.copy(alpha = 0.15f), scheme.secondaryContainer, "bar-indicator")
    return BarColors(
        content = animatedBarColor(onHome, Color.Transparent, scheme.background, "bar-content"),
        barContainer = animatedBarColor(onHome, Color.Transparent, NavigationBarDefaults.containerColor, "bar-container"),
        // Сверху слева туманность яркая — под левой панелью лёгкий тёмный скрим.
        railContainer = animatedBarColor(onHome, Color.Black.copy(alpha = RAIL_SCRIM), scheme.surface, "rail-container"),
        barItem = NavigationBarItemDefaults.colors(
            selectedIconColor = selected,
            selectedTextColor = selectedText,
            indicatorColor = indicator,
            unselectedIconColor = unselected,
            unselectedTextColor = unselected,
        ),
        railItem = NavigationRailItemDefaults.colors(
            selectedIconColor = selected,
            selectedTextColor = selectedText,
            indicatorColor = indicator,
            unselectedIconColor = unselected,
            unselectedTextColor = unselected,
        ),
    )
}

/** Светлые значки статус-бара и панели жестов на тёмной главной; на остальных экранах — как в теме. */
@Composable
private fun SystemBarIcons(lightIcons: Boolean) {
    val view = LocalView.current
    val lightTheme = MaterialTheme.colorScheme.background.luminance() > 0.5f
    if (view.isInEditMode) return
    DisposableEffect(lightIcons, lightTheme) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightTheme && !lightIcons
                isAppearanceLightNavigationBars = lightTheme && !lightIcons
            }
        }
        onDispose {}
    }
}

/**
 * Нажатие на вкладку: если открыт другой раздел — вернуться в него на тот экран, где был пользователь;
 * повторное нажатие на уже открытый раздел — на начальный экран раздела.
 */
private fun NavHostController.onTabClick(tab: TopLevel, selected: TopLevel) {
    if (tab == selected) {
        val atRoot = currentBackStackEntry?.destination?.hasRoute(tab.routeClass) == true
        if (!atRoot && !popBackStack(tab.route, inclusive = false)) navigateTopLevel(tab)
    } else {
        navigateTopLevel(tab)
    }
}

private fun NavHostController.navigateTopLevel(tab: TopLevel) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private class NavControllerNavigator(
    private val nav: NavHostController,
    private val setPlayerExpanded: (Boolean) -> Unit,
) : Navigator {
    private fun go(route: Any) {
        setPlayerExpanded(false)
        nav.navigate(route) { launchSingleTop = true }
    }

    override fun openAlbum(id: String) = go(AlbumRoute(id))

    override fun openArtist(id: String) = go(ArtistRoute(id))

    override fun openPlaylist(id: String) = go(PlaylistRoute(id))

    override fun openGenre(name: String) = go(GenreRoute(name))

    override fun openWave() = go(WaveRoute)

    override fun openPlayer() = setPlayerExpanded(true)

    override fun openLogs() = go(LogsRoute)

    override fun back() {
        nav.popBackStack()
    }
}

/** Для экранов, которым нужен доступ к общему переходу (плеер). */
typealias SharedScope = SharedTransitionScope

private const val MINI_PLAYER_MS = 250
private const val UNDO_SNACKBAR_MS = 5_000L
private const val BARS_MS = 300
private const val RAIL_SCRIM = 0.3f
