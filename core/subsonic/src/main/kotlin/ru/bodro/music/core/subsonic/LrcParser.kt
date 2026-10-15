package ru.bodro.music.core.subsonic

import ru.bodro.music.core.model.LyricLine
import ru.bodro.music.core.model.Lyrics

/** Разбирает текст песни: если в нём есть LRC-метки `[mm:ss.xx]`, получаем синхронизированный текст. */
object LrcParser {
    private val timeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val metaTag = Regex("""^\[(ar|ti|al|by|offset|length|re|ve|au|#):.*]$""", RegexOption.IGNORE_CASE)
    private val offsetTag = Regex("""^\[offset:\s*([+-]?\d+)\s*]$""", RegexOption.IGNORE_CASE)

    fun parse(text: String): Lyrics {
        val rawLines = text.replace("\r\n", "\n").replace('\r', '\n').lines()
        var offset = 0L
        val timed = mutableListOf<LyricLine>()
        var hasTimeTags = false
        for (raw in rawLines) {
            val line = raw.trim()
            offsetTag.find(line)?.let { offset = it.groupValues[1].toLongOrNull() ?: 0L }
            if (metaTag.matches(line)) continue
            val tags = timeTag.findAll(line).toList()
            if (tags.isEmpty() || tags.first().range.first != 0) continue
            hasTimeTags = true
            val content = line.substring(tags.last().range.last + 1).trim()
            for (tag in tags) {
                timed += LyricLine(startMs = tag.toMillis() + offset, text = content)
            }
        }
        if (!hasTimeTags) {
            return Lyrics(synced = false, lines = rawLines.map { LyricLine(null, it.trimEnd()) }.trimBlankEdges())
        }
        return Lyrics(synced = true, lines = timed.sortedBy { it.startMs })
    }

    private fun MatchResult.toMillis(): Long {
        val minutes = groupValues[1].toLong()
        val seconds = groupValues[2].toLong()
        val fraction = groupValues[3]
        val millis = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 100
            2 -> fraction.toLong() * 10
            else -> fraction.take(3).toLong()
        }
        return (minutes * 60 + seconds) * 1000 + millis
    }

    private fun List<LyricLine>.trimBlankEdges(): List<LyricLine> =
        dropWhile { it.text.isBlank() }.dropLastWhile { it.text.isBlank() }
}
