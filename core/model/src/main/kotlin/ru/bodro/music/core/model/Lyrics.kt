package ru.bodro.music.core.model

data class LyricLine(
    /** Время начала строки в мс; `null` для несинхронизированного текста. */
    val startMs: Long?,
    val text: String,
)

data class Lyrics(
    val synced: Boolean,
    val lines: List<LyricLine>,
    val lang: String? = null,
) {
    val isEmpty: Boolean get() = lines.all { it.text.isBlank() }

    /** Индекс активной строки для позиции [positionMs] (только для синхронизированного текста). */
    fun activeLineIndex(positionMs: Long): Int {
        if (!synced) return -1
        var result = -1
        for ((index, line) in lines.withIndex()) {
            val start = line.startMs ?: continue
            if (start <= positionMs) result = index else break
        }
        return result
    }
}
