package ru.bodro.music.core.downloads

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Трек из внешнего каталога (Яндекс Музыка / Deezer). [librarySongId] — если он уже есть в Navidrome. */
@Serializable
data class DiscoverTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val coverUrl: String? = null,
    val source: String,
    val sourceId: String,
    val librarySongId: String? = null,
) {
    val key: String get() = requestKey(artist, title)
}

@Serializable
data class DiscoverArtist(
    val name: String,
    val coverUrl: String? = null,
    val source: String,
    val sourceId: String,
)

@Serializable
enum class DiscoverIntent {
    @SerialName("track")
    Track,

    @SerialName("artist")
    Artist,

    @SerialName("none")
    None,
}

/** Результат глобального поиска. При [DiscoverIntent.Artist] запрос понят как имя исполнителя. */
@Serializable
data class DiscoverResult(
    val intent: DiscoverIntent = DiscoverIntent.None,
    val source: String? = null,
    val tracks: List<DiscoverTrack> = emptyList(),
    val artists: List<DiscoverArtist> = emptyList(),
    val artist: DiscoverArtist? = null,
    val topTracks: List<DiscoverTrack> = emptyList(),
) {
    val isEmpty: Boolean get() = tracks.isEmpty() && artists.isEmpty() && topTracks.isEmpty()
}

@Serializable
enum class RequestKind {
    @SerialName("track")
    Track,

    @SerialName("artist")
    Artist,
}

@Serializable
enum class RequestStatus {
    @SerialName("queued")
    Queued,

    @SerialName("searching")
    Searching,

    @SerialName("downloading")
    Downloading,

    @SerialName("importing")
    Importing,

    @SerialName("completed")
    Completed,

    @SerialName("failed")
    Failed,

    @SerialName("cancelled")
    Cancelled,
    ;

    val isActive: Boolean get() = this == Queued || this == Searching || this == Downloading || this == Importing
}

/** Заявка на загрузку на сервере. */
@Serializable
data class DownloadRequest(
    val id: String,
    val kind: RequestKind,
    val artist: String,
    val title: String? = null,
    val album: String? = null,
    val coverUrl: String? = null,
    val status: RequestStatus,
    val message: String? = null,
    val progress: Float? = null,
    val songId: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    val key: String get() = if (kind == RequestKind.Artist) artistKey(artist) else requestKey(artist, title.orEmpty())
}

@Serializable
data class ServiceInfo(
    val version: String,
    val trackDownloads: Boolean,
    val artistDownloads: Boolean,
    val metadataSources: List<String> = emptyList(),
    /** На сервере настроен раздел «На удаление» (bodrod 0.3.0+ с доступом к БД Navidrome). */
    val cleanup: Boolean = false,
    /** Пользователь — администратор Navidrome: может удалять треки и править чёрный список. */
    val admin: Boolean = false,
)

/** Пользователь Navidrome для вкладок «Не нравятся …» (служебные учётки сервер не отдаёт). */
@Serializable
data class CleanupUser(val username: String, val isAdmin: Boolean = false)

/** Трек с дизлайком. [path] — путь к файлу относительно папки музыки на сервере. */
@Serializable
data class DislikedSong(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val albumId: String? = null,
    /** Секунды. */
    val duration: Int? = null,
    val size: Long? = null,
    val suffix: String? = null,
    val bitRate: Int? = null,
    val path: String = "",
    val dislikedBy: List<String> = emptyList(),
)

@Serializable
data class DeleteSongResult(val id: String, val ok: Boolean, val message: String = "")

/** Запись чёрного списка: трек удалён с сервера и не будет скачан снова. */
@Serializable
data class BlockEntry(
    val id: Long,
    val artist: String,
    val title: String,
    val album: String? = null,
    val createdBy: String = "",
    val createdAt: Long = 0,
    /** Сколько раз файл удалялся: первое удаление и повторные появления. */
    val removedCount: Int = 1,
    val lastRemovedAt: Long? = null,
)

@Serializable
data class BlocklistPage(val total: Int = 0, val items: List<BlockEntry> = emptyList())

@Serializable
internal data class DeleteSongsBody(val ids: List<String>)

@Serializable
internal data class CreateRequestBody(
    val kind: RequestKind,
    val artist: String,
    val title: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val coverUrl: String? = null,
)

/** Ключ для сопоставления трека из каталога с заявкой. */
fun requestKey(artist: String, title: String): String = "${normalizeKey(artist)}|${normalizeKey(title)}"

fun artistKey(artist: String): String = "artist|${normalizeKey(artist)}"

private val nonWord = Regex("[^\\p{L}\\p{N}]+")

private fun normalizeKey(value: String): String = value.lowercase().replace('ё', 'е').replace(nonWord, " ").trim()
