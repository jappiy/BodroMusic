package ru.bodro.music.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import javax.inject.Inject
import javax.inject.Singleton
import ru.bodro.music.core.model.Song
import ru.bodro.music.data.repository.AppJson
import ru.bodro.music.ui.image.CoverArtRef
import ru.bodro.music.ui.image.CoverArtUrls

/**
 * Song ↔ MediaItem. URI трека — `bodro://song/<id>`: настоящий URL потока подписывается в момент
 * открытия (см. [StreamDataSourceFactory]), поэтому смена LAN/WAN и качества не требует пересборки очереди.
 */
@Singleton
class MediaItemFactory @Inject constructor(private val covers: CoverArtUrls) {
    fun create(song: Song): MediaItem = MediaItem.Builder()
        .setMediaId(song.id)
        .setUri(songUri(song.id))
        .setMediaMetadata(metadata(song))
        .build()

    fun create(songs: List<Song>): List<MediaItem> = songs.map(::create)

    /** Элементы от MediaController приходят без URI — восстанавливаем. */
    fun restoreUri(item: MediaItem): MediaItem =
        if (item.localConfiguration != null) item else item.buildUpon().setUri(songUri(item.mediaId)).build()

    private fun metadata(song: Song): MediaMetadata = MediaMetadata.Builder()
        .setTitle(song.title)
        .setArtist(song.artist)
        .setAlbumTitle(song.album)
        .setAlbumArtist(song.artist)
        .setGenre(song.genre)
        .setTrackNumber(song.track)
        .setDiscNumber(song.discNumber)
        .setRecordingYear(song.year)
        .setArtworkUri(song.coverArt?.let { covers.url(CoverArtRef(it, ARTWORK_SIZE)) }?.let(Uri::parse))
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .setExtras(Bundle().apply { putString(EXTRA_SONG, AppJson.encodeSong(song)) })
        .build()

    companion object {
        const val SCHEME = "bodro"
        const val EXTRA_SONG = "ru.bodro.music.song"
        private const val ARTWORK_SIZE = 600

        fun songUri(songId: String): Uri = Uri.Builder().scheme(SCHEME).authority("song").appendPath(songId).build()

        fun songOf(item: MediaItem?): Song? {
            item ?: return null
            item.mediaMetadata.extras?.getString(EXTRA_SONG)?.let(AppJson::decodeSong)?.let { return it }
            // Резерв: собрать из метаданных.
            val meta = item.mediaMetadata
            return Song(
                id = item.mediaId,
                title = meta.title?.toString() ?: item.mediaId,
                artist = meta.artist?.toString(),
                album = meta.albumTitle?.toString(),
            )
        }
    }
}
