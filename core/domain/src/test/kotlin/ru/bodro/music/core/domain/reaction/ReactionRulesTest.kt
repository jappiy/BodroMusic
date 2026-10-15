package ru.bodro.music.core.domain.reaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.bodro.music.core.model.Reaction
import ru.bodro.music.core.model.Song

class ReactionRulesTest {
    private val song = ReactionTarget.Song

    @Test
    fun `reaction of song from server fields`() {
        assertEquals(Reaction.Liked, ReactionRules.reactionOf(Song("1", "t", starred = true)))
        assertEquals(Reaction.Disliked, ReactionRules.reactionOf(Song("1", "t", userRating = 1)))
        assertEquals(Reaction.None, ReactionRules.reactionOf(Song("1", "t", userRating = 4)))
    }

    @Test
    fun `like and dislike are mutually exclusive`() {
        assertEquals(
            listOf(ReactionOp.SetRating("1", 0), ReactionOp.Star("1", song)),
            ReactionRules.songOps("1", Reaction.Disliked, Reaction.Liked),
        )
        assertEquals(
            listOf(ReactionOp.Unstar("1", song), ReactionOp.SetRating("1", 1)),
            ReactionRules.songOps("1", Reaction.Liked, Reaction.Disliked),
        )
    }

    @Test
    fun `toggles`() {
        assertEquals(Reaction.Liked, ReactionRules.toggleLike(Reaction.None))
        assertEquals(Reaction.None, ReactionRules.toggleLike(Reaction.Liked))
        assertEquals(Reaction.Liked, ReactionRules.toggleLike(Reaction.Disliked))
        assertEquals(Reaction.Disliked, ReactionRules.toggleDislike(Reaction.Liked))
        assertEquals(Reaction.None, ReactionRules.toggleDislike(Reaction.Disliked))
        assertEquals(listOf(ReactionOp.SetRating("1", 0)), ReactionRules.songOps("1", Reaction.Disliked, Reaction.None))
        assertEquals(listOf(ReactionOp.Star("1", song)), ReactionRules.songOps("1", Reaction.None, Reaction.Liked))
        assertTrue(ReactionRules.songOps("1", Reaction.Liked, Reaction.Liked).isEmpty())
    }

    @Test
    fun `dislike of current wave track skips it and removes upcoming copies only`() {
        val queue = listOf("a", "x", "b", "x", "c", "x")
        val action = WaveQueueRules.onDislike(queue, currentIndex = 3, dislikedSongId = "x")
        assertTrue(action.skipCurrent)
        assertEquals(listOf(5), action.indexesToRemove)
    }

    @Test
    fun `dislike of upcoming track does not skip current`() {
        val action = WaveQueueRules.onDislike(listOf("a", "b", "x", "x"), currentIndex = 1, dislikedSongId = "x")
        assertFalse(action.skipCurrent)
        assertEquals(listOf(3, 2), action.indexesToRemove)
    }

    @Test
    fun `offline changes collapse to a single transition`() {
        val p1 = PendingReaction(1, song, "s", Reaction.None, Reaction.Liked, 1)
        val merged1 = PendingReactionMerger.merge(null, p1)!!
        val p2 = PendingReaction(1, song, "s", Reaction.Liked, Reaction.Disliked, 2)
        val merged2 = PendingReactionMerger.merge(merged1, p2)!!
        assertEquals(Reaction.None, merged2.serverState)
        assertEquals(Reaction.Disliked, merged2.desiredState)
        assertEquals(listOf(ReactionOp.SetRating("s", 1)), PendingReactionMerger.opsFor(merged2))
        val back = PendingReaction(1, song, "s", Reaction.Disliked, Reaction.None, 3)
        assertNull("back to server state — nothing to sync", PendingReactionMerger.merge(merged2, back))
    }

    @Test
    fun `album like uses star with album target`() {
        val pending = PendingReaction(1, ReactionTarget.Album, "al", Reaction.None, Reaction.Liked, 1)
        assertEquals(listOf(ReactionOp.Star("al", ReactionTarget.Album)), PendingReactionMerger.opsFor(pending))
    }
}
