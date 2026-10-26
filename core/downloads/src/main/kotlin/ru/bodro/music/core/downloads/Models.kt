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
    /** Подбор «Моей волны» на сервере: режимы по звучанию и темпоритм (bodrod 0.19.0+). */
    val wave: Boolean = false,
    /** Перенос лайков из Яндекс Музыки (bodrod 0.26.0+). */
    val likesImport: Boolean = false,
)

/** Трек из Яндекса в отчёте переноса лайков. [download] — none / queued / downloading / done / notFound / failed. */
@Serializable
data class LikedTrack(
    val id: String,
    val artist: String,
    val title: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val coverUrl: String? = null,
    val songId: String? = null,
    val requestId: String? = null,
    val download: String = "none",
)

@Serializable
data class LikesImportReport(
    val total: Int = 0,
    val already: Int = 0,
    val starred: Int = 0,
    val conflicts: Int = 0,
    val notFound: Int = 0,
)

/** Перенос лайков: [state] — idle / running / finished / failed; [done] из [total] — прогресс текущего шага. */
@Serializable
data class LikesImportStatus(
    val state: String = "idle",
    val message: String? = null,
    val done: Int = 0,
    val total: Int = 0,
    val lastRunAt: Long? = null,
    val report: LikesImportReport? = null,
    val conflicts: List<LikedTrack> = emptyList(),
    val notFound: List<LikedTrack> = emptyList(),
) {
    val running: Boolean get() = state == "running"
}

@Serializable
data class ImageUrl(val url: String? = null)

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

/** Событие сессии волны для подбора следующей порции: like / dislike / skip (в первые 30 с) / complete. */
@Serializable
data class WaveSessionEvent(val id: String, val event: String)

/** Запрос порции волны у bodrod: режим (`energetic`, `artist:<имя>`, `release:<id альбома>`…) и настройки волны. */
@Serializable
data class WaveNextRequest(
    val mode: String = "default",
    val mood: String? = null,
    val lang: String? = null,
    val character: String? = null,
    val currentId: String? = null,
    val recent: List<String> = emptyList(),
    val session: List<WaveSessionEvent> = emptyList(),
    val count: Int = 20,
)

/** Порция волны: id песен Navidrome по порядку; [lowCoverage] — под режим мало треков. */
@Serializable
data class WaveNext(
    val songIds: List<String> = emptyList(),
    val matched: Int = 0,
    val lowCoverage: Boolean = false,
    val mode: String = "default",
)

/** Режим волны для главной: [count] — сколько треков подходит; [dynamic] — «В стиле X», «Новый релиз X». */
@Serializable
data class WaveModeInfo(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val count: Int? = null,
    val dynamic: Boolean = false,
)

/**
 * Отчёт об анализе: сколько проанализировано и не удалось (с причинами), сколько треков в каждом режиме и
 * настроении, у скольких есть каждый признак. Поля с [failed] по [modeList] — bodrod 0.24.0+; старый сервер
 * присылает только [modes] (id → число).
 */
@Serializable
data class WaveReport(
    val library: Int = 0,
    val analyzed: Int = 0,
    val failed: Int = 0,
    val excluded: Int = 0,
    val pending: Int? = null,
    val failures: List<WaveReportFailure> = emptyList(),
    val loudnessEstimated: Int = 0,
    val noMood: Int? = null,
    val modeList: List<WaveReportMode> = emptyList(),
    val features: Map<String, Int> = emptyMap(),
    val languages: Map<String, Int> = emptyMap(),
    val modes: Map<String, Int> = emptyMap(),
)

@Serializable
data class WaveReportFailure(val reason: String, val count: Int)

/** Режим в отчёте: [mood] — режим-настроение («Бодрое», «Пошумим»…). */
@Serializable
data class WaveReportMode(val id: String, val title: String, val count: Int, val mood: Boolean = false)

/** Подводка к треку на главной (bodrod 0.23.0+): [type] — откуда факт (context, personal, story, sound…). */
@Serializable
data class Blurb(val id: String, val text: String, val type: String = "")

@Serializable
data class Blurbs(val items: List<Blurb> = emptyList())

