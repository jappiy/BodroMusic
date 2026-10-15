package ru.bodro.music.core.domain.reaction

import ru.bodro.music.core.model.Reaction

/** Несинхронизированное изменение реакции. Хранит исходное серверное состояние и желаемое. */
data class PendingReaction(
    val accountId: Long,
    val target: ReactionTarget,
    val targetId: String,
    val serverState: Reaction,
    val desiredState: Reaction,
    val updatedAtMs: Long,
)

/**
 * Схлопывание офлайн-изменений: лайк → снять лайк → дизлайк превращается в одно изменение
 * «как было на сервере → как сейчас». Если вернулись к исходному — изменение не нужно.
 */
object PendingReactionMerger {
    fun merge(existing: PendingReaction?, new: PendingReaction): PendingReaction? {
        val serverState = existing?.serverState ?: new.serverState
        if (serverState == new.desiredState) return null
        return new.copy(serverState = serverState)
    }

    fun opsFor(pending: PendingReaction): List<ReactionOp> = when (pending.target) {
        ReactionTarget.Song -> ReactionRules.songOps(pending.targetId, pending.serverState, pending.desiredState)
        else -> ReactionRules.starOps(pending.targetId, pending.target, pending.desiredState == Reaction.Liked)
    }
}
