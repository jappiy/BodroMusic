"""Модели API (JSON в camelCase — так удобнее клиенту на Kotlin)."""

from __future__ import annotations

from enum import StrEnum
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class ApiModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class TrackCandidate(ApiModel):
    title: str
    artist: str
    album: str | None = None
    duration_ms: int | None = None
    cover_url: str | None = None
    source: str
    source_id: str
    """id трека в Navidrome, если такой трек уже есть в библиотеке."""
    library_song_id: str | None = None


class ArtistCandidate(ApiModel):
    name: str
    cover_url: str | None = None
    source: str
    source_id: str


class DiscoverResponse(ApiModel):
    """`artist` — запрос похож на имя исполнителя: показываем его популярные треки; `track` — ищут песню."""

    intent: Literal["track", "artist", "none"]
    source: str | None = None
    tracks: list[TrackCandidate] = Field(default_factory=list)
    artists: list[ArtistCandidate] = Field(default_factory=list)
    artist: ArtistCandidate | None = None
    top_tracks: list[TrackCandidate] = Field(default_factory=list)


class RequestKind(StrEnum):
    TRACK = "track"
    """Альбом целиком (bodrod 0.7.0+): `artist` + `album`."""
    ALBUM = "album"
    ARTIST = "artist"


class RequestStatus(StrEnum):
    QUEUED = "queued"
    SEARCHING = "searching"
    DOWNLOADING = "downloading"
    """Файл скачан, bodrod кладёт его в библиотеку и прописывает теги."""
    PLACING = "placing"
    IMPORTING = "importing"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"

    @property
    def is_active(self) -> bool:
        return self in ACTIVE_STATUSES


ACTIVE_STATUSES = frozenset(
    {
        RequestStatus.QUEUED,
        RequestStatus.SEARCHING,
        RequestStatus.DOWNLOADING,
        RequestStatus.PLACING,
        RequestStatus.IMPORTING,
    }
)


class CreateRequest(ApiModel):
    kind: RequestKind
    artist: str = Field(min_length=1, max_length=300)
    title: str | None = Field(default=None, max_length=300)
    album: str | None = Field(default=None, max_length=300)
    duration_ms: int | None = None
    cover_url: str | None = Field(default=None, max_length=1000)
    """Трек качается из позиции чарта: прямая привязка позиции к скачанному файлу."""
    chart_id: str | None = Field(default=None, max_length=100)
    chart_position: int | None = None


class AttemptOut(ApiModel):
    """Попытка источника цепочки: `ok` — скачано, `fail` — не нашёл или не отдал, `skip` — выключен или недоступен."""

    source: str
    title: str
    result: Literal["ok", "fail", "skip"]
    reason: str | None = None
    at: int


class DownloadRequestOut(ApiModel):
    id: str
    kind: RequestKind
    artist: str
    title: str | None = None
    album: str | None = None
    cover_url: str | None = None
    status: RequestStatus
    message: str | None = None
    """0..1, если известен."""
    progress: float | None = None
    """id трека в Navidrome после импорта (для track)."""
    song_id: str | None = None
    created_at: int
    updated_at: int
    """Итоговый источник: «Яндекс Музыка, FLAC», «Soulseek, MP3 320» (bodrod 0.7.0+)."""
    source: str | None = None
    attempts: list[AttemptOut] = Field(default_factory=list)


class ServiceInfo(ApiModel):
    version: str
    track_downloads: bool
    artist_downloads: bool
    metadata_sources: list[str]
    """Раздел «На удаление» настроен (задан NAVIDROME_DB)."""
    cleanup: bool = False
    """Пользователь — администратор Navidrome (только ему доступны удаление и чёрный список)."""
    admin: bool = False
    """Включено «Обслуживание библиотеки» (bodrod 0.4.0+)."""
    maintenance: bool = False
    """Скачивание альбомов целиком (bodrod 0.7.0+)."""
    album_downloads: bool = False
    """Есть экран «Источники загрузки» (bodrod 0.7.0+)."""
    sources: bool = False
    """Discovery-пул «на пробу» и события обратной связи (bodrod 0.9.0+)."""
    pool: bool = False
    """Анализ звучания: признаки треков и «Похожие по звучанию» (bodrod 0.12.0+)."""
    analysis: bool = False


class CleanupUser(ApiModel):
    username: str
    is_admin: bool


class DislikedSong(ApiModel):
    id: str
    title: str
    artist: str
    album: str | None = None
    album_id: str | None = None
    duration: int | None = None
    size: int | None = None
    suffix: str | None = None
    bit_rate: int | None = None
    """Путь относительно папки музыки."""
    path: str
    disliked_by: list[str] = Field(default_factory=list)


class DeleteSongsRequest(ApiModel):
    ids: list[str] = Field(min_length=1, max_length=5000)


