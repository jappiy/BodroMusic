package ru.bodro.music.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import ru.bodro.music.MainActivity
import ru.bodro.music.R
import ru.bodro.music.core.model.Reaction

/**
 * Фоновое воспроизведение: ExoPlayer + MediaLibrarySession (готово к Android Auto).
 * Уведомление, экран блокировки, гарнитура, Bluetooth — через Media3. Аудиофокус и
 * «становится шумно» (отключение наушников) обрабатывает ExoPlayer.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {
    @Inject lateinit var dataSources: StreamDataSourceFactory

    @Inject lateinit var deps: PlaybackDeps

    @Inject lateinit var http: OkHttpClient

    private val scope = MainScope()
    private var session: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private lateinit var coordinator: PlaybackCoordinator
    private var prefetcher: StreamPrefetcher? = null
    private var currentReaction: Reaction? = null

    override fun onCreate() {
        super.onCreate()
        // Аудиопроцессор спектра — для анимированного фона «Моей волны» (без разрешения на микрофон).
        player = ExoPlayer.Builder(this, SpectrumRenderersFactory(this))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources.create()))
            .setLoadControl(fastStartLoadControl())
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        @Suppress("DEPRECATION")
        val bitmapLoader = CacheBitmapLoader(
            DataSourceBitmapLoader(
                MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()),
                OkHttpDataSource.Factory(http),
            ),
        )

        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(sessionActivity())
            .setBitmapLoader(bitmapLoader)
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_notification) },
        )

        coordinator = PlaybackCoordinator(player, scope, deps) { reaction ->
            if (reaction != currentReaction) {
                currentReaction = reaction
                updateLayout()
            }
        }
        coordinator.start()
        prefetcher = StreamPrefetcher(player, dataSources, scope).also(player::addListener)
    }

    /** Старт после 0,5 с буфера вместо 2,5 с по умолчанию: новый трек начинает играть почти сразу. */
    private fun fastStartLoadControl() = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
            DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
            START_BUFFER_MS,
            REBUFFER_MS,
        )
        .build()

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Смахнули приложение: если музыка играет — продолжаем, иначе останавливаем сервис.
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        prefetcher?.release()
        coordinator.release()
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private fun sessionActivity(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    @Suppress("DEPRECATION")
    private fun likeButton(): CommandButton {
        val liked = currentReaction == Reaction.Liked
        return CommandButton.Builder()
            .setDisplayName(getString(if (liked) R.string.action_unlike else R.string.action_like))
            .setIconResId(if (liked) R.drawable.ic_heart_filled else R.drawable.ic_heart)
            .setSessionCommand(LIKE_COMMAND)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun updateLayout() {
        val layout: ImmutableList<CommandButton> = if (currentReaction == null) ImmutableList.of() else ImmutableList.of(likeButton())
        session?.setCustomLayout(layout)
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        @Suppress("DEPRECATION")
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(LIKE_COMMAND)
                .build()
            val layout: ImmutableList<CommandButton> = if (currentReaction == null) ImmutableList.of() else ImmutableList.of(likeButton())
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setCustomLayout(layout)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == ACTION_LIKE) {
                val song = coordinator.currentSong
                if (song != null) scope.launch { deps.reactions.toggleLike(song) }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map(deps.items::restoreUri).toMutableList())

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch {
                val snapshot = coordinator.snapshotForResumption()
                if (snapshot == null || snapshot.songs.isEmpty()) {
                    future.setException(UnsupportedOperationException("Нет сохранённой очереди"))
                } else {
                    deps.sources.set(snapshot.source)
                    future.set(
                        MediaSession.MediaItemsWithStartPosition(
                            deps.items.create(snapshot.songs),
                            snapshot.currentIndex,
                            snapshot.positionMs,
                        ),
                    )
                }
            }
            return future
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(getString(R.string.app_name))
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build(),
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        // Каталог для Android Auto — в планах; пока корень пустой.
        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of<MediaItem>(), params))
    }

    companion object {
        const val ACTION_LIKE = "ru.bodro.music.LIKE"
        val LIKE_COMMAND = SessionCommand(ACTION_LIKE, Bundle.EMPTY)
        private const val ROOT_ID = "root"
        private const val START_BUFFER_MS = 500
        private const val REBUFFER_MS = 2_000
    }
}
