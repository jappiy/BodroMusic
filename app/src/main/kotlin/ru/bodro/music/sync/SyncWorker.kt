package ru.bodro.music.sync

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
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import ru.bodro.music.data.account.ConnectionManager
import ru.bodro.music.data.log.LogRepository
import ru.bodro.music.data.log.PlayerEvent
import ru.bodro.music.data.repository.ReactionRepository
import ru.bodro.music.data.repository.ScrobbleRepository

/** Отправка отложенных скробблов и изменений лайков/дизлайков, когда появилась сеть. */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val scrobbles: ScrobbleRepository,
    private val reactions: ReactionRepository,
    private val connection: ConnectionManager,
    private val log: LogRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (connection.activeAccountId == null) return Result.success()
        connection.reselect()
        val reactionsDone = runCatching { reactions.syncPending() }.getOrDefault(false)
        val scrobbleResult = runCatching { scrobbles.flush() }.getOrNull()
        val retry = !reactionsDone || scrobbleResult?.shouldRetry == true
        log.event(
            PlayerEvent.Sync,
            "Синхронизация: реакции ${if (reactionsDone) "отправлены" else "ждут"}, скробблов осталось ${scrobbleResult?.remaining ?: "?"}",
        )
        return if (retry) Result.retry() else Result.success()
    }
}

@Singleton
class SyncScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun start() {
        val periodic = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)
    }

    /** Отправить сразу, как появится сеть (с экспоненциальными повторами). */
    fun requestSync() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.KEEP, request)
    }

    /** «Отправить сейчас» на экране логов. */
    fun syncNow() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.REPLACE, request)
    }

    private companion object {
        const val PERIODIC = "bodro-sync-periodic"
        const val ONE_TIME = "bodro-sync-now"
    }
}
