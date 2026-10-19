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
from .keys import artist_key, track_key

REFRESH_S = 3600


@dataclass
class LibrarySnapshot:
    """Ключ трека → id в Navidrome (библиотека и пул отдельно); ключи исполнителей библиотеки."""

    library: dict[str, str] = field(default_factory=dict)
    pool: dict[str, str] = field(default_factory=dict)
    artists: set[str] = field(default_factory=set)
    """id трека → путь (как его видит Navidrome) для треков пула."""
    pool_paths: dict[str, str] = field(default_factory=dict)
    built_at: float = 0.0


class LibraryIndex:
    def __init__(
        self,
        db: NavidromeDb | None,
        navidrome: NavidromeClient,
        pool_prefix: str | None,
        clock: Callable[[], float] = time.time,
    ) -> None:
        """`pool_prefix` — путь discovery-пула так, как его видит Navidrome (`/music/_discovery`)."""
        self._db = db
        self._navidrome = navidrome
        self._pool_prefix = pool_prefix.rstrip("/") + "/" if pool_prefix else None
        self._clock = clock
        self._snapshot = LibrarySnapshot()
        self._lock = asyncio.Lock()

    @property
    def available(self) -> bool:
        return self._db is not None

    def invalidate(self) -> None:
        self._snapshot.built_at = 0.0

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
        for song in self._db.iter_songs():
            self._add(snap, song)
        return snap

    def _add(self, snap: LibrarySnapshot, song: NdSong) -> None:
        key = track_key(song.artist, song.title)
        if self._pool_prefix and song.path.startswith(self._pool_prefix):
            snap.pool[key] = song.id
            snap.pool_paths[song.id] = song.path
            return
        snap.library.setdefault(key, song.id)
        snap.artists.add(artist_key(song.artist))
        if song.album_artist:
            snap.artists.add(artist_key(song.album_artist))

    async def in_library(self, artist: str, title: str) -> str | None:
        """id трека в библиотеке (не в пуле) или `None`."""
        if self._db is not None:
            return (await self.snapshot()).library.get(track_key(artist, title))
        return await find_in_library(self._navidrome, artist, title)
