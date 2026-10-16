package ru.bodro.music

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import ru.bodro.music.data.account.ConnectionManager
import ru.bodro.music.data.downloads.DownloadWatchScheduler
import ru.bodro.music.data.offline.OfflineScheduler
import ru.bodro.music.sync.SyncScheduler
import ru.bodro.music.ui.image.ImageLoaderFactory

@HiltAndroidApp
class BodroApp :
    Application(),
    Configuration.Provider,
    SingletonImageLoader.Factory {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var connectionManager: ConnectionManager

    @Inject lateinit var syncScheduler: SyncScheduler

    @Inject lateinit var downloadWatchScheduler: DownloadWatchScheduler

    @Inject lateinit var imageLoaderFactory: ImageLoaderFactory

    @Inject lateinit var offlineScheduler: OfflineScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        connectionManager.start()
        syncScheduler.start()
        // Заявки могли завершиться, пока приложение было закрыто, — проверить и уведомить.
        downloadWatchScheduler.watch()
        offlineScheduler.start()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoaderFactory.create(context)
}
