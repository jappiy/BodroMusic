"""«Похожие треки» для полноэкранного плеера: Last.fm `track.getSimilar` и похожие по звучанию из библиотеки.

Треки Last.fm сопоставляются с библиотекой (`LibraryIndex.in_library`): найденные получают `library_song_id` и сразу
играют, остальные приложение предлагает скачать по обычной цепочке источников; им подбирается обложка
(`ImageLookup`: Deezer → Last.fm). Похожие по звучанию (эмбеддинги
анализатора) — только из библиотеки, они добавляются после Last.fm, без повторов. Нет Last.fm или анализа — блок из
того, что есть; нет ничего — пустой список (приложение блок прячет).
"""

from __future__ import annotations

import asyncio
import logging
import time
from collections.abc import Callable
from typing import Any

from ..models import TrackCandidate
from ..navidrome_db import NavidromeDb
from ..reco.keys import track_key
from .images import ImageLookup
from .lastfm import LastFmClient, LastFmError, LastFmProvider, _items

log = logging.getLogger(__name__)

CACHE_TTL_S = 6 * 3600
CACHE_MAX = 500
LASTFM_LIMIT = 30
SOUND_LIMIT = 10


class SimilarTracks:
    def __init__(
        self,
        lastfm: LastFmClient | None,
        in_library: Callable[[str, str], Any] | None,
        analysis: Any = None,
        db: NavidromeDb | None = None,
        images: ImageLookup | None = None,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._lastfm = lastfm
        self._provider = LastFmProvider(lastfm) if lastfm else None
        self._in_library = in_library
        self._analysis = analysis
        self._db = db
        self._images = images
        self._clock = clock
        self._cache: dict[tuple[str, str, str], tuple[float, list[TrackCandidate]]] = {}

    async def similar(
        self, artist: str, title: str, song_id: str | None = None, limit: int = 30
    ) -> list[TrackCandidate]:
        key = (artist.lower(), title.lower(), song_id or "")
        cached = self._cache.get(key)
        if cached and self._clock() - cached[0] < CACHE_TTL_S:
            return cached[1][:limit]
        tracks = await self._lastfm_similar(artist, title)
        seen = {track_key(t.artist, t.title) for t in tracks} | {track_key(artist, title)}
        for t in await self._sound_similar(song_id):
            if track_key(t.artist, t.title) not in seen:
                seen.add(track_key(t.artist, t.title))
                tracks.append(t)
        if len(self._cache) >= CACHE_MAX:
            self._cache.pop(next(iter(self._cache)))
        self._cache[key] = (self._clock(), tracks)
        return tracks[:limit]

    async def _lastfm_similar(self, artist: str, title: str) -> list[TrackCandidate]:
        if self._lastfm is None or self._provider is None:
            return []
        try:
            body = await self._lastfm.call(
                "track.getSimilar", artist=artist, track=title, limit=LASTFM_LIMIT, autocorrect=1
            )
        except LastFmError as e:
            log.info("Похожие на «%s — %s»: Last.fm недоступен (%s)", artist, title, e)
            return []
        raw = _items(((body or {}).get("similartracks") or {}).get("track"))
        tracks = [t for t in (self._provider._track(r) for r in raw) if t]  # noqa: SLF001
        if self._in_library is not None:
            ids = await asyncio.gather(*(self._in_library(t.artist, t.title) for t in tracks))
            tracks = [t.model_copy(update={"library_song_id": i}) if i else t for t, i in zip(tracks, ids, strict=True)]
        if self._images is not None:
            # Обложка трека не из библиотеки: Deezer → Last.fm (у Last.fm в похожих картинки почти всегда пустые).
            missing = [t for t in tracks if not t.library_song_id and not t.cover_url]
            covers = await asyncio.gather(*(self._images.track_cover(t.artist, t.title) for t in missing))
            found = {id(t): c for t, c in zip(missing, covers, strict=True) if c}
            tracks = [t.model_copy(update={"cover_url": found[id(t)]}) if id(t) in found else t for t in tracks]
        return tracks

    async def _sound_similar(self, song_id: str | None) -> list[TrackCandidate]:
        if not song_id or self._analysis is None or self._db is None:
            return []
        ids = await self._analysis.similar(song_id, SOUND_LIMIT)
        songs = {s.id: s for s in await asyncio.to_thread(self._db.songs, ids)}
        return [
            TrackCandidate(
                title=s.title, artist=s.artist, album=s.album,
                duration_ms=int(s.duration_s * 1000) if s.duration_s else None,
                source="sound", source_id=s.id, library_song_id=s.id,
            )
            for i in ids if (s := songs.get(i))
        ]  # fmt: skip
