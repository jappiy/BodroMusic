"""Источники обложек и тегов для обслуживания библиотеки: один интерфейс, цепочка с фолбэком.

Каждый источник ищет по исполнителю, названию, альбому и длительности и возвращает кандидатов с оценкой
совпадения 0..1 (`match_score`). Порядок источников задаётся отдельно для обложек и тегов
(`COVER_PROVIDERS`, `TAG_PROVIDERS`); версия набора (`chain_version`) входит в отметки «не найдено»,
поэтому после смены списка ненайденное ищется заново.
"""

from __future__ import annotations

import asyncio
import hashlib
import logging
import time
from dataclasses import dataclass
from typing import TYPE_CHECKING, Any, Protocol

import httpx

from .. import __version__
from ..text import main_artist, normalize, similarity_any
from .providers import MetadataProvider

if TYPE_CHECKING:
    from .lastfm import LastFmClient

log = logging.getLogger(__name__)

MATCH_THRESHOLD = 0.85
DURATION_TOLERANCE_S = 3.0
USER_AGENT = f"bodrod/{__version__} ( https://github.com/jappiy/BodroMusic )"
SOURCE_NAMES = {
    "yandex": "Яндекс Музыка",
    "deezer": "Deezer",
    "itunes": "iTunes",
    "musicbrainz": "MusicBrainz",
    "discogs": "Discogs",
    "lastfm": "Last.fm",
    "local": "соседний файл",
}


@dataclass
class LookupMatch:
    artist: str
    title: str
    album: str | None
    duration_s: float | None
    cover_url: str | None
    source: str
    score: float = 0.0
    """Релиз — сборник разных исполнителей (Various Artists)."""
    compilation: bool = False

    @property
    def source_name(self) -> str:
        return SOURCE_NAMES.get(self.source, self.source)


class LookupProvider(Protocol):
    name: str
    """Годится для тегов (знает название трека и длительность), а не только для обложек альбомов."""
    tags: bool

    async def lookup(
        self, artist: str, title: str, album: str | None, duration_s: float | None
    ) -> list[LookupMatch]: ...


def match_score(
    artist: str,
    title: str,
    duration_s: float | None,
    found_artist: str,
    found_title: str,
    found_duration_s: float | None,
) -> float:
    """Оценка совпадения 0..1: исполнитель и название (с транслитом), длительность ±3 с — иначе 0."""
    if duration_s and found_duration_s and abs(duration_s - found_duration_s) > DURATION_TOLERANCE_S:
        return 0.0
    artist_score = max(
        similarity_any(found_artist, artist),
        similarity_any(main_artist(found_artist), main_artist(artist)),
        similarity_any(main_artist(found_artist), artist),
    )
    return round(min(artist_score, similarity_any(found_title, title)), 3)


def chain_version(names: list[str] | tuple[str, ...]) -> str:
    """Короткий хэш списка источников: меняется при добавлении, удалении или перестановке."""
    return hashlib.sha1(",".join(names).encode()).hexdigest()[:8]  # noqa: S324 — не для безопасности


def large_cover(url: str) -> str:
    """Обложка покрупнее: Яндекс `400x400` → `1000x1000`, Deezer `250x250` → `1000x1000`, iTunes → 1200."""
    return url.replace("400x400", "1000x1000").replace("/250x250-", "/1000x1000-").replace("100x100bb", "1200x1200bb")


class CatalogLookup:
    """Яндекс Музыка или Deezer: поиск трека в каталоге «глобального» поиска."""

    tags = True

    def __init__(self, catalog: MetadataProvider) -> None:
        self._catalog = catalog
        self.name = catalog.name

    async def lookup(self, artist: str, title: str, album: str | None, duration_s: float | None) -> list[LookupMatch]:
        hits = await self._catalog.search(f"{artist} {title}")
        result = []
        for t in hits.tracks:
            duration = t.duration_ms / 1000 if t.duration_ms else None
            result.append(
                LookupMatch(
                    artist=t.artist,
                    title=t.title,
                    album=t.album,
                    duration_s=duration,
                    cover_url=large_cover(t.cover_url) if t.cover_url else None,
                    source=self.name,
                    score=match_score(artist, title, duration_s, t.artist, t.title, duration),
                )
            )
        return result


class ITunesLookup:
    """iTunes Search API: без ключа, обложки до 1200×1200."""

    name = "itunes"
    tags = True
    URL = "https://itunes.apple.com/search"

    def __init__(self, http: httpx.AsyncClient, country: str = "RU") -> None:
        self._http = http
        self._country = country

    async def lookup(self, artist: str, title: str, album: str | None, duration_s: float | None) -> list[LookupMatch]:
        params = {"term": f"{artist} {title}", "media": "music", "entity": "song", "limit": 15}
        if self._country:
            params["country"] = self._country
        response = await self._http.get(self.URL, params=params, timeout=15)
        response.raise_for_status()
        result = []
        for raw in response.json().get("results") or []:
            found_artist, found_title = raw.get("artistName") or "", raw.get("trackName") or ""
            millis = raw.get("trackTimeMillis")
            duration = millis / 1000 if millis else None
            artwork = raw.get("artworkUrl100")
            result.append(
                LookupMatch(
                    artist=found_artist,
                    title=found_title,
                    album=raw.get("collectionName"),
                    duration_s=duration,
                    cover_url=large_cover(artwork) if artwork else None,
                    source=self.name,
                    score=match_score(artist, title, duration_s, found_artist, found_title, duration),
                    compilation=normalize(raw.get("collectionArtistName")) in ("various artists", "various"),
                )
            )
        return result


