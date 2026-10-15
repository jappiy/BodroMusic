package ru.bodro.music.core.domain.changelog

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangelogParserTest {
    private val sample = """
        # Changelog
        Все заметные изменения.

        ## [Unreleased]

        ## [0.2.0] - 2026-10-14
        ### Добавлено
        - Главный экран
        - Поиск с историей
          запросов
        ### Исправлено
        * Падение при пустой библиотеке

        ## 0.1.0 – 2026-10-01
        ### Добавлено
        - Вход в учётку

        [0.2.0]: https://example.org/compare/v0.1.0...v0.2.0
    """.trimIndent()

    @Test
    fun `parses releases, dates and sections`() {
        val releases = ChangelogParser.parse(sample)
        assertEquals(listOf("Unreleased", "0.2.0", "0.1.0"), releases.map { it.version })
        assertTrue(releases[0].isUnreleased)
        assertTrue(releases[0].sections.isEmpty())
        val r2 = releases[1]
        assertEquals("2026-10-14", r2.date)
        assertEquals(listOf("Добавлено", "Исправлено"), r2.sections.map { it.title })
        assertEquals(listOf("Главный экран", "Поиск с историей запросов"), r2.sections[0].items)
        assertEquals("2026-10-01", releases[2].date)
        assertEquals(1, releases[2].sections.single().items.size)
    }

    @Test
    fun `empty input`() {
        assertTrue(ChangelogParser.parse("").isEmpty())
        assertNull(ChangelogParser.parse("## [1.0.0]\n### Добавлено\n- x").single().date)
    }

    @Test
    fun `project changelog is valid and newest first`() {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "CHANGELOG.md") }.firstOrNull { it.exists() }
            ?: return
        val releases = ChangelogParser.parse(file.readText()).filterNot { it.isUnreleased }
        assertTrue(releases.isNotEmpty())
        assertTrue(releases.all { it.version.matches(Regex("""\d+\.\d+\.\d+(-[\w.]+)?""")) && it.date != null })
        val versions = releases.map { v -> v.version.substringBefore('-').split('.').map { it.toInt() } }
        val sorted = versions.sortedWith(compareByDescending<List<Int>> { it[0] }.thenByDescending { it[1] }.thenByDescending { it[2] })
        assertEquals(sorted, versions)
    }
}
