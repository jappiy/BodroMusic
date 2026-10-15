package ru.bodro.music.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Откуда сформирована очередь воспроизведения. */
@Serializable
sealed interface QueueSource {
    @Serializable
    @SerialName("album")
    data class AlbumSource(val albumId: String, val title: String) : QueueSource

    @Serializable
    @SerialName("playlist")
    data class PlaylistSource(val playlistId: String, val title: String) : QueueSource

    @Serializable
    @SerialName("artist")
    data class ArtistSource(val artistId: String, val title: String) : QueueSource

    @Serializable
    @SerialName("liked")
    data object LikedSource : QueueSource

    @Serializable
    @SerialName("wave")
    data class WaveSource(val settings: WaveSettings) : QueueSource

    @Serializable
    @SerialName("custom")
    data class CustomSource(val title: String? = null) : QueueSource

    val isWave: Boolean get() = this is WaveSource
}

enum class RepeatMode { Off, All, One }

/** Снимок очереди для восстановления после перезапуска. */
@Serializable
data class QueueSnapshot(
    val songs: List<Song>,
    val currentIndex: Int,
    val positionMs: Long,
    val repeatMode: String = RepeatMode.Off.name,
    val shuffle: Boolean = false,
    val source: QueueSource = QueueSource.CustomSource(),
) {
    val currentSong: Song? get() = songs.getOrNull(currentIndex)

    /** Безопасные значения после восстановления из хранилища. */
    fun sanitized(): QueueSnapshot {
        if (songs.isEmpty()) return copy(currentIndex = 0, positionMs = 0)
        val index = currentIndex.coerceIn(0, songs.lastIndex)
        val maxPosition = songs[index].durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        return copy(currentIndex = index, positionMs = positionMs.coerceIn(0, maxPosition))
    }
}
