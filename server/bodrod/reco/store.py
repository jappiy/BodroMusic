"""Таблицы рекомендаций в БД bodrod: архив Яндекса, рёбра «семя → кандидат», чёрные списки пользователей, пул.

Все данные — по пользователю Navidrome (`username`). Общее для всех (чарт Яндекса, новые релизы) пишется с
`username = '*'`.
"""

from __future__ import annotations

import json
import logging
import time
from collections.abc import Iterable
from dataclasses import dataclass
from typing import Any

from ..db import Store
from .keys import KEYS_VERSION, track_key

log = logging.getLogger(__name__)
KEYS_VERSION_KEY = "reco.keys_version"

ALL_USERS = "*"
DAY_MS = 24 * 3600 * 1000

SCHEMA = """
CREATE TABLE IF NOT EXISTS y_tracks (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    artists TEXT NOT NULL,
    artist_ids TEXT NOT NULL DEFAULT '[]',
    album TEXT,
    album_id TEXT,
    duration_ms INTEGER,
    cover_url TEXT,
    year INTEGER,
    genre TEXT,
    available INTEGER NOT NULL DEFAULT 1,
    updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS y_artists (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS y_albums (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    artist TEXT,
    year INTEGER,
    updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS y_likes (
    username TEXT NOT NULL,
    kind TEXT NOT NULL,
    item_id TEXT NOT NULL,
    value INTEGER NOT NULL,
    liked_at INTEGER,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (username, kind, item_id)
);
CREATE TABLE IF NOT EXISTS reco_edges (
    username TEXT NOT NULL,
    type TEXT NOT NULL,
    seed_id TEXT NOT NULL,
    cand_id TEXT NOT NULL,
    rank INTEGER NOT NULL,
    seen_count INTEGER NOT NULL DEFAULT 1,
    first_at INTEGER NOT NULL,
    last_at INTEGER NOT NULL,
    PRIMARY KEY (username, type, seed_id, cand_id)
);
CREATE INDEX IF NOT EXISTS idx_edges_cand ON reco_edges(username, cand_id);
CREATE TABLE IF NOT EXISTS harvest_done (
    username TEXT NOT NULL,
    task TEXT NOT NULL,
    item_id TEXT NOT NULL,
    done_at INTEGER NOT NULL,
    PRIMARY KEY (username, task, item_id)
);
CREATE TABLE IF NOT EXISTS user_blocklist (
    username TEXT NOT NULL,
    key TEXT NOT NULL,
    artist TEXT NOT NULL,
    title TEXT NOT NULL,
    reason TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    until_at INTEGER,
    PRIMARY KEY (username, key)
);
CREATE TABLE IF NOT EXISTS pool_tracks (
    id INTEGER PRIMARY KEY,
    key TEXT NOT NULL UNIQUE,
    artist TEXT NOT NULL,
    title TEXT NOT NULL,
    album TEXT,
    duration_ms INTEGER,
    cover_url TEXT,
    yandex_id TEXT,
    path TEXT,
    location TEXT NOT NULL,
    song_id TEXT,
    request_id TEXT,
    message TEXT,
    loader TEXT,
    attempt_at INTEGER,
    attempts INTEGER NOT NULL DEFAULT 0,
    progress_sig TEXT,
    progress_at INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_pool_tracks_song ON pool_tracks(song_id);
CREATE INDEX IF NOT EXISTS idx_pool_tracks_yandex ON pool_tracks(yandex_id);
CREATE TABLE IF NOT EXISTS pool_owners (
    track_id INTEGER NOT NULL,
    username TEXT NOT NULL,
    status TEXT NOT NULL,
    source TEXT,
    score REAL NOT NULL DEFAULT 0,
    shown INTEGER NOT NULL DEFAULT 0,
    starts INTEGER NOT NULL DEFAULT 0,
    full_plays INTEGER NOT NULL DEFAULT 0,
    early_skips INTEGER NOT NULL DEFAULT 0,
    last_played_at INTEGER,
    added_at INTEGER NOT NULL,
    status_at INTEGER NOT NULL,
    PRIMARY KEY (track_id, username)
);
CREATE INDEX IF NOT EXISTS idx_pool_owners_user ON pool_owners(username, status);
"""


def now_ms() -> int:
    return int(time.time() * 1000)


@dataclass
class TrackInfo:
    """Трек каталога Яндекса в нормализованной таблице."""

    id: str
    title: str
    artists: list[str]
    artist_ids: list[str]
    album: str | None
    album_id: str | None
    duration_ms: int | None
    cover_url: str | None
    year: int | None = None
    genre: str | None = None
    available: bool = True

    @property
    def artist(self) -> str:
        return ", ".join(self.artists)


