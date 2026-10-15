package ru.bodro.music.core.domain.reaction

import ru.bodro.music.core.model.Reaction
import ru.bodro.music.core.model.Song

enum class ReactionTarget { Song, Album, Artist }

/** Операция на сервере Subsonic. */
sealed interface ReactionOp {
    val targetId: String

    data class Star(override val targetId: String, val target: ReactionTarget) : ReactionOp

    data class Unstar(override val targetId: String, val target: ReactionTarget) : ReactionOp

    data class SetRating(override val targetId: String, val rating: Int) : ReactionOp
}

data class ReactionTransition(val from: Reaction, val to: Reaction, val ops: List<ReactionOp>)

/**
 * Лайк = star, дизлайк = setRating(1). Лайк и дизлайк взаимоисключающие.
 */
object ReactionRules {
    fun reactionOf(song: Song): Reaction = when {
        song.isDislikedOnServer -> Reaction.Disliked
        song.starred -> Reaction.Liked
        else -> Reaction.None
    }

    fun toggleLike(current: Reaction): Reaction = if (current == Reaction.Liked) Reaction.None else Reaction.Liked

    fun toggleDislike(current: Reaction): Reaction = if (current == Reaction.Disliked) Reaction.None else Reaction.Disliked

    /** Минимальный набор операций для перехода трека [from] → [to]. Пустой, если состояние не меняется. */
    fun songOps(songId: String, from: Reaction, to: Reaction): List<ReactionOp> {
        if (from == to) return emptyList()
        val target = ReactionTarget.Song
        return buildList {
            // Сначала снимаем старое состояние, потом ставим новое.
            when (from) {
                Reaction.Liked -> add(ReactionOp.Unstar(songId, target))
                Reaction.Disliked -> add(ReactionOp.SetRating(songId, 0))
                Reaction.None -> Unit
            }
            when (to) {
                Reaction.Liked -> add(ReactionOp.Star(songId, target))
                Reaction.Disliked -> add(ReactionOp.SetRating(songId, Song.DISLIKE_RATING))
                Reaction.None -> Unit
            }
        }
    }

    fun songTransition(songId: String, from: Reaction, to: Reaction) = ReactionTransition(from, to, songOps(songId, from, to))

    /** Альбом и исполнитель: только лайк (star/unstar). */
    fun starOps(targetId: String, target: ReactionTarget, liked: Boolean): List<ReactionOp> =
        listOf(if (liked) ReactionOp.Star(targetId, target) else ReactionOp.Unstar(targetId, target))
}

/** Что сделать с очередью волны после дизлайка трека. */
data class DislikeQueueAction(
    /** Пропустить текущий трек (дизлайкнули то, что играет). */
    val skipCurrent: Boolean,
    /** Индексы в очереди, которые нужно удалить (по убыванию — удобно удалять). */
    val indexesToRemove: List<Int>,
)

object WaveQueueRules {
    /**
     * Дизлайк во время волны: сразу к следующему треку и вычистить этот трек из оставшейся очереди.
     * Уже сыгранные треки (до текущего) не трогаем — это история.
     */
    fun onDislike(queueSongIds: List<String>, currentIndex: Int, dislikedSongId: String): DislikeQueueAction {
        val skip = queueSongIds.getOrNull(currentIndex) == dislikedSongId
        val toRemove = queueSongIds.indices
            .filter { it > currentIndex && queueSongIds[it] == dislikedSongId }
            .sortedDescending()
        return DislikeQueueAction(skip, toRemove)
    }
}
