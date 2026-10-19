package ru.bodro.music.ui.app

import kotlinx.serialization.Serializable

@Serializable data object HomeRoute

@Serializable data object CollectionRoute

@Serializable data object SearchRoute

@Serializable data object SettingsRoute

@Serializable data class AlbumRoute(val id: String)

@Serializable data class ArtistRoute(val id: String)

@Serializable data class PlaylistRoute(val id: String)

@Serializable data class GenreRoute(val name: String)

@Serializable data object WaveRoute

@Serializable data object LikedRoute

@Serializable data object PlaylistsRoute

@Serializable data object ArtistsRoute

@Serializable data object StarredArtistsRoute

@Serializable data class AlbumsRoute(val sort: String? = null, val starredOnly: Boolean = false)

@Serializable data object TracksRoute

@Serializable data object GenresRoute

@Serializable data object LogsRoute

@Serializable data class LogDetailRoute(val id: Long)

@Serializable data object VersionsRoute

@Serializable data object AboutRoute

@Serializable data object AccountsRoute

@Serializable data object AddAccountRoute

@Serializable data object PlaybackSettingsRoute

@Serializable data object WaveSettingsRoute

@Serializable data object AppearanceRoute

@Serializable data object DownloadsRoute

@Serializable data object CleanupRoute

@Serializable data object BlocklistRoute

@Serializable data object MaintenanceRoute

@Serializable data object SourcesRoute

@Serializable data object ExportArtistsRoute
