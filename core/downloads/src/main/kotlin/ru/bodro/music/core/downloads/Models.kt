package ru.bodro.music.core.downloads

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Трек из внешнего каталога (Яндекс Музыка / Deezer / Last.fm). [librarySongId] — если он уже есть в Navidrome. */
@Serializable
data class DiscoverTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val coverUrl: String? = null,
    val source: String,
    val sourceId: String,
    val librarySongId: String? = null,
) {
    val key: String get() = requestKey(artist, title)
}

@Serializable
data class DiscoverArtist(
    val name: String,
    val coverUrl: String? = null,
    val source: String,
    val sourceId: String,
)

@Serializable
enum class DiscoverIntent {
    @SerialName("track")
    Track,

    @SerialName("artist")
    Artist,

    @SerialName("none")
    None,
}

/** Результат глобального поиска. При [DiscoverIntent.Artist] запрос понят как имя исполнителя. */
@Serializable
data class DiscoverResult(
    val intent: DiscoverIntent = DiscoverIntent.None,
    val source: String? = null,
    val tracks: List<DiscoverTrack> = emptyList(),
    val artists: List<DiscoverArtist> = emptyList(),
    val artist: DiscoverArtist? = null,
    val topTracks: List<DiscoverTrack> = emptyList(),
) {
    val isEmpty: Boolean get() = tracks.isEmpty() && artists.isEmpty() && topTracks.isEmpty()
}

@Serializable
enum class RequestKind {
    @SerialName("track")
    Track,

    /** Альбом целиком (bodrod 0.7.0+). */
    @SerialName("album")
    Album,

    @SerialName("artist")
    Artist,
}

@Serializable
enum class RequestStatus {
    @SerialName("queued")
    Queued,

    @SerialName("searching")
    Searching,

    @SerialName("downloading")
    Downloading,

    @SerialName("importing")
    Importing,

    @SerialName("completed")
    Completed,

    @SerialName("failed")
    Failed,

    @SerialName("cancelled")
    Cancelled,
    ;

    val isActive: Boolean get() = this == Queued || this == Searching || this == Downloading || this == Importing
}

/** Заявка на загрузку на сервере. */
@Serializable
data class DownloadRequest(
    val id: String,
    val kind: RequestKind,
    val artist: String,
    val title: String? = null,
    val album: String? = null,
    val coverUrl: String? = null,
    val status: RequestStatus,
    val message: String? = null,
    val progress: Float? = null,
    val songId: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** Итоговый источник: «Яндекс Музыка, FLAC» (bodrod 0.7.0+). */
    val source: String? = null,
    /** Какие источники пробовались и с каким результатом. */
    val attempts: List<SourceAttempt> = emptyList(),
) {
    val key: String get() = when (kind) {
        RequestKind.Artist -> artistKey(artist)
        RequestKind.Album -> albumKey(artist, album.orEmpty())
        RequestKind.Track -> requestKey(artist, title.orEmpty())
    }
}

/** Попытка источника цепочки: [result] — `ok` (скачано), `fail` (не нашёл), `skip` (выключен или недоступен). */
@Serializable
data class SourceAttempt(
    val source: String,
    val title: String,
    val result: String,
    val reason: String? = null,
    val at: Long = 0,
)

@Serializable
data class ServiceInfo(
    val version: String,
    val trackDownloads: Boolean,
    val artistDownloads: Boolean,
    val metadataSources: List<String> = emptyList(),
    /** На сервере настроен раздел «На удаление» (bodrod 0.3.0+ с доступом к БД Navidrome). */
    val cleanup: Boolean = false,
    /** Пользователь — администратор Navidrome: может удалять треки и править чёрный список. */
    val admin: Boolean = false,
    /** Включено «Обслуживание библиотеки» (bodrod 0.4.0+). */
    val maintenance: Boolean = false,
    /** Скачивание альбомов целиком (bodrod 0.7.0+). */
    val albumDownloads: Boolean = false,
    /** Есть экран «Источники загрузки» (bodrod 0.7.0+). */
    val sources: Boolean = false,
    /** Discovery-пул «На пробу» и события обратной связи (bodrod 0.9.0+). */
    val pool: Boolean = false,
    /** Анализ звучания: настроение волны, «Похожие по звучанию» (bodrod 0.12.0+). */
    val analysis: Boolean = false,
)

