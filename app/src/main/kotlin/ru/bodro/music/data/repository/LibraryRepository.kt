package ru.bodro.music.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.bodro.music.core.model.Album
import ru.bodro.music.core.model.AlbumDetails
import ru.bodro.music.core.model.AlbumListType
import ru.bodro.music.core.model.Artist
import ru.bodro.music.core.model.ArtistDetails
import ru.bodro.music.core.model.Genre
import ru.bodro.music.core.model.Lyrics
import ru.bodro.music.core.model.Playlist
import ru.bodro.music.core.model.SearchResult
import ru.bodro.music.core.model.Song
import ru.bodro.music.core.model.Starred
import ru.bodro.music.core.subsonic.SubsonicClient
import ru.bodro.music.data.account.AccountRepository
import ru.bodro.music.data.account.ConnectionManager
import ru.bodro.music.di.Clock

data class HomeData(
    val starredSongs: List<Song>,
    val newestAlbums: List<Album>,
    val recentAlbums: List<Album>,
    val frequentAlbums: List<Album>,
    val randomArtists: List<Artist>,
    val playlists: List<Playlist>,
) {
    val isEmpty: Boolean
        get() = starredSongs.isEmpty() &&
            newestAlbums.isEmpty() &&
            recentAlbums.isEmpty() &&
            frequentAlbums.isEmpty() &&
            randomArtists.isEmpty() &&
            playlists.isEmpty()
}

enum class AlbumSort(val type: AlbumListType) {
    Name(AlbumListType.AlphabeticalByName),
    Artist(AlbumListType.AlphabeticalByArtist),
    Newest(AlbumListType.Newest),
    Recent(AlbumListType.Recent),
    Frequent(AlbumListType.Frequent),
    Random(AlbumListType.Random),
    Year(AlbumListType.ByYear),
    Starred(AlbumListType.Starred),
}

