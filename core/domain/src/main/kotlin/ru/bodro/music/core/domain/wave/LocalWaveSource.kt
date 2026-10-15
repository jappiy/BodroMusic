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
        val seedArtistId = seedArtistId(settings.seed)
        val seedGenre = settings.genre ?: seedGenre(settings.seed)
        val liked = async { likedSongs(settings.seed) }
        val familiar = async { familiarSongs(settings.mode) }
        val neighbors = async { neighborSongs(request, seedArtistId, seedGenre) }
        val discovery = async { discoverySongs(request, seedGenre) }

        val profile = request.profile
        buildList {
            liked.await().forEach { add(WaveCandidate(it, WaveBucket.Liked, 1.0)) }
            familiar.await().forEach { add(WaveCandidate(it, WaveBucket.Familiar, if (it.id in profile.likedSongIds) 1.3 else 1.0)) }
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

    private fun seedArtistId(seed: WaveSeed): String? = when (seed) {
        is WaveSeed.Artist -> seed.artistId
        is WaveSeed.Track -> seed.artistId
        else -> null
    }

    private fun seedGenre(seed: WaveSeed): String? = when (seed) {
        is WaveSeed.Genre -> seed.genre
        is WaveSeed.Track -> seed.genre
        else -> null
    }

    /** 1. Лайкнутые (или список-«семя»). */
    private suspend fun likedSongs(seed: WaveSeed): List<Song> {
        if (seed is WaveSeed.SongList) return library.songs(seed.songIds)
        val starred = library.starredSongs()
        return when (seed) {
            is WaveSeed.Artist -> starred.filter { it.artistId == seed.artistId }
            is WaveSeed.Genre -> starred.filter { it.genre.equals(seed.genre, ignoreCase = true) }
            else -> starred
        }
    }

    /** 2. Часто и недавно слушаемое. */
    private suspend fun familiarSongs(mode: WaveMode): List<Song> {
        if (mode == WaveMode.Discover) return emptyList()
        val albums = if (mode == WaveMode.AlbumMix) 6 else 3
        return library.albumListSongs(AlbumListType.Frequent, albums) + library.albumListSongs(AlbumListType.Recent, albums)
    }

    /** 3. «Соседи по вкусу»: исполнители и жанры, которые пользователь любит, + похожие (если сервер умеет). */
    private suspend fun neighborSongs(request: WaveRequest, seedArtistId: String?, seedGenre: String?): List<Song> {
        val profile = request.profile
        val random = request.random
        val artists = (
            listOfNotNull(seedArtistId, request.currentSong?.artistId) +
                profile.topArtistIds.take(TOP_POOL).shuffled(random).take(NEIGHBOR_ARTISTS)
            )
            .distinct()
            .take(NEIGHBOR_ARTISTS + 1)
        val genres = (
            listOfNotNull(seedGenre, request.currentSong?.genre) +
                profile.topGenres.take(TOP_POOL).shuffled(random).take(NEIGHBOR_GENRES)
            )
            .distinct()
            .take(NEIGHBOR_GENRES + 1)
        val byArtist = artists.flatMap { library.artistSongs(it, ARTIST_SONGS) }
        val byGenre = genres.flatMap { library.genreSongs(it, GENRE_SONGS) }
        val similar = (seedArtistId ?: request.currentSong?.artistId)?.let { library.similarSongs(it, SIMILAR_SONGS) }.orEmpty()
        return byArtist + byGenre + similar
    }

    /** 4. «Открытия»: случайное по любимым жанрам, совсем случайное и новинки. */
    private suspend fun discoverySongs(request: WaveRequest, seedGenre: String?): List<Song> {
        val settings = request.settings
        val years = settings.yearRange
        val favoriteGenre = seedGenre ?: request.profile.topGenres.take(TOP_POOL).randomOrNull(request.random)
        val byGenre = library.randomSongs(RANDOM_GENRE_SONGS, favoriteGenre, years?.first, years?.last)
        val anything = library.randomSongs(RANDOM_SONGS, settings.genre, years?.first, years?.last)
        val newest = if (years == null) library.albumListSongs(AlbumListType.Newest, 2) else emptyList()
        return byGenre + anything + newest
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
