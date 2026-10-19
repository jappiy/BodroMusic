package ru.bodro.music.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY createdAt")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY createdAt")
    suspend fun getAll(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun get(id: Long): AccountEntity?

    @Query(
        "SELECT * FROM accounts WHERE username = :username " +
            "AND IFNULL(localUrl, '') = IFNULL(:localUrl, '') AND IFNULL(externalUrl, '') = IFNULL(:externalUrl, '')",
    )
    suspend fun find(username: String, localUrl: String?, externalUrl: String?): AccountEntity?

    @Insert
    suspend fun insert(account: AccountEntity): Long

    @Upsert
    suspend fun upsert(account: AccountEntity)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ReactionDao {
    @Query("SELECT * FROM disliked_tracks WHERE accountId = :accountId ORDER BY createdAt DESC")
    fun observeDisliked(accountId: Long): Flow<List<DislikedTrackEntity>>

    @Query("SELECT * FROM disliked_tracks WHERE accountId = :accountId")
    suspend fun getDisliked(accountId: Long): List<DislikedTrackEntity>

    @Upsert
    suspend fun upsertDisliked(entity: DislikedTrackEntity)

    @Query("DELETE FROM disliked_tracks WHERE accountId = :accountId AND songId = :songId")
    suspend fun deleteDisliked(accountId: Long, songId: String)

    /** Полная замена дизлайков после синхронизации с сервером (кроме ещё не отправленных). */
    @Transaction
    suspend fun replaceSyncedDisliked(accountId: Long, entities: List<DislikedTrackEntity>) {
        deleteSyncedDisliked(accountId)
        entities.forEach { insertDislikedIgnore(it) }
    }

    @Query("DELETE FROM disliked_tracks WHERE accountId = :accountId AND syncState = 0")
    suspend fun deleteSyncedDisliked(accountId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDislikedIgnore(entity: DislikedTrackEntity)

    @Query("SELECT * FROM liked_tracks WHERE accountId = :accountId ORDER BY updatedAt DESC")
    fun observeLiked(accountId: Long): Flow<List<LikedTrackEntity>>

    @Query("SELECT * FROM liked_tracks WHERE accountId = :accountId ORDER BY updatedAt DESC")
    suspend fun getLiked(accountId: Long): List<LikedTrackEntity>

    @Upsert
    suspend fun upsertLiked(entity: LikedTrackEntity)

    @Query("DELETE FROM liked_tracks WHERE accountId = :accountId AND songId = :songId")
    suspend fun deleteLiked(accountId: Long, songId: String)

    @Transaction
    suspend fun replaceLiked(accountId: Long, entities: List<LikedTrackEntity>) {
        deleteAllLiked(accountId)
        entities.forEach { upsertLiked(it) }
    }

    @Query("DELETE FROM liked_tracks WHERE accountId = :accountId")
    suspend fun deleteAllLiked(accountId: Long)

    @Query("SELECT * FROM pending_reactions WHERE accountId = :accountId ORDER BY updatedAt")
    suspend fun getPending(accountId: Long): List<PendingReactionEntity>

    @Query("SELECT * FROM pending_reactions WHERE accountId = :accountId AND target = :target AND targetId = :targetId")
    suspend fun getPending(accountId: Long, target: String, targetId: String): PendingReactionEntity?

    @Query("SELECT COUNT(*) FROM pending_reactions WHERE accountId = :accountId")
    fun observePendingCount(accountId: Long): Flow<Int>

    @Upsert
    suspend fun upsertPending(entity: PendingReactionEntity)

    @Query("DELETE FROM pending_reactions WHERE accountId = :accountId AND target = :target AND targetId = :targetId")
    suspend fun deletePending(accountId: Long, target: String, targetId: String)
}

@Dao
interface ScrobbleDao {
    @Query("SELECT * FROM pending_scrobbles WHERE accountId = :accountId ORDER BY timestamp, id LIMIT :limit")
    suspend fun pending(accountId: Long, limit: Int): List<PendingScrobbleEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: PendingScrobbleEntity): Long

    @Query("DELETE FROM pending_scrobbles WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE pending_scrobbles SET attempts = attempts + 1 WHERE id = :id")
    suspend fun incrementAttempts(id: Long)

    @Query("SELECT COUNT(*) FROM pending_scrobbles WHERE accountId = :accountId")
    suspend fun count(accountId: Long): Int

    @Query("SELECT COUNT(*) FROM pending_scrobbles WHERE accountId = :accountId")
    fun observeCount(accountId: Long): Flow<Int>

    @Query("SELECT DISTINCT accountId FROM pending_scrobbles")
    suspend fun accountsWithPending(): List<Long>
}

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(entity: PlayHistoryEntity)

    @Query("SELECT * FROM play_history WHERE accountId = :accountId AND playedAt >= :sinceMs ORDER BY playedAt DESC")
    suspend fun since(accountId: Long, sinceMs: Long): List<PlayHistoryEntity>

    @Query("SELECT * FROM play_history WHERE accountId = :accountId AND skippedEarly = 0 ORDER BY playedAt DESC LIMIT :limit")
    fun observeRecent(accountId: Long, limit: Int): Flow<List<PlayHistoryEntity>>

    @Query(
        "SELECT songId, songJson, COUNT(*) AS plays FROM play_history " +
            "WHERE accountId = :accountId AND artistId = :artistId AND skippedEarly = 0 " +
            "GROUP BY songId ORDER BY plays DESC LIMIT :limit",
    )
    suspend fun topSongsOfArtist(accountId: Long, artistId: String, limit: Int): List<SongPlays>

    @Query("DELETE FROM play_history WHERE accountId = :accountId AND playedAt < :beforeMs")
    suspend fun deleteOlderThan(accountId: Long, beforeMs: Long)
}