/**
 * Короткий текст о треке или исполнителе для главной (bodrod 0.21.0+: только русский, законченная фраза).
 * [variants] — все подходящие фразы от лучшей к запасным: показывается первая, которая помещается целиком.
 * Старый сервер присылает только [fact] (может быть английским) — его варианты = `[fact]`.
 */
@Serializable
data class ArtistFacts(
    val fact: String? = null,
    val variants: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val source: String? = null,
) {
    val texts: List<String> get() = variants.ifEmpty { listOfNotNull(fact?.takeIf { it.isNotBlank() }) }
    val isEmpty: Boolean get() = texts.isEmpty()
}

/** Плейлист выгрузки из Яндекса: id песен Navidrome по порядку; [missing] — треков ещё нет в библиотеке. */
@Serializable
data class ExportPlaylist(val title: String, val songIds: List<String> = emptyList(), val missing: Int = 0, val owner: String? = null)

/** Плейлист сервера в отчёте администратору (из БД Navidrome). */
@Serializable
data class AuditPlaylist(
    val id: String,
    val name: String,
    val owner: String? = null,
    val public: Boolean = false,
    val songCount: Int = 0,
    /** Файл, из которого Navidrome импортировал плейлист; `null` — создан через API или веб. */
    val path: String? = null,
    val smart: Boolean = false,
    val createdAt: String? = null,
    /** no_owner / imported_file / smart_file / yandex_export / explo. */
    val reasons: List<String> = emptyList(),
)

/** Чьи плейлисты на сервере и какие стоит проверить ([review]). Ничего не переносится автоматически. */
@Serializable
data class PlaylistAudit(
    val users: List<String> = emptyList(),
    val admins: List<String> = emptyList(),
    val byOwner: Map<String, Int> = emptyMap(),
    val playlists: List<AuditPlaylist> = emptyList(),
    val review: Int = 0,
)

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

/** Строка сводки обслуживания: [key] — ключ статистики или код причины, [title] — подпись от сервера. */
@Serializable
data class MaintenanceCount(val key: String, val title: String = "", val count: Int = 0)

/** Файл в карантине (можно вернуть по [id]). [path] — откуда убран, относительно папки музыки. */
@Serializable
data class QuarantineItem(
    val id: Long,
    val path: String = "",
    val reason: String = "",
    /** Вид действия, которое убрало файл: duplicate / short. */
    val kind: String? = null,
    val artist: String? = null,
    val title: String? = null,
    val album: String? = null,
    val size: Long? = null,
    val createdAt: Long = 0,
    /** Когда файл удалится из карантина насовсем. */
    val expiresAt: Long = 0,
)

/** Поле тега: было → стало («название», «Ñåêðåò», «Секрет»). */
@Serializable
data class TagChange(val field: String = "", val before: String = "", val after: String = "")

/** Исправленная кодировка тегов файла (bodrod 0.21.0+); [id] — для отката. */
@Serializable
data class EncodingFix(
    val id: Long,
    val path: String = "",
    val changes: List<TagChange> = emptyList(),
    val source: String = "",
    val reverted: Boolean = false,
)

/**
 * Сводка последнего запуска: исправлено по типам, не исправлено по причинам и всё, что сейчас в карантине.
 * [encoding] — исправления кодировки тегов («было → стало»), [encodingManual] — файлы на ручную проверку.
 */
@Serializable
data class MaintenanceSummary(
    val run: MaintenanceRun? = null,
    val fixed: List<MaintenanceCount> = emptyList(),
    val unresolved: List<MaintenanceCount> = emptyList(),
    val quarantine: List<QuarantineItem> = emptyList(),
    val encoding: List<EncodingFix> = emptyList(),
    val encodingManual: List<String> = emptyList(),
)

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

/** Аудит обложек (bodrod 0.25.0+, `GET /api/v1/library/covers`). */
@Serializable
data class CoverStats(
    val albums: Int = 0,
    val albumsWithoutCover: Int = 0,
    val songs: Int = 0,
    val songsWithoutCover: Int = 0,
    val artists: Int = 0,
    val artistsWithoutImage: Int = 0,
    /** Верхняя папка библиотеки → треков без обложки, по убыванию. */
    val byFolder: Map<String, Int> = emptyMap(),
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
internal data class CreateRequestsBody(val items: List<CreateRequestBody>)

@Serializable
internal data class LikesDownloadBody(val ids: List<String>? = null)

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
