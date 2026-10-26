package ru.bodro.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.bodro.music.core.domain.player.SleepTimerOption
import ru.bodro.music.core.domain.reaction.WaveQueueRules
import ru.bodro.music.core.domain.scrobble.PlayContext
import ru.bodro.music.core.domain.scrobble.PlayOutcome
import ru.bodro.music.core.domain.scrobble.ScrobbleTracker
import ru.bodro.music.core.domain.wave.WaveEngine
import ru.bodro.music.core.model.QueueSnapshot
import ru.bodro.music.core.model.QueueSource
import ru.bodro.music.core.model.Reaction
import ru.bodro.music.core.model.RepeatMode
import ru.bodro.music.core.model.Song
import ru.bodro.music.core.model.WaveSettings
import ru.bodro.music.data.account.ConnectionManager
import ru.bodro.music.data.account.SessionState
import ru.bodro.music.data.log.LogRepository
import ru.bodro.music.data.log.PlayerEvent
import ru.bodro.music.data.reco.PoolRepository
import ru.bodro.music.data.repository.HistoryRepository
import ru.bodro.music.data.repository.ReactionRepository
import ru.bodro.music.data.repository.ScrobbleRepository
import ru.bodro.music.data.repository.WaveRepository
import ru.bodro.music.data.settings.SettingsRepository
import ru.bodro.music.di.Clock

/** Зависимости сервиса плеера одним пакетом. */
class PlaybackDeps @Inject constructor(
    val connection: ConnectionManager,
    val settings: SettingsRepository,
    val reactions: ReactionRepository,
    val scrobbles: ScrobbleRepository,
    val history: HistoryRepository,
    val wave: WaveRepository,
    val persister: QueuePersister,
    val sources: QueueSourceHolder,
    val sleepTimer: SleepTimerController,
    val log: LogRepository,
    val items: MediaItemFactory,
    val clock: Clock,
    val pool: PoolRepository,
)

/**
 * Вся «умная» логика вокруг плеера в сервисе: скробблинг, локальная история, сохранение очереди,
 * подгрузка волны, таймер сна, реакции, обработка ошибок, переключение учёток.
 * Все обращения к плееру — в главном потоке ([scope] — Main).
 */
