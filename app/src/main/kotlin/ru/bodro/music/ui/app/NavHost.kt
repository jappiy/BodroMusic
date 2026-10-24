package ru.bodro.music.ui.app

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import ru.bodro.music.data.repository.AlbumSort
import ru.bodro.music.ui.screens.album.AlbumScreen
import ru.bodro.music.ui.screens.artist.ArtistScreen
import ru.bodro.music.ui.screens.charts.ChartScreen
import ru.bodro.music.ui.screens.charts.ChartsSettingsScreen
import ru.bodro.music.ui.screens.charts.ChartsTabScreen
import ru.bodro.music.ui.screens.collection.CollectionScreen
import ru.bodro.music.ui.screens.collection.LikedScreen
import ru.bodro.music.ui.screens.downloads.DownloadsScreen
import ru.bodro.music.ui.screens.home.HomeScreen
import ru.bodro.music.ui.screens.library.AlbumsScreen
import ru.bodro.music.ui.screens.library.ArtistsScreen
import ru.bodro.music.ui.screens.library.GenreScreen
import ru.bodro.music.ui.screens.library.GenresScreen
import ru.bodro.music.ui.screens.library.TracksScreen
import ru.bodro.music.ui.screens.login.LoginScreen
import ru.bodro.music.ui.screens.playlist.PlaylistScreen
import ru.bodro.music.ui.screens.playlist.PlaylistsScreen
import ru.bodro.music.ui.screens.pool.PoolScreen
import ru.bodro.music.ui.screens.search.SearchScreen
import ru.bodro.music.ui.screens.settings.AboutScreen
import ru.bodro.music.ui.screens.settings.AccountsScreen
import ru.bodro.music.ui.screens.settings.AppearanceScreen
import ru.bodro.music.ui.screens.settings.BlocklistScreen
import ru.bodro.music.ui.screens.settings.CleanupScreen
import ru.bodro.music.ui.screens.settings.ExportArtistsScreen
import ru.bodro.music.ui.screens.settings.LogDetailScreen
import ru.bodro.music.ui.screens.settings.LogsScreen
import ru.bodro.music.ui.screens.settings.MaintenanceScreen
import ru.bodro.music.ui.screens.settings.PlaybackSettingsScreen
import ru.bodro.music.ui.screens.settings.PlaylistAuditScreen
import ru.bodro.music.ui.screens.settings.RecoScreen
import ru.bodro.music.ui.screens.settings.SettingsScreen
import ru.bodro.music.ui.screens.settings.SourcesScreen
import ru.bodro.music.ui.screens.settings.VersionsScreen
import ru.bodro.music.ui.screens.settings.WaveSettingsScreen
import ru.bodro.music.ui.screens.wave.WaveScreen