data class SongPlays(val songId: String, val songJson: String, val plays: Int)

@Dao
interface QueueDao {
    @Query("SELECT * FROM queue_state WHERE accountId = :accountId")
    suspend fun get(accountId: Long): QueueStateEntity?

    @Upsert
    suspend fun upsert(entity: QueueStateEntity)

    @Query("UPDATE queue_state SET currentIndex = :index, positionMs = :positionMs, updatedAt = :updatedAt WHERE accountId = :accountId")
    suspend fun updatePosition(accountId: Long, index: Int, positionMs: Long, updatedAt: Long)

    @Query("DELETE FROM queue_state WHERE accountId = :accountId")
    suspend fun delete(accountId: Long)
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history WHERE accountId = :accountId ORDER BY updatedAt DESC LIMIT :limit")
    fun observe(accountId: Long, limit: Int): Flow<List<SearchHistoryEntity>>

    @Upsert
    suspend fun upsert(entity: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE accountId = :accountId AND queryText = :queryText")
    suspend fun delete(accountId: Long, queryText: String)

    @Query("DELETE FROM search_history WHERE accountId = :accountId")
    suspend fun clear(accountId: Long)
}

@Dao
interface LogDao {
    @Insert
    suspend fun insertAll(entries: List<LogEntryEntity>)

    @Query(
        "SELECT * FROM log_entries WHERE (accountId = :accountId OR accountId IS NULL) AND type = :type " +
            "AND (:errorsOnly = 0 OR isError = 1) " +
            "AND (:category IS NULL OR category = :category) " +
            "AND (:endpoint IS NULL OR endpoint = :endpoint) " +
            "AND (:query IS NULL OR endpoint LIKE '%' || :query || '%' OR url LIKE '%' || :query || '%' " +
            "OR message LIKE '%' || :query || '%') " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit",
    )
    fun observe(
        accountId: Long,
        type: String,
        errorsOnly: Boolean,
        category: String?,
        endpoint: String?,
        query: String?,
        limit: Int,
    ): Flow<List<LogEntryEntity>>

    @Query("SELECT * FROM log_entries WHERE id = :id")
    suspend fun get(id: Long): LogEntryEntity?

    @Query("SELECT DISTINCT endpoint FROM log_entries WHERE type = 'Network' ORDER BY endpoint")
    fun observeEndpoints(): Flow<List<String>>

    @Query("SELECT * FROM log_entries WHERE accountId = :accountId OR accountId IS NULL ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun export(accountId: Long, limit: Int): List<LogEntryEntity>

    @Query("SELECT COUNT(*) FROM log_entries")
    suspend fun count(): Int

    @Query("DELETE FROM log_entries WHERE timestamp < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long)

    @Query("DELETE FROM log_entries WHERE id NOT IN (SELECT id FROM log_entries ORDER BY timestamp DESC, id DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)

    @Query("DELETE FROM log_entries WHERE accountId = :accountId OR accountId IS NULL")
    suspend fun clear(accountId: Long)
}

@Dao
interface FeedbackDao {
    @Insert
    suspend fun insert(entity: PendingFeedbackEntity): Long

    @Query("SELECT * FROM pending_feedback WHERE accountId = :accountId ORDER BY id LIMIT :limit")
    suspend fun pending(accountId: Long, limit: Int): List<PendingFeedbackEntity>

    @Query("DELETE FROM pending_feedback WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM pending_feedback WHERE accountId = :accountId")
    suspend fun count(accountId: Long): Int

    /** Очередь не растёт бесконечно без сети или без bodrod: старые события отбрасываются. */
    @Query(
        "DELETE FROM pending_feedback WHERE accountId = :accountId AND id NOT IN " +
            "(SELECT id FROM pending_feedback WHERE accountId = :accountId ORDER BY id DESC LIMIT :keep)",
    )
    suspend fun trim(accountId: Long, keep: Int)
}