class DeleteSongResult(ApiModel):
    id: str
    ok: bool
    message: str


class BlockEntryOut(ApiModel):
    id: int
    artist: str
    title: str
    album: str | None = None
    created_by: str
    created_at: int
    """Сколько раз файл удалялся (первое удаление + повторные появления)."""
    removed_count: int
    last_removed_at: int | None = None


class BlocklistPage(ApiModel):
    total: int
    items: list[BlockEntryOut]


class ArtistPopular(ApiModel):
    """Популярные треки исполнителя по каталогу (для экрана исполнителя в приложении)."""

    source: str | None = None
    artist: ArtistCandidate | None = None
    tracks: list[TrackCandidate] = Field(default_factory=list)


class LibraryCheckRequest(ApiModel):
    ids: list[str] = Field(min_length=1, max_length=5000)


class LibraryCheck(ApiModel):
    """`missing` — треков больше нет в библиотеке; `replaced` — трек заменён копией (старый id → новый)."""

    missing: list[str] = Field(default_factory=list)
    replaced: dict[str, str] = Field(default_factory=dict)


class MaintenanceRunOut(ApiModel):
    id: int
    trigger: str
    """running / completed / failed."""
    status: str
    dry_run: bool
    stats: dict[str, int] = Field(default_factory=dict)
    error: str | None = None
    started_at: int
    finished_at: int | None = None


class MaintenanceStatus(ApiModel):
    enabled: bool
    running: bool
    dry_run: bool
    """Время ежедневного запуска, «ЧЧ:ММ»."""
    schedule: str
    next_run_at: int | None = None
    quarantine_days: int
    last_run: MaintenanceRunOut | None = None


class MaintenanceActionOut(ApiModel):
    id: int
    run_id: int
    """short / duplicate / album / artist_merge / tags / cover / mismatch."""
    kind: str
    """`True` — исправлено; `False` — только найдено (в отчёт) или запланировано пробным запуском (`planned`)."""
    applied: bool
    planned: bool = False
    artist: str | None = None
    title: str | None = None
    album: str | None = None
    path: str | None = None
    details: str
    created_at: int
    quarantine_id: int | None = None
    restorable: bool = False


class MaintenanceActionsPage(ApiModel):
    total: int
    items: list[MaintenanceActionOut]


class SourceOut(ApiModel):
    """Источник загрузки. `state`: `ok` — работает, `disabled` — выключен вручную, `unavailable` — не может работать."""

    name: str
    title: str
    enabled: bool
    state: Literal["ok", "disabled", "unavailable"]
    reason: str | None = None
    """Что умеет: track / album / artist."""
    kinds: list[str] = Field(default_factory=list)
    """Дополнительно: версия yt-dlp, статус подписки и т. п."""
    details: str | None = None
    """Яндекс: чей токен — `user` (пользователя приложения) или `global` (общий YANDEX_MUSIC_TOKEN)."""
    token_source: str | None = None
    """То же текстом: «Токен пользователя» / «Общий токен»."""
    token_source_title: str | None = None


class SourcesOut(ApiModel):
    sources: list[SourceOut]
    """«Яндекс: метаданные» — поиск, теги, обложки и тексты из Яндекса для файлов из любых источников."""
    yandex_metadata: bool
    yandex_metadata_reason: str | None = None


class SourcesUpdate(ApiModel):
    """Новый порядок (все имена источников) и/или переключатели. Не заданное поле не меняется."""

    order: list[str] | None = Field(default=None, max_length=20)
    enabled: dict[str, bool] | None = None
    yandex_metadata: bool | None = None


class ExportCounts(ApiModel):
    total: int = 0
    done: int = 0
    skipped: int = 0
    failed: int = 0
    pending: int = 0


class ExportStatus(ApiModel):
    """Массовая выгрузка из Яндекса. `state`: idle / collecting / running / paused / finished / unavailable."""

    state: str
    message: str | None = None
    likes: bool = True
    playlists: bool = True
    counts: ExportCounts = Field(default_factory=ExportCounts)
    """Сколько артистов из лайков отмечено «брать целиком» / всего."""
    artists_selected: int = 0
    artists_total: int = 0
    current: str | None = None
    updated_at: int | None = None


class ExportStart(ApiModel):
    likes: bool = True
    playlists: bool = True
    """Дискографии отмеченных артистов."""
    artists: bool = True


class ExportArtistOut(ApiModel):
    id: str
    name: str
    """Сколько треков артиста в «Мне нравится»."""
    liked: int
    selected: bool


class ExportArtistsUpdate(ApiModel):
    """id артистов, которых брать целиком (остальные — снять отметку)."""

    selected: list[str] = Field(max_length=5000)


class HarvestCollector(ApiModel):
    name: str
    title: str
    last_at: int | None = None
    """Отключён после ошибки доступа (причина); через 7 дней — новая попытка."""
    disabled: str | None = None
    count: int | None = None


