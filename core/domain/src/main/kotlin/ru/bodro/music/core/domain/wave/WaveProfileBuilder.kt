package ru.bodro.music.core.domain.wave

import ru.bodro.music.core.model.Song

/** Запись локальной истории прослушиваний (из Room). */
data class PlayHistoryItem(
    val songId: String,
    val artistId: String?,
    val genre: String?,
    val playedAtMs: Long,
    val skippedEarly: Boolean,
)

/** Собирает [WaveProfile] из лайков, дизлайков и локальной истории. */
object WaveProfileBuilder {
    fun build(
        liked: List<Song>,
        disliked: List<Song>,
        history: List<PlayHistoryItem>,
        nowMs: Long,
        skipStatsSinceMs: Long = 0,
    ): WaveProfile {
        val lastPlayed = HashMap<String, Long>()
        history.filter { !it.skippedEarly }.forEach { item ->
            lastPlayed.merge(item.songId, item.playedAtMs, ::maxOf)
        }
        val recentSkips = history.filter { it.skippedEarly && it.playedAtMs >= skipStatsSinceMs }
        val artistScore = HashMap<String, Double>()
        val genreScore = HashMap<String, Double>()
        liked.forEach { song ->
            song.artistId?.let { artistScore.merge(it, LIKE_SCORE, Double::plus) }
            song.genre?.let { genreScore.merge(it, LIKE_SCORE, Double::plus) }
        }
        history.filter { !it.skippedEarly }.forEach { item ->
            // Недавние прослушивания важнее старых.
            val ageDays = ((nowMs - item.playedAtMs) / DAY_MS).coerceAtLeast(0)
            val score = 1.0 / (1.0 + ageDays / 30.0)
            item.artistId?.let { artistScore.merge(it, score, Double::plus) }
            item.genre?.let { genreScore.merge(it, score, Double::plus) }
        }
        return WaveProfile(
            dislikedSongIds = disliked.mapTo(HashSet()) { it.id },
            likedSongIds = liked.mapTo(HashSet()) { it.id },
            lastPlayedAt = lastPlayed,
            artistDislikes = disliked.mapNotNull { it.artistId }.groupingBy { it }.eachCount(),
            artistLikes = liked.mapNotNull { it.artistId }.groupingBy { it }.eachCount(),
            artistSkips = recentSkips.mapNotNull { it.artistId }.groupingBy { it }.eachCount(),
            genreSkips = recentSkips.mapNotNull { it.genre }.groupingBy { it }.eachCount(),
            topArtistIds = artistScore.entries.sortedByDescending { it.value }.map { it.key },
            topGenres = genreScore.entries.sortedByDescending { it.value }.map { it.key },
        )
    }

    private const val LIKE_SCORE = 3.0
    private const val DAY_MS = 24L * 60 * 60 * 1000
}
