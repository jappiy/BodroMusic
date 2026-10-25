package ru.bodro.music.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.bodro.music.core.domain.player.QualityBadge
import ru.bodro.music.core.domain.player.QualityBadges
import ru.bodro.music.core.domain.player.SleepTimerOption
import ru.bodro.music.core.domain.player.SleepTimerState
import ru.bodro.music.core.model.QueueSource
import ru.bodro.music.core.model.RepeatMode
import ru.bodro.music.core.model.Song
import ru.bodro.music.core.model.WaveSettings
import ru.bodro.music.data.repository.WaveRepository
import ru.bodro.music.data.settings.SettingsRepository
import ru.bodro.music.di.AppScope

data class PlayerUiState(
    val connected: Boolean = false,
    val currentSong: Song? = null,
    val currentIndex: Int = -1,
    val queue: List<Song> = emptyList(),
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val repeatMode: RepeatMode = RepeatMode.Off,
    val shuffle: Boolean = false,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val source: QueueSource = QueueSource.CustomSource(),
    val quality: QualityBadge? = null,
    val sleepTimer: SleepTimerState? = null,
    val errorMessage: String? = null,
    val waveLoading: Boolean = false,
) {
    val hasMedia: Boolean get() = currentSong != null
}

/**
 * Связь UI с сервисом плеера через MediaController. Состояние — [StateFlow], команды — методы.
 */