class HarvestAccount(ApiModel):
    """Архив Яндекса по аккаунту: что собрано и когда."""

    username: str
    plus: bool | None = None
    running: str | None = None
    collectors: list[HarvestCollector] = []
    likes: dict[str, int] = {}
    edges: dict[str, int] = {}
    reserve: int = 0
    blocked: int = 0
    similar_done: int = 0
    error: str | None = None


class HarvestStatus(ApiModel):
    enabled: bool
    accounts: list[HarvestAccount] = []


class FeedbackEvent(ApiModel):
    song_id: str
    """started / skipped / completed / liked / unliked / disliked / playlisted / shown."""
    event: str
    position_ms: int | None = None
    duration_ms: int | None = None
    """wave / chart / library / playlist / pool."""
    context: str | None = None
    at: int | None = None


class FeedbackBatch(ApiModel):
    events: list[FeedbackEvent] = Field(max_length=1000)


class FeedbackResult(ApiModel):
    accepted: int
    """Треки пула, у которых сменился статус: id → promoted / rejected / expired."""
    changed: dict[str, str] = {}


class PoolItemOut(ApiModel):
    song_id: str | None = None
    artist: str
    title: str
    album: str | None = None
    duration_ms: int | None = None
    cover_url: str | None = None
    status: str
    source: str | None = None
    added_at: int
    full_plays: int = 0
    early_skips: int = 0
    shown: int = 0
    """Звуковая близость к вкусу пользователя (0..1; нет — не проанализирован)."""
    sound_match: float | None = None
    """Можно ставить в волну: близость не ниже порога (или ещё неизвестна)."""
    in_wave: bool = True


class PoolOut(ApiModel):
    items: list[PoolItemOut] = []
    counts: dict[str, int] = {}
    size: int = 150


class PoolStatusRequest(ApiModel):
    ids: list[str] = Field(max_length=5000)


class PoolHidden(ApiModel):
    song_ids: list[str] = []
    album_ids: list[str] = []
    artist_ids: list[str] = []


class ChartOut(ApiModel):
    id: str
    title: str
    source: str
    updated_at: int | None = None
    """Обложки первых треков — для коллажа на карточке."""
    covers: list[str] = []
    size: int = 0


class ChartEntryOut(ApiModel):
    position: int
    artist: str
    title: str
    album: str | None = None
    cover_url: str | None = None
    duration_ms: int | None = None
    prev_position: int | None = None
    """up / down / same / new."""
    movement: str
    """library / pool — можно слушать (`songId`); queued — в очереди; downloading — качается; unavailable — нет."""
    state: str
    song_id: str | None = None
    """Заявка bodrod на этот трек и её статус (queued, downloading, completed, failed…)."""
    request_id: str | None = None
    request_status: str | None = None


class EngineSourceOut(ApiModel):
    name: str
    title: str
    enabled: bool
    """Ручной вес (0..3) — из приложения."""
    weight: float
    """Выученный по доле promote (1.0 — нет данных)."""
    learned_weight: float
    available: bool
    reason: str | None = None
    suggested: int = 0
    promoted: int = 0
    rejected: int = 0


class EngineRun(ApiModel):
    at: int
    picked: int
    by_source: dict[str, int] = {}


class EngineOut(ApiModel):
    enabled: bool
    sources: list[EngineSourceOut] = []
    new_artist_share: float = 0.5
    """Последнее пополнение по пользователям."""
    runs: dict[str, EngineRun | None] = {}


class EngineUpdate(ApiModel):
    enabled: dict[str, bool] | None = None
    weights: dict[str, float] | None = None
    new_artist_share: float | None = None


class EnginePreviewItem(ApiModel):
    artist: str
    title: str
    album: str | None = None
    score: float
    sources: list[str]
    new_artist: bool


class AnalysisJobs(ApiModel):
    """Пути относительно папки музыки."""

    jobs: list[str] = []


class AnalysisResult(ApiModel):
    path: str
    """bpm, key, scale, loudness, danceability, voice, moods {happy, sad, aggressive, relaxed}, genres [[метка, p]]."""
    features: dict[str, Any] | None = None
    embedding: list[float] | None = Field(default=None, max_length=4096)
    status: Literal["ok", "failed"] | None = None
    """`failed` — трек не обработан (текст в `error`); повтор через час, после трёх неудач подряд не выдаётся."""
    error: str | None = None


class AnalysisStatus(ApiModel):
    enabled: bool
    analyzed: int = 0
    errors: int = 0
    """Треки с ошибкой последней попытки (включая `skipped`)."""
    skipped: int = 0
    """Треки, исключённые из анализа после трёх неудач подряд."""
    library: int = 0
    last_result_at: int | None = None


class SimilarSongs(ApiModel):
    ids: list[str] = []
