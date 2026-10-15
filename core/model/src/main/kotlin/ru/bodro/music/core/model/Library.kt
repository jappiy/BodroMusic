package ru.bodro.music.core.model

import kotlinx.serialization.Serializable

/** Трек медиатеки Navidrome (общий для всех пользователей) + пользовательские поля (лайк, рейтинг). */
@Serializable
data class Song(
    val id: String,
    val title: String,
    val artist: String? = null,
    val artistId: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val coverArt: String? = null,
    /** Длительность в секундах. */
    val duration: Int = 0,
    val track: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val suffix: String? = null,
    val contentType: String? = null,
    /** Битрейт в kbps. */
    val bitRate: Int? = null,
    val bitDepth: Int? = null,
    val samplingRate: Int? = null,
    val size: Long? = null,
    val starred: Boolean = false,
    val userRating: Int? = null,
    val playCount: Long? = null,
) {
    val durationMs: Long get() = duration * 1000L
    val isDislikedOnServer: Boolean get() = userRating == DISLIKE_RATING

    companion object {
        /** Соглашение BodroMusic: рейтинг 1 на сервере = дизлайк. */
        const val DISLIKE_RATING = 1
    }
}

@Serializable
data class Album(
    val id: String,
    val name: String,
    val artist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int = 0,
    /** Длительность в секундах. */
    val duration: Int = 0,
    val year: Int? = null,
    val genre: String? = null,
    val starred: Boolean = false,
    val playCount: Long? = null,
    val created: String? = null,
)

@Serializable
data class Artist(
    val id: String,
    val name: String,
    val coverArt: String? = null,
    val albumCount: Int = 0,
    val starred: Boolean = false,
    val artistImageUrl: String? = null,
)

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val owner: String? = null,
    val public: Boolean = false,
    val songCount: Int = 0,
    val duration: Int = 0,
    val coverArt: String? = null,
    val comment: String? = null,
    val changed: String? = null,
) {
    fun isEditableBy(username: String): Boolean = owner == null || owner.equals(username, ignoreCase = true)
}

@Serializable
data class Genre(
    val name: String,
    val songCount: Int = 0,
    val albumCount: Int = 0,
)

data class AlbumDetails(val album: Album, val songs: List<Song>)

data class ArtistDetails(val artist: Artist, val albums: List<Album>)

data class PlaylistDetails(val playlist: Playlist, val songs: List<Song>)

data class SearchResult(
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val songs: List<Song> = emptyList(),
) {
    val isEmpty: Boolean get() = artists.isEmpty() && albums.isEmpty() && songs.isEmpty()
}

data class Starred(
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val songs: List<Song> = emptyList(),
)

/** Типы списков `getAlbumList2`. */
enum class AlbumListType(val apiValue: String) {
    Newest("newest"),
    Recent("recent"),
    Frequent("frequent"),
    Random("random"),
    Starred("starred"),
    ByGenre("byGenre"),
    ByYear("byYear"),
    AlphabeticalByName("alphabeticalByName"),
    AlphabeticalByArtist("alphabeticalByArtist"),
    Highest("highest"),
}
