package ru.bodro.music.core.domain.changelog

data class ChangelogSection(val title: String, val items: List<String>)

data class ChangelogRelease(val version: String, val date: String?, val sections: List<ChangelogSection>) {
    val isUnreleased: Boolean get() = version.equals("Unreleased", ignoreCase = true)
}

/** Разбор CHANGELOG.md в формате Keep a Changelog. */
object ChangelogParser {
    private val releaseHeader = Regex("""^##\s+\[?([^\]\s]+)]?(?:\s*[-–—]\s*(\S+))?.*$""")
    private val sectionHeader = Regex("""^###\s+(.+)$""")
    private val listItem = Regex("""^\s*[-*+]\s+(.+)$""")

    fun parse(markdown: String): List<ChangelogRelease> {
        val releases = mutableListOf<ChangelogRelease>()
        var version: String? = null
        var date: String? = null
        val sections = mutableListOf<ChangelogSection>()
        var sectionTitle: String? = null
        var items = mutableListOf<String>()

        fun closeSection() {
            val title = sectionTitle
            if (title != null && items.isNotEmpty()) sections += ChangelogSection(title, items.toList())
            sectionTitle = null
            items = mutableListOf()
        }

        fun closeRelease() {
            closeSection()
            version?.let { releases += ChangelogRelease(it, date, sections.toList()) }
            sections.clear()
            version = null
            date = null
        }

        for (line in markdown.lines()) {
            val trimmed = line.trimEnd()
            when {
                trimmed.startsWith("## ") -> {
                    closeRelease()
                    releaseHeader.find(trimmed)?.let {
                        version = it.groupValues[1]
                        date = it.groupValues[2].ifBlank { null }
                    }
                }
                trimmed.startsWith("### ") -> {
                    closeSection()
                    sectionTitle = sectionHeader.find(trimmed)?.groupValues?.get(1)?.trim()
                }
                version != null && sectionTitle != null -> {
                    val item = listItem.find(trimmed)
                    when {
                        item != null -> items += item.groupValues[1].trim()
                        // Продолжение многострочного пункта.
                        trimmed.isNotBlank() && line.startsWith("  ") && items.isNotEmpty() ->
                            items[items.lastIndex] = items.last() + " " + trimmed.trim()
                    }
                }
            }
            if (trimmed.startsWith("[") && trimmed.contains("]: ")) continue // ссылки внизу файла
        }
        closeRelease()
        return releases
    }
}