/** Медиатека (общая для всех пользователей) поверх SubsonicClient с кэшем в памяти. */
@Singleton
class LibraryRepository @Inject constructor(
    private val client: SubsonicClient,
    private val connection: ConnectionManager,
    private val accounts: AccountRepository,
    private val reactions: ReactionRepository,
    private val history: HistoryRepository,
    private val clock: Clock,
) {
    private data class Cached<T>(val value: T, val atMs: Long, val accountId: Long?)

    private val mutex = Mutex()
    private var artistsCache: Cached<List<Artist>>? = null
    private var genresCache: Cached<List<Genre>>? = null
    private val albumCache = object : LinkedHashMap<String, Cached<AlbumDetails>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached<AlbumDetails>>?) = size > 64
    }

    suspend fun home(): HomeData = coroutineScope {
        val starred = async { starred() }
        val newest = async { client.getAlbumList2(AlbumListType.Newest, size = 20) }
        val recent = async { client.getAlbumList2(AlbumListType.Recent, size = 20) }
        val frequent = async { client.getAlbumList2(AlbumListType.Frequent, size = 20) }
        val artists = async { artists().shuffled().take(20) }
        val playlists = async { client.getPlaylists() }
        HomeData(
            starredSongs = starred.await().songs.take(30),
            newestAlbums = newest.await(),
            recentAlbums = recent.await(),
            frequentAlbums = frequent.await(),
            randomArtists = artists.await(),
            playlists = playlists.await(),
        )
    }

    suspend fun starred(): Starred {
        val starred = client.getStarred2()
        reactions.ingestStarred(starred.songs)
        return starred
    }

    suspend fun album(id: String, force: Boolean = false): AlbumDetails {
        val accountId = connection.activeAccountId
        if (!force) {
            mutex.withLock {
                albumCache[id]
            }?.takeIf { it.accountId == accountId && clock.now() - it.atMs < TTL_MS }?.let { return it.value }
        }
        val details = client.getAlbum(id)
        reactions.ingest(details.songs)
        mutex.withLock { albumCache[id] = Cached(details, clock.now(), accountId) }
        return details
    }

    suspend fun albums(
        sort: AlbumSort,
        offset: Int,
        size: Int = PAGE,
        genre: String? = null,
        fromYear: Int? = null,
        toYear: Int? = null,
    ): List<Album> =
        client.getAlbumList2(
            type = if (genre != null) AlbumListType.ByGenre else sort.type,
            size = size,
            offset = offset,
            genre = genre,
            fromYear = fromYear ?: if (sort == AlbumSort.Year) 0 else null,
            toYear = toYear ?: if (sort == AlbumSort.Year) 9999 else null,
        )

    suspend fun artists(force: Boolean = false): List<Artist> {
        val accountId = connection.activeAccountId
        if (!force) {
            artistsCache?.takeIf { it.accountId == accountId && clock.now() - it.atMs < TTL_MS }?.let { return it.value }
        }
        return client.getArtists().also { artistsCache = Cached(it, clock.now(), accountId) }
    }

    /** Исполнитель + топ-треки: из своей истории, затем getTopSongs (если сервер умеет). */
    suspend fun artist(id: String): Pair<ArtistDetails, List<Song>> = coroutineScope {
        val details = client.getArtist(id)
        val own = async { history.topSongsOfArtist(id, TOP_SONGS) }
        val server = async { client.getTopSongs(details.artist.name, TOP_SONGS) }
        val top = (own.await() + server.await()).distinctBy { it.id }.take(TOP_SONGS)
        reactions.ingest(top)
        details.copy(albums = details.albums.sortedWith(compareByDescending<Album> { it.year ?: 0 }.thenBy { it.name })) to top
    }

    /** Все треки исполнителя (для «играть всё» и волны по исполнителю). */
    suspend fun artistSongs(artistId: String, limit: Int = 200): List<Song> = coroutineScope {
        val albums = client.getArtist(artistId).albums
        albums.map { async { runCatching { album(it.id).songs }.getOrDefault(emptyList()) } }
            .flatMap { it.await() }
            .take(limit)
    }

    suspend fun genres(force: Boolean = false): List<Genre> {
        val accountId = connection.activeAccountId
        if (!force) genresCache?.takeIf { it.accountId == accountId && clock.now() - it.atMs < TTL_MS }?.let { return it.value }
        return client.getGenres().sortedByDescending { it.songCount }.also { genresCache = Cached(it, clock.now(), accountId) }
    }

    suspend fun songsByGenre(genre: String, offset: Int, count: Int = PAGE): List<Song> =
        client.getSongsByGenre(genre, count, offset).also { reactions.ingest(it) }

    suspend fun allSongs(offset: Int, count: Int = PAGE): List<Song> =
        client.search3("", artistCount = 0, albumCount = 0, songCount = count, songOffset = offset).songs.also { reactions.ingest(it) }

    suspend fun randomSongs(count: Int = 50, genre: String? = null, fromYear: Int? = null, toYear: Int? = null): List<Song> =
        client.getRandomSongs(count, genre, fromYear, toYear).also { reactions.ingest(it) }

    suspend fun search(query: String): SearchResult =
        client.search3(query, artistCount = 10, albumCount = 20, songCount = 40).also { reactions.ingest(it.songs) }

    suspend fun song(id: String): Song = client.getSong(id)

    suspend fun lyrics(song: Song): Lyrics? {
        val accountId = connection.activeAccountId ?: return null
        val useOpenSubsonic = "songLyrics" in accounts.extensionsOf(accountId)
        return client.getLyrics(song, useOpenSubsonic)
    }

    suspend fun invalidate() = mutex.withLock {
        artistsCache = null
        genresCache = null
        albumCache.clear()
    }

    companion object {
        const val PAGE = 60
        const val TOP_SONGS = 10
        private const val TTL_MS = 10 * 60 * 1000L
    }
}