/** Источник загрузки. [state]: `ok` — работает, `disabled` — выключен, `unavailable` — не может работать ([reason]). */
@Serializable
data class DownloadSource(
    val name: String,
    val title: String,
    val enabled: Boolean,
    val state: String,
    val reason: String? = null,
    /** track / album / artist. */
    val kinds: List<String> = emptyList(),
    /** Версия yt-dlp, когда проверялась подписка и т. п. */
    val details: String? = null,
    /** Яндекс (bodrod 0.13.0+): чей токен — `user` (ваш) или `global` (общий) и то же текстом. */
    val tokenSource: String? = null,
    val tokenSourceTitle: String? = null,
)

/** Цепочка источников по порядку и «Яндекс: метаданные». */
@Serializable
data class DownloadSources(
    val sources: List<DownloadSource> = emptyList(),
    val yandexMetadata: Boolean = true,
    val yandexMetadataReason: String? = null,
)

@Serializable
data class ExportCounts(val total: Int = 0, val done: Int = 0, val skipped: Int = 0, val failed: Int = 0, val pending: Int = 0)

/** Массовая выгрузка из Яндекса. [state]: idle / collecting / running / paused / finished. */
@Serializable
data class ExportStatus(
    val state: String = "idle",
    val message: String? = null,
    val likes: Boolean = true,
    val playlists: Boolean = true,
    val counts: ExportCounts = ExportCounts(),
    val artistsSelected: Int = 0,
    val artistsTotal: Int = 0,
    val current: String? = null,
    val updatedAt: Long? = null,
) {
    val isActive: Boolean get() = state == "collecting" || state == "running"
}

/** Артист из «Мне нравится» в Яндексе: [selected] — брать дискографию целиком. */
@Serializable
data class ExportArtist(val id: String, val name: String, val liked: Int = 0, val selected: Boolean = false)

/** Популярные треки исполнителя по каталогу; у тех, что есть в библиотеке, заполнен [DiscoverTrack.librarySongId]. */
@Serializable
data class ArtistPopular(
    val source: String? = null,
    val artist: DiscoverArtist? = null,
    val tracks: List<DiscoverTrack> = emptyList(),
)

/** [missing] — треков больше нет в библиотеке; [replaced] — трек заменён другой копией (старый id → новый). */
@Serializable
data class LibraryCheck(val missing: List<String> = emptyList(), val replaced: Map<String, String> = emptyMap())

/** Запуск «Обслуживания библиотеки». [stats] — счётчики: `duplicates_removed`, `tags_fixed`… */
@Serializable
data class MaintenanceRun(
    val id: Long,
    val trigger: String = "",
    /** running / completed / failed. */
    val status: String,
    val dryRun: Boolean = false,
    val stats: Map<String, Int> = emptyMap(),
    val error: String? = null,
    val startedAt: Long = 0,
    val finishedAt: Long? = null,
)

@Serializable
data class MaintenanceStatus(
    val enabled: Boolean = false,
    val running: Boolean = false,
    val dryRun: Boolean = false,
    /** «ЧЧ:ММ» — время ежедневного запуска. */
    val schedule: String = "",
    val nextRunAt: Long? = null,
    val quarantineDays: Int = 30,
    val lastRun: MaintenanceRun? = null,
)

/** Что сделал (или нашёл) регламент. [kind]: short / duplicate / album / artist_merge / tags / cover / mismatch. */
@Serializable
data class MaintenanceAction(
    val id: Long,
    val runId: Long,
    val kind: String,
    /** `true` — исправлено; `false` — только найдено или запланировано пробным запуском ([planned]). */
    val applied: Boolean,
    /** Пробный запуск: исправление было бы сделано, но файлы не менялись. */
    val planned: Boolean = false,
    val artist: String? = null,
    val title: String? = null,
    val album: String? = null,
    val path: String? = null,
    val details: String = "",
    val createdAt: Long = 0,
    val quarantineId: Long? = null,
    /** Файл в карантине, его можно вернуть. */
    val restorable: Boolean = false,
)

@Serializable
data class MaintenanceActionsPage(val total: Int = 0, val items: List<MaintenanceAction> = emptyList())

/** Пользователь Navidrome для вкладок «Не нравятся …» (служебные учётки сервер не отдаёт). */
@Serializable
data class CleanupUser(val username: String, val isAdmin: Boolean = false)

