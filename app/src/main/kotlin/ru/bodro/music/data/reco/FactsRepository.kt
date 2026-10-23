package ru.bodro.music.data.reco

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import ru.bodro.music.core.downloads.ArtistFacts
import ru.bodro.music.core.model.Song
import ru.bodro.music.data.downloads.DownloadsRepository

/**
 * Строка-факт о треке или исполнителе на главной «Моя волна» (Last.fm через bodrod). Нет сервиса, сети или данных —
 * `null`, и строка не показывается. Кэш в памяти — на трек.
 */
@Singleton
class FactsRepository @Inject constructor(private val downloads: DownloadsRepository) {
    private val cache = object : LinkedHashMap<String, ArtistFacts>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArtistFacts>?) = size > MAX_CACHE
    }

    suspend fun factsFor(song: Song): ArtistFacts? {
        val artist = song.artist?.takeIf { it.isNotBlank() && downloads.isConfigured } ?: return null
        val key = "${artist.lowercase()}|${song.title.lowercase()}"
        val facts = synchronized(cache) { cache[key] }
            ?: runCatching { downloads.artistFacts(artist, song.title) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
                ?.also { synchronized(cache) { cache[key] = it } }
        return facts?.takeUnless { it.isEmpty }
    }

    private companion object {
        const val MAX_CACHE = 100
    }
}
