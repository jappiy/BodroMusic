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

    async def _ensure_artist(self, name: str, monitor: str, search: bool) -> tuple[dict[str, Any], bool]:
        """Исполнитель в Lidarr (найденный или добавленный с `addOptions.monitor`). `(исполнитель, был_раньше)`."""
        found = await self.lookup(name)
        if found is None:
            raise LidarrError(f"Lidarr не знает исполнителя «{name}»")
        existing = await self.find_existing(found["foreignArtistId"])
        if existing is not None:
            if not existing.get("monitored"):
                existing["monitored"] = True
                existing["monitorNewItems"] = "all"
                await self._request("PUT", f"/artist/{existing['id']}", json=existing)
            return existing, True
        root, quality, metadata = await self._defaults()
        body = {
            **found,
            "qualityProfileId": quality,
            "metadataProfileId": metadata,
            "rootFolderPath": root,
            "monitored": True,
            "monitorNewItems": "all",
            "addOptions": {"monitor": monitor, "searchForMissingAlbums": search},
        }
        return await self._request("POST", "/artist", json=body), False

    async def add_or_search(self, name: str) -> tuple[int, str]:
        """Добавить исполнителя (или запустить поиск у уже добавленного). Возвращает `(id в Lidarr, имя)`."""
        artist, existed = await self._ensure_artist(name, "all", True)
        artist_id = int(artist["id"])
        if existed:
            await self._request("POST", "/command", json={"name": "ArtistSearch", "artistId": artist_id})
        return artist_id, artist.get("artistName") or name

    async def follow_artist(self, name: str) -> int:
        """Отслеживать только новые релизы (дискографию уже скачал другой источник). Возвращает id в Lidarr."""
        artist, _ = await self._ensure_artist(name, "future", False)
        return int(artist["id"])

    async def refresh_artist(self, artist_id: int) -> None:
        """Пересканировать папку исполнителя: Lidarr увидит файлы, положенные bodrod, и снимет «метки»."""
        await self._request("POST", "/command", json={"name": "RefreshArtist", "artistId": artist_id})

    # region Альбом

    async def find_album(self, artist_id: int, title: str) -> tuple[int | None, int, str]:
        albums = await self._request("GET", "/album", params={"artistId": artist_id}) or []
        wanted = normalize(title)
        for album in albums:
            if normalize(album.get("title")) == wanted:
                return int(album["id"]), artist_id, album.get("title") or title
        return None, artist_id, title

    async def monitor_album(self, album_id: int) -> None:
        await self._request("PUT", "/album/monitor", json={"albumIds": [album_id], "monitored": True})
        await self._request("POST", "/command", json={"name": "AlbumSearch", "albumIds": [album_id]})

    async def add_album(self, artist: str, title: str) -> tuple[int | None, int, str]:
        """Один альбом: исполнитель без мониторинга остальных, альбом — с мониторингом и поиском.

        `(id альбома или None, если Lidarr ещё не подтянул список альбомов, id исполнителя, название)`.
        """
        found, _ = await self._ensure_artist(artist, "none", False)
        album_id, artist_id, name = await self.find_album(int(found["id"]), title)
        if album_id is not None:
            await self.monitor_album(album_id)
        return album_id, artist_id, name

    async def album_stats(self, album_id: int) -> LidarrStats:
        album = await self._request("GET", f"/album/{album_id}") or {}
        stats = album.get("statistics") or {}
        return LidarrStats(
            track_file_count=int(stats.get("trackFileCount") or 0),
            total_track_count=int(stats.get("totalTrackCount") or stats.get("trackCount") or 0),
        )

    async def wanted_missing(self, limit: int) -> list[dict[str, Any]]:
        """«Метки»: недостающие альбомы отслеживаемых исполнителей, новые сверху."""
        page = (
            await self._request(
                "GET",
                "/wanted/missing",
                params={
                    "page": 1,
                    "pageSize": limit,
                    "monitored": "true",
                    "sortKey": "releaseDate",
                    "sortDirection": "descending",
                    "includeArtist": "true",
                },
            )
            or {}
        )
        return page.get("records") or []

    # endregion

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

    async def stop_artist(self, artist_id: int) -> int:
        """Отмена «скачать всё»: снять мониторинг исполнителя и убрать его загрузки из очереди. Файлы остаются."""
        artist = await self._request("GET", f"/artist/{artist_id}")
        if artist:
            artist["monitored"] = False
            artist["monitorNewItems"] = "none"
            await self._request("PUT", f"/artist/{artist_id}", json=artist)
        items = await self._request("GET", "/queue/details", params={"artistId": artist_id}) or []
        for item in items:
            await self._request(
                "DELETE", f"/queue/{item['id']}", params={"removeFromClient": "true", "blocklist": "false"}
            )
        return len(items)

    # region Удаление файлов

    async def artists(self) -> list[dict[str, Any]]:
        return await self._request("GET", "/artist") or []

    async def track_files(self, artist_id: int) -> list[dict[str, Any]]:
        return await self._request("GET", "/trackfile", params={"artistId": artist_id}) or []

    async def delete_track_file(self, track_file_id: int) -> None:
        await self._request("DELETE", f"/trackfile/{track_file_id}")

    async def album(self, album_id: int) -> dict[str, Any]:
        return await self._request("GET", f"/album/{album_id}") or {}

    async def album_file_count(self, album_id: int) -> int:
        album = await self._request("GET", f"/album/{album_id}") or {}
        return int((album.get("statistics") or {}).get("trackFileCount") or 0)

    async def unmonitor_album(self, album_id: int) -> None:
        await self._request("PUT", "/album/monitor", json={"albumIds": [album_id], "monitored": False})

    # endregion