@Composable
fun BodroNavHost(navController: NavHostController, modifier: Modifier = Modifier) {
    val back: () -> Unit = { navController.popBackStack() }
    NavHost(
        navController = navController,
        startDestination = HomeRoute,
        modifier = modifier,
        enterTransition = { fadeIn() },
        exitTransition = { fadeOut() },
    ) {
        composable<HomeRoute> {
            HomeScreen(
                onOpenSearch = {
                    // Как нажатие на вкладку «Поиск»: с сохранением состояния разделов.
                    navController.navigate(SearchRoute) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        }
        // Всё, что раньше было на главной под волной: чарты, избранное, недавнее, исполнители, плейлисты.
        composable<ChartsRoute> {
            ChartsTabScreen(
                onOpenWave = { navController.navigate(WaveRoute) },
                onShowAllAlbums = { sort -> navController.navigate(AlbumsRoute(sort.name)) },
                onShowLiked = { navController.navigate(LikedRoute) },
                onShowArtists = { navController.navigate(ArtistsRoute) },
                onShowPlaylists = { navController.navigate(PlaylistsRoute) },
                onOpenChart = { navController.navigate(ChartRoute(it.id, it.title)) },
            )
        }
        composable<CollectionRoute> {
            CollectionScreen(
                onOpenLiked = { navController.navigate(LikedRoute) },
                onOpenPlaylists = { navController.navigate(PlaylistsRoute) },
                onOpenArtists = { navController.navigate(ArtistsRoute) },
                onOpenStarredArtists = { navController.navigate(StarredArtistsRoute) },
                onOpenAlbums = { navController.navigate(AlbumsRoute(AlbumSort.Name.name)) },
                onOpenStarredAlbums = { navController.navigate(AlbumsRoute(AlbumSort.Starred.name, starredOnly = true)) },
                onOpenGenres = { navController.navigate(GenresRoute) },
                onOpenTracks = { navController.navigate(TracksRoute) },
                onOpenPool = { navController.navigate(PoolRoute) },
            )
        }
        composable<SearchRoute> { SearchScreen(onOpenDownloads = { navController.navigate(DownloadsRoute) }) }
        composable<SettingsRoute> {
            SettingsScreen(
                onOpenAccounts = { navController.navigate(AccountsRoute) },
                onOpenPlayback = { navController.navigate(PlaybackSettingsRoute) },
                onOpenWave = { navController.navigate(WaveSettingsRoute) },
                onOpenCharts = { navController.navigate(ChartsSettingsRoute) },
                onOpenAppearance = { navController.navigate(AppearanceRoute) },
                onOpenDownloads = { navController.navigate(DownloadsRoute) },
                onOpenCleanup = { navController.navigate(CleanupRoute) },
                onOpenBlocklist = { navController.navigate(BlocklistRoute) },
                onOpenMaintenance = { navController.navigate(MaintenanceRoute) },
                onOpenPlaylistAudit = { navController.navigate(PlaylistAuditRoute) },
                onOpenSources = { navController.navigate(SourcesRoute) },
                onOpenReco = { navController.navigate(RecoRoute) },
                onOpenVersions = { navController.navigate(VersionsRoute) },
                onOpenLogs = { navController.navigate(LogsRoute) },
                onOpenAbout = { navController.navigate(AboutRoute) },
            )
        }
        composable<AlbumRoute> { AlbumScreen(onBack = back) }
        composable<ArtistRoute> { ArtistScreen(onBack = back) }
        composable<PlaylistRoute> { PlaylistScreen(onBack = back) }
        composable<GenreRoute> { GenreScreen(onBack = back) }
        composable<WaveRoute> { WaveScreen(onBack = back) }
        composable<LikedRoute> { LikedScreen(onBack = back) }
        composable<PlaylistsRoute> { PlaylistsScreen(onBack = back) }
        composable<ArtistsRoute> { ArtistsScreen(onBack = back, starredOnly = false) }
        composable<StarredArtistsRoute> { ArtistsScreen(onBack = back, starredOnly = true) }
        composable<AlbumsRoute> { entry ->
            val route = entry.toRoute<AlbumsRoute>()
            AlbumsScreen(
                onBack = back,
                initialSort =
                route.sort?.let { name -> AlbumSort.entries.firstOrNull { it.name == name } } ?: AlbumSort.Name,
            )
        }
        composable<TracksRoute> { TracksScreen(onBack = back) }
        composable<GenresRoute> { GenresScreen(onBack = back) }
        composable<LogsRoute> { LogsScreen(onBack = back, onOpenEntry = { navController.navigate(LogDetailRoute(it)) }) }
        composable<LogDetailRoute> { LogDetailScreen(onBack = back) }
        composable<VersionsRoute> { VersionsScreen(onBack = back) }
        composable<AboutRoute> { AboutScreen(onBack = back) }
        composable<AccountsRoute> { AccountsScreen(onBack = back, onAddAccount = { navController.navigate(AddAccountRoute) }) }
        composable<AddAccountRoute> { LoginScreen(prefill = null, onBack = back, onDone = back) }
        composable<PlaybackSettingsRoute> { PlaybackSettingsScreen(onBack = back) }
        composable<WaveSettingsRoute> { WaveSettingsScreen(onBack = back) }
        composable<AppearanceRoute> { AppearanceScreen(onBack = back) }
        composable<DownloadsRoute> { DownloadsScreen(onBack = back) }
        composable<CleanupRoute> { CleanupScreen(onBack = back) }
        composable<BlocklistRoute> { BlocklistScreen(onBack = back) }
        composable<MaintenanceRoute> { MaintenanceScreen(onBack = back) }
        composable<PlaylistAuditRoute> { PlaylistAuditScreen(onBack = back) }
        composable<SourcesRoute> {
            SourcesScreen(onBack = back, onOpenArtists = { navController.navigate(ExportArtistsRoute) })
        }
        composable<ExportArtistsRoute> { ExportArtistsScreen(onBack = back) }
        composable<PoolRoute> { PoolScreen(onBack = back) }
        composable<RecoRoute> { RecoScreen(onBack = back) }
        composable<ChartRoute> { ChartScreen(onBack = back) }
        composable<ChartsSettingsRoute> { ChartsSettingsScreen(onBack = back) }
    }
}
