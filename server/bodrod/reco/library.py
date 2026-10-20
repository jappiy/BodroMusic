"""Индекс библиотеки Navidrome по ключам треков (`keys.track_key`): «есть ли уже такой трек».

Строится по БД Navidrome (`NAVIDROME_DB`) раз в час. Треки discovery-пула в Navidrome видны, но в библиотеку не
входят — они отделены по пути. Без БД Navidrome индекс пуст, и проверка идёт поиском Subsonic (`find_in_library`).
"""

from __future__ import annotations

import asyncio
import time
from collections.abc import Callable
from dataclasses import dataclass, field

from ..clients.navidrome import NavidromeClient
from ..metadata.discover import find_in_library
from ..navidrome_db import NavidromeDb, NdSong
from .keys import artist_key, track_key, track_keys

REFRESH_S = 3600


@dataclass
class LibrarySnapshot:
    """Ключ трека → id в Navidrome (библиотека и пул отдельно); ключи исполнителей библиотеки."""

    library: dict[str, str] = field(default_factory=dict)
    pool: dict[str, str] = field(default_factory=dict)
    artists: set[str] = field(default_factory=set)
    """id трека → путь (как его видит Navidrome) для треков пула."""
    pool_paths: dict[str, str] = field(default_factory=dict)
    """Альбомы и исполнители, у которых все треки — в пуле: приложение их скрывает."""
    pool_only_albums: set[str] = field(default_factory=set)
    pool_only_artists: set[str] = field(default_factory=set)
    """Библиотека: ключи по каждому исполнителю трека (`A, B` — и `A`, и `B`) и ISRC → id."""
    library_alt: dict[str, str] = field(default_factory=dict)
    library_isrc: dict[str, str] = field(default_factory=dict)
    """id всех треков (библиотека и пул) — проверка, что привязанная песня ещё есть."""
    ids: set[str] = field(default_factory=set)
    built_at: float = 0.0

    def find(self, artist: str, title: str, isrc: str | None = None) -> str | None:
        """id трека библиотеки: по ключу, по любому исполнителю из списка, затем по ISRC."""
        keys = track_keys(artist, title)
        for key in keys:
            if key in self.library:
                return self.library[key]
        for key in keys:
            if key in self.library_alt:
                return self.library_alt[key]
        return self.library_isrc.get(isrc.strip().upper()) if isrc else None


class LibraryIndex:
    def __init__(
        self,
        db: NavidromeDb | None,
        navidrome: NavidromeClient,
        pool_prefix: str | None,
        clock: Callable[[], float] = time.time,
        path_map: Callable[[str], str | None] | None = None,
    ) -> None:
        """`pool_prefix` — путь discovery-пула так, как его видит Navidrome (`/music/_discovery`);
        `path_map` — путь bodrod → путь Navidrome (разные точки монтирования)."""
        self._db = db
        self._navidrome = navidrome
        self._pool_prefix = pool_prefix.rstrip("/") + "/" if pool_prefix else None
        self._clock = clock
        self._path_map = path_map
        self._snapshot = LibrarySnapshot()
        self._lock = asyncio.Lock()

    @property
    def available(self) -> bool:
        return self._db is not None

    def invalidate(self) -> None:
        self._snapshot.built_at = 0.0

    def age(self) -> float:
        """Сколько секунд назад построен индекс (не построен — бесконечность)."""
        built = self._snapshot.built_at
        return self._clock() - built if built else float("inf")

    async def song_by_path(self, path: str) -> str | None:
        """id песни Navidrome по пути файла (как его видит bodrod); до скана Navidrome — `None`."""
        if self._db is None:
            return None
        nd_path = (self._path_map(path) if self._path_map else None) or path
        return (await asyncio.to_thread(self._db.song_ids_by_paths, [nd_path])).get(nd_path)

    async def snapshot(self) -> LibrarySnapshot:
        if self._db is None:
            return self._snapshot
        async with self._lock:
            if self._clock() - self._snapshot.built_at > REFRESH_S:
                self._snapshot = await asyncio.to_thread(self._build)
        return self._snapshot

    def _build(self) -> LibrarySnapshot:
        assert self._db is not None
        snap = LibrarySnapshot(built_at=self._clock())
        albums: dict[str, bool] = {}
        artists: dict[str, bool] = {}
        for song in self._db.iter_songs():
            in_pool = self._add(snap, song)
            for album in (song.album_id,):
                if album:
                    albums[album] = albums.get(album, True) and in_pool
            for artist in {song.artist_id, song.album_artist_id} - {None}:
                artists[artist] = artists.get(artist, True) and in_pool
        snap.pool_only_albums = {a for a, only in albums.items() if only}
        snap.pool_only_artists = {a for a, only in artists.items() if only}
        return snap

    def _add(self, snap: LibrarySnapshot, song: NdSong) -> bool:
        """`True` — трек из пула."""
        key = track_key(song.artist, song.title)
        snap.ids.add(song.id)
        if self._pool_prefix and song.path.startswith(self._pool_prefix):
            snap.pool[key] = song.id
            snap.pool_paths[song.id] = song.path
            return True
        snap.library.setdefault(key, song.id)
        for alt in track_keys(song.artist, song.title)[1:]:
            snap.library_alt.setdefault(alt, song.id)
        if song.isrc:
            snap.library_isrc.setdefault(song.isrc, song.id)
        snap.artists.add(artist_key(song.artist))
        if song.album_artist:
            snap.artists.add(artist_key(song.album_artist))
        return False

    async def in_library(self, artist: str, title: str) -> str | None:
        """id трека в библиотеке (не в пуле) или `None`."""
        if self._db is not None:
            return (await self.snapshot()).find(artist, title)
        return await find_in_library(self._navidrome, artist, title)