"""Колонки `pool_tracks`, добавленные после 0.14.0: загрузчик и время последней попытки, число попыток, снимок
продвижения заявки (для поиска зависших)."""
_POOL_COLUMNS = (
    ("loader", "TEXT"),
    ("attempt_at", "INTEGER"),
    ("attempts", "INTEGER NOT NULL DEFAULT 0"),
    ("progress_sig", "TEXT"),
    ("progress_at", "INTEGER"),
)

"""Статусы кандидата для статистики источников (экран «Рекомендации»)."""
CANDIDATE_STATES = ("queued", "downloading", "downloaded", "notFound", "promoted", "rejected")


def candidate_state(owner_status: str, location: str | None) -> str:
    """Статус кандидата по статусу владельца и месту трека: в очереди · качается · скачано (резерв или пул) ·
    не найдено · в библиотеку · отклонено."""
    if owner_status == "promoted":
        return "promoted"
    if owner_status in ("rejected", "expired"):
        return "rejected"
    if owner_status == "failed":
        return "notFound"
    if owner_status == "in_pool":
        return "downloaded"
    if owner_status == "downloading":
        return "downloading"
    if owner_status == "candidate":  # резерв архива Яндекса: состояние — по месту трека
        if location == "reserve":
            return "downloaded"
        if location == "downloading":
            return "downloading"
        if location == "failed":
            return "notFound"
    return "queued"


