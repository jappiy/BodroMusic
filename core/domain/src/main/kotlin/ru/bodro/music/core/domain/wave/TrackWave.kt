package ru.bodro.music.core.domain.wave

import kotlin.random.Random
import ru.bodro.music.core.model.Song

/**
 * Откуда трек попал в «Волну по треку». Порядок — от самого похожего к «расширению круга».
 * [local] — источник отвечает быстро (своя библиотека и bodrod), остальные ходят во внешние сервисы и догружаются
 * в фоне: первая порция волны не ждёт их.
 */
enum class TrackWaveSource(val label: String, val local: Boolean) {
    /** Похожие по звучанию (эмбеддинги анализатора bodrod). */
    Sound("звук", true),

    /** Last.fm `track.getSimilar` через bodrod — только треки из библиотеки. */
    LastFm("Last.fm", false),

    /** Треки похожих исполнителей (Navidrome `getSimilarSongs2`, внешние агенты Navidrome). */
    SimilarArtists("похожие исполнители", false),

    /** Тот же исполнитель. */
    SameArtist("исполнитель", true),

    /** Расширение круга: тот же жанр. Только если похожих мало. */
    Genre("жанр", true),
}

data class TrackWaveCandidate(val song: Song, val source: TrackWaveSource, val score: Double)

/** Выбранный трек и почему он в волне — для журнала. */
data class TrackWavePick(val song: Song, val source: TrackWaveSource, val score: Double)

data class TrackWaveBatch(
    val picks: List<TrackWavePick>,
    /** Похожих не хватило — добрали треками жанра (честное расширение, а не случайные треки). */
    val expanded: Boolean,
    /** Сколько кандидатов отсеяно кулдауном «играл недавно» и возвращено, потому что иначе порция пустая. */
    val cooldownRelaxed: Boolean,
) {
    val songs: List<Song> get() = picks.map { it.song }
}

/**
 * «Волна по треку» (и «Похожие по звучанию»): только похожее на трек-семя, без случайных треков, лайков и
 * «часто слушаемого» — их раньше подмешивала общая «Моя волна», и волна по треку звучала как случайная.
 *
 * Правила отбора:
 * - дизлайкнутые и трек-семя — никогда; уже стоящие в очереди — не повторяются;
 * - трек из кулдауна ([WaveProfile.lastPlayedAt] за 7 дней, недавний ранний пропуск) пропускается, но если без них
 *   порция выходит меньше [MIN_BATCH], кулдаун ослабляется (флаг [TrackWaveBatch.cooldownRelaxed]);
 * - тот же исполнитель — не больше [SAME_ARTIST_SHARE] порции и не больше [WaveEngine.MAX_ARTIST_RUN] подряд;
 * - «жанр» берётся, только когда похожих меньше порции ([TrackWaveBatch.expanded]);
 * - порядок — по скору с лёгким разбросом, чтобы волна по одному треку не повторялась слово в слово.
 */
object TrackWavePlanner {
    const val MIN_BATCH = 5
    const val SAME_ARTIST_SHARE = 0.3

    fun select(
        seed: Song,
        candidates: List<TrackWaveCandidate>,
        profile: WaveProfile,
        recent: List<Song>,
        batchSize: Int,
        nowMs: Long,
        random: Random,
        sources: Set<TrackWaveSource> = TrackWaveSource.entries.toSet(),
    ): TrackWaveBatch {
        val excluded = recent.mapTo(HashSet()) { it.id } + seed.id
        val best = candidates.asSequence()
            .filter { it.source in sources }
            .filter { it.song.id !in excluded }
            .filter { it.song.id !in profile.dislikedSongIds && !it.song.isDislikedOnServer }
            .groupBy { it.song.id }
            .map { (_, same) -> same.maxBy { it.score * weightOf(it.source) } }
            .map { it.copy(score = it.score * weightOf(it.source) * skipFactor(it.song, profile)) }
            .toList()
        val fresh = best.filter { !inCooldown(it.song, profile, nowMs) }
        val relaxed = fresh.count { it.source != TrackWaveSource.Genre } < MIN_BATCH && best.size > fresh.size
        val pool = if (relaxed) best else fresh
        val similar = pool.filter { it.source != TrackWaveSource.Genre }
        val expanded = similar.size < batchSize && pool.any { it.source == TrackWaveSource.Genre }
        val ordered =
            jitter(similar, random) + if (expanded) jitter(pool.filter { it.source == TrackWaveSource.Genre }, random) else emptyList()
        return TrackWaveBatch(arrange(seed, ordered, recent, batchSize), expanded, relaxed)
    }

    /** Чем ближе источник к «похоже на этот трек», тем больше вес. */
    private fun weightOf(source: TrackWaveSource): Double = when (source) {
        TrackWaveSource.Sound -> 1.0
        TrackWaveSource.LastFm -> 0.95
        TrackWaveSource.SimilarArtists -> 0.75
        TrackWaveSource.SameArtist -> 0.6
        TrackWaveSource.Genre -> 0.3
    }

    /** Повторные ранние пропуски без полного прослушивания — вес трека ниже (один пропуск — только кулдаун). */
    private fun skipFactor(song: Song, profile: WaveProfile): Double =
        if ((profile.songEarlySkips[song.id] ?: 0) >= WaveProfile.REPEATED_SKIPS) WaveProfile.REPEATED_SKIP_FACTOR else 1.0

    private fun inCooldown(song: Song, profile: WaveProfile, nowMs: Long): Boolean {
        profile.lastSkippedAt[song.id]?.let { if (nowMs - it < profile.skipCooldownMs) return true }
        val played = profile.lastPlayedAt[song.id] ?: return false
        val liked = song.id in profile.likedSongIds || song.starred
        return nowMs - played < if (liked) WaveEngine.LIKED_COOLDOWN_MS else WaveEngine.COOLDOWN_MS
    }

    /** По убыванию скора с разбросом ±15 %: близкие по скору треки меняются местами от волны к волне. */
    private fun jitter(list: List<TrackWaveCandidate>, random: Random): List<TrackWaveCandidate> =
        list.map { it to it.score * (1.0 + (random.nextDouble() - 0.5) * JITTER) }.sortedByDescending { it.second }.map { it.first }

    private fun arrange(seed: Song, ordered: List<TrackWaveCandidate>, recent: List<Song>, batchSize: Int): List<TrackWavePick> {
        val seedArtist = WaveEngine.artistKey(seed)
        val maxSameArtist = maxOf(1, (batchSize * SAME_ARTIST_SHARE).toInt())
        val picked = mutableListOf<TrackWaveCandidate>()
        val rest = ordered.toMutableList()
        while (picked.size < batchSize && rest.isNotEmpty()) {
            val history = recent.takeLast(WaveEngine.MAX_ARTIST_RUN) + picked.map { it.song }
            val sameArtistCount = picked.count { WaveEngine.artistKey(it.song) == seedArtist }
            val index = rest.indexOfFirst { c ->
                val key = WaveEngine.artistKey(c.song)
                val run = history.takeLast(WaveEngine.MAX_ARTIST_RUN)
                val tooLong = key != null && run.size == WaveEngine.MAX_ARTIST_RUN && run.all { WaveEngine.artistKey(it) == key }
                val tooMany = seedArtist != null && key == seedArtist && sameArtistCount >= maxSameArtist
                !tooLong && !tooMany
            }
            if (index < 0) break
            picked += rest.removeAt(index)
        }
        return picked.map { TrackWavePick(it.song, it.source, it.score) }
    }

    private const val JITTER = 0.3
}
