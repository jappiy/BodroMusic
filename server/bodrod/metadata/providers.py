"""Каталоги для «глобального» поиска: Яндекс Музыка (основной) и Deezer (запасной)."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Protocol

import httpx

from ..models import ArtistCandidate, TrackCandidate


@dataclass
class SearchHits:
    tracks: list[TrackCandidate] = field(default_factory=list)
    artists: list[ArtistCandidate] = field(default_factory=list)
    """Каталог сам считает лучшим результатом исполнителя (у Яндекса есть поле `best`)."""
    best_is_artist: bool = False

    @property
    def is_empty(self) -> bool:
        return not self.tracks and not self.artists


class MetadataProvider(Protocol):
    name: str

    async def search(self, query: str) -> SearchHits: ...

    async def top_tracks(self, artist_id: str, limit: int) -> list[TrackCandidate]: ...


class YandexMusicProvider:
    """Неофициальный API Яндекс Музыки. Токен необязателен, но без него часть каталога может быть недоступна."""

    name = "yandex"
    BASE = "https://api.music.yandex.net"

    def __init__(self, http: httpx.AsyncClient, token: str | None) -> None:
        self._http = http
        self._headers = {"Accept-Language": "ru", "X-Yandex-Music-Client": "YandexMusicAndroid/24023621"}
        if token:
            self._headers["Authorization"] = f"OAuth {token}"

    async def _get(self, path: str, **params: Any) -> Any:
        response = await self._http.get(self.BASE + path, params=params, headers=self._headers, timeout=15)
        response.raise_for_status()
        return response.json().get("result")

    async def search(self, query: str) -> SearchHits:
        result = await self._get("/search", text=query, type="all", page=0, nocorrect="false") or {}
        tracks = [t for t in (_track_yandex(raw) for raw in (result.get("tracks") or {}).get("results") or []) if t]
        artists = [a for a in (_artist_yandex(raw) for raw in (result.get("artists") or {}).get("results") or []) if a]
        best = result.get("best") or {}
        return SearchHits(tracks=tracks[:20], artists=artists[:10], best_is_artist=best.get("type") == "artist")

    async def top_tracks(self, artist_id: str, limit: int) -> list[TrackCandidate]:
        result = await self._get(f"/artists/{artist_id}/tracks", page=0, **{"page-size": limit}) or {}
        tracks = [t for t in (_track_yandex(raw) for raw in result.get("tracks") or []) if t]
        if not tracks:
            brief = await self._get(f"/artists/{artist_id}/brief-info") or {}
            tracks = [t for t in (_track_yandex(raw) for raw in brief.get("popularTracks") or []) if t]
        return tracks[:limit]


def _yandex_cover(uri: str | None, size: str = "400x400") -> str | None:
    if not uri:
        return None
    return "https://" + uri.replace("%%", size)


def _track_yandex(raw: dict[str, Any]) -> TrackCandidate | None:
    if raw.get("available") is False and not raw.get("title"):
        return None
    artists = [a.get("name") for a in raw.get("artists") or [] if a.get("name")]
    title = raw.get("title")
    if not title or not artists:
        return None
    version = raw.get("version")
    albums = raw.get("albums") or []
    return TrackCandidate(
        title=f"{title} ({version})" if version else title,
        artist=", ".join(artists),
        album=albums[0].get("title") if albums else None,
        duration_ms=raw.get("durationMs"),
        cover_url=_yandex_cover(raw.get("coverUri") or raw.get("ogImage")),
        source="yandex",
        source_id=str(raw.get("id")),
    )


def _artist_yandex(raw: dict[str, Any]) -> ArtistCandidate | None:
    if not raw.get("name"):
        return None
    return ArtistCandidate(
        name=raw["name"],
        cover_url=_yandex_cover((raw.get("cover") or {}).get("uri") or raw.get("ogImage")),
        source="yandex",
        source_id=str(raw.get("id")),
    )


class DeezerProvider:
    """Публичный API Deezer, без ключа."""

    name = "deezer"
    BASE = "https://api.deezer.com"

    def __init__(self, http: httpx.AsyncClient) -> None:
        self._http = http

    async def _get(self, path: str, **params: Any) -> list[dict[str, Any]]:
        response = await self._http.get(self.BASE + path, params=params, timeout=15)
        response.raise_for_status()
        body = response.json()
        if "error" in body:
            raise httpx.HTTPError(f"Deezer: {body['error']}")
        return body.get("data") or []

    async def search(self, query: str) -> SearchHits:
        tracks = [_track_deezer(t) for t in await self._get("/search/track", q=query, limit=20)]
        artists = [_artist_deezer(a) for a in await self._get("/search/artist", q=query, limit=10)]
        return SearchHits(tracks=tracks, artists=artists)

    async def top_tracks(self, artist_id: str, limit: int) -> list[TrackCandidate]:
        return [_track_deezer(t) for t in await self._get(f"/artist/{artist_id}/top", limit=limit)]


def _track_deezer(raw: dict[str, Any]) -> TrackCandidate:
    album = raw.get("album") or {}
    duration = raw.get("duration")
    return TrackCandidate(
        title=raw.get("title") or "",
        artist=(raw.get("artist") or {}).get("name") or "",
        album=album.get("title"),
        duration_ms=duration * 1000 if duration else None,
        cover_url=album.get("cover_medium") or album.get("cover"),
        source="deezer",
        source_id=str(raw.get("id")),
    )


def _artist_deezer(raw: dict[str, Any]) -> ArtistCandidate:
    return ArtistCandidate(
        name=raw.get("name") or "",
        cover_url=raw.get("picture_medium") or raw.get("picture"),
        source="deezer",
        source_id=str(raw.get("id")),
    )