/** Трек с дизлайком. [path] — путь к файлу относительно папки музыки на сервере. */
@Serializable
data class DislikedSong(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val albumId: String? = null,
    /** Секунды. */
    val duration: Int? = null,
    val size: Long? = null,
    val suffix: String? = null,
    val bitRate: Int? = null,
    val path: String = "",
    val dislikedBy: List<String> = emptyList(),
)

@Serializable
data class DeleteSongResult(val id: String, val ok: Boolean, val message: String = "")

/** Запись чёрного списка: трек удалён с сервера и не будет скачан снова. */
@Serializable
data class BlockEntry(
    val id: Long,
    val artist: String,
    val title: String,
    val album: String? = null,
    val createdBy: String = "",
    val createdAt: Long = 0,
    /** Сколько раз файл удалялся: первое удаление и повторные появления. */
    val removedCount: Int = 1,
    val lastRemovedAt: Long? = null,
)

@Serializable
data class BlocklistPage(val total: Int = 0, val items: List<BlockEntry> = emptyList())

/**
 * Событие прослушивания для рекомендаций. [event]: started / skipped / completed / liked / unliked / disliked /
 * playlisted / shown; [context]: wave / chart / library / playlist / pool.
 */
@Serializable
data class FeedbackEvent(
    val songId: String,
    val event: String,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    val context: String? = null,
    val at: Long,
)

/** [changed] — треки пула, у которых сменился статус (promoted / rejected / expired). */
@Serializable
data class FeedbackResult(val accepted: Int = 0, val changed: Map<String, String> = emptyMap())

/** Трек пула «На пробу». [status]: in_pool / promoted / rejected / expired. */
@Serializable
data class PoolItem(
    val songId: String? = null,
    val artist: String,
    val title: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val coverUrl: String? = null,
    val status: String = "in_pool",
    val source: String? = null,
    val addedAt: Long = 0,
    val fullPlays: Int = 0,
    val earlySkips: Int = 0,
    val shown: Int = 0,
    /** Звуковая близость к вкусу (0..1; `null` — не проанализирован). */
    val soundMatch: Float? = null,
    /** Можно ставить в волну (близость не ниже порога сервера). */
    val inWave: Boolean = true,
)

@Serializable
data class PoolList(val items: List<PoolItem> = emptyList(), val counts: Map<String, Int> = emptyMap(), val size: Int = 150)

/** Что прятать из исполнителей, альбомов и поиска: треки пула и альбомы/исполнители, у которых все треки в пуле. */
@Serializable
data class PoolHidden(
    val songIds: List<String> = emptyList(),
    val albumIds: List<String> = emptyList(),
    val artistIds: List<String> = emptyList(),
)

/** Чарт (bodrod 0.11.0+): [covers] — обложки первых треков для коллажа. */
@Serializable
data class Chart(
    val id: String,
    val title: String,
    val source: String = "",
    val updatedAt: Long? = null,
    val covers: List<String> = emptyList(),
    val size: Int = 0,
)

/**
 * Позиция чарта. [movement]: up / down / same / new. [state]: library и pool — можно слушать ([songId]);
 * queued — в очереди; downloading — качается; unavailable — нет на сервере.
 * [requestStatus] (bodrod 0.13.0+) — статус заявки bodrod на этот трек: queued, downloading, completed, failed…
 */
@Serializable
data class ChartEntry(
    val position: Int,
    val artist: String,
    val title: String,
    val album: String? = null,
    val coverUrl: String? = null,
    val durationMs: Long? = null,
    val prevPosition: Int? = null,
    val movement: String = "same",
    val state: String = "unavailable",
    val songId: String? = null,
    val requestId: String? = null,
    val requestStatus: String? = null,
) {
    val isPlayable: Boolean get() = songId != null && (state == "library" || state == "pool")

    /** Скачивание идёт (в очереди или качается) — экран чарта обновляет доступность сам. */
    val isPending: Boolean get() = state == "queued" || state == "downloading"

    /** Скачано, но Navidrome ещё не нашёл трек (ждём скана). */
    val isReady: Boolean get() = !isPlayable && !isPending && requestStatus == "completed"
}

/**
 * Источник движка рекомендаций: [weight] — ручной (0..3), [learnedWeight] — выученный по доле promote.
 * [available] = false — ошибка конфигурации ([reason]): источник не участвует в подборе, даже если включён.
 * Счётчики — статусы предложенных треков: в очереди, качается, скачано (резерв или пул), не найдено, в библиотеку, отклонено.
 */