class RecoStore:
    def __init__(self, store: Store) -> None:
        self.db = store
        store.executescript(SCHEMA)
        columns = store.columns("pool_tracks")
        for column, ddl in _POOL_COLUMNS:
            if column not in columns:
                store.execute(f"ALTER TABLE pool_tracks ADD COLUMN {column} {ddl}")
        self._rekey()

    def _rekey(self) -> None:
        """Правила ключей изменились (`KEYS_VERSION`) — пересчитать сохранённые ключи пула и личных блокировок."""
        if self.db.get_value(KEYS_VERSION_KEY) == KEYS_VERSION:
            return
        changed = 0
        for row in self.db.query("SELECT id, key, artist, title FROM pool_tracks"):
            key = track_key(row["artist"], row["title"])
            if key != row["key"] and self.db.query_one("SELECT id FROM pool_tracks WHERE key = ?", (key,)) is None:
                self.db.execute("UPDATE pool_tracks SET key = ? WHERE id = ?", (key, row["id"]))
                changed += 1
        for row in self.db.query("SELECT username, key, artist, title FROM user_blocklist"):
            key = track_key(row["artist"], row["title"])
            if key != row["key"]:
                self.db.execute(
                    "UPDATE OR IGNORE user_blocklist SET key = ? WHERE username = ? AND key = ?",
                    (key, row["username"], row["key"]),
                )
                changed += 1
        self.db.set_value(KEYS_VERSION_KEY, KEYS_VERSION)
        if changed:
            log.info("Рекомендации: ключи треков пересчитаны (%s)", changed)

    # region Каталог

    def upsert_tracks(self, tracks: Iterable[Any]) -> None:
        """`YTrack` (или совместимые) → `y_tracks`; уже известные обновляются."""
        ts = now_ms()
        rows = [
            (
                t.id,
                t.title,
                json.dumps(list(t.artists), ensure_ascii=False),
                json.dumps(list(t.artist_ids)),
                t.album,
                t.album_id,
                t.duration_ms,
                t.cover_url,
                t.year,
                t.genre,
                int(bool(t.available)),
                ts,
            )
            for t in tracks
            if t.id
        ]
        self.db.executemany(
            "INSERT INTO y_tracks (id, title, artists, artist_ids, album, album_id, duration_ms, cover_url, year, "
            "genre, available, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(id) DO UPDATE SET "
            "title = excluded.title, artists = excluded.artists, artist_ids = excluded.artist_ids, "
            "album = COALESCE(excluded.album, album), album_id = COALESCE(excluded.album_id, album_id), "
            "duration_ms = COALESCE(excluded.duration_ms, duration_ms), "
            "cover_url = COALESCE(excluded.cover_url, cover_url), year = COALESCE(excluded.year, year), "
            "genre = COALESCE(excluded.genre, genre), available = excluded.available, updated_at = excluded.updated_at",
            rows,
        )
        artists = {
            (artist_id, name)
            for t in tracks
            for artist_id, name in zip(t.artist_ids, t.artists, strict=False)
            if artist_id and name
        }
        self.upsert_artists(artists)

    def upsert_artists(self, artists: Iterable[tuple[str, str]]) -> None:
        ts = now_ms()
        self.db.executemany(
            "INSERT INTO y_artists (id, name, updated_at) VALUES (?, ?, ?) "
            "ON CONFLICT(id) DO UPDATE SET name = excluded.name, updated_at = excluded.updated_at",
            [(a, n, ts) for a, n in artists if a and n],
        )

    def upsert_albums(self, albums: Iterable[tuple[str, str, str | None, int | None]]) -> None:
        ts = now_ms()
        self.db.executemany(
            "INSERT INTO y_albums (id, title, artist, year, updated_at) VALUES (?, ?, ?, ?, ?) "
            "ON CONFLICT(id) DO UPDATE SET title = excluded.title, artist = excluded.artist, year = excluded.year, "
            "updated_at = excluded.updated_at",
            [(i, t, a, y, ts) for i, t, a, y in albums if i],
        )

    def track(self, track_id: str) -> TrackInfo | None:
        row = self.db.query_one("SELECT * FROM y_tracks WHERE id = ?", (track_id,))
        return _track(row) if row else None

    def tracks(self, ids: list[str]) -> dict[str, TrackInfo]:
        result: dict[str, TrackInfo] = {}
        for start in range(0, len(ids), 500):
            chunk = ids[start : start + 500]
            marks = ",".join("?" for _ in chunk)
            for row in self.db.query(f"SELECT * FROM y_tracks WHERE id IN ({marks})", chunk):
                result[row["id"]] = _track(row)
        return result

    def artist_names(self, ids: list[str]) -> dict[str, str]:
        result: dict[str, str] = {}
        for start in range(0, len(ids), 500):
            chunk = ids[start : start + 500]
            marks = ",".join("?" for _ in chunk)
            for row in self.db.query(f"SELECT id, name FROM y_artists WHERE id IN ({marks})", chunk):
                result[row["id"]] = row["name"]
        return result

    # endregion

    # region Лайки и дизлайки

    def replace_likes(self, username: str, kind: str, value: int, items: list[tuple[str, int | None]]) -> int:
        """Текущий список лайков (`value = 1`) или дизлайков (`-1`) вида `kind`; снятые удаляются. Новых — сколько."""
        ts = now_ms()
        before = {
            r["item_id"]
            for r in self.db.query(
                "SELECT item_id FROM y_likes WHERE username = ? AND kind = ? AND value = ?", (username, kind, value)
            )
        }
        current = {item_id for item_id, _ in items}
        gone = before - current
        if gone:
            self.db.executemany(
                "DELETE FROM y_likes WHERE username = ? AND kind = ? AND item_id = ? AND value = ?",
                [(username, kind, item_id, value) for item_id in gone],
            )
        self.db.executemany(
            "INSERT INTO y_likes (username, kind, item_id, value, liked_at, updated_at) VALUES (?, ?, ?, ?, ?, ?) "
            "ON CONFLICT(username, kind, item_id) DO UPDATE SET value = excluded.value, "
            "liked_at = COALESCE(excluded.liked_at, liked_at), updated_at = excluded.updated_at",
            [(username, kind, item_id, value, at, ts) for item_id, at in items],
        )
        return len(current - before)

    def liked_ids(self, username: str, kind: str = "track") -> list[str]:
        rows = self.db.query(
            "SELECT item_id FROM y_likes WHERE username = ? AND kind = ? AND value = 1 "
            "ORDER BY COALESCE(liked_at, 0) DESC",
            (username, kind),
        )
        return [r["item_id"] for r in rows]

    def like_counts(self, username: str) -> dict[str, int]:
        rows = self.db.query(
            "SELECT kind || ':' || value AS k, COUNT(*) AS n FROM y_likes WHERE username = ? GROUP BY kind, value",
            (username,),
        )
        return {r["k"]: r["n"] for r in rows}

    def top_artists(self, username: str, limit: int) -> list[str]:
        """Артисты пользователя по убыванию: лайк артиста — 5 очков, каждый лайкнутый трек — 1."""
        scores: dict[str, float] = {}
        for item_id in self.liked_ids(username, "artist"):
            scores[item_id] = scores.get(item_id, 0) + 5
        rows = self.db.query(
            "SELECT t.artist_ids FROM y_likes l JOIN y_tracks t ON t.id = l.item_id "
            "WHERE l.username = ? AND l.kind = 'track' AND l.value = 1",
            (username,),
        )
        for row in rows:
            for artist_id in json.loads(row["artist_ids"] or "[]"):
                scores[artist_id] = scores.get(artist_id, 0) + 1
        return [a for a, _ in sorted(scores.items(), key=lambda kv: (-kv[1], kv[0]))][:limit]

    # endregion

    # region Рёбра «семя → кандидат»

    def add_edges(self, username: str, type_: str, seed_id: str, cand_ids: list[str]) -> int:
        """Кандидаты по порядку (ранг с 1). Повторная встреча увеличивает `seen_count`. Возвращает число новых."""
        ts = now_ms()
        seen: set[str] = set()
        rows = []
        for rank, cand in enumerate(cand_ids, start=1):
            if cand and cand not in seen and cand != seed_id:
                seen.add(cand)
                rows.append((username, type_, seed_id, cand, rank, ts, ts))
        if not rows:
            return 0
        marks = ",".join("?" for _ in rows)
        known = self.db.query_one(
            f"SELECT COUNT(*) AS n FROM reco_edges WHERE username = ? AND type = ? AND seed_id = ? "
            f"AND cand_id IN ({marks})",
            (username, type_, seed_id, *(r[3] for r in rows)),
        )
        self.db.executemany(
            "INSERT INTO reco_edges (username, type, seed_id, cand_id, rank, first_at, last_at) "
            "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(username, type, seed_id, cand_id) DO UPDATE SET "
            "seen_count = seen_count + 1, rank = MIN(rank, excluded.rank), last_at = excluded.last_at",
            rows,
        )
        return len(rows) - int(known["n"] if known else 0)

    def edge_counts(self, username: str) -> dict[str, int]:
        rows = self.db.query("SELECT type, COUNT(*) AS n FROM reco_edges WHERE username = ? GROUP BY type", (username,))
        return {r["type"]: r["n"] for r in rows}

    def edges(self, username: str, types: tuple[str, ...]) -> list[dict[str, Any]]:
        marks = ",".join("?" for _ in types)
        return self.db.query(f"SELECT * FROM reco_edges WHERE username = ? AND type IN ({marks})", (username, *types))

    def candidate_frequency(self, username: str, types: tuple[str, ...], limit: int) -> list[tuple[str, int]]:
        """Кандидаты по частоте: сколько раз встречались во всех семенах указанных типов."""
        marks = ",".join("?" for _ in types)
        rows = self.db.query(
            f"SELECT cand_id, SUM(seen_count) AS n, MIN(rank) AS best FROM reco_edges "
            f"WHERE username = ? AND type IN ({marks}) GROUP BY cand_id ORDER BY n DESC, best ASC LIMIT ?",
            (username, *types, limit),
        )
        return [(r["cand_id"], int(r["n"])) for r in rows]

    # endregion

    # region Прогресс разовых проходов (продолжение после обрыва)

    def mark_done(self, username: str, task: str, item_ids: Iterable[str]) -> None:
        ts = now_ms()
        self.db.executemany(
            "INSERT OR REPLACE INTO harvest_done (username, task, item_id, done_at) VALUES (?, ?, ?, ?)",
            [(username, task, i, ts) for i in item_ids],
        )

    def done_ids(self, username: str, task: str, newer_than_ms: int = 0) -> set[str]:
        rows = self.db.query(
            "SELECT item_id FROM harvest_done WHERE username = ? AND task = ? AND done_at >= ?",
            (username, task, newer_than_ms),
        )
        return {r["item_id"] for r in rows}

    # endregion

    # region Чёрный список пользователя

    def block_user(self, username: str, artist: str, title: str, reason: str, until_ms: int | None = None) -> None:
        """Трек больше не предлагается этому пользователю (`until_ms = None` — навсегда)."""
        self.db.execute(
            "INSERT INTO user_blocklist (username, key, artist, title, reason, created_at, until_at) "
            "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(username, key) DO UPDATE SET reason = excluded.reason, "
            "until_at = CASE WHEN until_at IS NULL OR excluded.until_at IS NULL THEN NULL "
            "ELSE MAX(until_at, excluded.until_at) END",
            (username, track_key(artist, title), artist, title, reason, now_ms(), until_ms),
        )

    def user_blocked_keys(self, username: str, now: int | None = None) -> set[str]:
        ts = now if now is not None else now_ms()
        rows = self.db.query(
            "SELECT key FROM user_blocklist WHERE username = ? AND (until_at IS NULL OR until_at > ?)",
            (username, ts),
        )
        return {r["key"] for r in rows}

    def user_blocked_count(self, username: str) -> int:
        row = self.db.query_one("SELECT COUNT(*) AS n FROM user_blocklist WHERE username = ?", (username,))
        return int(row["n"]) if row else 0

    # endregion

    # region Пул и резерв (общие треки + владельцы)

    def pool_track_by_key(self, key: str) -> dict[str, Any] | None:
        return self.db.query_one("SELECT * FROM pool_tracks WHERE key = ?", (key,))

    def pool_track(self, track_id: int) -> dict[str, Any] | None:
        return self.db.query_one("SELECT * FROM pool_tracks WHERE id = ?", (track_id,))

    def pool_keys(self) -> set[str]:
        """Ключи всех треков пула и резерва, кроме ушедших (их можно предложить снова)."""
        rows = self.db.query("SELECT key FROM pool_tracks WHERE location NOT IN ('gone')")
        return {r["key"] for r in rows}

    def add_pool_track(
        self,
        *,
        artist: str,
        title: str,
        album: str | None,
        duration_ms: int | None,
        cover_url: str | None,
        yandex_id: str | None,
        location: str,
        path: str | None = None,
    ) -> int:
        """Новый трек пула/резерва или тот же по ключу (ушедший оживает с новым местом)."""
        key = track_key(artist, title)
        ts = now_ms()
        existing = self.pool_track_by_key(key)
        if existing is not None:
            if existing["location"] in ("gone", "failed"):
                self.db.execute(
                    "UPDATE pool_tracks SET location = ?, path = ?, message = NULL, updated_at = ? WHERE id = ?",
                    (location, path, ts, existing["id"]),
                )
            return int(existing["id"])
        return self.db.execute(
            "INSERT INTO pool_tracks (key, artist, title, album, duration_ms, cover_url, yandex_id, path, location, "
            "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (key, artist, title, album, duration_ms, cover_url, yandex_id, path, location, ts, ts),
        )

    def update_pool_track(self, track_id: int, **fields: Any) -> None:
        if not fields:
            return
        sets = ", ".join(f"{k} = ?" for k in fields)
        self.db.execute(
            f"UPDATE pool_tracks SET {sets}, updated_at = ? WHERE id = ?", (*fields.values(), now_ms(), track_id)
        )

    def set_owner(self, track_id: int, username: str, status: str, source: str | None, score: float = 0) -> None:
        ts = now_ms()
        self.db.execute(
            "INSERT INTO pool_owners (track_id, username, status, source, score, added_at, status_at) "
            "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(track_id, username) DO UPDATE SET status = excluded.status, "
            "source = COALESCE(excluded.source, source), score = MAX(score, excluded.score), "
            "status_at = excluded.status_at",
            (track_id, username, status, source, score, ts, ts),
        )

    def owner(self, track_id: int, username: str) -> dict[str, Any] | None:
        return self.db.query_one("SELECT * FROM pool_owners WHERE track_id = ? AND username = ?", (track_id, username))

    def owners(self, track_id: int) -> list[dict[str, Any]]:
        return self.db.query("SELECT * FROM pool_owners WHERE track_id = ?", (track_id,))

    def reserve_count(self, username: str) -> int:
        row = self.db.query_one(
            "SELECT COUNT(*) AS n FROM pool_owners o JOIN pool_tracks t ON t.id = o.track_id "
            "WHERE o.username = ? AND o.status = 'candidate' AND t.location = 'reserve'",
            (username,),
        )
        return int(row["n"]) if row else 0

    def move_user_data(self, old: str, new: str) -> int:
        """Резерв, пул и личный чёрный список, записанные под логином Яндекса, — пользователю приложения."""
        if old == new:
            return 0
        moved = 0
        for table in ("pool_owners", "user_blocklist"):
            row = self.db.query_one(f"SELECT COUNT(*) AS n FROM {table} WHERE username = ?", (old,))
            count = int(row["n"]) if row else 0
            if not count:
                continue
            self.db.execute(f"UPDATE OR IGNORE {table} SET username = ? WHERE username = ?", (new, old))
            self.db.execute(f"DELETE FROM {table} WHERE username = ?", (old,))  # уже были у нового — дубли
            moved += count
        if moved:
            log.info("Рекомендации: данные %s перенесены пользователю %s (%s записей)", old, new, moved)
        return moved

    def yandex_ids_in_pool(self) -> set[str]:
        rows = self.db.query("SELECT yandex_id FROM pool_tracks WHERE yandex_id IS NOT NULL")
        return {r["yandex_id"] for r in rows}

    # endregion


def _track(row: dict[str, Any]) -> TrackInfo:
    return TrackInfo(
        id=row["id"],
        title=row["title"],
        artists=json.loads(row["artists"] or "[]"),
        artist_ids=json.loads(row["artist_ids"] or "[]"),
        album=row["album"],
        album_id=row["album_id"],
        duration_ms=row["duration_ms"],
        cover_url=row["cover_url"],
        year=row["year"],
        genre=row["genre"],
        available=bool(row["available"]),
    )
