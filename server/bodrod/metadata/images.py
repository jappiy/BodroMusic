"""Картинки для треков и исполнителей, которых нет в библиотеке (или у которых нет своей обложки).

Порядок: Deezer (публичный API, без ключа) → Last.fm (`track.getInfo` — обложка альбома, `artist.getInfo`).
Заглушки отбрасываются: у Deezer — картинка исполнителя без хеша (`/images/artist//`), у Last.fm — серая звезда.
Ответы (и «не нашли») кэшируются в памяти на сутки.
"""

from __future__ import annotations

import asyncio
import logging
import time
from collections.abc import Callable
from typing import Any

import httpx

from ..text import same_artist, similarity_any
from .lastfm import LastFmClient, LastFmError, image

log = logging.getLogger(__name__)

DEEZER = "https://api.deezer.com"
CACHE_TTL_S = 24 * 3600
CACHE_MAX = 3000
TITLE_MATCH = 0.8
CONCURRENCY = 4


def _deezer_real(url: str | None) -> str | None:
    if not url or "/images/artist//" in url or "/images/cover//" in url:
        return None
    return url


class ImageLookup:
    def __init__(
        self,
        http: httpx.AsyncClient,
        lastfm: LastFmClient | None,
        deezer_enabled: bool = True,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._http = http
        self._lastfm = lastfm
        self._deezer = deezer_enabled
        self._clock = clock
        self._cache: dict[tuple[str, str, str], tuple[float, str | None]] = {}
        self._semaphore = asyncio.Semaphore(CONCURRENCY)

    def _cached(self, key: tuple[str, str, str]) -> tuple[bool, str | None]:
        hit = self._cache.get(key)
        if hit and self._clock() - hit[0] < CACHE_TTL_S:
            return True, hit[1]
        return False, None

    def _remember(self, key: tuple[str, str, str], url: str | None) -> str | None:
        if len(self._cache) >= CACHE_MAX:
            self._cache.pop(next(iter(self._cache)))
        self._cache[key] = (self._clock(), url)
        return url

    async def _deezer_get(self, path: str, **params: Any) -> list[dict[str, Any]]:
        response = await self._http.get(DEEZER + path, params=params, timeout=10)
        response.raise_for_status()
        body = response.json()
        if not isinstance(body, dict) or "error" in body:
            return []
        return body.get("data") or []

    async def track_cover(self, artist: str, title: str) -> str | None:
        key = ("track", artist.lower(), title.lower())
        hit, url = self._cached(key)
        if hit:
            return url
        async with self._semaphore:
            url = await self._deezer_track(artist, title) or await self._lastfm_track(artist, title)
        return self._remember(key, url)

    async def artist_image(self, name: str) -> str | None:
        key = ("artist", name.lower(), "")
        hit, url = self._cached(key)
        if hit:
            return url
        async with self._semaphore:
            url = await self._deezer_artist(name) or await self._lastfm_artist(name)
        return self._remember(key, url)

    async def _deezer_track(self, artist: str, title: str) -> str | None:
        if not self._deezer:
            return None
        try:
            hits = await self._deezer_get("/search/track", q=f'artist:"{artist}" track:"{title}"', limit=5)
            if not hits:
                hits = await self._deezer_get("/search/track", q=f"{artist} {title}", limit=5)
        except (httpx.HTTPError, ValueError) as e:
            log.info("Обложка «%s — %s»: Deezer недоступен (%s)", artist, title, e)
            return None
        for raw in hits:
            if same_artist((raw.get("artist") or {}).get("name"), artist) and (
                similarity_any(raw.get("title"), title) >= TITLE_MATCH
            ):
                album = raw.get("album") or {}
                return _deezer_real(album.get("cover_xl") or album.get("cover_big") or album.get("cover_medium"))
        return None

    async def _deezer_artist(self, name: str) -> str | None:
        if not self._deezer:
            return None
        try:
            hits = await self._deezer_get("/search/artist", q=name, limit=5)
        except (httpx.HTTPError, ValueError) as e:
            log.info("Фото «%s»: Deezer недоступен (%s)", name, e)
            return None
        for raw in hits:
            if same_artist(raw.get("name"), name):
                return _deezer_real(raw.get("picture_xl") or raw.get("picture_big") or raw.get("picture_medium"))
        return None

    async def _lastfm_track(self, artist: str, title: str) -> str | None:
        if self._lastfm is None:
            return None
        try:
            body = await self._lastfm.call("track.getInfo", artist=artist, track=title, autocorrect=1)
        except LastFmError:
            return None
        album = ((body or {}).get("track") or {}).get("album") or {}
        return image(album.get("image"))

    async def _lastfm_artist(self, name: str) -> str | None:
        if self._lastfm is None:
            return None
        try:
            body = await self._lastfm.call("artist.getInfo", artist=name, autocorrect=1)
        except LastFmError:
            return None
        return image(((body or {}).get("artist") or {}).get("image"))
