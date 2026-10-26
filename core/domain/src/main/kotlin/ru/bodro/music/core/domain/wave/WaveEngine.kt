package ru.bodro.music.core.domain.wave

import kotlin.math.floor
import ru.bodro.music.core.model.Song
import ru.bodro.music.core.model.WaveSettings

/**
 * «Моя волна»: собирает кандидатов из [WaveSource], применяет жёсткие правила и взвешенно выбирает порцию.
 *
 * Жёсткие правила:
 * - дизлайкнутые треки пользователя не попадают никогда;
 * - трек, игравший за последние 7 дней, не повторяется (лайкнутый — не чаще раза в 2 дня);
 * - не больше [MAX_ARTIST_RUN] треков одного исполнителя подряд (с учётом хвоста очереди);
 * - исполнитель с ≥ 3 дизлайками и 0 лайков получает вес в 5 раз меньше;
 * - ранние пропуски понижают вес исполнителя и жанра (с весом контекста); пропущенный в волне трек не
 *   возвращается [WaveProfile.skipCooldownMs], после 3 пропусков без полного прослушивания его вес ниже.
 *
 * Детерминирован: вся случайность — из [WaveRequest.random].
 */
class WaveEngine(
    private val sources: List<WaveSource>,
    private val discoveryShare: () -> Double? = { null },
) {
    suspend fun nextBatch(request: WaveRequest): List<Song> {
        val candidates = sources.flatMap { source ->
            try {
                source.candidates(request)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }
        return select(candidates, request)
    }

    /** Чистая часть алгоритма: отбор и упорядочивание (удобно для тестов). */
    fun select(candidates: List<WaveCandidate>, request: WaveRequest): List<Song> {
        val profile = request.profile
        val excluded = request.recentSongs.mapTo(HashSet()) { it.id }
        val eligible = candidates
            .asSequence()
            .filter { it.song.id !in excluded }
            .filter { isAllowed(it.song, request) }
            .groupBy { it.song.id }
            .map { (_, same) -> same.maxBy { it.weight } }
            .map { it.copy(weight = adjustedWeight(it, profile)) }
            .filter { it.weight > 0.0 }
            .toList()
        if (eligible.isEmpty()) return emptyList()

        val shares = WaveShares.forSettings(request.settings, discoveryShare())
        val byBucket = eligible.groupBy { it.bucket }.mapValues { it.value.toMutableList() }
        val targets = allocate(request.batchSize, shares)
        val picked = mutableListOf<WaveCandidate>()
        for (bucket in WaveBucket.entries) {
            val pool = byBucket[bucket] ?: continue
            repeat(minOf(targets.getValue(bucket), pool.size)) {
                picked += pool.removeAt(weightedIndex(pool, request))
            }
        }
        // Не хватило в какой-то корзине — добираем из остальных.
        val leftovers = byBucket.values.flatten().toMutableList()
        while (picked.size < request.batchSize && leftovers.isNotEmpty()) {
            picked += leftovers.removeAt(weightedIndex(leftovers, request))
        }
        val arranged = arrange(picked.map { it.song }, request)
        if (arranged.size < picked.size && leftovers.isNotEmpty()) {
            // Часть треков не встала из-за правила исполнителей — пробуем добрать другими исполнителями.
            val extra = leftovers.map { it.song }.shuffled(request.random)
            return arrange(arranged + extra, request, keepOrderPrefix = arranged.size).take(request.batchSize)
        }
        return arranged
    }

    internal fun isAllowed(song: Song, request: WaveRequest): Boolean {
        val profile = request.profile
        if (song.id in profile.dislikedSongIds || song.isDislikedOnServer) return false
        if (!matchesFilters(song, request.settings)) return false
        val skipped = profile.lastSkippedAt[song.id]?.let { request.nowMs - it < profile.skipCooldownMs } == true
        val lastPlayed = profile.lastPlayedAt[song.id]
        val liked = song.id in profile.likedSongIds || song.starred
        val cooldown = if (liked) LIKED_COOLDOWN_MS else COOLDOWN_MS
        return !skipped && (lastPlayed == null || request.nowMs - lastPlayed >= cooldown)
    }

    private fun matchesFilters(song: Song, settings: WaveSettings): Boolean {
        settings.genre?.let { genre -> if (!song.genre.equals(genre, ignoreCase = true)) return false }
        settings.yearRange?.let { range -> if (song.year == null || song.year !in range) return false }
        return true
    }

    internal fun adjustedWeight(candidate: WaveCandidate, profile: WaveProfile): Double {
        val song = candidate.song
        var weight = candidate.weight
        val artistId = song.artistId
        if (artistId != null) {
            val dislikes = profile.artistDislikes[artistId] ?: 0
            val likes = profile.artistLikes[artistId] ?: 0
            if (dislikes >= ARTIST_DISLIKE_THRESHOLD && likes == 0) weight /= ARTIST_DISLIKE_PENALTY
            val skips = profile.artistSkips[artistId] ?: 0.0
            weight *= maxOf(MIN_SKIP_FACTOR, 1.0 / (1.0 + ARTIST_SKIP_STEP * skips))
        }
        song.genre?.let { genre ->
            val skips = profile.genreSkips[genre] ?: 0.0
            weight *= maxOf(MIN_SKIP_FACTOR, 1.0 / (1.0 + GENRE_SKIP_STEP * skips))
        }
        if ((profile.songEarlySkips[song.id] ?: 0) >= WaveProfile.REPEATED_SKIPS) weight *= WaveProfile.REPEATED_SKIP_FACTOR
        return weight
    }

    /** Метод наибольшего остатка: точное распределение [size] по долям. */
    internal fun allocate(size: Int, shares: WaveShares): Map<WaveBucket, Int> {
        val normalized = shares.normalized()
        val raw = WaveBucket.entries.associateWith { normalized.of(it) * size }
        val result = raw.mapValues { floor(it.value).toInt() }.toMutableMap()
        var rest = size - result.values.sum()
        raw.entries.sortedByDescending { it.value - floor(it.value) }.forEach { (bucket, _) ->
            if (rest > 0) {
                result[bucket] = result.getValue(bucket) + 1
                rest--
            }
        }
        return result
    }

    private fun weightedIndex(pool: List<WaveCandidate>, request: WaveRequest): Int {
        val total = pool.sumOf { it.weight }
        var point = request.random.nextDouble() * total
        for ((index, candidate) in pool.withIndex()) {
            point -= candidate.weight
            if (point < 0) return index
        }
        return pool.lastIndex
    }

    /**
     * Перемешивает порцию так, чтобы не было больше [MAX_ARTIST_RUN] треков одного исполнителя подряд,
     * учитывая хвост уже стоящей очереди. Треки, которые невозможно поставить, отбрасываются.
     */
    internal fun arrange(songs: List<Song>, request: WaveRequest, keepOrderPrefix: Int = 0): List<Song> {
        val result = songs.take(keepOrderPrefix).toMutableList()
        val remaining = songs.drop(keepOrderPrefix).shuffled(request.random).toMutableList()
        val tail = request.recentSongs.takeLast(MAX_ARTIST_RUN)
        while (remaining.isNotEmpty()) {
            val history = tail + result
            val index = remaining.indexOfFirst { !wouldExceedRun(history, it) }
            if (index < 0) break
            result += remaining.removeAt(index)
        }
        return result
    }

    private fun wouldExceedRun(history: List<Song>, next: Song): Boolean {
        val key = artistKey(next) ?: return false
        val last = history.takeLast(MAX_ARTIST_RUN)
        return last.size == MAX_ARTIST_RUN && last.all { artistKey(it) == key }
    }

    companion object {
        const val MAX_ARTIST_RUN = 2
        const val COOLDOWN_MS = 7L * 24 * 60 * 60 * 1000
        const val LIKED_COOLDOWN_MS = 2L * 24 * 60 * 60 * 1000
        const val ARTIST_DISLIKE_THRESHOLD = 3
        const val ARTIST_DISLIKE_PENALTY = 5.0
        const val ARTIST_SKIP_STEP = 0.15
        const val GENRE_SKIP_STEP = 0.10
        const val MIN_SKIP_FACTOR = 0.25
        const val BATCH_SIZE = 20
        const val REFILL_THRESHOLD = 5

        fun artistKey(song: Song): String? = song.artistId ?: song.artist?.lowercase()
    }
}
