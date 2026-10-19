"""Last.fm — дополнительный канал: теги и обложки для обслуживания, глобальный поиск, популярное исполнителя.

Нужен ключ API (`LASTFM_API_KEY`, бесплатный: https://www.last.fm/api/account/create). Last.fm бывает недоступен
или отвечает ошибкой лимита — тогда клиент после нескольких сбоев подряд на время «выключается» (отказывает сразу,
без сетевых запросов), и цепочки идут к следующим источникам, не теряя секунды на тайм-ауты.
"""

from __future__ import annotations

import asyncio
import logging
import re
import time
from typing import Any

import httpx

from ..models import ArtistCandidate, TrackCandidate
from .lookup import USER_AGENT, LookupMatch, match_score
from .providers import SearchHits

log = logging.getLogger(__name__)

NAME = "lastfm"
"""Заглушка «нет картинки» (серая звезда) — у Last.fm вместо пустого поля."""
_PLACEHOLDER = "2a96cbd8b46e442fc41c2b86b821562f"
_SIZE = re.compile(r"/i/u/[^/]+/")
"""Код ошибки «не найдено / неверные параметры»: это ответ, а не сбой сервиса."""
_NOT_FOUND = 6


class LastFmError(Exception):
    pass


class LastFmUnavailable(LastFmError):
    """Last.fm временно не опрашиваем: недавно было несколько сбоев подряд."""


class LastFmClient:
    """JSON API Last.fm с паузой между запросами и «предохранителем» на случай недоступности."""

    URL = "https://ws.audioscrobbler.com/2.0/"

    def __init__(
        self,
        http: httpx.AsyncClient,
        api_key: str,
        *,
        min_interval_s: float = 0.25,
        failures_to_trip: int = 3,
        cooldown_s: float = 300.0,
        clock: Any = time.monotonic,
    ) -> None:
        self._http = http
        self._key = api_key
        self._interval = min_interval_s
        self._failures_to_trip = failures_to_trip
        self._cooldown = cooldown_s
        self._clock = clock
        self._lock = asyncio.Lock()
        self._last = 0.0
        self._failures = 0
        self._open_until = 0.0

    @property
    def available(self) -> bool:
        return self._clock() >= self._open_until

    async def call(self, method: str, **params: Any) -> dict[str, Any] | None:
        """Ответ метода; `None` — Last.fm ничего не нашёл. Сбой — исключение (и счётчик для предохранителя)."""
        if not self.available:
            raise LastFmUnavailable("Last.fm временно отключён после сбоев")
        await self._throttle()
        try:
            response = await self._http.get(
                self.URL,
                params={"method": method, "api_key": self._key, "format": "json", **params},
                headers={"User-Agent": USER_AGENT},
                timeout=10,
            )
            body = response.json() if response.content else {}
        except (httpx.HTTPError, ValueError) as e:
            self._failed(f"{type(e).__name__}: {e}")
            raise LastFmError(str(e)) from e
        if isinstance(body, dict) and "error" in body:
            if body["error"] == _NOT_FOUND:
                self._failures = 0
                return None
            self._failed(f"ошибка {body['error']}: {body.get('message')}")
            raise LastFmError(f"Last.fm: {body.get('message')} ({body['error']})")
        if response.status_code >= 400:
            self._failed(f"HTTP {response.status_code}")
            raise LastFmError(f"Last.fm: HTTP {response.status_code}")
        self._failures = 0
        return body

    async def _throttle(self) -> None:
        async with self._lock:
            wait = self._last + self._interval - self._clock()
            if wait > 0:
                await asyncio.sleep(wait)
            self._last = self._clock()

    def _failed(self, reason: str) -> None:
        self._failures += 1
        if self._failures >= self._failures_to_trip:
            self._open_until = self._clock() + self._cooldown
            self._failures = 0
            log.warning("Last.fm не отвечает (%s) — пропускаем его %d с", reason, int(self._cooldown))
        else:
            log.info("Last.fm: %s", reason)


def image(images: list[dict[str, Any]] | None) -> str | None:
    """Самая крупная настоящая картинка (без заглушки), в размере 770 px."""
    for raw in reversed(images or []):
        url = raw.get("#text") or ""
        if url and _PLACEHOLDER not in url:
            return _SIZE.sub("/i/u/770x0/", url)
    return None