@Serializable
data class EngineSource(
    val name: String,
    val title: String,
    val enabled: Boolean = true,
    val weight: Float = 1f,
    val learnedWeight: Float = 1f,
    val available: Boolean = true,
    val reason: String? = null,
    val suggested: Int = 0,
    val queued: Int = 0,
    val downloading: Int = 0,
    val downloaded: Int = 0,
    val notFound: Int = 0,
    val promoted: Int = 0,
    val rejected: Int = 0,
) {
    /** Переключатель выглядит включённым, только если источник включён и настроен. */
    val active: Boolean get() = enabled && available
}

/** Признаки звучания трека (анализ на сервере). */
@Serializable
data class TrackFeatures(
    val bpm: Double? = null,
    val energy: Double? = null,
    val danceability: Double? = null,
    val voice: Double? = null,
    val key: String? = null,
    val scale: String? = null,
    val moods: Map<String, Double> = emptyMap(),
)

@Serializable
data class SimilarSongs(val ids: List<String> = emptyList())

@Serializable
data class AnalysisStatus(
    val enabled: Boolean = false,
    val analyzed: Int = 0,
    val errors: Int = 0,
    val library: Int = 0,
    val lastResultAt: Long? = null,
)

@Serializable
data class EngineRun(val at: Long = 0, val picked: Int = 0, val bySource: Map<String, Int> = emptyMap())

@Serializable
data class EngineStatus(
    val enabled: Boolean = false,
    val sources: List<EngineSource> = emptyList(),
    val newArtistShare: Float = 0.5f,
    val runs: Map<String, EngineRun?> = emptyMap(),
)

@Serializable
data class HarvestCollector(
    val name: String,
    val title: String,
    val lastAt: Long? = null,
    val disabled: String? = null,
    val count: Int? = null,
)

/** Архив Яндекса по аккаунту: [username] — логин Яндекса, [navidromeUser] — к кому привязан (`null` — ни к кому). */
@Serializable
data class HarvestAccount(
    val username: String,
    val navidromeUser: String? = null,
    val plus: Boolean? = null,
    val running: String? = null,
    val collectors: List<HarvestCollector> = emptyList(),
    val likes: Map<String, Int> = emptyMap(),
    val edges: Map<String, Int> = emptyMap(),
    val reserve: Int = 0,
    /** Резерв по статусам кандидата: queued, downloading, downloaded, notFound, promoted, rejected. */
    val reserveStates: Map<String, Int> = emptyMap(),
    val blocked: Int = 0,
    val similarDone: Int = 0,
    val error: String? = null,
)

@Serializable
data class HarvestStatus(val enabled: Boolean = false, val accounts: List<HarvestAccount> = emptyList())

@Serializable
internal data class EngineUpdateBody(
    val enabled: Map<String, Boolean>? = null,
    val weights: Map<String, Float>? = null,
    val newArtistShare: Float? = null,
)

@Serializable
internal data class YandexLinksBody(val links: Map<String, String?>)

@Serializable
internal data class FeedbackBody(val events: List<FeedbackEvent>)

@Serializable
internal data class DeleteSongsBody(val ids: List<String>)

@Serializable
internal data class SourcesUpdateBody(
    val order: List<String>? = null,
    val enabled: Map<String, Boolean>? = null,
    val yandexMetadata: Boolean? = null,
)

@Serializable
internal data class ExportStartBody(val likes: Boolean, val playlists: Boolean, val artists: Boolean)

@Serializable
internal data class ExportSelectBody(val selected: List<String>)

@Serializable
internal data class IdsBody(val ids: List<String>)

@Serializable
internal data class CreateRequestBody(
    val kind: RequestKind,
    val artist: String,
    val title: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val coverUrl: String? = null,
    /** Трек качается из позиции чарта — сервер привяжет позицию к скачанному файлу. */
    val chartId: String? = null,
    val chartPosition: Int? = null,
)

/** Ключ для сопоставления трека из каталога с заявкой. */
fun requestKey(artist: String, title: String): String = "${normalizeKey(artist)}|${normalizeKey(title)}"

fun artistKey(artist: String): String = "artist|${normalizeKey(artist)}"

fun albumKey(artist: String, album: String): String = "album|${normalizeKey(artist)}|${normalizeKey(album)}"

private val nonWord = Regex("[^\\p{L}\\p{N}]+")

private fun normalizeKey(value: String): String = value.lowercase().replace('ё', 'е').replace(nonWord, " ").trim()
