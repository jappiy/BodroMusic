package ru.bodro.music.core.domain.wave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveProfileBuilderTest {
    @Test
    fun `profile from likes, dislikes and history`() {
        val liked = library.filter { it.artistId == "ar-5" }.take(3)
        val disliked = library.filter { it.artistId == "ar-6" }.take(3)
        val history = listOf(
            PlayHistoryItem("s-9-1", "ar-9", "Folk", NOW - DAY, skippedEarly = false),
            PlayHistoryItem("s-9-1", "ar-9", "Folk", NOW - 3 * DAY, skippedEarly = false),
            PlayHistoryItem("s-8-1", "ar-8", "Jazz", NOW - DAY, skippedEarly = true),
        )
        val profile = WaveProfileBuilder.build(liked, disliked, history, NOW)
        assertEquals(liked.map { it.id }.toSet(), profile.likedSongIds)
        assertEquals(3, profile.artistDislikes["ar-6"])
        assertEquals(NOW - DAY, profile.lastPlayedAt["s-9-1"])
        assertTrue("skips are not plays", "s-8-1" !in profile.lastPlayedAt)
        assertEquals(1.0, profile.artistSkips["ar-8"])
        assertEquals("ar-5", profile.topArtistIds.first())
        assertTrue("ar-9" in profile.topArtistIds)
    }

    @Test
    fun `skip statistics can be reset by time`() {
        val history = listOf(PlayHistoryItem("a", "ar-1", "Rock", NOW - DAY, skippedEarly = true))
        val profile = WaveProfileBuilder.build(emptyList(), emptyList(), history, NOW, skipStatsSinceMs = NOW - 1000)
        assertTrue(profile.artistSkips.isEmpty())
    }
}
