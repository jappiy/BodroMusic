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
import ru.bodro.music.core.model.PlaylistDetails
import ru.bodro.music.core.model.Song
import ru.bodro.music.core.subsonic.SubsonicClient
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

/** Плейлисты: редактировать можно только свои; чужие публичные — только чтение. */
@Singleton
class PlaylistRepository @Inject constructor(
    private val client: SubsonicClient,
    private val connection: ConnectionManager,
    private val reactions: ReactionRepository,
) {
    suspend fun playlists(): List<Playlist> = client.getPlaylists()

    suspend fun myPlaylists(): List<Playlist> = playlists().filter { isEditable(it) }

    suspend fun playlist(id: String): PlaylistDetails = client.getPlaylist(id).also { reactions.ingest(it.songs) }

    fun isEditable(playlist: Playlist): Boolean = playlist.isEditableBy(connection.activeAccount?.username.orEmpty())

    suspend fun create(name: String, songs: List<Song> = emptyList()): Playlist? = client.createPlaylist(name, songs.map { it.id })

    suspend fun rename(id: String, name: String) = client.updatePlaylist(id, name = name)

    suspend fun delete(id: String) = client.deletePlaylist(id)

    suspend fun addSongs(id: String, songs: List<Song>) = client.updatePlaylist(id, songIdsToAdd = songs.map { it.id })

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