class MusicBrainzLookup:
    """MusicBrainz + Cover Art Archive. Обязателен User-Agent с контактом, не чаще 1 запроса в секунду."""

    name = "musicbrainz"
    tags = True
    URL = "https://musicbrainz.org/ws/2/recording"

    def __init__(self, http: httpx.AsyncClient, min_interval_s: float = 1.0) -> None:
        self._http = http
        self._interval = min_interval_s
        self._lock = asyncio.Lock()
        self._last = 0.0

    async def _throttle(self) -> None:
        async with self._lock:
            wait = self._last + self._interval - time.monotonic()
            if wait > 0:
                await asyncio.sleep(wait)
            self._last = time.monotonic()

    async def lookup(self, artist: str, title: str, album: str | None, duration_s: float | None) -> list[LookupMatch]:
        query = f'recording:"{_lucene(title)}" AND artist:"{_lucene(main_artist(artist) or artist)}"'
        await self._throttle()
        response = await self._http.get(
            self.URL,
            params={"query": query, "fmt": "json", "limit": 10},
            headers={"User-Agent": USER_AGENT, "Accept": "application/json"},
            timeout=20,
        )
        response.raise_for_status()
        result = []
        for raw in response.json().get("recordings") or []:
            found_artist = "".join(
                (c.get("name") or "") + (c.get("joinphrase") or "") for c in raw.get("artist-credit") or []
            ).strip()
            found_title = raw.get("title") or ""
            duration = raw["length"] / 1000 if raw.get("length") else None
            release = _best_release(raw.get("releases") or [], album)
            cover = None
            if release is not None:
                group = (release.get("release-group") or {}).get("id")
                cover = (
                    f"https://coverartarchive.org/release-group/{group}/front-1200"
                    if group
                    else f"https://coverartarchive.org/release/{release['id']}/front-1200"
                )
            result.append(
                LookupMatch(
                    artist=found_artist,
                    title=found_title,
                    album=release.get("title") if release else None,
                    duration_s=duration,
                    cover_url=cover,
                    source=self.name,
                    score=match_score(artist, title, duration_s, found_artist, found_title, duration),
                    compilation=bool(release and _is_compilation(release)),
                )
            )
        return result


def _lucene(value: str) -> str:
    return value.replace("\\", " ").replace('"', " ")


def _is_compilation(release: dict[str, Any]) -> bool:
    group = release.get("release-group") or {}
    return "Compilation" in (group.get("secondary-types") or [])


def _best_release(releases: list[dict[str, Any]], album: str | None) -> dict[str, Any] | None:
    """Релиз с тем же названием альбома; иначе официальный студийный альбом, а не сборник или концерт."""

    def rank(release: dict[str, Any]) -> tuple[float, int, int]:
        group = release.get("release-group") or {}
        secondary = group.get("secondary-types") or []
        studio = group.get("primary-type") == "Album" and not secondary
        same_album = similarity_any(release.get("title"), album) if album else 0.0
        return (same_album, int(studio), int(release.get("status") == "Official"))

    return max(releases, key=rank) if releases else None


class DiscogsLookup:
    """Discogs: только обложки альбомов (длительности трека в поиске нет). Нужен токен."""

    name = "discogs"
    tags = False
    URL = "https://api.discogs.com/database/search"

    def __init__(self, http: httpx.AsyncClient, token: str) -> None:
        self._http = http
        self._headers = {"Authorization": f"Discogs token={token}", "User-Agent": USER_AGENT}

    async def lookup(self, artist: str, title: str, album: str | None, duration_s: float | None) -> list[LookupMatch]:
        if not album:
            return []
        response = await self._http.get(
            self.URL,
            params={"artist": main_artist(artist) or artist, "release_title": album, "type": "release", "per_page": 10},
            headers=self._headers,
            timeout=15,
        )
        response.raise_for_status()
        result = []
        for raw in response.json().get("results") or []:
            found_artist, _, found_album = (raw.get("title") or "").partition(" - ")
            cover = raw.get("cover_image")
            if not cover or cover.endswith("spacer.gif"):
                continue
            score = min(
                max(similarity_any(found_artist, artist), similarity_any(found_artist, main_artist(artist))),
                similarity_any(found_album, album),
            )
            result.append(
                LookupMatch(
                    artist=found_artist,
                    title=title,
                    album=found_album,
                    duration_s=None,
                    cover_url=cover,
                    source=self.name,
                    score=round(score, 3),
                    compilation="Compilation" in (raw.get("format") or []),
                )
            )
        return result


def build_chain(
    names: tuple[str, ...],
    http: httpx.AsyncClient,
    catalogs: list[MetadataProvider],
    discogs_token: str | None,
    lastfm: LastFmClient | None = None,
) -> list[LookupProvider]:
    """Источники по списку из настроек; неизвестные и ненастроенные пропускаются с предупреждением."""
    from .lastfm import LastFmLookup

    by_name = {c.name: c for c in catalogs}
    chain: list[LookupProvider] = []
    for name in names:
        if name in by_name:
            chain.append(CatalogLookup(by_name[name]))
        elif name == "itunes":
            chain.append(ITunesLookup(http))
        elif name == "musicbrainz":
            chain.append(MusicBrainzLookup(http))
        elif name == "discogs":
            if discogs_token:
                chain.append(DiscogsLookup(http, discogs_token))
            else:
                log.info("Discogs в списке источников, но DISCOGS_TOKEN не задан — пропускаем")
        elif name == "lastfm":
            if lastfm is not None:
                chain.append(LastFmLookup(lastfm))
            else:
                log.info("Last.fm в списке источников, но LASTFM_API_KEY не задан — пропускаем")
        else:
            log.warning("Неизвестный источник обложек/тегов: %s", name)
    return chain
