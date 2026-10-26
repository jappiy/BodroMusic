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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.bodro.music.R
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
    /** Нажали «следующий», а следующего трека ещё нет — переключим, как только волна его добавит. */
    val skipPending: Boolean = false,
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

    /** Сообщения для снекбара (id строки): перемотка не удалась, продолжение волны не нашлось. */
    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val messages: SharedFlow<Int> = _messages.asSharedFlow()
    private var skipTimeout: Job? = null

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
        if (settings.trackSeed != null) error("Волна по треку — startTrackWave")
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
     * «Волна по треку» (и «Похожие по звучанию», [soundOnly]): трек-семя играет сразу, похожие подбирает сервис плеера
     * (`PlaybackCoordinator.checkWaveRefill`) — первая порция из быстрых источников, внешние догружаются. Если семя
     * уже играет — оно не прерывается, меняется только хвост очереди.
     */
    fun startTrackWave(seed: Song, soundOnly: Boolean = false, onError: (Throwable) -> Unit = {}) {
        val settings = WaveSettings.byTrack(seed, soundOnly)
        if (_state.value.currentSong?.id == seed.id) {
            continueWithWave(seed, skip = false, settings = settings, onError = onError)
            return
        }
        play(listOf(seed), 0, QueueSource.WaveSource(settings))
    }

    /**
     * Перевести очередь на волну от [from], не прерывая его: после текущего трека — порция «волны по треку», хвост
     * прежней очереди (альбом, плейлист, чарт) убирается, сыгранное до него остаётся для «назад». [skip] — сразу
     * перейти к первому треку волны (свайп или «следующий» на главной). Пустая порция — [skip] работает как обычный
     * «следующий».
     */
    fun continueWithWave(
        from: Song,
        skip: Boolean,
        settings: WaveSettings = WaveSettings.byTrack(from),
        onError: (Throwable) -> Unit = {},
    ) {
        if (_state.value.waveLoading) {
            // Подбор уже идёт — нажатие не теряем: переключим, как только порция встанет в очередь.
            if (skip) requestPendingSkip()
            return
        }
        _state.update { it.copy(waveLoading = true) }
        scope.launch {
            try {
                val batch = wave.nextBatch(settings, currentSong = from, queue = listOf(from)).filter { it.id != from.id }
                if (batch.isEmpty()) {
                    cancelPendingSkip()
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
                    if (skip || _state.value.skipPending) {
                        cancelPendingSkip()
                        c.seekToNextMediaItem()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                cancelPendingSkip()
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

    /**
     * «Следующий» срабатывает всегда. Есть следующий трек — переключаем. Нет, а играет волна — ждём, пока сервис
     * добавит порцию (показываем загрузку), и переключаем сразу. Нет и это не волна — продолжаем волной по треку.
     */
    fun next() = withController { c ->
        when {
            c.hasNextMediaItem() -> c.seekToNextMediaItem()
            c.currentMediaItem == null -> Unit
            _state.value.source.isWave -> requestPendingSkip()
            else -> MediaItemFactory.songOf(c.currentMediaItem)?.let { continueWithWave(it, skip = true) }
        }
    }

    private fun requestPendingSkip() {
        _state.update { it.copy(skipPending = true) }
        skipTimeout?.cancel()
        skipTimeout = scope.launch {
            delay(SKIP_WAIT_MS)
            if (_state.value.skipPending) {
                _state.update { it.copy(skipPending = false) }
                _messages.tryEmit(R.string.wave_next_timeout)
            }
        }
    }

    private fun cancelPendingSkip() {
        skipTimeout?.cancel()
        _state.update { it.copy(skipPending = false) }
    }

    /** Следующий трек появился в очереди — выполнить отложенное «следующий». */
    private fun applyPendingSkip(c: MediaController) {
        if (_state.value.skipPending && c.hasNextMediaItem()) {
            cancelPendingSkip()
            c.seekToNextMediaItem()
        }
    }

    fun previous() = withController { it.seekToPrevious() }

    /**
     * Перемотка. Если у текущего потока нет карты перемотки, MediaController молча проигнорировал бы `seekTo`, —
     * тогда просим сервис переоткрыть поток с нужной позиции ([SeekRescue]); не удалось — сообщение пользователю.
     */
    fun seekTo(positionMs: Long) = withController { c ->
        if (c.currentMediaItem == null || c.isCurrentMediaItemSeekable) {
            c.seekTo(positionMs)
            return@withController
        }
        _state.update { it.copy(positionMs = positionMs, isBuffering = true) }
        val args = android.os.Bundle().apply { putLong(SeekRescue.EXTRA_POSITION_MS, positionMs) }
        val result = c.sendCustomCommand(SeekRescue.COMMAND, args)
        result.addListener(
            {
                val ok = runCatching { result.get().resultCode == androidx.media3.session.SessionResult.RESULT_SUCCESS }.getOrDefault(false)
                if (!ok) _messages.tryEmit(R.string.player_seek_unavailable)
                refresh()
            },
            ContextCompat.getMainExecutor(context),
        )
    }

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

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            refresh()
            controller?.let(::applyPendingSkip)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            _state.update { it.copy(errorMessage = null) }
            // Переключились сами (свайп, очередь) — отложенное «следующий» больше не нужно.
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) cancelPendingSkip()
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
        const val SKIP_WAIT_MS = 20_000L
    }
}