@OptIn(UnstableApi::class)
class PlaybackCoordinator(
    private val player: ExoPlayer,
    private val scope: CoroutineScope,
    private val deps: PlaybackDeps,
    private val onCurrentReaction: (Reaction?) -> Unit,
) : Player.Listener {
    private val tracker = ScrobbleTracker()
    private var sessionSong: Song? = null
    private var sessionAccountId: Long? = null
    private var queueAccountId: Long? = null
    private var tickJob: Job? = null
    private var refillJob: Job? = null
    private var continueJob: Job? = null

    /** Трек, от которого уже продолжали волной (не подбирать повторно при каждом изменении очереди). */
    private var continuedFrom: String? = null
    private var persistJob: Job? = null
    private var ticks = 0
    private var lastErrorAtMs = 0L
    private var errorRetries = 0
    private var consecutiveErrorSkips = 0

    val currentSong: Song? get() = MediaItemFactory.songOf(player.currentMediaItem)

    fun start() {
        player.addListener(this)
        scope.launch {
            deps.connection.session.collect { session -> onSessionChanged(session) }
        }
        scope.launch {
            deps.sleepTimer.state.collect { timer ->
                player.pauseAtEndOfMediaItems = timer?.option == SleepTimerOption.EndOfTrack
            }
        }
        scope.launch {
            var knownDisliked: Set<String>? = null
            combine(deps.reactions.state, deps.sources.source) { state, _ -> state }.collect { state ->
                publishReaction()
                // Новый дизлайк (из UI, уведомления — откуда угодно) → правило волны.
                val known = knownDisliked
                knownDisliked = state.dislikedIds
                if (known != null) (state.dislikedIds - known).forEach(::onSongDisliked)
            }
        }
    }

    fun release() {
        finishSession(userSkipped = false)
        persistNow(blocking = true)
        player.removeListener(this)
    }

    // region Учётки и восстановление

    private suspend fun onSessionChanged(session: SessionState) {
        val accountId = (session as? SessionState.Active)?.account?.id
        if (accountId == queueAccountId) return
        if (queueAccountId != null) {
            // Переключение учётки: остановить, очистить очередь в памяти, подгрузить состояние новой.
            finishSession(userSkipped = false)
            persistNow(blocking = true)
            player.stop()
            player.clearMediaItems()
            deps.sources.set(QueueSource.CustomSource())
            deps.sleepTimer.cancel()
            deps.log.event(PlayerEvent.Session, "Смена учётки: очередь очищена")
        }
        queueAccountId = accountId
        if (accountId != null && player.mediaItemCount == 0 && deps.settings.current.resumeOnStart) restore(accountId)
    }

    private suspend fun restore(accountId: Long) {
        val snapshot = deps.persister.load(accountId) ?: return
        if (snapshot.songs.isEmpty() || player.mediaItemCount > 0) return
        deps.sources.set(snapshot.source)
        player.setMediaItems(deps.items.create(snapshot.songs), snapshot.currentIndex, snapshot.positionMs)
        player.repeatMode = when (RepeatMode.valueOf(snapshot.repeatMode)) {
            RepeatMode.Off -> Player.REPEAT_MODE_OFF
            RepeatMode.All -> Player.REPEAT_MODE_ALL
            RepeatMode.One -> Player.REPEAT_MODE_ONE
        }
        player.shuffleModeEnabled = snapshot.shuffle
        // prepare() не вызываем: стрим начнёт грузиться только по нажатию play.
        deps.log.event(
            PlayerEvent.Session,
            "Очередь восстановлена: ${snapshot.songs.size} треков, «${snapshot.currentSong?.title}» с ${snapshot.positionMs / 1000} с",
        )
    }

    /** Для `onPlaybackResumption` (кнопка play на гарнитуре после перезагрузки). */
    suspend fun snapshotForResumption(): QueueSnapshot? = deps.persister.load(queueAccountId ?: deps.connection.activeAccountId)

    // endregion

    // region Player.Listener

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED &&
            mediaItem?.mediaId != null &&
            mediaItem.mediaId == sessionSong?.id
        ) {
            // Тот же трек переоткрыт (перемотка в потоке без длины, SeekRescue): сессия прослушивания продолжается.
            schedulePersist()
            return
        }
        finishSession(userSkipped = reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
        if (player.isPlaying) startSession()
        val song = MediaItemFactory.songOf(mediaItem)
        if (song != null && reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
            deps.log.event(PlayerEvent.TrackChanged, "▶ ${song.artist.orEmpty()} — ${song.title} (${song.id})")
        }
        publishReaction()
        schedulePersist()
        checkWaveRefill()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            startSession()
            startTicks()
        } else {
            tickJob?.cancel()
            persistPosition()
        }
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        val seek = reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
        if (seek && oldPosition.mediaItemIndex == newPosition.mediaItemIndex) {
            tracker.onSeek(newPosition.positionMs, deps.clock.now())
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
            schedulePersist()
            checkWaveRefill()
        }
    }

    override fun onRepeatModeChanged(repeatMode: Int) = schedulePersist()

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = schedulePersist()

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
            // Сработал таймер сна «до конца трека».
            deps.sleepTimer.cancel()
            deps.log.event(PlayerEvent.Session, "Таймер сна: пауза в конце трека")
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        val song = currentSong
        deps.log.event(
            PlayerEvent.PlayerError,
            "${error.errorCodeName}: ${error.message.orEmpty()} — ${song?.title ?: "?"} (${song?.id})",
            isError = true,
            details = error.cause?.let { "${it.javaClass.simpleName}: ${it.message}" },
        )
        val now = deps.clock.now()
        if (now - lastErrorAtMs > ERROR_WINDOW_MS) errorRetries = 0
        lastErrorAtMs = now
        when {
            errorRetries < 1 -> {
                // Первая ошибка: возможно, сменилась сеть — перепроверить LAN/WAN и повторить.
                errorRetries++
                scope.launch {
                    deps.connection.reselect()
                    player.prepare()
                    player.play()
                }
            }
            player.hasNextMediaItem() && consecutiveErrorSkips < MAX_ERROR_SKIPS -> {
                errorRetries = 0
                consecutiveErrorSkips++
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }
    }

    // endregion

    // region Скробблинг и история

    private fun startSession() {
        val song = currentSong ?: return
        if (tracker.currentSongId == song.id && sessionSong?.id == song.id) return
        finishSession(userSkipped = false)
        val accountId = deps.connection.activeAccountId ?: return
        tracker.onTrackStarted(song.id, song.durationMs, deps.clock.now())
        sessionSong = song
        sessionAccountId = accountId
        scope.launch(Dispatchers.IO) { deps.scrobbles.nowPlaying(song.id) }
        deps.pool.track(song, "started", feedbackContext(song))
    }

    /** Откуда играет трек — для событий рекомендаций. */
    private fun feedbackContext(song: Song): String = when {
        deps.sources.source.value is QueueSource.WaveSource -> "wave"
        deps.sources.source.value is QueueSource.PlaylistSource -> "playlist"
        deps.pool.isPoolSong(song.id) -> "pool"
        else -> "library"
    }

    private fun finishSession(userSkipped: Boolean) {
        val song = sessionSong ?: return
        val accountId = sessionAccountId ?: return
        val session = tracker.finish(userSkipped) ?: return
        sessionSong = null
        val context = playContext()
        scope.launch(Dispatchers.IO + NonCancellable) { deps.history.record(accountId, song, session, context) }
        // Рекомендациям — только явные сигналы: полное прослушивание и ранний пропуск; частичное — нейтрально.
        val event = when (session.outcome) {
            PlayOutcome.Full -> "completed"
            PlayOutcome.EarlySkip -> "skipped"
            PlayOutcome.Partial -> null
        }
        if (event != null && (session.listenedMs >= MIN_FEEDBACK_MS || session.skippedEarly)) {
            deps.pool.track(song, event, feedbackContext(song), session.listenedMs, session.durationMs.takeIf { it > 0 })
        }
    }

    /** Вес пропуска зависит от того, откуда играл трек ([PlayContext]). */
    private fun playContext(): PlayContext = when (deps.sources.source.value) {
        is QueueSource.WaveSource -> PlayContext.Wave
        is QueueSource.PlaylistSource, is QueueSource.AlbumSource, is QueueSource.ArtistSource, QueueSource.LikedSource -> PlayContext.Own
        is QueueSource.CustomSource -> PlayContext.Other
    }

    private fun startTicks() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                onTick()
                delay(TICK_MS)
            }
        }
    }

    private fun onTick() {
        val now = deps.clock.now()
        player.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let(tracker::onDurationKnown)
        tracker.onProgress(player.currentPosition, player.isPlaying, now)?.let { submission ->
            val accountId = sessionAccountId ?: return@let
            scope.launch(Dispatchers.IO + NonCancellable) { deps.scrobbles.submit(accountId, submission.songId, submission.startedAtMs) }
        }
        if (tracker.listenedMs > HEALTHY_PLAYBACK_MS) consecutiveErrorSkips = 0
        deps.sleepTimer.state.value?.takeIf { it.isExpired(now) }?.let {
            player.pause()
            deps.sleepTimer.cancel()
            deps.log.event(PlayerEvent.Session, "Таймер сна: пауза")
        }
        if (++ticks % PERSIST_EVERY_TICKS == 0) persistPosition()
    }

    // endregion

    // region Сохранение очереди

    private fun buildSnapshot(): QueueSnapshot {
        val songs = (0 until player.mediaItemCount).mapNotNull { MediaItemFactory.songOf(player.getMediaItemAt(it)) }
        return QueueSnapshot(
            songs = songs,
            currentIndex = player.currentMediaItemIndex.coerceAtLeast(0),
            positionMs = player.currentPosition.coerceAtLeast(0),
            repeatMode = when (player.repeatMode) {
                Player.REPEAT_MODE_ONE -> RepeatMode.One
                Player.REPEAT_MODE_ALL -> RepeatMode.All
                else -> RepeatMode.Off
            }.name,
            shuffle = player.shuffleModeEnabled,
            source = deps.sources.source.value,
        )
    }

    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            persistNow(blocking = false)
        }
    }

    private fun persistNow(blocking: Boolean) {
        val accountId = queueAccountId ?: return
        val snapshot = buildSnapshot()
        val job = scope.launch(Dispatchers.IO + NonCancellable) { deps.persister.save(accountId, snapshot) }
        if (blocking) kotlinx.coroutines.runBlocking { job.join() }
    }

    private fun persistPosition() {
        val accountId = queueAccountId ?: return
        if (player.mediaItemCount == 0) return
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        scope.launch(Dispatchers.IO + NonCancellable) { deps.persister.savePosition(accountId, index, position) }
    }

    // endregion

    // region Волна

    private fun checkWaveRefill() {
        val source = deps.sources.source.value as? QueueSource.WaveSource ?: return checkQueueEnd()
        if (player.mediaItemCount == 0) return
        val remaining = player.mediaItemCount - player.currentMediaItemIndex - 1
        if (remaining > WaveEngine.REFILL_THRESHOLD || refillJob?.isActive == true) return
        val accountId = deps.connection.activeAccountId
        val queue = (0 until player.mediaItemCount).mapNotNull { MediaItemFactory.songOf(player.getMediaItemAt(it)) }
        val current = currentSong
        refillJob = scope.launch {
            val batch = try {
                withContext(Dispatchers.IO) { deps.wave.nextBatch(source.settings, current, queue.takeLast(RECENT_FOR_WAVE)) }
            } catch (e: Exception) {
                deps.log.event(PlayerEvent.Wave, "Не удалось подгрузить волну: ${e.message}", isError = true)
                return@launch
            }
            // Пока грузили, пользователь мог сменить источник или учётку.
            if (deps.sources.source.value != source || deps.connection.activeAccountId != accountId) return@launch
            val inQueue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
            val fresh = batch.filter { it.id !in inQueue }
            if (fresh.isNotEmpty()) player.addMediaItems(deps.items.create(fresh))
        }
    }

    /**
     * Очередь не волны (одиночный трек из поиска или чартов, альбом, плейлист) дошла до последнего трека и повтор
     * выключен — сразу после него продолжаем волной, подобранной от этого трека (как «Моя волна» после альбома).
     * Порция добавляется заранее, в начале последнего трека: переход без паузы. Очередь до этого не трогается.
     */
    private fun checkQueueEnd() {
        if (player.mediaItemCount == 0 || player.hasNextMediaItem() || player.repeatMode != Player.REPEAT_MODE_OFF) return
        val song = currentSong ?: return
        if (continueJob?.isActive == true || continuedFrom == song.id) return
        continuedFrom = song.id
        val source = deps.sources.source.value
        val accountId = deps.connection.activeAccountId
        val settings = WaveSettings.byTrack(song)
        continueJob = scope.launch {
            val batch = try {
                withContext(Dispatchers.IO) { deps.wave.nextBatch(settings, song, listOf(song)) }.filter { it.id != song.id }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                deps.log.event(PlayerEvent.Wave, "Не удалось продолжить волной: ${e.message}", isError = true)
                return@launch
            }
            // Пока подбирали, пользователь мог запустить другое или сменить учётку.
            val stillLast = currentSong?.id == song.id && !player.hasNextMediaItem()
            val sameQueue = deps.sources.source.value == source && deps.connection.activeAccountId == accountId
            if (batch.isEmpty() || !stillLast || !sameQueue) return@launch
            deps.sources.set(QueueSource.WaveSource(settings))
            player.addMediaItems(deps.items.create(batch))
            deps.log.event(PlayerEvent.Wave, "Очередь кончается — дальше волна по треку «${song.title}»: +${batch.size}")
        }
    }

    /** Дизлайк в волне: сразу к следующему треку и вычистить трек из оставшейся очереди. */
    fun onSongDisliked(songId: String) {
        if (!deps.sources.source.value.isWave) return
        val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        val action = WaveQueueRules.onDislike(ids, player.currentMediaItemIndex, songId)
        action.indexesToRemove.forEach { player.removeMediaItem(it) }
        if (action.skipCurrent) {
            if (player.hasNextMediaItem()) {
                player.removeMediaItem(player.currentMediaItemIndex)
            } else {
                player.pause()
            }
        }
        checkWaveRefill()
    }

    // endregion

    private fun publishReaction() {
        val song = currentSong
        onCurrentReaction(song?.let { deps.reactions.reactionOf(it) })
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val PERSIST_EVERY_TICKS = 5
        const val PERSIST_DEBOUNCE_MS = 400L
        const val ERROR_WINDOW_MS = 30_000L
        const val MAX_ERROR_SKIPS = 3
        const val HEALTHY_PLAYBACK_MS = 5_000L
        const val RECENT_FOR_WAVE = 200
        const val MIN_FEEDBACK_MS = 1_000L
    }
}
