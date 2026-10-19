"""Модели API (JSON в camelCase — так удобнее клиенту на Kotlin)."""

from __future__ import annotations

from enum import StrEnum
from typing import Literal

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
