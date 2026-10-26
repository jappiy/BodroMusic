package ru.bodro.music.core.domain.wave

/**
 * Карточки «В стиле <артист>» на главной (0.31.0).
 *
 * Первая — артист текущего трека (ничего не играет — последний прослушанный). Вторая и третья — топ артистов
 * пользователя за последние [WINDOW_MS] по локальной истории (полные прослушивания весят 1, частичные — ½, ранние
 * пропуски не считаются), без повтора первой. Чтобы карточки не застывали, пара берётся из первых [POOL] артистов
 * топа со сдвигом, который меняется раз в [ROTATION_MS].
 */
object StyleArtists {
    const val WINDOW_MS: Long = 14L * 24 * 60 * 60 * 1000
    const val ROTATION_MS: Long = 6L * 60 * 60 * 1000
    const val POOL = 6
    const val TOP = 2

    /** Прослушивание артиста: [weight] — 1 за полное, ½ за частичное. */
    data class Play(val artist: String, val weight: Double)

    fun pick(current: String?, plays: List<Play>, nowMs: Long): List<String> {
        val first = current?.trim()?.takeIf { it.isNotEmpty() }
        val firstKey = first?.let(::key)
        val names = mutableMapOf<String, String>()
        val weights = mutableMapOf<String, Double>()
        for (play in plays) {
            val k = key(play.artist)
            if (k.isEmpty() || k == firstKey || play.weight <= 0.0) continue
            names.putIfAbsent(k, play.artist.trim())
            weights[k] = (weights[k] ?: 0.0) + play.weight
        }
        val top = weights.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(POOL)
            .map { names.getValue(it.key) }
        val rotated = if (top.size <= TOP) {
            top
        } else {
            val shift = ((nowMs / ROTATION_MS) % top.size).toInt()
            List(TOP) { top[(shift + it) % top.size] }
        }
        return listOfNotNull(first) + rotated
    }

    /** Сравнение имён без регистра, «ё» и лишних пробелов. */
    fun key(name: String): String = name.trim().lowercase().replace('ё', 'е').replace(Regex("\\s+"), " ")
}
