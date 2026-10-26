package ru.bodro.music.data.log

import android.content.Context
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.bodro.music.core.subsonic.NetworkLogEntry
import ru.bodro.music.core.subsonic.NetworkLogLevel
import ru.bodro.music.core.subsonic.NetworkLogSink
import ru.bodro.music.core.subsonic.SecretMasker
import ru.bodro.music.data.account.ConnectionManager
import ru.bodro.music.data.local.BodroDatabase
import ru.bodro.music.data.local.LogEntryEntity
import ru.bodro.music.data.local.LogType
import ru.bodro.music.data.settings.SettingsRepository
import ru.bodro.music.di.AppScope
import ru.bodro.music.di.Clock

/** События плеера для вкладки «События плеера». */
enum class PlayerEvent {
    TrackChanged,
    PlayerError,
    ServerSwitched,
    ScrobbleSent,
    ScrobbleQueued,
    ReactionSynced,
    ReactionQueued,
    Sync,
    Wave,
    Session,

    /** Открытие потока трека и перемотка: URL без токенов, формат, кэш или сеть, известна ли длина. */
    Stream,
}

data class LogFilter(
    val type: LogType = LogType.Network,
    val errorsOnly: Boolean = false,
    val category: String? = null,
    val endpoint: String? = null,
    val query: String? = null,
)

/**
 * Журнал Navidrome: запросы (из [ru.bodro.music.core.subsonic.NavidromeLogInterceptor]) и события плеера.
 * Кольцевой буфер в Room: [MAX_ENTRIES] записей или [MAX_AGE_MS]. Пишется пачками в фоне.
 */
@Singleton
class LogRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: BodroDatabase,
    private val settings: SettingsRepository,
    private val connection: Lazy<ConnectionManager>,
    private val clock: Clock,
    @AppScope private val scope: CoroutineScope,
) : NetworkLogSink {
    private val dao = database.logDao()
    private val queue = Channel<LogEntryEntity>(capacity = Channel.UNLIMITED)
    private var insertedSinceTrim = 0

    init {
        scope.launch(Dispatchers.IO) {
            trim()
            val batch = mutableListOf<LogEntryEntity>()
            while (true) {
                batch += queue.receive()
                while (true) batch += queue.tryReceive().getOrNull() ?: break
                runCatching { dao.insertAll(batch) }
                insertedSinceTrim += batch.size
                batch.clear()
                if (insertedSinceTrim >= TRIM_EVERY) {
                    insertedSinceTrim = 0
                    runCatching { trim() }
                }
            }
        }
    }

    override fun log(entry: NetworkLogEntry) {
        queue.trySend(
            LogEntryEntity(
                accountId = activeAccountId(),
                timestamp = entry.timestampMs,
                type = LogType.Network.name,
                category = entry.category.name,
                endpoint = entry.endpoint,
                serverKind = entry.serverKind?.name,
                method = entry.method,
                url = entry.maskedUrl,
                httpCode = entry.httpCode,
                subsonicStatus = entry.subsonicStatus,
                errorCode = entry.errorCode,
                message = entry.errorMessage?.let(SecretMasker::maskText),
                durationMs = entry.durationMs,
                size = entry.responseSize,
                requestHeaders = entry.requestHeaders,
                responseHeaders = entry.responseHeaders,
                body = entry.responseBody,
                isError = entry.isError,
            ),
        )
    }

    /** Событие плеера / синхронизации. Текст маскируется. */
    fun event(event: PlayerEvent, message: String, isError: Boolean = false, details: String? = null) {
        if (settings.current.logLevel == NetworkLogLevel.ErrorsOnly && !isError) return
        queue.trySend(
            LogEntryEntity(
                accountId = activeAccountId(),
                timestamp = clock.now(),
                type = LogType.Player.name,
                category = event.name,
                endpoint = event.name,
                serverKind = connection.get().selection.value?.kind?.name,
                method = null,
                url = null,
                httpCode = null,
                subsonicStatus = null,
                errorCode = null,
                message = SecretMasker.maskText(message),
                durationMs = null,
                size = null,
                requestHeaders = null,
                responseHeaders = null,
                body = details?.let(SecretMasker::maskText),
                isError = isError,
            ),
        )
    }

    fun observe(filter: LogFilter, limit: Int = PAGE): Flow<List<LogEntryEntity>> = dao.observe(
        accountId = activeAccountId() ?: -1,
        type = filter.type.name,
        errorsOnly = filter.errorsOnly,
        category = filter.category,
        endpoint = filter.endpoint,
        query = filter.query?.takeIf { it.isNotBlank() },
        limit = limit,
    )

    fun observeEndpoints(): Flow<List<String>> = dao.observeEndpoints()

    suspend fun get(id: Long): LogEntryEntity? = dao.get(id)

    suspend fun clear() = dao.clear(activeAccountId() ?: -1)

    /** Текстовый файл для «Поделиться логами». Секреты замаскированы ещё при записи; маскируем повторно. */
    suspend fun exportToFile(header: String): File = withContext(Dispatchers.IO) {
        val entries = dao.export(activeAccountId() ?: -1, MAX_ENTRIES)
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(clock.now()))
        val file = File(dir, "bodromusic-logs-$stamp.txt")
        file.bufferedWriter().use { out ->
            out.appendLine(header)
            out.appendLine()
            entries.forEach { out.appendLine(SecretMasker.maskText(format(it))) }
        }
        file
    }

    private fun format(e: LogEntryEntity): String = buildString {
        append(TIME_FORMAT.get()!!.format(Date(e.timestamp)))
        append(" [").append(e.type).append('/').append(e.category).append("] ")
        append(e.endpoint)
        e.serverKind?.let { append(" via ").append(it) }
        e.httpCode?.let { append(" HTTP ").append(it) }
        e.subsonicStatus?.let { append(" status=").append(it) }
        e.errorCode?.let { append(" code=").append(it) }
        e.durationMs?.let { append(' ').append(it).append("ms") }
        e.size?.let { append(' ').append(it).append('B') }
        e.message?.let { append(" — ").append(it) }
        e.url?.let { append("\n    ").append(it) }
    }

    private suspend fun trim() {
        dao.deleteOlderThan(clock.now() - MAX_AGE_MS)
        if (dao.count() > MAX_ENTRIES) dao.trimTo(MAX_ENTRIES)
    }

    private fun activeAccountId(): Long? = runCatching { connection.get().activeAccountId }.getOrNull()

    companion object {
        const val MAX_ENTRIES = 5_000
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        const val PAGE = 500
        private const val TRIM_EVERY = 200
        private val TIME_FORMAT = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US) }
    }
}
