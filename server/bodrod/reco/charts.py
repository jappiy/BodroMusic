"""Чарты: раз в сутки снимки из Яндекса, Deezer, Last.fm, Apple Music и ListenBrainz — с позицией и движением.

Каждый трек чарта сопоставляется с библиотекой и пулом (`keys.track_key`). Недостающие из топ-`CHARTS_DOWNLOAD_TOP`
(30) каждого включённого чарта ставятся в общий пул с владельцем `charts` — по тем же правилам: лайк любого
пользователя переводит трек в библиотеку. Трек, выпавший из всех чартов и не слушавшийся 30 дней, удаляется.
Чарт, который не ответил (например, Яндекс после конца подписки), скрывается до следующего удачного обновления.

Доступность позиции считается при каждой отдаче чарта: сначала прямая привязка (`chart_links`: id трека в
источнике чарта и ISRC → заявка bodrod → путь файла → id песни Navidrome), затем ключ «исполнитель + название»
(по любому исполнителю из списка, `keys.track_keys`), затем ISRC из тегов. Привязка создаётся, когда трек
качается из позиции чарта (кнопка в приложении или топ в пул «charts»); id песни находится по пути файла после
скана Navidrome.
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
from .keys import KEYS_VERSION, track_key
from .library import LibraryIndex, LibrarySnapshot
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
CREATE TABLE IF NOT EXISTS chart_links (
    ref TEXT PRIMARY KEY,
    key TEXT NOT NULL,
    isrc TEXT,
    request_id TEXT,
    path TEXT,
    song_id TEXT,
    updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_chart_links_key ON chart_links(key);
CREATE INDEX IF NOT EXISTS idx_chart_links_request ON chart_links(request_id);
"""
ENTRY_COLUMNS = {"ext_id": "TEXT", "isrc": "TEXT"}
KEYS_VERSION_KEY = "charts.keys_version"
RELINK_KEY = "charts.relinked"
RELINK_VERSION = 1
ACTIVE_STATUSES = ("queued", "searching", "downloading", "placing", "importing")
"""Если в чарте есть недоступные позиции, а индекс библиотеки старше этого — перечитать (новые файлы после скана)."""
SNAPSHOT_MAX_AGE_S = 5 * 60

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
    """id трека в источнике чарта (Deezer, Яндекс, Apple, MBID) и ISRC — для прямой привязки к файлу."""
    ext_id: str | None = None
    isrc: str | None = None


Fetcher = Callable[[], Awaitable[list[ChartTrack]]]


@dataclass
class ChartSource:
    id: str
    title: str
    source: str
    fetch: Fetcher


def _ref(source: str, ext_id: str | None) -> str | None:
    return f"{source}:{ext_id}" if ext_id else None


def _entry_ref(entry: dict[str, Any]) -> str:
    """Ссылка позиции на трек: `deezer:3135556`; без id в источнике (старые снимки) — `key:<ключ трека>`."""
    return entry["ext_id"] if entry.get("ext_id") else f"key:{entry['key']}"


