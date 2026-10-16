"""Хранилище заявок на загрузку (SQLite). Запросы короткие, поэтому выполняются синхронно под блокировкой."""

from __future__ import annotations

import json
import sqlite3
import threading
import time
import uuid
from dataclasses import dataclass, field
from typing import Any

from .models import ACTIVE_STATUSES, DownloadRequestOut, RequestKind, RequestStatus
from .text import normalize

_SCHEMA = """
CREATE TABLE IF NOT EXISTS requests (
    id TEXT PRIMARY KEY,
    username TEXT NOT NULL,
    kind TEXT NOT NULL,
    artist TEXT NOT NULL,
    title TEXT,
    album TEXT,
    duration_ms INTEGER,
    cover_url TEXT,
    status TEXT NOT NULL,
    message TEXT,
    progress REAL,
    song_id TEXT,
    dedup_key TEXT NOT NULL,
    state TEXT NOT NULL DEFAULT '{}',
    errors INTEGER NOT NULL DEFAULT 0,
    next_check_at REAL NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_requests_user ON requests(username, created_at);
CREATE INDEX IF NOT EXISTS idx_requests_status ON requests(status);
"""


def now_ms() -> int:
    return int(time.time() * 1000)


def dedup_key(kind: RequestKind, artist: str, title: str | None) -> str:
    return f"{kind}:{normalize(artist)}:{normalize(title)}"


@dataclass
class DownloadRequest:
    id: str
    username: str
    kind: RequestKind
    artist: str
    title: str | None
    album: str | None
    duration_ms: int | None
    cover_url: str | None
    status: RequestStatus
    message: str | None
    progress: float | None
    song_id: str | None
    dedup_key: str
    state: dict[str, Any] = field(default_factory=dict)
    errors: int = 0
    next_check_at: float = 0.0
    created_at: int = 0
    updated_at: int = 0

    def to_api(self) -> DownloadRequestOut:
        return DownloadRequestOut(
            id=self.id,
            kind=self.kind,
            artist=self.artist,
            title=self.title,
            album=self.album,
            cover_url=self.cover_url,
            status=self.status,
            message=self.message,
            progress=self.progress,
            song_id=self.song_id,
            created_at=self.created_at,
            updated_at=self.updated_at,
        )


class Store:
    def __init__(self, path: str) -> None:
        self._conn = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self._conn.row_factory = sqlite3.Row
        self._lock = threading.Lock()
        with self._lock:
            self._conn.execute("PRAGMA journal_mode=WAL")
            self._conn.executescript(_SCHEMA)

    def close(self) -> None:
        with self._lock:
            self._conn.close()

    def create(
        self,
        *,
        username: str,
        kind: RequestKind,
        artist: str,
        title: str | None,
        album: str | None,
        duration_ms: int | None,
        cover_url: str | None,
    ) -> tuple[DownloadRequest, bool]:
        """Новая заявка или уже существующая (та же песня/исполнитель в работе или скачана) — `(заявка, создана)`."""
        key = dedup_key(kind, artist, title)
        with self._lock:
            row = self._conn.execute(
                "SELECT * FROM requests WHERE username = ? AND dedup_key = ? AND status NOT IN (?, ?) "
                "ORDER BY created_at DESC LIMIT 1",
                (username, key, RequestStatus.FAILED.value, RequestStatus.CANCELLED.value),
            ).fetchone()
            if row is not None:
                return _from_row(row), False
            ts = now_ms()
            request = DownloadRequest(
                id=uuid.uuid4().hex,
                username=username,
                kind=kind,
                artist=artist.strip(),
                title=title.strip() if title else None,
                album=album,
                duration_ms=duration_ms,
                cover_url=cover_url,
                status=RequestStatus.QUEUED,
                message=None,
                progress=None,
                song_id=None,
                dedup_key=key,
                created_at=ts,
                updated_at=ts,
            )
            self._conn.execute(
                "INSERT INTO requests (id, username, kind, artist, title, album, duration_ms, cover_url, status, "
                "message, progress, song_id, dedup_key, state, errors, next_check_at, created_at, updated_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (
                    request.id,
                    request.username,
                    request.kind.value,
                    request.artist,
                    request.title,
                    request.album,
                    request.duration_ms,
                    request.cover_url,
                    request.status.value,
                    None,
                    None,
                    None,
                    key,
                    "{}",
                    0,
                    0.0,
                    ts,
                    ts,
                ),
            )
            return request, True

    def get(self, request_id: str) -> DownloadRequest | None:
        with self._lock:
            row = self._conn.execute("SELECT * FROM requests WHERE id = ?", (request_id,)).fetchone()
        return _from_row(row) if row else None

    def list_for_user(self, username: str, limit: int = 100) -> list[DownloadRequest]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT * FROM requests WHERE username = ? ORDER BY created_at DESC LIMIT ?", (username, limit)
            ).fetchall()
        return [_from_row(r) for r in rows]

    def list_active(self) -> list[DownloadRequest]:
        placeholders = ",".join("?" for _ in ACTIVE_STATUSES)
        with self._lock:
            rows = self._conn.execute(
                f"SELECT * FROM requests WHERE status IN ({placeholders}) ORDER BY created_at",
                tuple(s.value for s in ACTIVE_STATUSES),
            ).fetchall()
        return [_from_row(r) for r in rows]

    def save(self, request: DownloadRequest) -> None:
        request.updated_at = now_ms()
        with self._lock:
            self._conn.execute(
                "UPDATE requests SET status = ?, message = ?, progress = ?, song_id = ?, state = ?, errors = ?, "
                "next_check_at = ?, updated_at = ? WHERE id = ?",
                (
                    request.status.value,
                    request.message,
                    request.progress,
                    request.song_id,
                    json.dumps(request.state, ensure_ascii=False),
                    request.errors,
                    request.next_check_at,
                    request.updated_at,
                    request.id,
                ),
            )

    def delete(self, request_id: str) -> None:
        with self._lock:
            self._conn.execute("DELETE FROM requests WHERE id = ?", (request_id,))


def _from_row(row: sqlite3.Row) -> DownloadRequest:
    return DownloadRequest(
        id=row["id"],
        username=row["username"],
        kind=RequestKind(row["kind"]),
        artist=row["artist"],
        title=row["title"],
        album=row["album"],
        duration_ms=row["duration_ms"],
        cover_url=row["cover_url"],
        status=RequestStatus(row["status"]),
        message=row["message"],
        progress=row["progress"],
        song_id=row["song_id"],
        dedup_key=row["dedup_key"],
        state=json.loads(row["state"] or "{}"),
        errors=row["errors"],
        next_check_at=row["next_check_at"],
        created_at=row["created_at"],
        updated_at=row["updated_at"],
    )