def _items(value: Any) -> list[dict[str, Any]]:
    """Last.fm отдаёт один элемент объектом, а не списком."""
    if isinstance(value, dict):
        return [value]
    return [v for v in value or [] if isinstance(v, dict)]


def _artist_name(raw: Any) -> str:
    return (raw.get("name") or raw.get("#text") or "") if isinstance(raw, dict) else str(raw or "")


def _track_id(raw: dict[str, Any], artist: str, title: str) -> str:
    return raw.get("mbid") or raw.get("url") or f"{artist} — {title}"


class LastFmLookup:
    """Источник тегов и обложек для обслуживания: `track.getInfo` (с автоисправлением), обложка — альбома."""

    name = NAME
    tags = True

    def __init__(self, client: LastFmClient) -> None:
        self._client = client

    @property
    def available(self) -> bool:
        return self._client.available

    async def lookup(self, artist: str, title: str, album: str | None, duration_s: float | None) -> list[LookupMatch]:
        body = await self._client.call("track.getInfo", artist=artist, track=title, autocorrect=1)
        track = (body or {}).get("track")
        if not track:
            return []
        found_artist = _artist_name(track.get("artist"))
        found_title = track.get("name") or ""
        millis = int(track.get("duration") or 0)
        duration = millis / 1000 if millis else None
        found_album = track.get("album") or {}
        album_title = found_album.get("title")
        cover = image(found_album.get("image"))
        if album and not cover:
            cover = await self._album_cover(found_artist or artist, album)
            album_title = album_title or (album if cover else None)
        return [
            LookupMatch(
                artist=found_artist,
                title=found_title,
                album=album_title,
                duration_s=duration,
                cover_url=cover,
                source=self.name,
                score=match_score(artist, title, duration_s, found_artist, found_title, duration),
                compilation=(found_album.get("artist") or "").lower() in ("various artists", "various"),
            )
        ]

    async def _album_cover(self, artist: str, album: str) -> str | None:
        try:
            body = await self._client.call("album.getInfo", artist=artist, album=album, autocorrect=1)
        except LastFmError:
            return None
        return image(((body or {}).get("album") or {}).get("image"))


class LastFmProvider:
    """Каталог для глобального поиска и популярного исполнителя. `source_id` исполнителя — его имя."""

    name = NAME

    def __init__(self, client: LastFmClient) -> None:
        self._client = client

    async def search(self, query: str) -> SearchHits:
        tracks_body, artists_body = await asyncio.gather(
            self._client.call("track.search", track=query, limit=20),
            self._client.call("artist.search", artist=query, limit=10),
        )
        raw_tracks = _items((((tracks_body or {}).get("results") or {}).get("trackmatches") or {}).get("track"))
        raw_artists = _items((((artists_body or {}).get("results") or {}).get("artistmatches") or {}).get("artist"))
        tracks = [t for t in (self._track(raw) for raw in raw_tracks) if t]
        artists = [
            ArtistCandidate(
                name=raw["name"], cover_url=image(raw.get("image")), source=self.name, source_id=raw["name"]
            )
            for raw in raw_artists
            if raw.get("name")
        ]
        return SearchHits(tracks=tracks, artists=artists)

    async def top_tracks(self, artist_id: str, limit: int) -> list[TrackCandidate]:
        body = await self._client.call("artist.getTopTracks", artist=artist_id, autocorrect=1, limit=limit)
        raw_tracks = _items(((body or {}).get("toptracks") or {}).get("track"))
        return [t for t in (self._track(raw) for raw in raw_tracks) if t][:limit]

    def _track(self, raw: dict[str, Any]) -> TrackCandidate | None:
        title = raw.get("name") or ""
        artist = _artist_name(raw.get("artist"))
        if not title or not artist:
            return None
        # Длительности в поиске и топе Last.fm нет.
        return TrackCandidate(
            title=title,
            artist=artist,
            cover_url=image(raw.get("image")),
            source=self.name,
            source_id=_track_id(raw, artist, title),
        )
