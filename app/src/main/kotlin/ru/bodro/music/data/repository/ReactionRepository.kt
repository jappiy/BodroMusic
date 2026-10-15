package ru.bodro.music.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.bodro.music.core.domain.reaction.PendingReaction
import ru.bodro.music.core.domain.reaction.PendingReactionMerger
import ru.bodro.music.core.domain.reaction.ReactionOp
import ru.bodro.music.core.domain.reaction.ReactionRules
import ru.bodro.music.core.domain.reaction.ReactionTarget
import ru.bodro.music.core.model.Reaction
import ru.bodro.music.core.model.Song
import ru.bodro.music.core.subsonic.SubsonicClient
import ru.bodro.music.data.account.ConnectionManager
import ru.bodro.music.data.account.SessionState
import ru.bodro.music.data.local.BodroDatabase
import ru.bodro.music.data.local.DislikedTrackEntity
import ru.bodro.music.data.local.LikedTrackEntity
import ru.bodro.music.data.local.PendingReactionEntity
import ru.bodro.music.data.log.LogRepository
import ru.bodro.music.data.log.PlayerEvent
import ru.bodro.music.di.AppScope
import ru.bodro.music.di.Clock
import ru.bodro.music.sync.SyncScheduler

/** Локальные изменения реакций поверх данных сервера (оптимистичный UI). */
data class ReactionState(
    val songOverrides: Map<String, Reaction> = emptyMap(),
    val dislikedIds: Set<String> = emptySet(),
    /** "album:<id>" / "artist:<id>" → лайк. */
    val starOverrides: Map<String, Boolean> = emptyMap(),
) {
    fun of(song: Song): Reaction = songOverrides[song.id]
        ?: if (song.id in dislikedIds) Reaction.Disliked else ReactionRules.reactionOf(song)

    fun albumLiked(albumId: String, serverValue: Boolean) = starOverrides["album:$albumId"] ?: serverValue

    fun artistLiked(artistId: String, serverValue: Boolean) = starOverrides["artist:$artistId"] ?: serverValue
}

/**
 * Лайки (star) и дизлайки (setRating 1). Изменения применяются в UI сразу, на сервер — сразу или
 * через очередь `pending_reactions` (WorkManager), если сети нет.
 */