def _link_of(entry: dict[str, Any], links: dict[str, dict[str, Any]]) -> dict[str, Any] | None:
    return links.get(_entry_ref(entry)) or links.get(f"key:{entry['key']}")


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
                ext_id=str(t["id"]) if t.get("id") is not None else None,
                isrc=t.get("isrc") or None,
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
                ext_id=t.get("mbid") or None,
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
                ext_id=str(r["id"]) if r.get("id") else None,
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
            ChartTrack(
                artist=r.get("artist_name") or "",
                title=r.get("track_name") or "",
                album=r.get("release_name"),
                ext_id=r.get("recording_mbid") or None,
            )  # fmt: skip
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
        columns = {r["name"] for r in store.query("PRAGMA table_info(chart_entries)")}
        for column, ddl in ENTRY_COLUMNS.items():
            if column not in columns:
                store.execute(f"ALTER TABLE chart_entries ADD COLUMN {column} {ddl}")
        self._rekey()
        """Путь файла (как его видит bodrod) → id песни Navidrome; без БД Navidrome — `None`."""
        self.song_by_path: Callable[[str], Awaitable[str | None]] | None = None

    def _rekey(self) -> None:
        """Правила ключей изменились — пересчитать ключи сохранённых позиций (движение считается по ним)."""
        if self._store.get_value(KEYS_VERSION_KEY) == KEYS_VERSION:
            return
        for r in self._store.query("SELECT chart_id, position, artist, title FROM chart_entries"):
            self._store.execute(
                "UPDATE chart_entries SET key = ? WHERE chart_id = ? AND position = ?",
                (track_key(r["artist"], r["title"]), r["chart_id"], r["position"]),
            )
        self._store.set_value(KEYS_VERSION_KEY, KEYS_VERSION)

    def wake(self) -> None:
        self._wakeup.set()

    async def run_forever(self) -> None:
        with contextlib.suppress(Exception):
            await self.relink()  # разово: треки, скачанные до прямой привязки
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
                (
                    source.id, position, key, t.artist, t.title, t.album, t.cover_url, t.duration_ms,
                    previous.get(key), _ref(source.source, t.ext_id), t.isrc,
                )
            )  # fmt: skip
        self._store.execute("DELETE FROM chart_entries WHERE chart_id = ?", (source.id,))
        self._store.executemany(
            "INSERT INTO chart_entries (chart_id, position, key, artist, title, album, cover_url, duration_ms, "
            "prev_position, ext_id, isrc) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
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
            if snapshot.find(r["artist"], r["title"], r["isrc"]) or self._store.is_blocked(r["artist"], r["title"]):
                continue
            if await self._pool.enqueue(
                CHARTS_OWNER, r["artist"], r["title"], album=r["album"], duration_ms=r["duration_ms"],
                cover_url=r["cover_url"], source=f"chart:{chart_id}", score=float(100 - r["position"]),
            ):  # fmt: skip
                queued += 1
                track = self._store.query_one("SELECT request_id FROM pool_tracks WHERE key = ?", (r["key"],))
                if track and track["request_id"]:
                    self._link(r, track["request_id"])
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
        """Позиции чарта с доступностью, посчитанной сейчас (а не при сборке чарта)."""
        chart = self._store.query_one("SELECT * FROM charts WHERE id = ? AND available = 1", (chart_id,))
        if chart is None:
            return None
        rows = self._store.query("SELECT * FROM chart_entries WHERE chart_id = ? ORDER BY position", (chart_id,))
        await self.resolve_links()
        links = self._links_for(rows)
        snapshot = await self._library.snapshot()
        found = self._find_all(rows, links, snapshot)
        if any(v is None for v in found.values()) and self._library.age() > SNAPSHOT_MAX_AGE_S:
            self._library.invalidate()  # файлы могли появиться после скана — перечитать индекс
            snapshot = await self._library.snapshot()
            found = self._find_all(rows, links, snapshot)
        pool_rows = {
            r["key"]: r
            for r in self._store.query("SELECT key, location, song_id FROM pool_tracks WHERE location != 'gone'")
        }
        marks = ",".join("?" for _ in ACTIVE_STATUSES)
        active: dict[str, tuple[str, str]] = {}
        for r in self._store.query(
            f"SELECT id, artist, title, status FROM requests WHERE kind = 'track' AND status IN ({marks}) "
            "ORDER BY created_at",
            ACTIVE_STATUSES,
        ):
            active[track_key(r["artist"], r["title"] or "")] = (r["id"], r["status"])
        result = []
        for r in rows:
            key = r["key"]
            link = _link_of(r, links)
            song_id = found.get(r["position"])
            state = "library" if song_id else "unavailable"
            request_id = link["request_id"] if link else None
            request_status = None
            pool = pool_rows.get(key)
            if song_id is None and pool is not None:
                if pool["location"] == "pool" and (pool["song_id"] or snapshot.pool.get(key)):
                    state, song_id = "pool", pool["song_id"] or snapshot.pool.get(key)
                elif pool["location"] == "queued":
                    state = "queued"
                elif pool["location"] in ("downloading", "library"):
                    state = "downloading"  # «library» — переносится в библиотеку, появится после скана
            if song_id is None:
                request = self._store.get(request_id) if request_id else None
                if request is not None and request.status.is_active:
                    request_status = request.status.value
                elif key in active:
                    request_id, request_status = active[key]
                elif request is not None:
                    request_status = request.status.value
                if request_status in ACTIVE_STATUSES and state == "unavailable":
                    state = "queued" if request_status == "queued" else "downloading"
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
                    "request_id": request_id,
                    "request_status": request_status,
                }
            )
        return result

    # endregion

    # region Прямая привязка «позиция чарта → файл → песня Navidrome»

    def link_request(self, chart_id: str, position: int, request_id: str) -> bool:
        """Трек качается из позиции чарта: запомнить заявку (id песни найдётся по пути файла после скана)."""
        row = self._store.query_one(
            "SELECT * FROM chart_entries WHERE chart_id = ? AND position = ?", (chart_id, position)
        )
        if row is None:
            return False
        self._link(row, request_id)
        return True

    def _link(self, entry: dict[str, Any], request_id: str) -> None:
        self._store.execute(
            "INSERT INTO chart_links (ref, key, isrc, request_id, updated_at) VALUES (?, ?, ?, ?, ?) "
            "ON CONFLICT(ref) DO UPDATE SET request_id = excluded.request_id, path = NULL, song_id = NULL, "
            "isrc = COALESCE(excluded.isrc, isrc), updated_at = excluded.updated_at",
            (_entry_ref(entry), entry["key"], entry["isrc"], request_id, self._clock()),
        )

    def _links_for(self, rows: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
        refs = list({_entry_ref(r) for r in rows} | {f"key:{r['key']}" for r in rows})
        result: dict[str, dict[str, Any]] = {}
        for start in range(0, len(refs), 500):
            chunk = refs[start : start + 500]
            marks = ",".join("?" for _ in chunk)
            for link in self._store.query(f"SELECT * FROM chart_links WHERE ref IN ({marks})", chunk):
                result[link["ref"]] = link
        return result

    def _find_all(
        self, rows: list[dict[str, Any]], links: dict[str, dict[str, Any]], snapshot: LibrarySnapshot
    ) -> dict[int, str | None]:
        """Позиция → id песни: привязка, затем ключи (любой исполнитель из списка), затем ISRC."""
        result: dict[int, str | None] = {}
        for r in rows:
            link = _link_of(r, links)
            song_id = link["song_id"] if link and link["song_id"] else None
            if song_id is not None and snapshot.built_at and song_id not in snapshot.ids:
                song_id = None  # файл удалён или заменён — ищем заново
            result[r["position"]] = song_id or snapshot.find(r["artist"], r["title"], r["isrc"])
        return result

    async def resolve_links(self) -> int:
        """Привязки без песни: заявка завершена → id песни из заявки или по пути файла (после скана Navidrome)."""
        rows = self._store.query("SELECT * FROM chart_links WHERE song_id IS NULL AND request_id IS NOT NULL")
        resolved = 0
        for link in rows:
            request = self._store.get(link["request_id"])
            if request is None or request.status.is_active:
                continue
            path = link["path"] or request.state.get("destPath")
            song_id = None
            if path and self.song_by_path is not None:
                song_id = await self.song_by_path(path)
            song_id = song_id or request.song_id
            if song_id is None and path == link["path"]:
                continue
            self._store.execute(
                "UPDATE chart_links SET path = ?, song_id = ?, updated_at = ? WHERE ref = ?",
                (path, song_id, self._clock(), link["ref"]),
            )
            resolved += song_id is not None
        return resolved

    async def relink(self, force: bool = False) -> int:
        """Разово: привязать позиции собранных чартов к заявкам, скачанным раньше (по ключу трека)."""
        if not force and self._store.get_value(RELINK_KEY) == RELINK_VERSION:
            return 0
        by_key: dict[str, dict[str, Any]] = {}
        for r in self._store.query("SELECT * FROM chart_entries"):
            by_key.setdefault(r["key"], r)
        linked = 0
        for request in self._store.query(
            "SELECT id, artist, title FROM requests WHERE kind = 'track' AND status = 'completed' ORDER BY created_at"
        ):
            entry = by_key.get(track_key(request["artist"], request["title"] or ""))
            if entry is None:
                continue
            ref = _entry_ref(entry)
            if self._store.query_one("SELECT ref FROM chart_links WHERE ref = ? AND song_id IS NOT NULL", (ref,)):
                continue
            self._link(entry, request["id"])
            linked += 1
        resolved = await self.resolve_links()
        self._library.invalidate()
        self._store.set_value(RELINK_KEY, RELINK_VERSION)
        log.info("Чарты: пересчёт доступности — привязано заявок %s, найдено песен %s", linked, resolved)
        return linked

    # endregion
