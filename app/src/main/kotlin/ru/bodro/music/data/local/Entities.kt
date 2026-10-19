package ru.bodro.music.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "accounts", indices = [Index(value = ["username", "localUrl", "externalUrl"], unique = true)])
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val username: String,
    val localUrl: String?,
    val externalUrl: String?,
    val serverVersion: String? = null,
    val openSubsonic: Boolean = false,
    /** Имена расширений OpenSubsonic через запятую. */
    val extensions: String = "",
    val createdAt: Long,
)

/** Дизлайкнутые треки пользователя (локальная копия, источник правды — рейтинг 1 на сервере). */
@Entity(tableName = "disliked_tracks", primaryKeys = ["accountId", "songId"])
data class DislikedTrackEntity(
    val accountId: Long,
    val songId: String,
    val artistId: String?,
    /** Song в JSON — чтобы показывать список «Не нравится» без сети. */
    val songJson: String,
    val createdAt: Long,
    val syncState: Int = SYNCED,
) {
    companion object {
        const val SYNCED = 0
        const val PENDING = 1
    }
}

/** Кэш лайкнутых треков (для волны и «Мне нравится» без сети). */
@Entity(tableName = "liked_tracks", primaryKeys = ["accountId", "songId"])
data class LikedTrackEntity(
    val accountId: Long,
    val songId: String,
    val songJson: String,
    val updatedAt: Long,
)

@Entity(tableName = "pending_reactions", primaryKeys = ["accountId", "target", "targetId"])
data class PendingReactionEntity(
    val accountId: Long,
    val target: String,
    val targetId: String,
    val serverState: String,
    val desiredState: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "pending_scrobbles",
    indices = [Index(value = ["accountId", "songId", "timestamp"], unique = true), Index("accountId")],
)
data class PendingScrobbleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val songId: String,
    val timestamp: Long,
    val attempts: Int = 0,
)

@Entity(tableName = "play_history", indices = [Index(value = ["accountId", "playedAt"]), Index(value = ["accountId", "songId"])])
data class PlayHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val songId: String,
    val artistId: String?,
    val albumId: String?,
    val genre: String?,
    val playedAt: Long,
    val listenedMs: Long,
    val skippedEarly: Boolean,
    /** Song в JSON — для «Недавно прослушанного». */
    val songJson: String,
)

/** Очередь учётки. Позиция хранится отдельно — её обновление раз в 5 с не переписывает весь список. */
@Entity(tableName = "queue_state")
data class QueueStateEntity(
    @PrimaryKey val accountId: Long,
    val snapshotJson: String,
    val currentIndex: Int,
    val positionMs: Long,
    val updatedAt: Long,
)

@Entity(tableName = "search_history", primaryKeys = ["accountId", "queryText"])
data class SearchHistoryEntity(
    val accountId: Long,
    val queryText: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "log_entries",
    indices = [Index(value = ["accountId", "timestamp"]), Index("timestamp")],
)
data class LogEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long?,
    val timestamp: Long,
    /** [LogType]. */
    val type: String,
    /** Для сети — RequestCategory; для плеера — тип события. */
    val category: String,
    val endpoint: String,
    val serverKind: String?,
    val method: String?,
    val url: String?,
    val httpCode: Int?,
    val subsonicStatus: String?,
    val errorCode: Int?,
    val message: String?,
    val durationMs: Long?,
    val size: Long?,
    val requestHeaders: String?,
    val responseHeaders: String?,
    val body: String?,
    val isError: Boolean,
)

enum class LogType { Network, Player }

/** Событие прослушивания для рекомендаций bodrod (офлайн-очередь): [payload] — `FeedbackEvent` в JSON. */
@Entity(tableName = "pending_feedback", indices = [Index("accountId")])
data class PendingFeedbackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val payload: String,
    val createdAt: Long,
)
