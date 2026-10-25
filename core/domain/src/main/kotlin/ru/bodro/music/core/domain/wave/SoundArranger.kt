package ru.bodro.music.core.domain.wave

import kotlin.math.abs
import ru.bodro.music.core.model.Song

/** Настроение «Моей волны» по признакам звучания (анализ на сервере). */
enum class WaveMood {
    /** Без фильтра. */
    Any,

    /** Бодрое: высокая энергия. */
    Energetic,

    /** Спокойное: расслабленное и негромкое. */
    Calm,

    /** Весёлое. */
    Happy,

    /** Грустное. */
    Sad,

    /** «Пошумим»: громкое и агрессивное (тот же порог, что у режима на главной). */
    Loud,
}

/** Признаки звучания трека (0..1, кроме [bpm]). */
data class SoundFeatures(
    val bpm: Double? = null,
    val energy: Double? = null,
    val happy: Double? = null,
    val sad: Double? = null,
    val aggressive: Double? = null,
    val relaxed: Double? = null,
    val danceability: Double? = null,
)

/**
 * Настроение волны и плавные переходы: отбор треков по настроению и порядок, при котором соседние треки близки по
 * энергии и темпу. Треки без признаков не отбрасываются, а идут после подходящих.
 */
object SoundArranger {
    const val MOOD_THRESHOLD = 0.5

    fun moodScore(features: SoundFeatures?, mood: WaveMood): Double? {
        if (features == null) return null
        return when (mood) {
            WaveMood.Any -> 1.0
            WaveMood.Energetic -> features.energy
            WaveMood.Calm -> features.energy?.let { e -> ((features.relaxed ?: 0.5) + (1.0 - e)) / 2 } ?: features.relaxed
            WaveMood.Happy -> features.happy
            WaveMood.Sad -> features.sad
            WaveMood.Loud -> features.aggressive?.let { a -> features.energy?.let { e -> (a + e) / 2 } ?: a }
        }
    }

    /** Подходящие по настроению (по убыванию соответствия), затем треки без признаков; не больше [limit]. */
    fun filterByMood(songs: List<Song>, features: Map<String, SoundFeatures>, mood: WaveMood, limit: Int): List<Song> {
        if (mood == WaveMood.Any) return songs.take(limit)
        val scored = songs.map { it to moodScore(features[it.id], mood) }
        val matching = scored.filter { (_, s) -> s != null && s >= MOOD_THRESHOLD }.sortedByDescending { it.second }.map { it.first }
        val unknown = scored.filter { it.second == null }.map { it.first }
        return (matching + unknown).take(limit)
    }

    /** Жадно: следующий — ближайший к текущему по энергии и темпу. Треки без признаков сохраняют места в конце. */
    fun smooth(songs: List<Song>, features: Map<String, SoundFeatures>, start: Song? = null): List<Song> {
        val known = songs.filter { features[it.id]?.energy != null }.toMutableList()
        if (known.size < 2) return songs
        val unknown = songs.filter { features[it.id]?.energy == null }
        val result = mutableListOf<Song>()
        var current = start?.let { features[it.id] }?.takeIf { it.energy != null } ?: features.getValue(known.first().id)
        while (known.isNotEmpty()) {
            val next = known.minBy { distance(current, features.getValue(it.id)) }
            known.remove(next)
            result += next
            current = features.getValue(next.id)
        }
        return result + unknown
    }

    internal fun distance(a: SoundFeatures, b: SoundFeatures): Double {
        val energy = abs((a.energy ?: 0.5) - (b.energy ?: 0.5))
        val tempo = if (a.bpm != null && b.bpm != null) minOf(1.0, abs(a.bpm - b.bpm) / BPM_SCALE) else 0.25
        return energy + 0.5 * tempo
    }

    private const val BPM_SCALE = 60.0
}
