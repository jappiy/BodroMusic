package ru.bodro.music.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.withTransaction

@Database(
    entities = [
        AccountEntity::class,
        DislikedTrackEntity::class,
        LikedTrackEntity::class,
        PendingReactionEntity::class,
        PendingScrobbleEntity::class,
        PlayHistoryEntity::class,
        QueueStateEntity::class,
        SearchHistoryEntity::class,
        LogEntryEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class BodroDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao

    abstract fun reactionDao(): ReactionDao

    abstract fun scrobbleDao(): ScrobbleDao

    abstract fun historyDao(): HistoryDao

    abstract fun queueDao(): QueueDao

    abstract fun searchHistoryDao(): SearchHistoryDao

    abstract fun logDao(): LogDao

    /** Удаляет все локальные данные учётки (при выходе). */
    suspend fun clearAccountData(accountId: Long) {
        withTransaction {
            val sql = openHelper.writableDatabase
            ACCOUNT_TABLES.forEach { table -> sql.execSQL("DELETE FROM $table WHERE accountId = ?", arrayOf<Any>(accountId)) }
        }
    }

    companion object {
        const val NAME = "bodro.db"

        private val ACCOUNT_TABLES = listOf(
            "disliked_tracks",
            "liked_tracks",
            "pending_reactions",
            "pending_scrobbles",
            "play_history",
            "queue_state",
            "search_history",
            "log_entries",
        )
    }
}
