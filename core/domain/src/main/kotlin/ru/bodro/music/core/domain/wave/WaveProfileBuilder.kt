package ru.bodro.music.core.domain.wave

import ru.bodro.music.core.domain.scrobble.PlayContext
import ru.bodro.music.core.domain.scrobble.PlayOutcome
import ru.bodro.music.core.model.Song

/** Запись локальной истории прослушиваний (из Room). */
data class PlayHistoryItem(
    val songId: String,
    val artistId: String?,
    val genre: String?,
    val playedAtMs: Long,
    val skippedEarly: Boolean,
    /** Итог по [ListenRules]; у записей до 0.30.0 — по [skippedEarly] (иначе считаем полным). */
    val outcome: PlayOutcome = if (skippedEarly) PlayOutcome.EarlySkip else PlayOutcome.Full,
    val context: PlayContext = PlayContext.Wave,
)

/**
 * Собирает [WaveProfile] из лайков, дизлайков и локальной истории учётки (история в Room — по `accountId`).
 *
 * - полное прослушивание — позитив: исполнитель и жанр поднимаются, трек уходит на кулдаун «играл недавно»;
 * - частичное — нейтрально: только кулдаун «играл недавно», без влияния на вкус;
 * - ранний пропуск — слабый негатив: в волне трек не возвращается [WaveProfile.skipCooldownMs] (по умолчанию 7 дней),
 *   исполнитель и жанр чуть ниже с весом контекста ([PlayContext.skipWeight]: волна 1, свой плейлист 0,1);
 *   вес самого трека понижается только после [WaveProfile.REPEATED_SKIPS] ранних пропусков без полного прослушивания.
 * Дизлайк — жёсткое исключение (в [WaveEngine] и [TrackWavePlanner]).
 */
object WaveProfileBuilder {
    fun build(
        liked: List<Song>,
        disliked: List<Song>,
        history: List<PlayHistoryItem>,
        nowMs: Long,
        skipStatsSinceMs: Long = 0,
        skipCooldownMs: Long = WaveProfile.DEFAULT_SKIP_COOLDOWN_MS,
    ): WaveProfile {
        val lastPlayed = HashMap<String, Long>()
        history.filter { it.outcome != PlayOutcome.EarlySkip }.forEach { item ->
            lastPlayed.merge(item.songId, item.playedAtMs, ::maxOf)
        }
        val recentSkips = history.filter { it.outcome == PlayOutcome.EarlySkip && it.playedAtMs >= skipStatsSinceMs }
        val artistScore = HashMap<String, Double>()
        val genreScore = HashMap<String, Double>()
        liked.forEach { song ->
            song.artistId?.let { artistScore.merge(it, LIKE_SCORE, Double::plus) }
            song.genre?.let { genreScore.merge(it, LIKE_SCORE, Double::plus) }
        }
        history.filter { it.outcome == PlayOutcome.Full }.forEach { item ->
            // Недавние прослушивания важнее старых.
            val ageDays = ((nowMs - item.playedAtMs) / DAY_MS).coerceAtLeast(0)
            val score = 1.0 / (1.0 + ageDays / 30.0)
            item.artistId?.let { artistScore.merge(it, score, Double::plus) }
            item.genre?.let { genreScore.merge(it, score, Double::plus) }
        }
        val artistSkips = HashMap<String, Double>()
        val genreSkips = HashMap<String, Double>()
        recentSkips.forEach { item ->
            item.artistId?.let { artistSkips.merge(it, item.context.skipWeight, Double::plus) }
            item.genre?.let { genreSkips.merge(it, item.context.skipWeight, Double::plus) }
        }
        // Кулдаун и счётчик пропусков трека — только за пропуски вне своих плейлистов и альбомов.
        val counted = recentSkips.filter { it.context != PlayContext.Own }
        val lastSkipped = HashMap<String, Long>()
        counted.forEach { lastSkipped.merge(it.songId, it.playedAtMs, ::maxOf) }
        val lastFull = HashMap<String, Long>()
        history.filter { it.outcome == PlayOutcome.Full }.forEach { lastFull.merge(it.songId, it.playedAtMs, ::maxOf) }
        val songSkips = counted.filter { it.playedAtMs > (lastFull[it.songId] ?: Long.MIN_VALUE) }
            .groupingBy { it.songId }.eachCount()
        return WaveProfile(
            dislikedSongIds = disliked.mapTo(HashSet()) { it.id },
            likedSongIds = liked.mapTo(HashSet()) { it.id },
            lastPlayedAt = lastPlayed,
            artistDislikes = disliked.mapNotNull { it.artistId }.groupingBy { it }.eachCount(),
            artistLikes = liked.mapNotNull { it.artistId }.groupingBy { it }.eachCount(),
            artistSkips = artistSkips,
            genreSkips = genreSkips,
            lastSkippedAt = lastSkipped,
            songEarlySkips = songSkips,
            skipCooldownMs = skipCooldownMs,
            topArtistIds = artistScore.entries.sortedByDescending { it.value }.map { it.key },
            topGenres = genreScore.entries.sortedByDescending { it.value }.map { it.key },
        )
    }

    private const val LIKE_SCORE = 3.0
    private const val DAY_MS = 24L * 60 * 60 * 1000
}