@Singleton
class PlayerConnection @Inject constructor(
    @ApplicationContext private val context: Context,
    private val items: MediaItemFactory,
    private val sources: QueueSourceHolder,
    private val sleepTimer: SleepTimerController,
    private val wave: WaveRepository,
    private val settings: SettingsRepository,
    private val streams: StreamDataSourceFactory,
    @AppScope private val appScope: CoroutineScope,
) {
    private val scope = CoroutineScope(appScope.coroutineContext + Dispatchers.Main.immediate)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var progressJob: Job? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    init {
        scope.launch { sources.source.collect { source -> _state.update { it.copy(source = source) } } }
        scope.launch { sleepTimer.state.collect { timer -> _state.update { it.copy(sleepTimer = timer) } } }
    }

    fun connect() {
        if (controllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token)
            .setListener(
                object : MediaController.Listener {
                    override fun onDisconnected(controller: MediaController) {
                        // Сервис остановлен — при следующей команде подключимся заново.
                        this@PlayerConnection.controller = null
                        controllerFuture = null
                        progressJob?.cancel()
                        _state.update { it.copy(connected = false) }
                    }
                },
            )
            .buildAsync()
        controllerFuture = future
        future.addListener(
            {
                val c = runCatching { future.get() }.getOrNull() ?: run {
                    controllerFuture = null
                    return@addListener
                }
                controller = c
                c.addListener(listener)
                _state.update { it.copy(connected = true) }
                refresh()
                if (c.isPlaying) startProgress()
                pending.toList().forEach { it(c) }
                pending.clear()
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun disconnect() {
        progressJob?.cancel()
        controller?.removeListener(listener)
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        controller = null
        _state.update { it.copy(connected = false) }
    }

    private fun withController(action: (MediaController) -> Unit) {
        val c = controller
        if (c != null) {
            action(c)
        } else {
            pending += action
            connect()
        }
    }

    // region Команды

    /** Играть список с позиции [startIndex]. */
    fun play(songs: List<Song>, startIndex: Int = 0, source: QueueSource = QueueSource.CustomSource(), shuffle: Boolean = false) {
        if (songs.isEmpty()) return
        sources.set(source)
        withController { c ->
            c.shuffleModeEnabled = shuffle
            val start = if (shuffle) songs.indices.random() else startIndex.coerceIn(0, songs.lastIndex)
            c.setMediaItems(items.create(songs), start, 0L)
            c.prepare()
            c.play()
        }
    }

    /** Запуск волны / микса: первая порция грузится здесь, дальше сервис подгружает сам. */
    fun startWave(settings: WaveSettings, from: Song? = null, onError: (Throwable) -> Unit = {}) {
        _state.update { it.copy(waveLoading = true) }
        scope.launch {
            try {
                // [from] — трек, который играл: волна (и режим с главной) подбирается от него, без резкой смены звучания.
                val batch = wave.nextBatch(settings, currentSong = from, queue = listOfNotNull(from))
                    .filter { it.id != from?.id }
                if (batch.isEmpty()) {
                    onError(IllegalStateException("empty"))
                } else {
                    play(batch, 0, QueueSource.WaveSource(settings))
                }
            } catch (e: Exception) {
                onError(e)
            } finally {
                _state.update { it.copy(waveLoading = false) }
            }
        }
    }

    /**
     * Перевести очередь на волну от [from], не прерывая его: после текущего трека — порция «волны по треку», хвост
     * прежней очереди (альбом, плейлист, чарт) убирается, сыгранное до него остаётся для «назад». [skip] — сразу
     * перейти к первому треку волны (свайп или «следующий» на главной). Пустая порция — [skip] работает как обычный
     * «следующий».
     */
    fun continueWithWave(from: Song, skip: Boolean, onError: (Throwable) -> Unit = {}) {
        if (_state.value.waveLoading) return
        _state.update { it.copy(waveLoading = true) }
        val settings = WaveSettings.byTrack(from)
        scope.launch {
            try {
                val batch = wave.nextBatch(settings, currentSong = from, queue = listOf(from)).filter { it.id != from.id }
                if (batch.isEmpty()) {
                    if (skip) next()
                    onError(IllegalStateException("empty"))
                    return@launch
                }
                withController { c ->
                    // Пока подбирали, пользователь мог переключить трек сам — тогда очередь не трогаем.
                    if (c.currentMediaItem?.mediaId != from.id) return@withController
                    val index = c.currentMediaItemIndex
                    val oldCount = c.mediaItemCount
                    // Сначала источник и новая порция, потом удаление хвоста: сервис не должен успеть увидеть
                    // «последний трек не волны» и подгрузить продолжение сам.
                    sources.set(QueueSource.WaveSource(settings))
                    c.shuffleModeEnabled = false
                    c.addMediaItems(items.create(batch))
                    if (oldCount > index + 1) c.removeMediaItems(index + 1, oldCount)
                    if (skip) c.seekToNextMediaItem()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (skip) next()
                onError(e)
            } finally {
                _state.update { it.copy(waveLoading = false) }
            }
        }
    }

    fun playNext(songs: List<Song>) = withController { c ->
        if (c.mediaItemCount == 0) {
            play(songs)
        } else {
            c.addMediaItems(c.currentMediaItemIndex + 1, items.create(songs))
        }
    }

    fun addToQueue(songs: List<Song>) = withController { c ->
        if (c.mediaItemCount == 0) play(songs) else c.addMediaItems(items.create(songs))
    }

    fun togglePlay() = withController { c ->
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition(0)
            c.play()
        }
    }

    fun next() = withController { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }

    fun previous() = withController { it.seekToPrevious() }

    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs) }

    fun skipTo(index: Int) = withController { c ->
        if (index in 0 until c.mediaItemCount) {
            c.seekToDefaultPosition(index)
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun moveItem(from: Int, to: Int) = withController { c ->
        if (from in 0 until c.mediaItemCount && to in 0 until c.mediaItemCount && from != to) c.moveMediaItem(from, to)
    }

    fun removeItem(index: Int) = withController { c -> if (index in 0 until c.mediaItemCount) c.removeMediaItem(index) }

    fun clearQueue() = withController { c ->
        c.stop()
        c.clearMediaItems()
        sources.set(QueueSource.CustomSource())
    }

    fun cycleRepeat() = withController { c ->
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    fun setSleepTimer(option: SleepTimerOption?) = if (option == null) sleepTimer.cancel() else sleepTimer.start(option)

    // endregion

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh()
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                if (player.isPlaying) startProgress() else progressJob?.cancel()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.update { it.copy(errorMessage = error.errorCodeName) }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) = refresh()

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            _state.update { it.copy(errorMessage = null) }
        }
    }

    private fun refresh() {
        val c = controller ?: return
        val queue = (0 until c.mediaItemCount).mapNotNull { MediaItemFactory.songOf(c.getMediaItemAt(it)) }
        val current = MediaItemFactory.songOf(c.currentMediaItem)
        val s = settings.current
        _state.update {
            it.copy(
                currentSong = current,
                currentIndex = c.currentMediaItemIndex,
                queue = queue,
                isPlaying = c.isPlaying,
                isBuffering = c.playbackState == Player.STATE_BUFFERING,
                positionMs = c.currentPosition.coerceAtLeast(0),
                durationMs = c.duration.takeIf { d -> d != C.TIME_UNSET && d > 0 } ?: current?.durationMs ?: 0,
                repeatMode = when (c.repeatMode) {
                    Player.REPEAT_MODE_ONE -> RepeatMode.One
                    Player.REPEAT_MODE_ALL -> RepeatMode.All
                    else -> RepeatMode.Off
                },
                shuffle = c.shuffleModeEnabled,
                hasNext = c.hasNextMediaItem(),
                hasPrevious = c.hasPreviousMediaItem() || c.currentPosition > 3_000,
                quality = current?.let { song -> QualityBadges.of(song, streams.currentQuality(), s.transcodeCodec) },
            )
        }
    }

    private fun startProgress() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                controller?.let { c -> _state.update { it.copy(positionMs = c.currentPosition.coerceAtLeast(0)) } }
                delay(PROGRESS_MS)
            }
        }
    }

    private companion object {
        const val PROGRESS_MS = 250L
    }
}
