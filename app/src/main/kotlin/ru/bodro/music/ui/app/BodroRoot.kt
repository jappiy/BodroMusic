package ru.bodro.music.ui.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
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
    Collection(CollectionRoute, CollectionRoute::class, Icons.Rounded.LibraryMusic, R.string.tab_collection, "tab-collection"),
    Search(SearchRoute, SearchRoute::class, Icons.Rounded.Search, R.string.tab_search, "tab-search"),
    Settings(SettingsRoute, SettingsRoute::class, Icons.Rounded.Settings, R.string.tab_settings, "tab-settings"),
}

@Composable
fun BodroRoot(viewModel: MainViewModel, coverArtUrls: CoverArtUrls, openPlayerRequests: Flow<Unit>) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    BodroTheme(settings.themeMode, settings.dynamicColor) {
        CompositionLocalProvider(LocalCoverArtUrls provides coverArtUrls) {
            val session by viewModel.session.collectAsStateWithLifecycle()
            when (val s = session) {
                SessionState.Loading -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                SessionState.NoAccount -> LoginScreen(prefill = null)
                is SessionState.NeedsLogin -> LoginScreen(prefill = s.account)
                // Смена учётки — новый граф навигации и новые ViewModel (данные другой учётки).
                is SessionState.Active -> key(s.account.id) { MainShell(viewModel, openPlayerRequests) }
            }
        }
    }
}

@Composable
private fun MainShell(viewModel: MainViewModel, openPlayerRequests: Flow<Unit>) {
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
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { openPlayerRequests.collect { if (player.hasMedia) expanded = true } }
    BackHandler(enabled = expanded) { expanded = false }

    val backStackEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(backStackEntry) {
        val destination = backStackEntry?.destination ?: return@LaunchedEffect
        TopLevel.entries.firstOrNull { destination.hasRoute(it.routeClass) }?.let { selectedTab = it }
    }

    val navigator = remember(navController) { NavControllerNavigator(navController) { expanded = it } }

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
                    Row(Modifier.fillMaxSize()) {
                        if (wide) {
                            NavigationRail {
                                TopLevel.entries.forEach { tab ->
                                    NavigationRailItem(
                                        selected = selectedTab == tab,
                                        onClick = { navController.navigateTopLevel(tab) },
                                        icon = { Icon(tab.icon, contentDescription = null) },
                                        label = { Text(stringResource(tab.label)) },
                                        modifier = Modifier.testTag(tab.tag),
                                    )
                                }
                            }
                        }
                        Scaffold(
                            modifier = Modifier.weight(1f),
                            // Отступы под системные панели экраны делают сами (edge-to-edge).
                            contentWindowInsets = WindowInsets(0, 0, 0, 0),
                            snackbarHost = { SnackbarHost(snackbar) },
                            bottomBar = {
                                Column(if (wide) Modifier.navigationBarsPadding() else Modifier) {
                                    AnimatedVisibility(
                                        visible = player.hasMedia && !expanded,
                                        enter = fadeIn(tween(200)),
                                        exit = fadeOut(tween(200)),
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
                                        NavigationBar {
                                            TopLevel.entries.forEach { tab ->
                                                NavigationBarItem(
                                                    selected = selectedTab == tab,
                                                    onClick = { navController.navigateTopLevel(tab) },
                                                    icon = { Icon(tab.icon, contentDescription = null) },
                                                    label = { Text(stringResource(tab.label)) },
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
