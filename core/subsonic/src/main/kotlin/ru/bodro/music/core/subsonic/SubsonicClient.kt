package ru.bodro.music.core.subsonic

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.bodro.music.core.model.Album
import ru.bodro.music.core.model.AlbumDetails
import ru.bodro.music.core.model.AlbumListType
import ru.bodro.music.core.model.Artist
import ru.bodro.music.core.model.ArtistDetails
import ru.bodro.music.core.model.Genre
import ru.bodro.music.core.model.Lyrics
import ru.bodro.music.core.model.Playlist
import ru.bodro.music.core.model.PlaylistDetails
import ru.bodro.music.core.model.SearchResult
import ru.bodro.music.core.model.ServerInfo
import ru.bodro.music.core.model.Song
import ru.bodro.music.core.model.Starred

/**
 * Клиент Subsonic API (Navidrome). Все запросы — `f=json`, token-аутентификация с новым salt на каждый запрос.
 * Ошибки приводятся к [SubsonicException].
 */
class SubsonicClient(
    private val http: OkHttpClient,
    private val connection: ConnectionProvider,
    private val clientName: String = CLIENT_NAME,
    private val saltGenerator: () -> String = SubsonicAuth::randomSalt,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    // region Системные

    suspend fun ping(config: ConnectionConfig? = null): ServerInfo = call("ping", config = config) { it.toServerInfo() }

    /** Список расширений OpenSubsonic; если сервер их не поддерживает — пустой список. */
    suspend fun getOpenSubsonicExtensions(config: ConnectionConfig? = null) = try {
        call("getOpenSubsonicExtensions", config = config) { response ->
            response.decodeChild<List<ExtensionDto>>("openSubsonicExtensions") { emptyList() }.map { it.toModel() }
        }
    } catch (e: SubsonicException) {
        if (e.error is SubsonicError.Api || e.error is SubsonicError.Http) emptyList() else throw e
    }

    /** `ping` + расширения: используется при входе и «Проверить подключение». */
    suspend fun handshake(config: ConnectionConfig? = null): ServerInfo {
        val info = ping(config)
        return if (info.openSubsonic) info.copy(extensions = getOpenSubsonicExtensions(config)) else info
    }

    // endregion

    // region Библиотека

    suspend fun getArtists(): List<Artist> = call("getArtists") { response ->
        response.decodeChild<ArtistsDto>("artists") { ArtistsDto() }.index.flatMap { idx -> idx.artist.map { it.toModel() } }
    }

    suspend fun getArtist(id: String): ArtistDetails = call("getArtist", listOf("id" to id)) { response ->
        val dto = response.decodeChild<ArtistDto?>("artist") { null } ?: throw notFound()
        ArtistDetails(dto.toModel(), dto.album.map { it.toModel() })
    }

    suspend fun getAlbum(id: String): AlbumDetails = call("getAlbum", listOf("id" to id)) { response ->
        val dto = response.decodeChild<AlbumDto?>("album") { null } ?: throw notFound()
        AlbumDetails(dto.toModel(), dto.song.map { it.toModel() })
    }

    suspend fun getAlbumList2(
        type: AlbumListType,
        size: Int = 20,
        offset: Int = 0,
        genre: String? = null,
        fromYear: Int? = null,
        toYear: Int? = null,
    ): List<Album> {
        val params = buildList {
            add("type" to type.apiValue)
            add("size" to size.toString())
            add("offset" to offset.toString())
            genre?.let { add("genre" to it) }
            if (type == AlbumListType.ByYear) {
                add("fromYear" to (fromYear ?: 0).toString())
                add("toYear" to (toYear ?: 9999).toString())
            }
        }
        return call("getAlbumList2", params) { response ->
            response.decodeChild<AlbumListDto>("albumList2") { AlbumListDto() }.album.map { it.toModel() }
        }
    }

    suspend fun getSong(id: String): Song = call("getSong", listOf("id" to id)) { response ->
        response.decodeChild<SongDto?>("song") { null }?.toModel() ?: throw notFound()
    }

    suspend fun getGenres(): List<Genre> = call("getGenres") { response ->
        response.decodeChild<GenresDto>("genres") { GenresDto() }.genre.mapNotNull { it.toModel() }
    }

    suspend fun getSongsByGenre(genre: String, count: Int = 50, offset: Int = 0): List<Song> = call(
        "getSongsByGenre",
        listOf("genre" to genre, "count" to count.toString(), "offset" to offset.toString()),
    ) { response -> response.songs("songsByGenre") }

    suspend fun getRandomSongs(size: Int = 50, genre: String? = null, fromYear: Int? = null, toYear: Int? = null): List<Song> {
        val params = buildList {
            add("size" to size.toString())
            genre?.let { add("genre" to it) }
            fromYear?.let { add("fromYear" to it.toString()) }
            toYear?.let { add("toYear" to it.toString()) }
        }
        return call("getRandomSongs", params) { it.songs("randomSongs") }
    }

    suspend fun search3(
        query: String,
        artistCount: Int = 10,
        albumCount: Int = 20,
        songCount: Int = 30,
    ): SearchResult = call(
        "search3",
        listOf(
            "query" to query,
            "artistCount" to artistCount.toString(),
            "albumCount" to albumCount.toString(),
            "songCount" to songCount.toString(),
        ),
    ) { response ->
        val dto = response.decodeChild<SearchResult3Dto>("searchResult3") { SearchResult3Dto() }
        SearchResult(dto.artist.map { it.toModel() }, dto.album.map { it.toModel() }, dto.song.map { it.toModel() })
    }

    suspend fun getStarred2(): Starred = call("getStarred2") { response ->
        val dto = response.decodeChild<SearchResult3Dto>("starred2") { SearchResult3Dto() }
        Starred(dto.artist.map { it.toModel() }, dto.album.map { it.toModel() }, dto.song.map { it.toModel() })
    }

    /** Опционально: пусто, если на сервере нет внешних источников (Last.fm). */
    suspend fun getSimilarSongs2(artistId: String, count: Int = 30): List<Song> = optional {
        call("getSimilarSongs2", listOf("id" to artistId, "count" to count.toString())) { it.songs("similarSongs2") }
    }

    /** Опционально: пусто, если на сервере нет внешних источников (Last.fm). */
    suspend fun getTopSongs(artistName: String, count: Int = 20): List<Song> = optional {
        call("getTopSongs", listOf("artist" to artistName, "count" to count.toString())) { it.songs("topSongs") }
    }

    // endregion

    // region Плейлисты

    suspend fun getPlaylists(): List<Playlist> = call("getPlaylists") { response ->
        response.decodeChild<PlaylistsDto>("playlists") { PlaylistsDto() }.playlist.map { it.toModel() }
    }

    suspend fun getPlaylist(id: String): PlaylistDetails = call("getPlaylist", listOf("id" to id)) { response ->
        val dto = response.decodeChild<PlaylistDto?>("playlist") { null } ?: throw notFound()
        PlaylistDetails(dto.toModel(), dto.entry.map { it.toModel() })
    }

    suspend fun createPlaylist(name: String, songIds: List<String> = emptyList()): Playlist? = call(
        "createPlaylist",
        listOf("name" to name) + songIds.map { "songId" to it },
    ) { response -> response.decodeChild<PlaylistDto?>("playlist") { null }?.toModel() }

    suspend fun updatePlaylist(
        playlistId: String,
        name: String? = null,
        songIdsToAdd: List<String> = emptyList(),
        songIndexesToRemove: List<Int> = emptyList(),
    ) {
        val params = buildList {
            add("playlistId" to playlistId)
            name?.let { add("name" to it) }
            songIdsToAdd.forEach { add("songIdToAdd" to it) }
            songIndexesToRemove.forEach { add("songIndexToRemove" to it.toString()) }
        }
        call("updatePlaylist", params) { }
    }

    suspend fun deletePlaylist(id: String) = call("deletePlaylist", listOf("id" to id)) { }

    // endregion

    // region Действия

    suspend fun star(songId: String? = null, albumId: String? = null, artistId: String? = null) =
        call("star", starParams(songId, albumId, artistId)) { }

    suspend fun unstar(songId: String? = null, albumId: String? = null, artistId: String? = null) =
        call("unstar", starParams(songId, albumId, artistId)) { }

    /** `rating` 0 — снять рейтинг; 1 — в BodroMusic означает дизлайк. */
    suspend fun setRating(id: String, rating: Int) {
        require(rating in 0..5) { "rating must be 0..5" }
        call("setRating", listOf("id" to id, "rating" to rating.toString())) { }
    }

    suspend fun scrobble(songId: String, timeMs: Long? = null, submission: Boolean) {
        val params = buildList {
            add("id" to songId)
            timeMs?.let { add("time" to it.toString()) }
            add("submission" to submission.toString())
        }
        call("scrobble", params) { }
    }

    // endregion

    // region Медиа

    /** Текст песни: OpenSubsonic `getLyricsBySongId` (с синхронизацией), иначе классический `getLyrics`. */
    suspend fun getLyrics(song: Song, useOpenSubsonic: Boolean): Lyrics? {
        if (useOpenSubsonic) {
            val structured = optional {
                call("getLyricsBySongId", listOf("id" to song.id)) { response ->
                    response.decodeChild<LyricsListDto>("lyricsList") { LyricsListDto() }.structuredLyrics
                        .map { it.toModel() }
                }
            }
            val best = structured.firstOrNull { it.synced && !it.isEmpty } ?: structured.firstOrNull { !it.isEmpty }
            if (best != null) return best
        }
        val artist = song.artist ?: return null
        val legacy = optional {
            listOfNotNull(
                call("getLyrics", listOf("artist" to artist, "title" to song.title)) { response ->
                    response.decodeChild<LegacyLyricsDto?>("lyrics") { null }
                },
            )
        }.firstOrNull()
        val text = legacy?.value?.takeIf { it.isNotBlank() } ?: return null
        return LrcParser.parse(text).takeIf { !it.isEmpty }
    }

    /** URL потока с подписью. `maxBitRate=0` и `format=raw` — оригинал без транскодирования. */
    fun streamUrl(songId: String, maxBitRate: Int?, format: String?, config: ConnectionConfig? = null): HttpUrl {
        val params = buildList {
            add("id" to songId)
            add("maxBitRate" to (maxBitRate ?: 0).toString())
            format?.let { add("format" to it) }
        }
        return buildUrl("stream", params, config ?: requireConfig())
    }

    fun coverArtUrl(coverArtId: String, size: Int?, config: ConnectionConfig? = null): HttpUrl {
        val params = buildList {
            add("id" to coverArtId)
            size?.let { add("size" to it.toString()) }
        }
        return buildUrl("getCoverArt", params, config ?: requireConfig())
    }

    // endregion

    /** Собирает подписанный URL `/rest/<endpoint>.view`. */
    fun buildUrl(endpoint: String, params: List<Pair<String, String>>, config: ConnectionConfig): HttpUrl {
        val salt = saltGenerator()
        val builder = config.baseUrl.newBuilder()
            .addPathSegment("rest")
            .addPathSegment("$endpoint.view")
            .addQueryParameter("u", config.username)
            .addQueryParameter("t", SubsonicAuth.token(config.password, salt))
            .addQueryParameter("s", salt)
            .addQueryParameter("v", API_VERSION)
            .addQueryParameter("c", clientName)
            .addQueryParameter("f", "json")
        params.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build()
    }

    private suspend fun <T> call(
        endpoint: String,
        params: List<Pair<String, String>> = emptyList(),
        config: ConnectionConfig? = null,
        parse: (JsonObject) -> T,
    ): T = withContext(dispatcher) {
        val url = buildUrl(endpoint, params, config ?: requireConfig())
        val request = Request.Builder().url(url).get().build()
        val body = try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw SubsonicException(SubsonicError.Http(response.code))
                response.body.string()
            }
        } catch (e: SubsonicException) {
            throw e
        } catch (e: IOException) {
            throw SubsonicException(SubsonicError.Network(e.toNetworkKind(), e.message), e)
        }
        val envelope = parseEnvelope(body)
        try {
            parse(envelope)
        } catch (e: SubsonicException) {
            throw e
        } catch (e: Exception) {
            throw SubsonicException(SubsonicError.InvalidResponse(e.message), e)
        }
    }

    private fun requireConfig(): ConnectionConfig = connection.current() ?: throw SubsonicException(SubsonicError.NotConfigured)

    private fun starParams(songId: String?, albumId: String?, artistId: String?) = buildList {
        songId?.let { add("id" to it) }
        albumId?.let { add("albumId" to it) }
        artistId?.let { add("artistId" to it) }
        require(isNotEmpty()) { "star/unstar requires an id" }
    }

    private fun JsonObject.songs(key: String): List<Song> =
        decodeChild<SongListDto>(key) { SongListDto() }.song.filter { it.isDir != true }.map { it.toModel() }

    private fun JsonObject.toServerInfo() = ServerInfo(
        apiVersion = this["version"]?.jsonPrimitive?.contentOrNull ?: "",
        type = this["type"]?.jsonPrimitive?.contentOrNull,
        serverVersion = this["serverVersion"]?.jsonPrimitive?.contentOrNull,
        openSubsonic = this["openSubsonic"]?.jsonPrimitive?.booleanOrNull ?: false,
    )

    private fun notFound() = SubsonicException(SubsonicError.Api(ApiErrorKind.NotFound.code, null))

    /** Необязательные эндпоинты: ошибки API/HTTP превращаются в пустой результат. */
    private suspend fun <T> optional(block: suspend () -> List<T>): List<T> = try {
        block()
    } catch (e: SubsonicException) {
        when (e.error) {
            is SubsonicError.Api, is SubsonicError.Http, is SubsonicError.InvalidResponse -> emptyList()
            else -> throw e
        }
    }

    companion object {
        const val API_VERSION = "1.16.1"
        const val CLIENT_NAME = "BodroMusic"
    }
}

internal fun IOException.toNetworkKind(): NetworkErrorKind = when (this) {
    is SocketTimeoutException -> NetworkErrorKind.Timeout
    is InterruptedIOException -> if (message?.contains("timeout", ignoreCase = true) ==
        true
    ) {
        NetworkErrorKind.Timeout
    } else {
        NetworkErrorKind.Other
    }
    is UnknownHostException, is ConnectException, is NoRouteToHostException -> NetworkErrorKind.Unreachable
    is SSLException -> NetworkErrorKind.Ssl
    else -> NetworkErrorKind.Other
}
