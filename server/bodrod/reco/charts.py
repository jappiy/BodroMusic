"""Чарты: раз в сутки снимки из Яндекса, Deezer, Last.fm, Apple Music и ListenBrainz — с позицией и движением.

Каждый трек чарта сопоставляется с библиотекой и пулом (`keys.track_key`). Недостающие из топ-`CHARTS_DOWNLOAD_TOP`
(30) каждого включённого чарта ставятся в общий пул с владельцем `charts` — по тем же правилам: лайк любого
пользователя переводит трек в библиотеку. Трек, выпавший из всех чартов и не слушавшийся 30 дней, удаляется.
Чарт, который не ответил (например, Яндекс после конца подписки), скрывается до следующего удачного обновления.
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any

import httpx

from ..config import Settings
from ..db import Store
from ..metadata.lastfm import LastFmClient, _artist_name, _items, image
from .keys import track_key
from .library import LibraryIndex
from .pool import CHARTS_OWNER, PoolService
from .store import DAY_MS

log = logging.getLogger(__name__)

SCHEMA = """
CREATE TABLE IF NOT EXISTS charts (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    source TEXT NOT NULL,
    available INTEGER NOT NULL DEFAULT 0,
    error TEXT,
    updated_at INTEGER
);
CREATE TABLE IF NOT EXISTS chart_entries (
    chart_id TEXT NOT NULL,
    position INTEGER NOT NULL,
    key TEXT NOT NULL,
    artist TEXT NOT NULL,
    title TEXT NOT NULL,
    album TEXT,
    cover_url TEXT,
    duration_ms INTEGER,
    prev_position INTEGER,
    PRIMARY KEY (chart_id, position)
);
CREATE INDEX IF NOT EXISTS idx_chart_entries_key ON chart_entries(key);
"""

UPDATE_EVERY_MS = DAY_MS - 30 * 60 * 1000
CHECK_S = 3600
CHART_EXPIRE_MS = 30 * DAY_MS
SIZE = 100


@dataclass
class ChartTrack:
    artist: str
    title: str
    album: str | None = None
    cover_url: str | None = None
    duration_ms: int | None = None


Fetcher = Callable[[], Awaitable[list[ChartTrack]]]


@dataclass
class ChartSource:
    id: str
    title: str
    source: str
    fetch: Fetcher


def build_sources(
    http: httpx.AsyncClient,
    lastfm: LastFmClient | None,
    yandex_chart: Fetcher | None,
    deezer_enabled: bool = True,
) -> list[ChartSource]:
    """Все чарты по умолчанию; какие обновлять — `CHARTS` (по id)."""

    async def deezer(path: str) -> list[ChartTrack]:
        response = await http.get(f"https://api.deezer.com{path}", params={"limit": SIZE}, timeout=15)
        response.raise_for_status()
        body = response.json()
        if "error" in body:
            raise httpx.HTTPError(f"Deezer: {body['error']}")
        return [
            ChartTrack(
                artist=(t.get("artist") or {}).get("name") or "",
                title=t.get("title") or "",
                album=(t.get("album") or {}).get("title"),
                cover_url=(t.get("album") or {}).get("cover_xl") or (t.get("album") or {}).get("cover_big"),
                duration_ms=t["duration"] * 1000 if t.get("duration") else None,
            )
            for t in body.get("data") or []
        ]

    async def lastfm_call(method: str, **params: Any) -> list[ChartTrack]:
        assert lastfm is not None
        body = await lastfm.call(method, limit=SIZE, **params) or {}
        root = body.get("tracks") or body.get("toptracks") or {}
        return [
            ChartTrack(
                artist=_artist_name(t.get("artist")),
                title=t.get("name") or "",
                cover_url=image(t.get("image")),
                duration_ms=int(t["duration"]) * 1000
                if str(t.get("duration") or "0").isdigit() and int(t["duration"])
                else None,
            )
            for t in _items(root.get("track"))
        ]

    async def apple() -> list[ChartTrack]:
        url = f"https://rss.applemarketingtools.com/api/v2/ru/music/most-played/{SIZE}/songs.json"
        response = await http.get(url, timeout=15)
        response.raise_for_status()
        return [
            ChartTrack(
                artist=r.get("artistName") or "",
                title=r.get("name") or "",
                cover_url=(r.get("artworkUrl100") or "").replace("100x100", "600x600") or None,
            )
            for r in (response.json().get("feed") or {}).get("results") or []
        ]

    async def listenbrainz() -> list[ChartTrack]:
        response = await http.get(
            "https://api.listenbrainz.org/1/stats/sitewide/recordings",
            params={"count": SIZE, "range": "week"},
            timeout=20,
        )
        response.raise_for_status()
        return [
            ChartTrack(artist=r.get("artist_name") or "", title=r.get("track_name") or "", album=r.get("release_name"))
            for r in (response.json().get("payload") or {}).get("recordings") or []
        ]

    sources: list[ChartSource] = []
    if yandex_chart is not None:
        sources.append(ChartSource("yandex", "Чарт Яндекс Музыки", "yandex", yandex_chart))
    if deezer_enabled:
        sources.append(ChartSource("deezer_world", "Deezer: мир", "deezer", lambda: deezer("/chart/0/tracks")))
        sources.append(
            ChartSource("deezer_russia", "Deezer: Top Russia", "deezer", lambda: deezer("/playlist/1116189381/tracks"))
        )
    if lastfm is not None:
        sources.append(ChartSource("lastfm_world", "Last.fm: мир", "lastfm", lambda: lastfm_call("chart.getTopTracks")))
        sources.append(
            ChartSource(
                "lastfm_russia", "Last.fm: Россия", "lastfm",
                lambda: lastfm_call("geo.getTopTracks", country="Russian Federation"),
            )
        )  # fmt: skip
    sources.append(ChartSource("apple_ru", "Apple Music: Россия", "apple", apple))
    sources.append(ChartSource("listenbrainz", "ListenBrainz: неделя", "listenbrainz", listenbrainz))
    return sources


class ChartsService:
    def __init__(
        self,
        store: Store,
        settings: Settings,
        sources: list[ChartSource],
        library: LibraryIndex,
        pool: PoolService | None,
        clock: Callable[[], int] = lambda: int(time.time() * 1000),
    ) -> None:
        self._store = store
        self._settings = settings
        enabled = set(settings.charts) if settings.charts else None
        self._sources = [s for s in sources if enabled is None or s.id in enabled]
        self._library = library
        self._pool = pool
        self._clock = clock
        self._wakeup = asyncio.Event()
        store.executescript(SCHEMA)

    def wake(self) -> None:
        self._wakeup.set()

    async def run_forever(self) -> None:
        while True:
            try:
                await self.update()
                await self.upkeep()
            except Exception:  # noqa: BLE001 — повторим через час
                log.exception("Чарты: ошибка обновления")
            self._wakeup.clear()
            with contextlib.suppress(TimeoutError):
                await asyncio.wait_for(self._wakeup.wait(), timeout=CHECK_S)

    async def update(self, force: bool = False) -> int:
        """Обновить чарты, у которых прошли сутки. Возвращает число обновлённых."""
        updated = 0
        for source in self._sources:
            row = self._store.query_one("SELECT updated_at FROM charts WHERE id = ?", (source.id,))
            if not force and row and row["updated_at"] and self._clock() - int(row["updated_at"]) < UPDATE_EVERY_MS:
                continue
            try:
                tracks = [t for t in await source.fetch() if t.artist and t.title][:SIZE]
                if not tracks:
                    raise ValueError("пустой ответ")
            except Exception as e:  # noqa: BLE001 — чарт скрывается до следующего удачного обновления
                log.warning("Чарты: %s не обновлён: %s", source.title, e)
                self._store.execute(
                    "INSERT INTO charts (id, title, source, available, error) VALUES (?, ?, ?, 0, ?) "
                    "ON CONFLICT(id) DO UPDATE SET available = 0, error = excluded.error, title = excluded.title",
                    (source.id, source.title, source.source, str(e)[:300]),
                )
                continue
            self._save(source, tracks)
            updated += 1
            await self._download_top(source.id)
        return updated

    def _save(self, source: ChartSource, tracks: list[ChartTrack]) -> None:
        previous = {
            r["key"]: r["position"]
            for r in self._store.query("SELECT key, position FROM chart_entries WHERE chart_id = ?", (source.id,))
        }
        rows = []
        seen: set[str] = set()
        for t in tracks:
            key = track_key(t.artist, t.title)
            if key in seen:
                continue
            seen.add(key)
            position = len(rows) + 1
            rows.append(
                (source.id, position, key, t.artist, t.title, t.album, t.cover_url, t.duration_ms, previous.get(key))
            )
        self._store.execute("DELETE FROM chart_entries WHERE chart_id = ?", (source.id,))
        self._store.executemany(
            "INSERT INTO chart_entries (chart_id, position, key, artist, title, album, cover_url, duration_ms, "
            "prev_position) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            rows,
        )
        self._store.execute(
            "INSERT INTO charts (id, title, source, available, error, updated_at) VALUES (?, ?, ?, 1, NULL, ?) "
            "ON CONFLICT(id) DO UPDATE SET available = 1, error = NULL, updated_at = excluded.updated_at, "
            "title = excluded.title",
            (source.id, source.title, source.source, self._clock()),
        )
        log.info("Чарты: %s — %s позиций", source.title, len(rows))

    async def _download_top(self, chart_id: str) -> None:
        if self._pool is None or self._settings.charts_download_top <= 0:
            return
        snapshot = await self._library.snapshot()
        rows = self._store.query(
            "SELECT * FROM chart_entries WHERE chart_id = ? AND position <= ? ORDER BY position",
            (chart_id, self._settings.charts_download_top),
        )
        queued = 0
        for r in rows:
            if r["key"] in snapshot.library or self._store.is_blocked(r["artist"], r["title"]):
                continue
            if await self._pool.enqueue(
                CHARTS_OWNER, r["artist"], r["title"], album=r["album"], duration_ms=r["duration_ms"],
                cover_url=r["cover_url"], source=f"chart:{chart_id}", score=float(100 - r["position"]),
            ):  # fmt: skip
                queued += 1
        if queued:
            log.info("Чарты: %s — в пул поставлено %s", chart_id, queued)

    async def upkeep(self) -> None:
        """Трек пула «charts», выпавший из всех чартов и не слушавшийся 30 дней, — удалить."""
        if self._pool is None:
            return
        in_charts = {r["key"] for r in self._store.query("SELECT DISTINCT key FROM chart_entries")}
        before = self._clock() - CHART_EXPIRE_MS
        rows = self._store.query(
            "SELECT t.id, t.key, t.song_id, o.added_at FROM pool_owners o JOIN pool_tracks t ON t.id = o.track_id "
            "WHERE o.username = ? AND o.status = 'in_pool'",
            (CHARTS_OWNER,),
        )
        for r in rows:
            if r["key"] in in_charts:
                continue
            played = self._store.query_one(
                "SELECT MAX(at) AS at FROM feedback_events WHERE song_id = ? AND event IN ('started', 'completed')",
                (r["song_id"] or "",),
            )
            last = max(int(r["added_at"]), int((played or {}).get("at") or 0))
            if last < before:
                await self._pool.expire_owner(int(r["id"]), CHARTS_OWNER)

    # region API

    def list(self) -> list[dict[str, Any]]:
        charts = self._store.query("SELECT * FROM charts WHERE available = 1")
        order = {s.id: i for i, s in enumerate(self._sources)}
        charts = [c for c in charts if c["id"] in order]
        charts.sort(key=lambda c: order[c["id"]])
        result = []
        for c in charts:
            covers = self._store.query(
                "SELECT cover_url FROM chart_entries WHERE chart_id = ? AND cover_url IS NOT NULL "
                "ORDER BY position LIMIT 4",
                (c["id"],),
            )
            size = self._store.query_one("SELECT COUNT(*) AS n FROM chart_entries WHERE chart_id = ?", (c["id"],))
            result.append(
                {
                    "id": c["id"],
                    "title": c["title"],
                    "source": c["source"],
                    "updated_at": c["updated_at"],
                    "covers": [r["cover_url"] for r in covers],
                    "size": int(size["n"]) if size else 0,
                }
            )
        return result

    async def entries(self, chart_id: str, username: str) -> list[dict[str, Any]] | None:
        chart = self._store.query_one("SELECT * FROM charts WHERE id = ? AND available = 1", (chart_id,))
        if chart is None:
            return None
        snapshot = await self._library.snapshot()
        pool_rows = {
            r["key"]: r
            for r in self._store.query("SELECT key, location, song_id FROM pool_tracks WHERE location != 'gone'")
        }
        active_requests = {
            track_key(r["artist"], r["title"] or "")
            for r in self._store.query(
                "SELECT artist, title FROM requests WHERE kind = 'track' AND status IN "
                "('queued', 'searching', 'downloading', 'placing', 'importing')"
            )
        }
        result = []
        for r in self._store.query("SELECT * FROM chart_entries WHERE chart_id = ? ORDER BY position", (chart_id,)):
            key = r["key"]
            song_id = snapshot.library.get(key)
            state = "library" if song_id else "unavailable"
            pool = pool_rows.get(key)
            if song_id is None and pool is not None:
                if pool["location"] == "pool" and (pool["song_id"] or snapshot.pool.get(key)):
                    state, song_id = "pool", pool["song_id"] or snapshot.pool.get(key)
                elif pool["location"] in ("queued", "downloading"):
                    state = "downloading"
                elif pool["location"] == "library":
                    state = "downloading"  # переносится в библиотеку — появится после скана
            if state == "unavailable" and key in active_requests:
                state = "downloading"
            prev = r["prev_position"]
            movement = (
                "new" if prev is None else "up" if prev > r["position"] else "down" if prev < r["position"] else "same"
            )
            result.append(
                {
                    "position": r["position"],
                    "artist": r["artist"],
                    "title": r["title"],
                    "album": r["album"],
                    "cover_url": r["cover_url"],
                    "duration_ms": r["duration_ms"],
                    "prev_position": prev,
                    "movement": movement,
                    "state": state,
                    "song_id": song_id,
                }
            )
        return result

    # endregion
