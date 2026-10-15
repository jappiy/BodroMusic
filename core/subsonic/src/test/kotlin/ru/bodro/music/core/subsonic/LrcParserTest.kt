package ru.bodro.music.core.subsonic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {
    @Test
    fun `parses timestamps, multi tags and offset`() {
        val lyrics = LrcParser.parse(
            """
            [ar:Кино]
            [offset:+500]
            [00:12.34]Первая
            [01:02.5][01:30.123]Припев
            """.trimIndent(),
        )
        assertTrue(lyrics.synced)
        assertEquals(listOf(12_840L, 63_000L, 90_623L), lyrics.lines.map { it.startMs })
        assertEquals("Припев", lyrics.lines.last().text)
    }

    @Test
    fun `plain text stays unsynced`() {
        val lyrics = LrcParser.parse("\nКуплет\n\nПрипев\n")
        assertFalse(lyrics.synced)
        assertEquals(listOf("Куплет", "", "Припев"), lyrics.lines.map { it.text })
        assertEquals(-1, lyrics.activeLineIndex(1000))
    }
}
