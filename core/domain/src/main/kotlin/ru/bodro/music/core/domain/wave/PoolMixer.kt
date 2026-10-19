package ru.bodro.music.core.domain.wave

import kotlin.math.roundToInt
import kotlin.random.Random
import ru.bodro.music.core.model.Song

/** Характер «Моей волны»: доля треков из пула «На пробу» в каждой порции. */
enum class WaveCharacter(val poolShare: Double) {
    /** Только знакомое — без пула. */
    Favorites(0.0),

    /** Четверть порции — из пула. */
    Mix(0.25),

    /** Половина порции — из пула. */
    Discover(0.5),
}

/**
 * Подмешивает треки пула «На пробу» в порцию волны: знакомая часть — как раньше ([WaveEngine]), пул — равномерно
 * по порции. Два трека пула одного исполнителя подряд не ставятся (с учётом последнего трека пула в очереди).
 */
object PoolMixer {
    fun mix(
        base: List<Song>,
        pool: List<Song>,
        share: Double,
        random: Random,
        recent: List<Song> = emptyList(),
        poolIds: Set<String> = pool.mapTo(HashSet()) { it.id },
    ): List<Song> {
        val size = base.size
        if (share <= 0.0) return base.filter { it.id !in poolIds }
        if (size == 0 || pool.isEmpty()) return base
        val taken = recent.mapTo(HashSet()) { it.id } + base.mapTo(HashSet()) { it.id }
        val wanted = (size * share).roundToInt().coerceIn(1, size)
        val picked = pickPool(pool.filter { it.id !in taken }.shuffled(random), wanted, lastPoolArtist(recent, poolIds))
        if (picked.isEmpty()) return base
        val familiar = base.filter { it.id !in poolIds }.take(size - picked.size)
        return interleave(familiar, picked, random)
    }

    private fun lastPoolArtist(recent: List<Song>, poolIds: Set<String>): String? =
        recent.lastOrNull { it.id in poolIds }?.let(WaveEngine::artistKey)

    /** Треки пула по порядку, без одного исполнителя подряд. */
    internal fun pickPool(candidates: List<Song>, count: Int, previousArtist: String?): List<Song> {
        val rest = candidates.toMutableList()
        val result = mutableListOf<Song>()
        var last = previousArtist
        while (result.size < count && rest.isNotEmpty()) {
            val index = rest.indexOfFirst { WaveEngine.artistKey(it) != last || last == null }
            if (index < 0) break
            val song = rest.removeAt(index)
            result += song
            last = WaveEngine.artistKey(song)
        }
        return result
    }

    /** Равномерно: пул на позициях с шагом `всего / пул`, со случайным сдвигом внутри шага. */
    internal fun interleave(familiar: List<Song>, pool: List<Song>, random: Random): List<Song> {
        val total = familiar.size + pool.size
        val step = total.toDouble() / pool.size
        val positions = pool.indices.map { i -> (i * step + random.nextDouble() * (step - 1).coerceAtLeast(0.0)).toInt() }
        val result = ArrayList<Song>(total)
        var f = 0
        var p = 0
        for (index in 0 until total) {
            if (p < pool.size && (index >= positions[p] || f >= familiar.size)) {
                result += pool[p++]
            } else if (f < familiar.size) {
                result += familiar[f++]
            }
        }
        while (p < pool.size) result += pool[p++]
        return result
    }
}
