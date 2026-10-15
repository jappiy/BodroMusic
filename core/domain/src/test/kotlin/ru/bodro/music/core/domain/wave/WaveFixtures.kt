package ru.bodro.music.core.domain.wave

import kotlin.random.Random
import ru.bodro.music.core.model.AlbumListType
import ru.bodro.music.core.model.Song
import ru.bodro.music.core.model.WaveSettings

internal const val DAY = 24L * 60 * 60 * 1000
internal const val NOW = 1_800_000_000_000L

internal val genres = listOf("Rock", "Jazz", "Pop", "Electronic", "Hip-Hop", "Folk", "Metal", "Classical")

/** Синтетическая медиатека: 50 исполнителей × 40 треков. */
internal val library: List<Song> = (0 until 50).flatMap { artist ->
    (0 until 40).map { n ->
        Song(
            id = "s-$artist-$n",
            title = "Song $n",
            artist = "Artist $artist",
            artistId = "ar-$artist",
            albumId = "al-$artist-${n / 10}",
            genre = genres[artist % genres.size],
            year = 1970 + (artist * 7 + n) % 50,
            duration = 200,
        )
    }
}

internal fun request(
    profile: WaveProfile = WaveProfile(),
    settings: WaveSettings = WaveSettings(),
    recent: List<Song> = emptyList(),
    seed: Int = 1,
    batch: Int = 20,
    current: Song? = null,
) = WaveRequest(
    settings = settings,
    profile = profile,
    currentSong = current,
    recentSongs = recent,
    batchSize = batch,
    nowMs = NOW,
    random = Random(seed),
)

/** Непересекающиеся корзины, чтобы по треку однозначно понимать его корзину. */
internal fun disjointCandidates(): List<WaveCandidate> = library.mapIndexed { index, song ->
    WaveCandidate(song, WaveBucket.entries[index % WaveBucket.entries.size])
}

internal class FakeWaveLibrary(private val songs: List<Song> = library) : WaveLibrary {
    val calls = mutableListOf<String>()
    var starred: List<Song> = songs.filterIndexed { i, _ -> i % 17 == 0 }.map { it.copy(starred = true) }
    var similarAvailable = false

    override suspend fun starredSongs(): List<Song> = starred.also { calls += "starred" }

    override suspend fun albumListSongs(type: AlbumListType, albumCount: Int): List<Song> {
        calls += "albums:${type.apiValue}:$albumCount"
        val offset = when (type) {
            AlbumListType.Frequent -> 0
            AlbumListType.Recent -> 10
            else -> 40
        }
        return songs.filter { it.artistId!!.removePrefix("ar-").toInt() in offset until offset + albumCount }
    }

    override suspend fun artistSongs(artistId: String, limit: Int) =
        songs.filter { it.artistId == artistId }.take(limit).also { calls += "artist:$artistId" }

    override suspend fun genreSongs(genre: String, count: Int) =
        songs.filter { it.genre == genre }.shuffled(Random(genre.hashCode())).take(count).also { calls += "genre:$genre" }

    override suspend fun randomSongs(count: Int, genre: String?, fromYear: Int?, toYear: Int?): List<Song> {
        calls += "random:$genre:$fromYear:$toYear"
        return songs.asSequence()
            .filter { genre == null || it.genre == genre }
            .filter { fromYear == null || (it.year ?: 0) in fromYear..(toYear ?: 9999) }
            .shuffled(Random(count))
            .take(count)
            .toList()
    }

    override suspend fun similarSongs(artistId: String, count: Int): List<Song> {
        calls += "similar:$artistId"
        return if (similarAvailable) songs.filter { it.artistId == "ar-49" }.take(count) else emptyList()
    }

    override suspend fun songs(ids: List<String>) = songs.filter { it.id in ids }
}
