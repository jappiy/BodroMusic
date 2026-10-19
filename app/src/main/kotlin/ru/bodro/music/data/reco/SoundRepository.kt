package ru.bodro.music.data.reco

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import ru.bodro.music.core.domain.wave.SoundArranger
import ru.bodro.music.core.domain.wave.SoundFeatures
import ru.bodro.music.core.domain.wave.WaveMood
import ru.bodro.music.core.downloads.TrackFeatures
import ru.bodro.music.core.model.Song
import ru.bodro.music.core.subsonic.SubsonicClient
import ru.bodro.music.data.account.ConnectionManager
import ru.bodro.music.data.downloads.DownloadsRepository

/** Анализ звучания bodrod (0.12.0+): признаки для настроения и плавных переходов волны, «Похожие по звучанию». */
@Singleton
class SoundRepository @Inject constructor(
    private val downloads: DownloadsRepository,
    private val client: SubsonicClient,
    private val connection: ConnectionManager,
) {
    private var supported: Pair<Long, Boolean>? = null
    private val cache = object : LinkedHashMap<String, SoundFeatures?>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SoundFeatures?>?) = size > MAX_CACHE
    }

    suspend fun isSupported(): Boolean {
        val accountId = connection.activeAccountId ?: return false
        supported?.takeIf { it.first == accountId }?.let { return it.second }
        if (!downloads.isConfigured) return false
        val value = runCatching { downloads.info().analysis }.onFailure { if (it is CancellationException) throw it }.getOrNull()
        value?.let { supported = accountId to it }
        return value == true
    }

    /** Признаки треков (с кэшем); у непроанализированных — нет записи. */
    suspend fun features(ids: List<String>): Map<String, SoundFeatures> {
        if (!isSupported()) return emptyMap()
        val missing = ids.filter { it !in cache }
        if (missing.isNotEmpty()) {
            val loaded = runCatching { downloads.trackFeatures(missing) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
            if (loaded != null) missing.forEach { id -> cache[id] = loaded[id]?.toDomain() }
        }
        return ids.mapNotNull { id -> cache[id]?.let { id to it } }.toMap()
    }

    /** Порция волны: отбор по настроению (до [limit]) и плавный порядок от [current]. Без анализа — как есть. */
    suspend fun arrange(songs: List<Song>, mood: WaveMood, limit: Int, current: Song?): List<Song> {
        val features = features(songs.map { it.id } + listOfNotNull(current?.id))
        if (features.isEmpty()) return songs.take(limit)
        val picked = SoundArranger.filterByMood(songs, features, mood, limit)
        return SoundArranger.smooth(picked, features, current)
    }

    /** Похожие по звучанию треки библиотеки. */
    suspend fun similar(song: Song): List<Song> = coroutineScope {
        val gate = Semaphore(PARALLEL)
        downloads.similarBySound(song.id).map { id ->
            async { gate.withPermit { runCatching { client.getSong(id) }.getOrNull() } }
        }.mapNotNull { it.await() }
    }

    private fun TrackFeatures.toDomain() = SoundFeatures(
        bpm = bpm,
        energy = energy,
        happy = moods["happy"],
        sad = moods["sad"],
        aggressive = moods["aggressive"],
        relaxed = moods["relaxed"],
        danceability = danceability,
    )

    private companion object {
        const val MAX_CACHE = 5_000
        const val PARALLEL = 6
    }
}
