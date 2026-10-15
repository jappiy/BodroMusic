package ru.bodro.music.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import ru.bodro.music.core.domain.scrobble.PlaySession
import ru.bodro.music.core.domain.wave.PlayHistoryItem
import ru.bodro.music.core.model.Song
import ru.bodro.music.data.account.ConnectionManager
import ru.bodro.music.data.account.SessionState
import ru.bodro.music.data.local.BodroDatabase
import ru.bodro.music.data.local.PlayHistoryEntity
import ru.bodro.music.di.Clock

/** Локальная история прослушиваний (для «Недавно прослушанного», топа исполнителя и волны). */
@Singleton
class HistoryRepository @Inject constructor(
    database: BodroDatabase,
    private val connection: ConnectionManager,
    private val clock: Clock,
) {
    private val dao = database.historyDao()

    suspend fun record(accountId: Long, song: Song, session: PlaySession) {
        // Совсем короткие «касания» (< 3 с) без скипа не пишем.
        if (session.listenedMs < MIN_LISTEN_MS && !session.skippedEarly) return
        dao.insert(
            PlayHistoryEntity(
                accountId = accountId,
                songId = song.id,
                artistId = song.artistId,
                albumId = song.albumId,
                genre = song.genre,
                playedAt = session.startedAtMs,
                listenedMs = session.listenedMs,
                skippedEarly = session.skippedEarly,
                songJson = AppJson.encodeSong(song),
            ),
        )
        dao.deleteOlderThan(accountId, clock.now() - RETENTION_MS)
    }

    suspend fun items(sinceMs: Long): List<PlayHistoryItem> {
        val accountId = connection.activeAccountId ?: return emptyList()
        return dao.since(accountId, sinceMs).map {
            PlayHistoryItem(it.songId, it.artistId, it.genre, it.playedAt, it.skippedEarly)
        }
    }

    suspend fun songsSince(sinceMs: Long): List<Song> {
        val accountId = connection.activeAccountId ?: return emptyList()
        return dao.since(accountId, sinceMs).filter { !it.skippedEarly }.mapNotNull { AppJson.decodeSong(it.songJson) }
    }

    /** Недавно прослушанные треки (без повторов). */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeRecentSongs(limit: Int = 30): Flow<List<Song>> = connection.session.flatMapLatest { session ->
        val id = (session as? SessionState.Active)?.account?.id ?: return@flatMapLatest flowOf(emptyList())
        dao.observeRecent(id, limit * 3).map { rows ->
            rows.distinctBy { it.songId }.take(limit).mapNotNull { AppJson.decodeSong(it.songJson) }
        }
    }

    suspend fun topSongsOfArtist(artistId: String, limit: Int): List<Song> {
        val accountId = connection.activeAccountId ?: return emptyList()
        return dao.topSongsOfArtist(accountId, artistId, limit).mapNotNull { AppJson.decodeSong(it.songJson) }
    }

    private companion object {
        const val MIN_LISTEN_MS = 3_000L
        const val RETENTION_MS = 365L * 24 * 60 * 60 * 1000
    }
}
