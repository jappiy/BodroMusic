package ru.bodro.music.core.domain.wave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.bodro.music.core.domain.wave.StyleArtists.Play

class StyleArtistsTest {
    private val plays = listOf(
        Play("Noize MC", 1.0), Play("Noize MC", 1.0), Play("Noize MC", 1.0),
        Play("Земфира", 1.0), Play("Земфира", 0.5),
        Play("Ёлка", 1.0), Play("елка", 1.0),
        Play("Кино", 0.5),
        Play("Muse", 1.0), Play("Muse", 1.0),
        Play("Пропущенный", 0.0),
    )

    @Test
    fun `first card is the current artist and is not repeated`() {
        val cards = StyleArtists.pick("Noize MC", plays, nowMs = 0)
        assertEquals("Noize MC", cards.first())
        assertEquals(3, cards.size)
        assertTrue("Noize MC" !in cards.drop(1))
    }

    @Test
    fun `early skips and case or yo variants are counted correctly`() {
        assertEquals(listOf("Noize MC", "Muse"), StyleArtists.pick(null, plays, nowMs = 0))
        // «Ёлка» и «елка» — один артист (2 прослушивания), он выше «Земфиры» (1,5).
        val cards = StyleArtists.pick("muse", plays, nowMs = 0)
        assertEquals(listOf("muse", "Noize MC", "Ёлка"), cards)
        assertTrue(cards.none { it == "Пропущенный" })
    }

    @Test
    fun `pair rotates over time`() {
        val a = StyleArtists.pick("Кино", plays, nowMs = 0)
        val b = StyleArtists.pick("Кино", plays, nowMs = StyleArtists.ROTATION_MS)
        assertEquals("Кино", a.first())
        assertNotEquals(a.drop(1), b.drop(1))
    }

    @Test
    fun `nothing played and nothing playing gives no cards`() {
        assertEquals(emptyList<String>(), StyleArtists.pick(null, emptyList(), 0))
    }
}
