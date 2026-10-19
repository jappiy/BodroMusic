package ru.bodro.music.data.downloads

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import ru.bodro.music.MainActivity
import ru.bodro.music.R
import ru.bodro.music.core.downloads.DownloadRequest
import ru.bodro.music.core.downloads.DownloadWatch
import ru.bodro.music.core.downloads.DownloadsError
import ru.bodro.music.core.downloads.DownloadsException
import ru.bodro.music.core.downloads.RequestKind
import ru.bodro.music.core.downloads.RequestStatus
import ru.bodro.music.data.account.ConnectionManager
import ru.bodro.music.data.log.LogRepository
import ru.bodro.music.data.log.PlayerEvent

/**
 * Опрашивает сервис загрузок, пока есть активные заявки, и показывает уведомление, когда трек скачан.
 * Один запуск живёт до ~8 минут (лимит WorkManager — 10), затем ставит себя в очередь снова.
 */
@HiltWorker
class DownloadWatchWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val downloads: DownloadsRepository,
    private val connection: ConnectionManager,
    private val notifications: DownloadNotifications,
    private val scheduler: DownloadWatchScheduler,
    private val log: LogRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (connection.activeAccountId == null || !downloads.isConfigured) return Result.success()
        val started = System.currentTimeMillis()
        var known = downloads.knownStatuses()
        while (true) {
            val current = try {
                downloads.refresh()
            } catch (e: DownloadsException) {
                return when (e.error) {
                    DownloadsError.NotConfigured, DownloadsError.Unauthorized -> Result.success()
                    else -> Result.retry()
                }
            }
            DownloadWatch.finished(known, current).forEach { request ->
                notifications.show(request)
                log.event(PlayerEvent.Sync, "Загрузка: ${request.label()} — ${request.status.name}: ${request.message.orEmpty()}")
            }
            known = DownloadWatch.snapshot(current)
            downloads.saveKnownStatuses(known)
            if (!DownloadWatch.hasActive(current)) return Result.success()
            if (System.currentTimeMillis() - started > RUN_LIMIT_MS) {
                scheduler.watch(delaySeconds = POLL_SECONDS)
                return Result.success()
            }
            delay(TimeUnit.SECONDS.toMillis(POLL_SECONDS))
        }
    }

    private companion object {
        const val POLL_SECONDS = 15L
        val RUN_LIMIT_MS = TimeUnit.MINUTES.toMillis(8)
    }
}

fun DownloadRequest.label(): String = (title ?: album)?.let { "$artist — $it" } ?: artist

@Singleton
class DownloadWatchScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Следить за заявками. Если наблюдатель уже работает, новый встанет за ним в цепочку. */
    fun watch(delaySeconds: Long = 0) {
        val request = OneTimeWorkRequestBuilder<DownloadWatchWorker>()
            .setConstraints(constraints)
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private companion object {
        const val NAME = "bodro-downloads-watch"
    }
}

@Singleton
class DownloadNotifications @Inject constructor(@ApplicationContext private val context: Context) {
    fun show(request: DownloadRequest) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel()
        val ok = request.status == RequestStatus.Completed
        val title = context.getString(
            when {
                !ok -> R.string.downloads_notification_failed
                request.kind == RequestKind.Artist -> R.string.downloads_notification_artist_done
                request.kind == RequestKind.Album -> R.string.downloads_notification_album_done
                else -> R.string.downloads_notification_track_done
            },
        )
        val text = listOfNotNull(request.label(), request.message?.takeIf { !ok || request.kind != RequestKind.Track })
            .joinToString(" · ")
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply {
                if (ok && request.songId != null) {
                    putExtra(MainActivity.EXTRA_PLAY_SONG_ID, request.songId)
                } else {
                    putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true)
                }
            }
        val pending = PendingIntent.getActivity(
            context,
            request.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(request.id.hashCode(), notification)
        } catch (_: SecurityException) {
            // Разрешение отозвали между проверкой и показом — молча пропускаем.
        }
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.downloads_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private companion object {
        const val CHANNEL_ID = "downloads"
    }
}
