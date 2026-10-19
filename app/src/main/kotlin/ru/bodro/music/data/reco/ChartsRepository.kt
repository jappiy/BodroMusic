package ru.bodro.music.data.reco

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import ru.bodro.music.core.downloads.Chart
import ru.bodro.music.core.downloads.ChartEntry
import ru.bodro.music.core.downloads.DiscoverTrack
import ru.bodro.music.core.model.Song
import ru.bodro.music.core.subsonic.SubsonicClient
import ru.bodro.music.data.downloads.DownloadsRepository
import ru.bodro.music.data.settings.SettingsRepository

/** Чарты bodrod (0.11.0+): список для главной (порядок и скрытые — в настройках), позиции, треки для воспроизведения. */
@Singleton
class ChartsRepository @Inject constructor(
    private val downloads: DownloadsRepository,
    private val settings: SettingsRepository,
    private val client: SubsonicClient,
) {
    /** Все доступные чарты в порядке пользователя. Без bodrod или на старом bodrod — пусто. */
    suspend fun all(): List<Chart> {
        if (!downloads.isConfigured) return emptyList()
        val charts = try {
            downloads.charts()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }
        return arrange(charts, settings.current.chartsOrder)
    }

    /** Чарты для главной: без скрытых. */
    suspend fun home(): List<Chart> = all().filter { it.id !in settings.current.chartsHidden }

    suspend fun entries(id: String): List<ChartEntry> = downloads.chart(id)

    /** Треки чарта, которые можно слушать, — по порядку (по 6 запросов `getSong` параллельно). */
    suspend fun songs(entries: List<ChartEntry>): Map<String, Song> = coroutineScope {
        val gate = Semaphore(PARALLEL)
        entries.mapNotNull { it.songId?.takeIf { _ -> it.isPlayable } }.distinct().map { id ->
            async { gate.withPermit { runCatching { client.getSong(id) }.getOrNull() } }
        }.mapNotNull { it.await() }.associateBy { it.id }
    }

    /** «Скачать» недоступный трек — обычная заявка в цепочку источников. */
    suspend fun download(entry: ChartEntry) = downloads.downloadTrack(
        DiscoverTrack(
            title = entry.title,
            artist = entry.artist,
            album = entry.album,
            durationMs = entry.durationMs,
            coverUrl = entry.coverUrl,
            source = "chart",
            sourceId = "${entry.artist} — ${entry.title}",
        ),
    )

    suspend fun saveOrder(order: List<String>, hidden: Set<String>) {
        settings.update { it.copy(chartsOrder = order, chartsHidden = hidden) }
    }

    companion object {
        private const val PARALLEL = 6

        /** Сначала в сохранённом порядке, новые чарты — в конце в порядке сервера. */
        fun arrange(charts: List<Chart>, order: List<String>): List<Chart> {
            val index = order.withIndex().associate { it.value to it.index }
            return charts.withIndex().sortedBy { (i, chart) -> index[chart.id] ?: (order.size + i) }.map { it.value }
        }
    }
}
