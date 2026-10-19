"""Общий интерфейс источника загрузки и сопоставление найденного с заявкой.

Источники бывают двух видов:
- «прямые» (Яндекс Музыка, YouTube Music) — `DirectSource`: сами ищут и скачивают файл во временную папку,
  bodrod тут же кладёт его в библиотеку (`Placer`);
- «внешние» (slskd, Lidarr) — работают в своём сервисе, воркер опрашивает их по шагам (как до 0.7.0).
"""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Protocol

from ..metadata.lookup import match_score
from ..models import RequestKind
from ..placement import Tags
from ..text import normalize

MATCH_THRESHOLD = 0.85
DURATION_TOLERANCE_S = 3.0
SOURCE_TITLES = {
    "yandex": "Яндекс Музыка",
    "slskd": "Soulseek",
    "youtube": "YouTube Music",
    "lidarr": "Lidarr",
}
SOURCE_KINDS: dict[str, frozenset[RequestKind]] = {
    "yandex": frozenset({RequestKind.TRACK, RequestKind.ALBUM, RequestKind.ARTIST}),
    "slskd": frozenset({RequestKind.TRACK}),
    "youtube": frozenset({RequestKind.TRACK, RequestKind.ALBUM, RequestKind.ARTIST}),
    "lidarr": frozenset({RequestKind.ALBUM, RequestKind.ARTIST}),
}
KIND_NAMES = {RequestKind.TRACK: "треки", RequestKind.ALBUM: "альбомы", RequestKind.ARTIST: "исполнителей"}


@dataclass
class SourceStatus:
    available: bool
    """Почему недоступен: «нет подписки Плюс», «не задан токен» — показывается в приложении."""
    reason: str | None = None
    """Дополнительно для экрана источников: версия yt-dlp, срок подписки."""
    details: str | None = None


class SourceMiss(Exception):  # noqa: N818 — это не ошибка, а «не нашли»
    """Источник не нашёл подходящего или не отдал файл — цепочка идёт к следующему."""

    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


@dataclass
class FetchedFile:
    """Скачанный во временную папку файл с метаданными каталога."""

    path: Path
    tags: Tags
    cover_url: str | None
    """flac / aac / mp3 / opus."""
    codec: str
    bitrate: int | None
    lossy: bool
    duration_ms: int | None
    """id трека в источнике: по нему массовая выгрузка и повторные загрузки пропускают уже положенное."""
    source_id: str | None = None


@dataclass
class FetchResult:
    """Итог прямого источника: положено `placed` файлов, `skipped` уже было в библиотеке."""

    label: str
    placed: int = 0
    skipped: int = 0
    failed: int = 0
    paths: list[str] = field(default_factory=list)


@dataclass
class FetchJob:
    kind: RequestKind
    artist: str
    title: str | None
    album: str | None
    duration_ms: int | None
    workdir: Path
    """Песня уже есть в библиотеке (Navidrome или положена bodrod раньше)? Источник её не скачивает."""
    exists: Callable[[str, str, str | None], Awaitable[bool]]
    """Положить скачанный файл в библиотеку (теги, обложка) — вызывается сразу после каждого файла."""
    place: Callable[[FetchedFile], Awaitable[Path]]
    """Прогресс для приложения: доля 0..1 и сообщение."""
    progress: Callable[[float | None, str], None] = lambda _p, _m: None
    state: dict[str, Any] = field(default_factory=dict)


class DirectSource(Protocol):
    name: str

    async def status(self) -> SourceStatus: ...

    async def fetch(self, job: FetchJob) -> FetchResult: ...


# region Сопоставление

_UNWANTED = (
    "remix", "ремикс", "rmx", "live", "лайв", "концерт", "concert", "cover", "кавер", "karaoke", "караоке",
    "instrumental", "инструментал", "минус", "minus", "sped up", "speed up", "slowed", "nightcore", "8d",
    "clip", "клип", "video", "видео",
)  # fmt: skip


def unwanted_extras(found: str | None, wanted: str | None) -> list[str]:
    """Пометки версии (remix, live, cover…), которых нет в запросе: такой вариант не годится."""
    found_n, wanted_n = f" {normalize(found, strip_extras=False)} ", f" {normalize(wanted, strip_extras=False)} "
    return [w for w in _UNWANTED if f" {w} " in found_n and f" {w} " not in wanted_n]


def duration_ok(wanted_ms: int | None, found_ms: int | None) -> bool:
    if not wanted_ms or not found_ms:
        return True
    return abs(wanted_ms - found_ms) / 1000 <= DURATION_TOLERANCE_S


def track_score(
    artist: str, title: str, duration_ms: int | None, found_artist: str, found_title: str, found_ms: int | None
) -> float:
    """0..1; длительность вне ±3 с или лишняя пометка версии — 0."""
    if not duration_ok(duration_ms, found_ms) or unwanted_extras(found_title, title):
        return 0.0
    return match_score(
        artist,
        title,
        duration_ms / 1000 if duration_ms else None,
        found_artist,
        found_title,
        found_ms / 1000 if found_ms else None,
    )


def is_preview(path: Path, expected_ms: int | None, ratio: float) -> float | None:
    """Длительность файла (с), если он короче `ratio` от длительности по каталогу (превью вместо трека)."""
    if not expected_ms:
        return None
    try:
        import mutagen

        audio = mutagen.File(path)
    except Exception:  # noqa: BLE001 — нечитаемый файл тоже не годится
        return 0.0
    length = float(getattr(getattr(audio, "info", None), "length", 0) or 0) if audio is not None else 0.0
    return length if length < expected_ms / 1000 * ratio else None


# endregion
