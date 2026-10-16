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


class ServiceInfo(ApiModel):
    version: str
    track_downloads: bool
    artist_downloads: bool
    metadata_sources: list[str]
