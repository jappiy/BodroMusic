package ru.bodro.music.core.model

import kotlinx.serialization.Serializable

/** Режим «Моей волны» (чипы на экране волны). */
@Serializable
enum class WaveMode {
    /** По умолчанию: сбалансированная смесь. */
    Default,

    /** «Любимое». */
    Favorites,

    /** «Незнакомое». */
    Discover,

    /** «Популярное у меня». */
    Popular,

    /** «Микс треков» — случайные из любимого. */
    TrackMix,

    /** «Микс альбомов». */
    AlbumMix,

    /** «Микс десятилетия». */
    DecadeMix,
}

/** Источник для волны «по чему-то»: по коллекции, по лайкам, по исполнителю, по треку. */
@Serializable
sealed interface WaveSeed {
    @Serializable
    data object Everything : WaveSeed

    @Serializable
    data object Collection : WaveSeed

    @Serializable
    data class Artist(val artistId: String, val name: String) : WaveSeed

    @Serializable
    data class Track(val songId: String, val title: String, val artistId: String?, val genre: String?) : WaveSeed

    @Serializable
    data class Genre(val genre: String) : WaveSeed

    /** Волна по списку треков (например, по «Мне нравится» с фильтром жанра). */
    @Serializable
    data class SongList(val title: String, val songIds: List<String>) : WaveSeed
}

@Serializable
data class WaveSettings(
    val mode: WaveMode = WaveMode.Default,
    val genre: String? = null,
    /** Начало десятилетия, например 1990. */
    val decade: Int? = null,
    val seed: WaveSeed = WaveSeed.Everything,
) {
    val yearRange: IntRange? get() = decade?.let { it..(it + 9) }
}
