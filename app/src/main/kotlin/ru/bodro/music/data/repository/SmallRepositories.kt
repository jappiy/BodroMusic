package ru.bodro.music.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import ru.bodro.music.core.domain.changelog.ChangelogParser
import ru.bodro.music.core.domain.changelog.ChangelogRelease
import ru.bodro.music.core.model.Playlist
import ru.bodro.music.core.model.PlaylistAccess
import ru.bodro.music.core.model.PlaylistDetails
import ru.bodro.music.core.model.Song
import ru.bodro.music.core.subsonic.ApiErrorKind
import ru.bodro.music.core.subsonic.SubsonicClient
import ru.bodro.music.core.subsonic.SubsonicError
import ru.bodro.music.core.subsonic.SubsonicException
import ru.bodro.music.data.account.ConnectionManager
import ru.bodro.music.data.account.SessionState
import ru.bodro.music.data.local.BodroDatabase
import ru.bodro.music.data.local.SearchHistoryEntity
import ru.bodro.music.di.Clock

@Singleton
class SearchHistoryRepository @Inject constructor(
    database: BodroDatabase,
    private val connection: ConnectionManager,
    private val clock: Clock,
) {
    private val dao = database.searchHistoryDao()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(limit: Int = 12): Flow<List<String>> = connection.session.flatMapLatest { session ->
        val id = (session as? SessionState.Active)?.account?.id ?: return@flatMapLatest flowOf(emptyList())
        dao.observe(id, limit).map { list -> list.map { it.queryText } }
    }

    suspend fun add(query: String) {
        val id = connection.activeAccountId ?: return
        val trimmed = query.trim()
        if (trimmed.length >= 2) dao.upsert(SearchHistoryEntity(id, trimmed, clock.now()))
    }

    suspend fun remove(query: String) {
        dao.delete(connection.activeAccountId ?: return, query)
    }

    suspend fun clear() {
        dao.clear(connection.activeAccountId ?: return)
    }
}

/**
 * Плейлисты принадлежат создателю. Свои — полное редактирование; чужие публичные («Общие») — только чтение
 * и «Сохранить себе»; чужие приватные (их видит администратор Navidrome) не показываются.
 * «Поделиться» = сделать публичным (Navidrome не умеет делиться с конкретным пользователем).
 */
@Singleton
class PlaylistRepository @Inject constructor(
    private val client: SubsonicClient,
    private val connection: ConnectionManager,
    private val reactions: ReactionRepository,
    private val pool: dagger.Lazy<ru.bodro.music.data.reco.PoolRepository>,
) {
    /** Свои и общие (чужие публичные). */
    suspend fun playlists(): List<Playlist> = client.getPlaylists().filter { access(it) != PlaylistAccess.Hidden }

    suspend fun myPlaylists(): List<Playlist> = client.getPlaylists().filter { access(it) == PlaylistAccess.Own }

    suspend fun playlist(id: String): PlaylistDetails {
        val details = client.getPlaylist(id)
        // Чужой приватный открыт по ссылке (например, администратором) — не показываем.
        if (access(details.playlist) == PlaylistAccess.Hidden) throw SubsonicException(SubsonicError.Api(ApiErrorKind.NotFound.code, null))
        reactions.ingest(details.songs)
        return details
    }

    fun access(playlist: Playlist): PlaylistAccess = playlist.accessFor(connection.activeAccount?.username.orEmpty())

    fun isEditable(playlist: Playlist): Boolean = access(playlist) == PlaylistAccess.Own

    /** Новый плейлист — от текущей учётки и приватный. */
    suspend fun create(name: String, songs: List<Song> = emptyList()): Playlist? =
        client.createPlaylist(name, songs.map { it.id }).also { created ->
            if (created != null && created.public) client.updatePlaylist(created.id, public = false)
            songs.filter { pool.get().isPoolSong(it.id) }.forEach { pool.get().track(it, "playlisted", "playlist") }
        }?.copy(public = false)

    /** Плейлист из готового списка id песен (выгрузка Яндекса) — от текущей учётки, приватный. */
    suspend fun createWithIds(name: String, songIds: List<String>): Playlist? =
        client.createPlaylist(name, songIds).also { created ->
            if (created != null && created.public) client.updatePlaylist(created.id, public = false)
        }

    /** «Сохранить себе»: копия чужого общего плейлиста в своей учётке. */
    suspend fun saveCopy(id: String): Playlist? {
        val details = client.getPlaylist(id)
        return create(details.playlist.name, details.songs)
    }

    /** Поделиться (публичный) / перестать делиться (приватный). */
    suspend fun setShared(id: String, shared: Boolean) = client.updatePlaylist(id, public = shared)

    /** Новый порядок треков: состав заменяется целиком. */
    suspend fun reorder(id: String, songs: List<Song>) = client.replacePlaylistSongs(id, songs.map { it.id })

    suspend fun rename(id: String, name: String) = client.updatePlaylist(id, name = name)

    suspend fun delete(id: String) = client.deletePlaylist(id)

    suspend fun addSongs(id: String, songs: List<Song>) {
        client.updatePlaylist(id, songIdsToAdd = songs.map { it.id })
        // Трек пула «На пробу», добавленный в плейлист, переходит в библиотеку.
        songs.filter { pool.get().isPoolSong(it.id) }.forEach { pool.get().track(it, "playlisted", "playlist") }
    }

    suspend fun removeAt(id: String, index: Int) = client.updatePlaylist(id, songIndexesToRemove = listOf(index))
}

@Singleton
class ChangelogRepository @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun releases(): List<ChangelogRelease> = withContext(Dispatchers.IO) {
        val text = runCatching { context.assets.open(ASSET).bufferedReader().use { it.readText() } }.getOrDefault("")
        ChangelogParser.parse(text).filterNot { it.isUnreleased && it.sections.isEmpty() }
    }

    private companion object {
        const val ASSET = "CHANGELOG.md"
    }
}
