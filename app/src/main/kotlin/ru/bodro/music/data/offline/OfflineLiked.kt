package ru.bodro.music.data.offline

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.bodro.music.core.subsonic.SubsonicClient
import ru.bodro.music.data.account.ConnectionManager
import ru.bodro.music.data.log.LogRepository
import ru.bodro.music.data.log.PlayerEvent
import ru.bodro.music.data.repository.LibraryRepository
import ru.bodro.music.data.repository.ReactionRepository
import ru.bodro.music.data.settings.SettingsRepository
import ru.bodro.music.di.AppScope

/**
 * Скачанные лайкнутые треки: `files/offline/<accountId>/<songId>` (оригинальный файл без перекодирования).
 * Наличие файла = трек скачан; плеер берёт его вместо потока (см. `StreamDataSourceFactory`).
 */
@Singleton
class OfflineStore @Inject constructor(@ApplicationContext context: Context) {
    private val root = File(context.filesDir, "offline")

    fun dir(accountId: Long): File = File(root, accountId.toString())

    fun file(accountId: Long, songId: String): File = File(dir(accountId), songId.replace(UNSAFE, "_"))

    /** Скачанный файл трека или null. */
    fun localFile(accountId: Long, songId: String): File? = file(accountId, songId).takeIf { it.isFile && it.length() > 0 }

    suspend fun usage(): OfflineUsage = withContext(Dispatchers.IO) {
        val files = root.walkTopDown().filter { it.isFile && !it.name.endsWith(TMP) }.toList()
        OfflineUsage(files.size, files.sumOf { it.length() })
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) { root.deleteRecursively() }

    private companion object {
        val UNSAFE = Regex("[^A-Za-z0-9_.-]")
        const val TMP = ".part"
    }
}

data class OfflineUsage(val count: Int, val bytes: Long)

/** Докачивает недостающие лайкнутые треки и удаляет файлы треков, с которых лайк снят. Только по Wi-Fi. */
@HiltWorker
class OfflineSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val store: OfflineStore,
    private val settings: SettingsRepository,
    private val connection: ConnectionManager,
    private val library: LibraryRepository,
    private val reactions: ReactionRepository,
    private val client: SubsonicClient,
    private val http: OkHttpClient,
    private val log: LogRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!settings.current.downloadLiked) return@withContext Result.success()
        val accountId = connection.activeAccountId ?: return@withContext Result.success()
        connection.reselect()
        // Свежий список лайков с сервера (попадает в кэш Room); без сети — то, что уже в кэше.
        val fresh = runCatching { library.starred() }.isSuccess
        val liked = reactions.likedSongs().map { it.id }.toSet()
        // Пустой кэш без ответа сервера — не повод удалять скачанное.
        if (liked.isEmpty() && !fresh) return@withContext Result.retry()

        val dir = store.dir(accountId).apply { mkdirs() }
        val keep = liked.map { store.file(accountId, it).name }.toSet()
        dir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }

        var downloaded = 0
        var failed = 0
        val missing = liked.filter { store.localFile(accountId, it) == null }
        for (songId in missing) {
            if (isStopped || !settings.current.downloadLiked) break
            if (download(accountId, songId)) downloaded++ else failed++
        }
        if (downloaded > 0 || failed > 0) {
            log.event(
                PlayerEvent.Sync,
                "Офлайн: скачано $downloaded, ошибок $failed, всего лайков ${liked.size}",
                isError = failed > 0,
            )
        }
        if (failed > 0) Result.retry() else Result.success()
    }

    private fun download(accountId: Long, songId: String): Boolean {
        val target = store.file(accountId, songId)
        val tmp = File(target.parentFile, target.name + ".part")
        return try {
            val url = client.streamUrl(songId, maxBitRate = 0, format = "raw")
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val type = response.header("Content-Type").orEmpty()
                // Ошибка Subsonic приходит как JSON/XML с кодом 200 — такой ответ не сохраняем.
                if (type.contains("json") || type.contains("xml")) throw IOException("Сервер вернул ошибку вместо файла")
                tmp.outputStream().use { out -> response.body.byteStream().copyTo(out) }
            }
            tmp.renameTo(target)
        } catch (e: Exception) {
            tmp.delete()
            log.event(PlayerEvent.Sync, "Офлайн: не удалось скачать $songId: ${e.message}", isError = true)
            false
        }
    }
}

@Singleton
class OfflineScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    @AppScope private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val reactions: ReactionRepository,
    private val store: OfflineStore,
) {
    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresStorageNotLow(true)
        .build()

    /** Следит за настройкой и списком лайков: включено — докачать, выключено — удалить скачанное. */
    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            combine(settings.settings.map { it.downloadLiked }.distinctUntilChanged(), reactions.observeLikedCache()) { enabled, liked ->
                enabled to liked.map { it.id }.toSet()
            }
                .distinctUntilChanged()
                .debounce(DEBOUNCE_MS)
                .collect { (enabled, _) ->
                    if (enabled) {
                        request()
                        schedulePeriodic()
                    } else {
                        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
                        WorkManager.getInstance(context).cancelUniqueWork(ONE_TIME)
                        store.clearAll()
                    }
                }
        }
    }

    private fun request() {
        val request = OneTimeWorkRequestBuilder<OfflineSyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private fun schedulePeriodic() {
        val periodic = PeriodicWorkRequestBuilder<OfflineSyncWorker>(6, TimeUnit.HOURS).setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)
    }

    private companion object {
        const val ONE_TIME = "bodro-offline-now"
        const val PERIODIC = "bodro-offline-periodic"
        const val DEBOUNCE_MS = 2_000L
    }
}
