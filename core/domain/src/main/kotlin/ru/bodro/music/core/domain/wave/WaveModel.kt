package ru.bodro.music.core.domain.wave

import kotlin.random.Random
import ru.bodro.music.core.model.Song
import ru.bodro.music.core.model.WaveMode
import ru.bodro.music.core.model.WaveSeed
import ru.bodro.music.core.model.WaveSettings

/** Корзины пула кандидатов волны. */
enum class WaveBucket {
    /** Лайкнутые треки. */
    Liked,

    /** Часто и недавно слушаемое. */
    Familiar,

    /** «Соседи по вкусу»: те же исполнители и жанры. */
    Neighbors,

    /** «Открытия»: случайное по любимым жанрам, новинки. */
    Discovery,
}

data class WaveCandidate(val song: Song, val bucket: WaveBucket, val weight: Double = 1.0)

/** Локальные данные пользователя, на которых строится волна (собираются из Room + сервера). */
data class WaveProfile(
    val dislikedSongIds: Set<String> = emptySet(),
    val likedSongIds: Set<String> = emptySet(),
    /** songId → когда последний раз играл (мс). */
    val lastPlayedAt: Map<String, Long> = emptyMap(),
    /** artistId → число дизлайкнутых треков. */
    val artistDislikes: Map<String, Int> = emptyMap(),
    /** artistId → число лайкнутых треков. */
    val artistLikes: Map<String, Int> = emptyMap(),
    /** artistId → число ранних пропусков. */
    val artistSkips: Map<String, Int> = emptyMap(),
    /** жанр → число ранних пропусков. */
    val genreSkips: Map<String, Int> = emptyMap(),
    /** Любимые исполнители (по лайкам и прослушиваниям), по убыванию. */
    val topArtistIds: List<String> = emptyList(),
    /** Любимые жанры, по убыванию. */
    val topGenres: List<String> = emptyList(),
)

data class WaveRequest(
    val settings: WaveSettings,
    val profile: WaveProfile,
    /** Текущий трек — для «похожих» (getSimilarSongs2). */
    val currentSong: Song?,
    /** Уже стоящие в очереди / сыгранные в этой сессии треки (не повторять, учитывать подряд идущих исполнителей). */
    val recentSongs: List<Song>,
    val batchSize: Int,
    val nowMs: Long,
    val random: Random,
)

/**
 * Источник кандидатов. Сейчас один — [LocalWaveSource] (клиентский), позже можно добавить серверный
 * (ListenBrainz и т.п.) без изменения UI и [WaveEngine].
 */
interface WaveSource {
    val name: String

    suspend fun candidates(request: WaveRequest): List<WaveCandidate>
}

/** Доли корзин в порции. */
data class WaveShares(val liked: Double, val familiar: Double, val neighbors: Double, val discovery: Double) {
    fun of(bucket: WaveBucket): Double = when (bucket) {
        WaveBucket.Liked -> liked
        WaveBucket.Familiar -> familiar
        WaveBucket.Neighbors -> neighbors
        WaveBucket.Discovery -> discovery
    }

    fun normalized(): WaveShares {
        val sum = liked + familiar + neighbors + discovery
        if (sum <= 0.0) return DEFAULT
        return WaveShares(liked / sum, familiar / sum, neighbors / sum, discovery / sum)
    }

    /** Подменяет долю «открытий», остальные корзины масштабируются пропорционально. */
    fun withDiscovery(share: Double): WaveShares {
        val target = share.coerceIn(0.0, 0.9)
        val rest = liked + familiar + neighbors
        if (rest <= 0.0) return WaveShares(0.0, 0.0, 0.0, 1.0)
        val k = (1.0 - target) / rest
        return WaveShares(liked * k, familiar * k, neighbors * k, target)
    }

    companion object {
        val DEFAULT = WaveShares(liked = 0.25, familiar = 0.20, neighbors = 0.35, discovery = 0.20)

        /**
         * Доли по режиму и «семени» волны.
         * @param discoveryShare доля «открытий» из настроек (null — по умолчанию для режима).
         */
        fun forSettings(settings: WaveSettings, discoveryShare: Double? = null): WaveShares {
            val base = when (settings.mode) {
                WaveMode.Default -> DEFAULT
                WaveMode.Favorites -> WaveShares(0.55, 0.30, 0.15, 0.0)
                WaveMode.Discover -> WaveShares(0.05, 0.05, 0.40, 0.50)
                WaveMode.Popular -> WaveShares(0.25, 0.55, 0.15, 0.05)
                WaveMode.TrackMix -> WaveShares(0.70, 0.30, 0.0, 0.0)
                WaveMode.AlbumMix -> WaveShares(0.20, 0.60, 0.20, 0.0)
                WaveMode.DecadeMix -> WaveShares(0.30, 0.20, 0.25, 0.25)
            }
            val seeded = when (settings.seed) {
                is WaveSeed.Artist, is WaveSeed.Track, is WaveSeed.Genre ->
                    WaveShares(base.liked * 0.5, base.familiar * 0.5, base.neighbors + 0.35, base.discovery * 0.6)
                is WaveSeed.SongList -> WaveShares(0.6, 0.0, 0.3, 0.1)
                WaveSeed.Collection -> WaveShares(base.liked + 0.1, base.familiar + 0.1, base.neighbors, base.discovery * 0.5)
                WaveSeed.Everything -> base
            }.normalized()
            val modeHasDiscovery = settings.mode == WaveMode.Default || settings.mode == WaveMode.DecadeMix
            return if (discoveryShare != null && modeHasDiscovery && settings.seed == WaveSeed.Everything) {
                seeded.withDiscovery(discoveryShare)
            } else {
                seeded
            }
        }
    }
}
