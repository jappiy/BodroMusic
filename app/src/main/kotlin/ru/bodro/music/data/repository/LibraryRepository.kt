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
import ru.bodro.music.data.reco.PoolRepository
import ru.bodro.music.di.Clock

data class HomeData(
    val starredSongs: List<Song>,
    val newestAlbums: List<Album>,
    val recentAlbums: List<Album>,
    val randomArtists: List<Artist>,
    val playlists: List<Playlist>,
) {
    val isEmpty: Boolean
        get() = starredSongs.isEmpty() &&
            newestAlbums.isEmpty() &&
            recentAlbums.isEmpty() &&
            randomArtists.isEmpty() &&
            playlists.isEmpty()
}

/** Статистика прослушиваний пользователя (по счётчикам Navidrome). */
data class ListeningStats(
    val totalPlays: Long,
    val topArtists: List<ArtistPlays>,
    val topSongs: List<Song>,
)

data class ArtistPlays(val artist: Artist, val plays: Long)

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
    private val pool: PoolRepository,
    private val clock: Clock,
) {
    private data class Cached<T>(val value: T, val atMs: Long, val accountId: Long?)

    private val mutex = Mutex()
    private var artistsCache: Cached<List<Artist>>? = null
    private var genresCache: Cached<List<Genre>>? = null
    private var statsCache: Cached<ListeningStats>? = null
    private val albumCache = object : LinkedHashMap<String, Cached<AlbumDetails>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached<AlbumDetails>>?) = size > 64
    }

    // region Пул «На пробу» скрыт из исполнителей, альбомов и поиска

    private val hidden get() = pool.visibility.value

    private fun List<Song>.visible(): List<Song> = hidden.let { h -> if (h.songIds.isEmpty()) this else filterNot { it.id in h.songIds } }

    @JvmName("visibleAlbums")
    private fun List<Album>.visible(): List<Album> = hidden.let { h ->
        if (h.albumIds.isEmpty()) this else filterNot { it.id in h.albumIds }
    }

    @JvmName("visibleArtists")
    private fun List<Artist>.visible(): List<Artist> = hidden.let { h ->
        if (h.artistIds.isEmpty()) {
            this
        } else {
            filterNot {
                it.id in
                    h.artistIds
            }
        }
    }

    // endregion

    suspend fun home(): HomeData = coroutineScope {
        val starred = async { starred() }
        val newest = async { client.getAlbumList2(AlbumListType.Newest, size = 20) }
        val recent = async { client.getAlbumList2(AlbumListType.Recent, size = 20) }
        val artists = async { artists().shuffled().take(20) }
        val me = connection.activeAccount?.username.orEmpty()
        val playlists = async { client.getPlaylists().filter { it.accessFor(me) != ru.bodro.music.core.model.PlaylistAccess.Hidden } }
        HomeData(
            starredSongs = starred.await().songs.take(30),
            newestAlbums = newest.await().visible(),
            recentAlbums = recent.await().visible(),
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
        val details = client.getAlbum(id).let { it.copy(songs = it.songs.visible()) }
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
        ).visible()

    suspend fun artists(force: Boolean = false): List<Artist> {
        val accountId = connection.activeAccountId
        if (!force) {
            artistsCache?.takeIf { it.accountId == accountId && clock.now() - it.atMs < TTL_MS }?.let { return it.value.visible() }
        }
        return client.getArtists().also { artistsCache = Cached(it, clock.now(), accountId) }.visible()
    }

    /** Исполнитель + топ-треки: из своей истории, затем getTopSongs (если сервер умеет). */
    suspend fun artist(id: String): Pair<ArtistDetails, List<Song>> = coroutineScope {
        val details = client.getArtist(id)
        val own = async { history.topSongsOfArtist(id, TOP_SONGS) }
        val server = async { client.getTopSongs(details.artist.name, TOP_SONGS) }
        val top = (own.await() + server.await()).visible().distinctBy { it.id }.take(TOP_SONGS)
        reactions.ingest(top)
        details.copy(albums = details.albums.visible().sortedWith(compareByDescending<Album> { it.year ?: 0 }.thenBy { it.name })) to top
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
        client.getSongsByGenre(genre, count, offset).visible().also { reactions.ingest(it) }

    suspend fun allSongs(offset: Int, count: Int = PAGE): List<Song> =
        client.search3("", artistCount = 0, albumCount = 0, songCount = count, songOffset = offset).songs.visible().also {
            reactions.ingest(it)
        }

    suspend fun randomSongs(count: Int = 50, genre: String? = null, fromYear: Int? = null, toYear: Int? = null): List<Song> =
        client.getRandomSongs(count, genre, fromYear, toYear).visible().also { reactions.ingest(it) }

    suspend fun search(query: String): SearchResult =
        client.search3(query, artistCount = 10, albumCount = 20, songCount = 40)
            .let { it.copy(songs = it.songs.visible(), albums = it.albums.visible(), artists = it.artists.visible()) }
            .also { reactions.ingest(it.songs) }

    suspend fun song(id: String): Song = client.getSong(id)

    /**
     * Счётчик прослушиваний, любимые исполнители и треки — по `playCount` Navidrome (учитываются все клиенты).
     * Альбомы `frequent` идут по убыванию прослушиваний; у альбома счётчик = сумма прослушиваний его треков.
     */
    suspend fun listeningStats(force: Boolean = false): ListeningStats = coroutineScope {
        val accountId = connection.activeAccountId
        val cached = statsCache?.takeIf { it.accountId == accountId && clock.now() - it.atMs < TTL_MS }
        if (!force && cached != null) return@coroutineScope cached.value
        val played = buildList {
            var offset = 0
            do {
                val page = client.getAlbumList2(AlbumListType.Frequent, size = STATS_PAGE, offset = offset)
                addAll(page.filter { (it.playCount ?: 0) > 0 })
                offset += STATS_PAGE
            } while (page.size == STATS_PAGE && offset < STATS_MAX_ALBUMS)
        }
        val knownArtists = runCatching { artists() }.getOrDefault(emptyList()).associateBy { it.id }
        val topArtists = played.filter { it.artistId != null }
            .groupBy { it.artistId!! }
            .map { (id, albums) ->
                val artist = knownArtists[id] ?: Artist(id = id, name = albums.first().artist.orEmpty(), coverArt = "ar-$id")
                ArtistPlays(artist, albums.sumOf { it.playCount ?: 0 })
            }
            .sortedByDescending { it.plays }
            .take(TOP_ARTISTS)
        val topSongs = played.take(TOP_SONG_ALBUMS)
            .map { async { runCatching { album(it.id).songs }.getOrDefault(emptyList()) } }
            .flatMap { it.await() }
            .filter { (it.playCount ?: 0) > 0 }
            .sortedByDescending { it.playCount }
            .take(TOP_FAVORITE_SONGS)
        ListeningStats(played.sumOf { it.playCount ?: 0 }, topArtists, topSongs)
            .also { statsCache = Cached(it, clock.now(), accountId) }
    }

    suspend fun lyrics(song: Song): Lyrics? {
        val accountId = connection.activeAccountId ?: return null
        val useOpenSubsonic = "songLyrics" in accounts.extensionsOf(accountId)
        return client.getLyrics(song, useOpenSubsonic)
    }

    suspend fun invalidate() = mutex.withLock {
        artistsCache = null
        genresCache = null
        albumCache.clear()
        statsCache = null
    }

    companion object {
        const val PAGE = 60
        const val TOP_SONGS = 10
        private const val STATS_PAGE = 500
        private const val STATS_MAX_ALBUMS = 5_000
        private const val TOP_ARTISTS = 15
        private const val TOP_SONG_ALBUMS = 20
        private const val TOP_FAVORITE_SONGS = 20
        private const val TTL_MS = 10 * 60 * 1000L
    }
}
