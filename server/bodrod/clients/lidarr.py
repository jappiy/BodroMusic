"""Lidarr API v1: добавление исполнителя с мониторингом всей дискографии и отслеживание прогресса."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import httpx

from ..text import normalize


@dataclass(frozen=True)
class LidarrStats:
    track_file_count: int
    total_track_count: int

    @property
    def progress(self) -> float | None:
        if self.total_track_count <= 0:
            return None
        return min(1.0, self.track_file_count / self.total_track_count)


class LidarrError(Exception):
    pass


class LidarrClient:
    def __init__(
        self,
        http: httpx.AsyncClient,
        base_url: str,
        api_key: str,
        root_folder: str | None = None,
        quality_profile_id: int | None = None,
        metadata_profile_id: int | None = None,
    ) -> None:
        self._http = http
        self._base = base_url.rstrip("/") + "/api/v1"
        self._headers = {"X-Api-Key": api_key}
        self._root_folder = root_folder
        self._quality_profile_id = quality_profile_id
        self._metadata_profile_id = metadata_profile_id

    async def _request(self, method: str, path: str, **kwargs: Any) -> Any:
        response = await self._http.request(method, self._base + path, headers=self._headers, timeout=60, **kwargs)
        response.raise_for_status()
        return response.json() if response.content else None

    async def lookup(self, name: str) -> dict[str, Any] | None:
        """Лучшее совпадение по имени в MusicBrainz (через Lidarr)."""
        results = await self._request("GET", "/artist/lookup", params={"term": name}) or []
        if not results:
            return None
        wanted = normalize(name)
        for artist in results:
            if normalize(artist.get("artistName")) == wanted:
                return artist
        return results[0]

    async def find_existing(self, foreign_artist_id: str) -> dict[str, Any] | None:
        artists = await self._request("GET", "/artist") or []
        return next((a for a in artists if a.get("foreignArtistId") == foreign_artist_id), None)

    async def _defaults(self) -> tuple[str, int, int]:
        root = self._root_folder
        if not root:
            folders = await self._request("GET", "/rootfolder") or []
            if not folders:
                raise LidarrError("В Lidarr не настроена корневая папка")
            root = folders[0]["path"]
        quality = self._quality_profile_id
        if quality is None:
            profiles = await self._request("GET", "/qualityprofile") or []
            if not profiles:
                raise LidarrError("В Lidarr нет профилей качества")
            quality = int(profiles[0]["id"])
        metadata = self._metadata_profile_id
        if metadata is None:
            profiles = await self._request("GET", "/metadataprofile") or []
            if not profiles:
                raise LidarrError("В Lidarr нет профилей метаданных")
            metadata = int(profiles[0]["id"])
        return root, quality, metadata

    async def add_or_search(self, name: str) -> tuple[int, str]:
        """Добавить исполнителя (или запустить поиск у уже добавленного). Возвращает `(id в Lidarr, имя)`."""
        found = await self.lookup(name)
        if found is None:
            raise LidarrError(f"Lidarr не знает исполнителя «{name}»")
        existing = await self.find_existing(found["foreignArtistId"])
        if existing is not None:
            artist_id = int(existing["id"])
            if not existing.get("monitored"):
                existing["monitored"] = True
                existing["monitorNewItems"] = "all"
                await self._request("PUT", f"/artist/{artist_id}", json=existing)
            await self._request("POST", "/command", json={"name": "ArtistSearch", "artistId": artist_id})
            return artist_id, existing.get("artistName") or name
        root, quality, metadata = await self._defaults()
        body = {
            **found,
            "qualityProfileId": quality,
            "metadataProfileId": metadata,
            "rootFolderPath": root,
            "monitored": True,
            "monitorNewItems": "all",
            "addOptions": {"monitor": "all", "searchForMissingAlbums": True},
        }
        created = await self._request("POST", "/artist", json=body)
        return int(created["id"]), created.get("artistName") or name

    async def stats(self, artist_id: int) -> LidarrStats:
        artist = await self._request("GET", f"/artist/{artist_id}") or {}
        stats = artist.get("statistics") or {}
        return LidarrStats(
            track_file_count=int(stats.get("trackFileCount") or 0),
            total_track_count=int(stats.get("totalTrackCount") or stats.get("trackCount") or 0),
        )

    async def queue_size(self, artist_id: int) -> int:
        items = await self._request("GET", "/queue/details", params={"artistId": artist_id}) or []
        return len(items)
