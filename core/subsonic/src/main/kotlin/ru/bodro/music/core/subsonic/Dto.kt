@file:Suppress("ConstructorParameterNaming")

package ru.bodro.music.core.subsonic

import kotlinx.serialization.Serializable
import ru.bodro.music.core.model.Album
import ru.bodro.music.core.model.Artist
import ru.bodro.music.core.model.Genre
import ru.bodro.music.core.model.LyricLine
import ru.bodro.music.core.model.Lyrics
import ru.bodro.music.core.model.OpenSubsonicExtension
import ru.bodro.music.core.model.Playlist
import ru.bodro.music.core.model.Song

@Serializable
internal data class SongDto(
    @Serializable(with = FlexibleStringSerializer::class) val id: String,
    val title: String? = null,
    val artist: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val artistId: String? = null,
    val album: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val albumId: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val coverArt: String? = null,
    val duration: Int? = null,
    val track: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val suffix: String? = null,
    val contentType: String? = null,
    val bitRate: Int? = null,
    val bitDepth: Int? = null,
    val samplingRate: Int? = null,
    val size: Long? = null,
    val starred: String? = null,
    val userRating: Int? = null,
    val playCount: Long? = null,
    val isDir: Boolean? = null,
    val path: String? = null,
) {
    fun toModel() = Song(
        id = id,
        title = title ?: path?.substringAfterLast('/') ?: id,
        artist = artist,
        artistId = artistId,
        album = album,
        albumId = albumId,
        coverArt = coverArt,
        duration = duration ?: 0,
        track = track,
        discNumber = discNumber,
        year = year?.takeIf { it > 0 },
        genre = genre?.takeIf { it.isNotBlank() },
        suffix = suffix,
        contentType = contentType,
        bitRate = bitRate?.takeIf { it > 0 },
        bitDepth = bitDepth?.takeIf { it > 0 },
        samplingRate = samplingRate?.takeIf { it > 0 },
        size = size,
        starred = starred != null,
        userRating = userRating?.takeIf { it > 0 },
        playCount = playCount,
    )
}

@Serializable
internal data class AlbumDto(
    @Serializable(with = FlexibleStringSerializer::class) val id: String,
    val name: String? = null,
    val title: String? = null,
    val artist: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val artistId: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val coverArt: String? = null,
    val songCount: Int? = null,
    val duration: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val starred: String? = null,
    val playCount: Long? = null,
    val created: String? = null,
    val song: List<SongDto> = emptyList(),
) {
    fun toModel() = Album(
        id = id,
        name = name ?: title ?: id,
        artist = artist,
        artistId = artistId,
        coverArt = coverArt,
        songCount = songCount ?: song.size,
        duration = duration ?: song.sumOf { it.duration ?: 0 },
        year = year?.takeIf { it > 0 },
        genre = genre?.takeIf { it.isNotBlank() },
        starred = starred != null,
        playCount = playCount,
        created = created,
    )
}

@Serializable
internal data class ArtistDto(
    @Serializable(with = FlexibleStringSerializer::class) val id: String,
    val name: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val coverArt: String? = null,
    val albumCount: Int? = null,
    val starred: String? = null,
    val artistImageUrl: String? = null,
    val album: List<AlbumDto> = emptyList(),
) {
    fun toModel() = Artist(
        id = id,
        name = name ?: id,
        coverArt = coverArt,
        albumCount = albumCount ?: album.size,
        starred = starred != null,
        artistImageUrl = artistImageUrl?.takeIf { it.isNotBlank() },
    )
}

@Serializable
internal data class ArtistIndexDto(val name: String? = null, val artist: List<ArtistDto> = emptyList())

@Serializable
internal data class ArtistsDto(val index: List<ArtistIndexDto> = emptyList())

@Serializable
internal data class AlbumListDto(val album: List<AlbumDto> = emptyList())

@Serializable
internal data class SongListDto(val song: List<SongDto> = emptyList())

@Serializable
internal data class GenreDto(val value: String? = null, val songCount: Int? = null, val albumCount: Int? = null) {
    fun toModel(): Genre? = value?.takeIf { it.isNotBlank() }?.let { Genre(it, songCount ?: 0, albumCount ?: 0) }
}

@Serializable
internal data class GenresDto(val genre: List<GenreDto> = emptyList())

@Serializable
internal data class SearchResult3Dto(
    val artist: List<ArtistDto> = emptyList(),
    val album: List<AlbumDto> = emptyList(),
    val song: List<SongDto> = emptyList(),
)

@Serializable
internal data class PlaylistDto(
    @Serializable(with = FlexibleStringSerializer::class) val id: String,
    val name: String? = null,
    val owner: String? = null,
    val public: Boolean? = null,
    val songCount: Int? = null,
    val duration: Int? = null,
    @Serializable(with = FlexibleStringSerializer::class) val coverArt: String? = null,
    val comment: String? = null,
    val changed: String? = null,
    val entry: List<SongDto> = emptyList(),
) {
    fun toModel() = Playlist(
        id = id,
        name = name ?: id,
        owner = owner,
        public = public ?: false,
        songCount = songCount ?: entry.size,
        duration = duration ?: entry.sumOf { it.duration ?: 0 },
        coverArt = coverArt,
        comment = comment,
        changed = changed,
    )
}

@Serializable
internal data class PlaylistsDto(val playlist: List<PlaylistDto> = emptyList())

@Serializable
internal data class ExtensionDto(val name: String, val versions: List<Int> = emptyList()) {
    fun toModel() = OpenSubsonicExtension(name, versions)
}

@Serializable
internal data class LyricsLineDto(val start: Long? = null, val value: String = "")

@Serializable
internal data class StructuredLyricsDto(
    val lang: String? = null,
    val synced: Boolean = false,
    val offset: Long? = null,
    val line: List<LyricsLineDto> = emptyList(),
) {
    fun toModel(): Lyrics {
        val offset = offset ?: 0L
        return Lyrics(
            synced = synced,
            lang = lang,
            lines = line.map { LyricLine(startMs = if (synced) (it.start ?: 0L) + offset else null, text = it.value) },
        )
    }
}

@Serializable
internal data class LyricsListDto(val structuredLyrics: List<StructuredLyricsDto> = emptyList())

@Serializable
internal data class LegacyLyricsDto(val artist: String? = null, val title: String? = null, val value: String? = null)