@Singleton
class ReactionRepository @Inject constructor(
    private val database: BodroDatabase,
    private val client: SubsonicClient,
    private val connection: ConnectionManager,
    private val log: LogRepository,
    private val syncScheduler: dagger.Lazy<SyncScheduler>,
    private val clock: Clock,
    @AppScope private val scope: CoroutineScope,
) {
    private val dao = database.reactionDao()
    private val syncMutex = Mutex()
    private val _state = MutableStateFlow(ReactionState())
    val state: StateFlow<ReactionState> = _state.asStateFlow()

    private var loadedFor: Long? = null

    init {
        scope.launch {
            connection.session.collect { session ->
                val accountId = (session as? SessionState.Active)?.account?.id
                if (accountId != loadedFor) {
                    loadedFor = accountId
                    _state.value = ReactionState()
                    if (accountId != null) loadLocal(accountId)
                }
            }
        }
    }

    private suspend fun loadLocal(accountId: Long) {
        val disliked = dao.getDisliked(accountId).map { it.songId }.toSet()
        val pending = dao.getPending(accountId)
        _state.update { current ->
            current.copy(
                dislikedIds = disliked,
                songOverrides = pending.filter { it.target == ReactionTarget.Song.name }
                    .associate { it.targetId to Reaction.valueOf(it.desiredState) },
                starOverrides = pending.filter { it.target != ReactionTarget.Song.name }
                    .associate { "${it.target.lowercase()}:${it.targetId}" to (it.desiredState == Reaction.Liked.name) },
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeDisliked(): Flow<List<Song>> = connection.session.flatMapLatest { session ->
        val id = (session as? SessionState.Active)?.account?.id ?: return@flatMapLatest flowOf(emptyList())
        dao.observeDisliked(id).map { list -> list.mapNotNull { AppJson.decodeSong(it.songJson) } }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeLikedCache(): Flow<List<Song>> = connection.session.flatMapLatest { session ->
        val id = (session as? SessionState.Active)?.account?.id ?: return@flatMapLatest flowOf(emptyList())
        dao.observeLiked(id).map { list -> list.mapNotNull { AppJson.decodeSong(it.songJson) } }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observePendingCount(): Flow<Int> = connection.session.flatMapLatest { session ->
        val id = (session as? SessionState.Active)?.account?.id ?: return@flatMapLatest flowOf(0)
        dao.observePendingCount(id)
    }

    suspend fun likedSongs(): List<Song> {
        val id = connection.activeAccountId ?: return emptyList()
        return dao.getLiked(id).mapNotNull { AppJson.decodeSong(it.songJson) }
    }

    suspend fun dislikedSongs(): List<Song> {
        val id = connection.activeAccountId ?: return emptyList()
        return dao.getDisliked(id).mapNotNull { AppJson.decodeSong(it.songJson) }
    }

    fun reactionOf(song: Song): Reaction = _state.value.of(song)

    suspend fun toggleLike(song: Song): Reaction = setReaction(song, ReactionRules.toggleLike(reactionOf(song)))

    suspend fun toggleDislike(song: Song): Reaction = setReaction(song, ReactionRules.toggleDislike(reactionOf(song)))

    /** Поставить реакцию треку: оптимистично локально, затем на сервер (или в очередь). */
    suspend fun setReaction(song: Song, to: Reaction): Reaction {
        val accountId = connection.activeAccountId ?: return reactionOf(song)
        val from = reactionOf(song)
        if (from == to) return to
        val now = clock.now()
        _state.update { current ->
            current.copy(
                songOverrides = current.songOverrides + (song.id to to),
                dislikedIds = if (to == Reaction.Disliked) current.dislikedIds + song.id else current.dislikedIds - song.id,
            )
        }
        val cleanSong = song.copy(starred = to == Reaction.Liked, userRating = if (to == Reaction.Disliked) Song.DISLIKE_RATING else null)
        when (to) {
            Reaction.Liked -> {
                dao.upsertLiked(LikedTrackEntity(accountId, song.id, AppJson.encodeSong(cleanSong), now))
                dao.deleteDisliked(accountId, song.id)
            }
            Reaction.Disliked -> {
                dao.deleteLiked(accountId, song.id)
                dao.upsertDisliked(
                    DislikedTrackEntity(accountId, song.id, song.artistId, AppJson.encodeSong(cleanSong), now, DislikedTrackEntity.PENDING),
                )
            }
            Reaction.None -> {
                dao.deleteLiked(accountId, song.id)
                dao.deleteDisliked(accountId, song.id)
            }
        }
        enqueue(PendingReaction(accountId, ReactionTarget.Song, song.id, from, to, now))
        return to
    }

    suspend fun setStarred(target: ReactionTarget, id: String, liked: Boolean) {
        require(target != ReactionTarget.Song)
        val accountId = connection.activeAccountId ?: return
        val key = "${target.name.lowercase()}:$id"
        _state.update { it.copy(starOverrides = it.starOverrides + (key to liked)) }
        val from = if (liked) Reaction.None else Reaction.Liked
        enqueue(PendingReaction(accountId, target, id, from, if (liked) Reaction.Liked else Reaction.None, clock.now()))
    }

    private suspend fun enqueue(change: PendingReaction) {
        val existing = dao.getPending(change.accountId, change.target.name, change.targetId)?.toDomain()
        val merged = PendingReactionMerger.merge(existing, change)
        if (merged == null) {
            dao.deletePending(change.accountId, change.target.name, change.targetId)
            markDislikeSynced(change.accountId, change.targetId)
        } else {
            dao.upsertPending(merged.toEntity())
        }
        scope.launch { syncPending() }
    }

    /** Отправить накопленные изменения. Возвращает `true`, если очередь пуста. */
    suspend fun syncPending(): Boolean = syncMutex.withLock {
        val accountId = connection.activeAccountId ?: return true
        val pending = dao.getPending(accountId)
        for (entity in pending) {
            val change = entity.toDomain()
            try {
                PendingReactionMerger.opsFor(change).forEach { execute(it) }
                // Пока отправляли, могло прийти новое изменение — удаляем только если оно то же.
                val current = dao.getPending(accountId, entity.target, entity.targetId)
                if (current == entity) dao.deletePending(accountId, entity.target, entity.targetId)
                if (change.target == ReactionTarget.Song) markDislikeSynced(accountId, change.targetId)
                log.event(PlayerEvent.ReactionSynced, "${change.target} ${change.targetId}: ${change.serverState} → ${change.desiredState}")
            } catch (e: Exception) {
                log.event(
                    PlayerEvent.ReactionQueued,
                    "Не отправлено, повторим позже: ${change.target} ${change.targetId} (${e.message})",
                    isError = true,
                )
                syncScheduler.get().requestSync()
                return false
            }
        }
        true
    }

    private suspend fun execute(op: ReactionOp) {
        when (op) {
            is ReactionOp.Star -> when (op.target) {
                ReactionTarget.Song -> client.star(songId = op.targetId)
                ReactionTarget.Album -> client.star(albumId = op.targetId)
                ReactionTarget.Artist -> client.star(artistId = op.targetId)
            }
            is ReactionOp.Unstar -> when (op.target) {
                ReactionTarget.Song -> client.unstar(songId = op.targetId)
                ReactionTarget.Album -> client.unstar(albumId = op.targetId)
                ReactionTarget.Artist -> client.unstar(artistId = op.targetId)
            }
            is ReactionOp.SetRating -> client.setRating(op.targetId, op.rating)
        }
    }

    private suspend fun markDislikeSynced(accountId: Long, songId: String) {
        val disliked = dao.getDisliked(accountId).firstOrNull { it.songId == songId } ?: return
        dao.upsertDisliked(disliked.copy(syncState = DislikedTrackEntity.SYNCED))
    }

    /**
     * Синхронизация с данными сервера: трек с `userRating == 1` — дизлайк, иначе (если нет локального
     * неотправленного изменения) дизлайк снят на другом устройстве.
     */
    suspend fun ingest(songs: List<Song>) {
        val accountId = connection.activeAccountId ?: return
        if (songs.isEmpty()) return
        val pendingIds = dao.getPending(accountId).filter { it.target == ReactionTarget.Song.name }.map { it.targetId }.toSet()
        val localDisliked = _state.value.dislikedIds
        val now = clock.now()
        val added = mutableSetOf<String>()
        val removed = mutableSetOf<String>()
        for (song in songs) {
            if (song.id in pendingIds) continue
            if (song.isDislikedOnServer && song.id !in localDisliked) {
                dao.upsertDisliked(DislikedTrackEntity(accountId, song.id, song.artistId, AppJson.encodeSong(song), now))
                added += song.id
            } else if (!song.isDislikedOnServer && song.id in localDisliked) {
                dao.deleteDisliked(accountId, song.id)
                removed += song.id
            }
        }
        if (added.isNotEmpty() || removed.isNotEmpty()) {
            _state.update { it.copy(dislikedIds = it.dislikedIds + added - removed) }
        }
    }

    /** Полный список лайков с сервера (`getStarred2`) → кэш в Room. */
    suspend fun ingestStarred(songs: List<Song>) {
        val accountId = connection.activeAccountId ?: return
        val now = clock.now()
        val pendingIds = dao.getPending(accountId).filter { it.target == ReactionTarget.Song.name }.map { it.targetId }.toSet()
        val keepLocal = dao.getLiked(accountId).filter { it.songId in pendingIds }
        val entities = songs.filter { it.id !in pendingIds }
            .mapIndexed { index, song -> LikedTrackEntity(accountId, song.id, AppJson.encodeSong(song), now - index) } + keepLocal
        dao.replaceLiked(accountId, entities)
        ingest(songs)
    }

    private fun PendingReactionEntity.toDomain() = PendingReaction(
        accountId = accountId,
        target = ReactionTarget.valueOf(target),
        targetId = targetId,
        serverState = Reaction.valueOf(serverState),
        desiredState = Reaction.valueOf(desiredState),
        updatedAtMs = updatedAt,
    )

    private fun PendingReaction.toEntity() = PendingReactionEntity(
        accountId = accountId,
        target = target.name,
        targetId = targetId,
        serverState = serverState.name,
        desiredState = desiredState.name,
        updatedAt = updatedAtMs,
    )
}
