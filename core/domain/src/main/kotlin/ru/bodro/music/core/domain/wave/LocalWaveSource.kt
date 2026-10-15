package ru.bodro.music.core.domain.wave

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import ru.bodro.music.core.model.AlbumListType
import ru.bodro.music.core.model.Song
import ru.bodro.music.core.model.WaveMode
import ru.bodro.music.core.model.WaveSeed

/** Доступ к медиатеке для волны (реализуется поверх SubsonicClient с кэшем). */
interface WaveLibrary {
    suspend fun starredSongs(): List<Song>

    /** Треки из [albumCount] альбомов списка [type] (frequent / recent / newest). */
    suspend fun albumListSongs(type: AlbumListType, albumCount: Int): List<Song>

    suspend fun artistSongs(artistId: String, limit: Int): List<Song>

    suspend fun genreSongs(genre: String, count: Int): List<Song>

    suspend fun randomSongs(count: Int, genre: String? = null, fromYear: Int? = null, toYear: Int? = null): List<Song>

    /** Пусто, если сервер не умеет (нет Last.fm). */
    suspend fun similarSongs(artistId: String, count: Int): List<Song>

    suspend fun songs(ids: List<String>): List<Song>
}

/**
 * Клиентский источник волны: строит пул кандидатов из данных текущего пользователя.
 */
class LocalWaveSource(private val library: WaveLibrary) : WaveSource {
    override val name: String = "local"

    override suspend fun candidates(request: WaveRequest): List<WaveCandidate> = coroutineScope {
        val settings = request.settings
        val seed = settings.seed
        val random = request.random
        val profile = request.profile
        val years = settings.yearRange

        val seedArtistId = when (seed) {
            is WaveSeed.Artist -> seed.artistId
            is WaveSeed.Track -> seed.artistId
            else -> null
        }
        val seedGenre = settings.genre ?: when (seed) {
            is WaveSeed.Genre -> seed.genre
            is WaveSeed.Track -> seed.genre
            else -> null
        }

        val liked = async {
            val starred = library.starredSongs()
            when (seed) {
                is WaveSeed.SongList -> library.songs(seed.songIds)
                is WaveSeed.Artist -> starred.filter { it.artistId == seed.artistId }
                is WaveSeed.Genre -> starred.filter { it.genre.equals(seed.genre, ignoreCase = true) }
                else -> starred
            }
        }
        val familiar = async {
            if (settings.mode == WaveMode.Discover) {
                emptyList()
            } else {
                val albums = if (settings.mode == WaveMode.AlbumMix) 6 else 3
                library.albumListSongs(AlbumListType.Frequent, albums) + library.albumListSongs(AlbumListType.Recent, albums)
            }
        }
        val neighborArtists = buildList {
            seedArtistId?.let { add(it) }
            request.currentSong?.artistId?.let { add(it) }
            addAll(profile.topArtistIds.take(TOP_POOL).shuffled(random).take(NEIGHBOR_ARTISTS))
        }.distinct().take(NEIGHBOR_ARTISTS + 1)
        val neighborGenres = buildList {
            seedGenre?.let { add(it) }
            request.currentSong?.genre?.let { add(it) }
            addAll(profile.topGenres.take(TOP_POOL).shuffled(random).take(NEIGHBOR_GENRES))
        }.distinct().take(NEIGHBOR_GENRES + 1)

        val neighbors = async {
            val byArtist = neighborArtists.flatMap { library.artistSongs(it, ARTIST_SONGS) }
            val byGenre = neighborGenres.flatMap { library.genreSongs(it, GENRE_SONGS) }
            val similarArtist = seedArtistId ?: request.currentSong?.artistId
            val similar = similarArtist?.let { library.similarSongs(it, SIMILAR_SONGS) }.orEmpty()
            byArtist + byGenre + similar
        }
        val discovery = async {
            val favoriteGenre = seedGenre ?: profile.topGenres.take(TOP_POOL).randomOrNull(random)
            val byGenre = library.randomSongs(RANDOM_GENRE_SONGS, favoriteGenre, years?.first, years?.last)
            val anything = library.randomSongs(RANDOM_SONGS, settings.genre, years?.first, years?.last)
            val newest = if (years == null) library.albumListSongs(AlbumListType.Newest, 2) else emptyList()
            byGenre + anything + newest
        }

        val likedIds = profile.likedSongIds
        buildList {
            liked.await().forEach { add(WaveCandidate(it, WaveBucket.Liked, 1.0)) }
            familiar.await().forEach { add(WaveCandidate(it, WaveBucket.Familiar, if (it.id in likedIds) 1.3 else 1.0)) }
            neighbors.await().forEach { song ->
                val boost = if (song.artistId != null && song.artistId == seedArtistId) 1.5 else 1.0
                add(WaveCandidate(song, WaveBucket.Neighbors, boost))
            }
            discovery.await().forEach { song ->
                // «Незнакомое» — то, чего пользователь ещё не слушал.
                val unknown = song.id !in profile.lastPlayedAt && (song.playCount ?: 0L) == 0L
                add(WaveCandidate(song, WaveBucket.Discovery, if (unknown) 1.4 else 0.8))
            }
        }
    }

    private companion object {
        const val TOP_POOL = 8
        const val NEIGHBOR_ARTISTS = 3
        const val NEIGHBOR_GENRES = 2
        const val ARTIST_SONGS = 30
        const val GENRE_SONGS = 40
        const val SIMILAR_SONGS = 20
        const val RANDOM_GENRE_SONGS = 30
        const val RANDOM_SONGS = 15
    }
}
