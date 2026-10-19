"""Хранилище заявок на загрузку (SQLite). Запросы короткие, поэтому выполняются синхронно под блокировкой."""

from __future__ import annotations

import json
import sqlite3
import threading
import time
import uuid
from dataclasses import dataclass, field
from typing import Any

from .models import ACTIVE_STATUSES, AttemptOut, DownloadRequestOut, RequestKind, RequestStatus
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
CREATE TABLE IF NOT EXISTS blocklist (
    id INTEGER PRIMARY KEY,
    key TEXT NOT NULL UNIQUE,
    artist TEXT NOT NULL,
    title TEXT NOT NULL,
    album TEXT,
    created_by TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    removed_count INTEGER NOT NULL DEFAULT 0,
    last_removed_at INTEGER
);
CREATE TABLE IF NOT EXISTS maintenance_runs (
    id INTEGER PRIMARY KEY,
    trigger TEXT NOT NULL,
    status TEXT NOT NULL,
    dry_run INTEGER NOT NULL DEFAULT 0,
    stats TEXT NOT NULL DEFAULT '{}',
    error TEXT,
    started_at INTEGER NOT NULL,
    finished_at INTEGER
);
CREATE TABLE IF NOT EXISTS maintenance_actions (
    id INTEGER PRIMARY KEY,
    run_id INTEGER NOT NULL,
    kind TEXT NOT NULL,
    applied INTEGER NOT NULL,
    artist TEXT,
    title TEXT,
    album TEXT,
    path TEXT,
    details TEXT NOT NULL,
    quarantine_id INTEGER,
    created_at INTEGER NOT NULL,
    planned INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_actions_run ON maintenance_actions(run_id, id);
CREATE TABLE IF NOT EXISTS quarantine (
    id INTEGER PRIMARY KEY,
    original_path TEXT NOT NULL,
    stored_path TEXT NOT NULL,
    reason TEXT NOT NULL,
    size INTEGER,
    created_at INTEGER NOT NULL,
    restored_at INTEGER,
    purged_at INTEGER
);
CREATE TABLE IF NOT EXISTS replacements (
    song_id TEXT PRIMARY KEY,
    kept_path TEXT NOT NULL,
    quarantine_id INTEGER,
    created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS maintenance_checked (
    key TEXT PRIMARY KEY,
    signature TEXT NOT NULL,
    checked_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS kv (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS library_files (
    path TEXT PRIMARY KEY,
    request_id TEXT,
    source TEXT NOT NULL,
    codec TEXT,
    lossy INTEGER NOT NULL DEFAULT 0,
    source_id TEXT,
    created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_library_files_source ON library_files(source, source_id);
CREATE TABLE IF NOT EXISTS wanted_seen (
    album_id INTEGER PRIMARY KEY,
    request_id TEXT,
    created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS export_items (
    id INTEGER PRIMARY KEY,
    track_id TEXT NOT NULL UNIQUE,
    artist TEXT NOT NULL,
    title TEXT NOT NULL,
    album TEXT,
    origin TEXT NOT NULL,
    meta TEXT NOT NULL DEFAULT '{}',
    status TEXT NOT NULL DEFAULT 'pending',
    message TEXT,
    updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_export_status ON export_items(status, id);
CREATE TABLE IF NOT EXISTS export_artists (
    artist_id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    liked INTEGER NOT NULL DEFAULT 0,
    selected INTEGER NOT NULL DEFAULT 0,
    collected INTEGER NOT NULL DEFAULT 0
);
"""


SCHEMA_VERSION = 2
"""Версия данных (`PRAGMA user_version`). 1 — очищен кэш проверок обслуживания, записанный пробными запусками 0.4.0,
у действий появился признак `planned` (исправление запланировано пробным запуском).
2 — у заявок цепочка источников, история попыток и итоговый источник (bodrod 0.7.0)."""
DRY_RUN_SUFFIX = " (пробный запуск — ничего не изменено)"
"""Так bodrod 0.4.0 помечал запланированные исправления пробного запуска."""

ATTENTION = "attention"
"""Фильтр отчёта «требует внимания»: не исправлено и нужно посмотреть человеку."""
ATTENTION_KINDS = ("tags", "cover", "short", "mismatch")


def now_ms() -> int:
    return int(time.time() * 1000)


def block_key(artist: str | None, title: str | None) -> str:
    """Чёрный список: исполнитель + название без регистра, пунктуации, скобок и feat."""
    return f"{normalize(artist)}\x1f{normalize(title)}"  # не \0: SQLite LIKE обрывает строку на NUL


@dataclass
class BlockEntry:
    id: int
    artist: str
    title: str
    album: str | None
    created_by: str
    created_at: int
    removed_count: int
    last_removed_at: int | None


@dataclass
class MaintenanceRun:
    id: int
    trigger: str
    status: str
    dry_run: bool
    stats: dict[str, int]
    error: str | None
    started_at: int
    finished_at: int | None


@dataclass
class MaintenanceAction:
    id: int
    run_id: int
    kind: str
    applied: bool
    artist: str | None
    title: str | None
    album: str | None
    path: str | None
    details: str
    quarantine_id: int | None
    created_at: int
    """Файл лежит в карантине, и его можно вернуть."""
    restorable: bool = False
    """Исправление запланировано пробным запуском (ничего не изменено)."""
    planned: bool = False


@dataclass
class QuarantineEntry:
    id: int
    original_path: str
    stored_path: str
    reason: str
    size: int | None
    created_at: int
    restored_at: int | None
    purged_at: int | None


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
    """Источники цепочки (снимок порядка на момент старта) и номер текущего; -1 — цепочка не начата."""
    chain: list[str] = field(default_factory=list)
    chain_index: int = -1
    """История попыток: `{"source", "title", "result": ok/fail/skip, "reason", "at"}`."""
    attempts: list[dict[str, Any]] = field(default_factory=list)
    """Итоговый источник для приложения: «Яндекс Музыка, FLAC»."""
    source_label: str | None = None

    @property
    def source(self) -> str | None:
        """Текущий источник цепочки."""
        return self.chain[self.chain_index] if 0 <= self.chain_index < len(self.chain) else None

    def to_api(self) -> DownloadRequestOut:
        return DownloadRequestOut(
            id=self.id,
            kind=self.kind,
            artist=self.artist,
            title=self.title,
            album=self.album,
            cover_url=self.cover_url,
            # Для приложения размещение — часть «Добавляем в библиотеку»; клиенты 0.8.0 не знают статуса placing.
            status=RequestStatus.IMPORTING if self.status == RequestStatus.PLACING else self.status,
            message=self.message,
            progress=self.progress,
            song_id=self.song_id,
            created_at=self.created_at,
            updated_at=self.updated_at,
            source=self.source_label,
            attempts=[AttemptOut(**a) for a in self.attempts],
        )


class Store:
    def __init__(self, path: str) -> None:
        self._conn = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self._conn.row_factory = sqlite3.Row
        self._lock = threading.Lock()
        with self._lock:
            self._conn.execute("PRAGMA journal_mode=WAL")
            self._conn.executescript(_SCHEMA)
            self._migrate()

    def _migrate(self) -> None:
        version = self._conn.execute("PRAGMA user_version").fetchone()[0]
        columns = {r["name"] for r in self._conn.execute("PRAGMA table_info(maintenance_actions)")}
        if "planned" not in columns:
            self._conn.execute("ALTER TABLE maintenance_actions ADD COLUMN planned INTEGER NOT NULL DEFAULT 0")
            self._conn.execute(
                "UPDATE maintenance_actions SET planned = 1 WHERE details LIKE ?", (f"%{DRY_RUN_SUFFIX}",)
            )
        request_columns = {r["name"] for r in self._conn.execute("PRAGMA table_info(requests)")}
        for column, ddl in (
            ("chain", "TEXT NOT NULL DEFAULT '[]'"),
            ("chain_index", "INTEGER NOT NULL DEFAULT -1"),
            ("attempts", "TEXT NOT NULL DEFAULT '[]'"),
            ("source_label", "TEXT"),
        ):
            if column not in request_columns:
                self._conn.execute(f"ALTER TABLE requests ADD COLUMN {column} {ddl}")
        if version < 2:
            # Заявки, начатые до цепочки источников, продолжают со своим источником (slskd или Lidarr).
            for kind, source in (("track", "slskd"), ("artist", "lidarr")):
                self._conn.execute(
                    "UPDATE requests SET chain = ?, chain_index = 0 WHERE kind = ? AND status NOT IN "
                    "('queued', 'completed', 'failed', 'cancelled')",
                    (json.dumps([source]), kind),
                )
        if version < 1:
            # bodrod 0.4.0 писал отметки «проверено» и в пробном режиме — по ним настоящий запуск пропустил бы файлы.
            self._conn.execute("DELETE FROM maintenance_checked")
        if version < SCHEMA_VERSION:
            self._conn.execute(f"PRAGMA user_version = {SCHEMA_VERSION}")

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
        key = dedup_key(kind, artist, album if kind == RequestKind.ALBUM else title)
        with self._lock:
            revived = self._revive_failed(username, key)
            if revived is not None:
                return revived, False
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

    def _revive_failed(self, username: str, key: str) -> DownloadRequest | None:
        """Последняя заявка упала после загрузки (на размещении/импорте) — продолжить её, не скачивая заново."""
        row = self._conn.execute(
            "SELECT * FROM requests WHERE username = ? AND dedup_key = ? ORDER BY created_at DESC LIMIT 1",
            (username, key),
        ).fetchone()
        if row is None:
            return None
        request = _from_row(row)
        resume = request.state.pop("failedStatus", None)
        if resume is None:  # заявки, упавшие до версии 0.2.1, — шаг понятен по состоянию
            if "importStartedAt" in request.state:
                resume = RequestStatus.IMPORTING.value
            elif "placeStartedAt" in request.state:
                resume = RequestStatus.PLACING.value
        if request.status != RequestStatus.FAILED or resume not in (
            RequestStatus.PLACING.value,
            RequestStatus.IMPORTING.value,
        ):
            return None
        request.status = RequestStatus(resume)
        request.message = "Повторяем"
        request.errors = 0
        request.next_check_at = 0
        now = time.time()
        request.state["placeStartedAt"] = now
        request.state["importStartedAt"] = now
        request.updated_at = now_ms()
        self._conn.execute(
            "UPDATE requests SET status = ?, message = ?, errors = 0, next_check_at = 0, state = ?, updated_at = ? "
            "WHERE id = ?",
            (
                request.status.value,
                request.message,
                json.dumps(request.state, ensure_ascii=False),
                request.updated_at,
                request.id,
            ),
        )
        return request

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
                "next_check_at = ?, updated_at = ?, chain = ?, chain_index = ?, attempts = ?, source_label = ? "
                "WHERE id = ?",
                (
                    request.status.value,
                    request.message,
                    request.progress,
                    request.song_id,
                    json.dumps(request.state, ensure_ascii=False),
                    request.errors,
                    request.next_check_at,
                    request.updated_at,
                    json.dumps(request.chain),
                    request.chain_index,
                    json.dumps(request.attempts, ensure_ascii=False),
                    request.source_label,
                    request.id,
                ),
            )

    def delete(self, request_id: str) -> None:
        with self._lock:
            self._conn.execute("DELETE FROM requests WHERE id = ?", (request_id,))

    # region Чёрный список

    def block(self, artist: str, title: str, album: str | None, username: str) -> BlockEntry:
        """Добавить трек в чёрный список (повторное добавление — та же запись) и учесть удалённый файл."""
        key = block_key(artist, title)
        ts = now_ms()
        with self._lock:
            self._conn.execute(
                "INSERT INTO blocklist (key, artist, title, album, created_by, created_at, removed_count, "
                "last_removed_at) VALUES (?, ?, ?, ?, ?, ?, 1, ?) "
                "ON CONFLICT(key) DO UPDATE SET removed_count = removed_count + 1, "
                "last_removed_at = excluded.created_at",
                (key, artist.strip(), title.strip(), album, username, ts, ts),
            )
            row = self._conn.execute("SELECT * FROM blocklist WHERE key = ?", (key,)).fetchone()
        return _block_from_row(row)

    def mark_removed(self, key: str) -> None:
        with self._lock:
            self._conn.execute(
                "UPDATE blocklist SET removed_count = removed_count + 1, last_removed_at = ? WHERE key = ?",
                (now_ms(), key),
            )

    def blocked_keys(self) -> set[str]:
        with self._lock:
            return {r[0] for r in self._conn.execute("SELECT key FROM blocklist")}

    def is_blocked(self, artist: str | None, title: str | None) -> bool:
        with self._lock:
            row = self._conn.execute("SELECT 1 FROM blocklist WHERE key = ?", (block_key(artist, title),)).fetchone()
        return row is not None

    def list_blocked(self, query: str | None, limit: int, offset: int) -> tuple[int, list[BlockEntry]]:
        """Поиск по словам запроса в исполнителе и названии (нормализованных), новые сверху."""
        words = [w for w in normalize(query).split(" ") if w]
        where = " AND ".join("key LIKE ?" for _ in words) or "1 = 1"
        params = [f"%{w}%" for w in words]
        with self._lock:
            total = self._conn.execute(f"SELECT COUNT(*) FROM blocklist WHERE {where}", params).fetchone()[0]
            rows = self._conn.execute(
                f"SELECT * FROM blocklist WHERE {where} ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                (*params, limit, offset),
            ).fetchall()
        return total, [_block_from_row(r) for r in rows]

    def unblock(self, entry_id: int) -> BlockEntry | None:
        with self._lock:
            row = self._conn.execute("SELECT * FROM blocklist WHERE id = ?", (entry_id,)).fetchone()
            if row is not None:
                self._conn.execute("DELETE FROM blocklist WHERE id = ?", (entry_id,))
        return _block_from_row(row) if row else None

    # endregion

    # region Обслуживание библиотеки

    def start_run(self, trigger: str, dry_run: bool) -> MaintenanceRun:
        ts = now_ms()
        with self._lock:
            cur = self._conn.execute(
                "INSERT INTO maintenance_runs (trigger, status, dry_run, started_at) VALUES (?, 'running', ?, ?)",
                (trigger, int(dry_run), ts),
            )
            run_id = int(cur.lastrowid)
        return MaintenanceRun(run_id, trigger, "running", dry_run, {}, None, ts, None)

    def finish_run(self, run_id: int, status: str, stats: dict[str, int], error: str | None = None) -> None:
        with self._lock:
            self._conn.execute(
                "UPDATE maintenance_runs SET status = ?, stats = ?, error = ?, finished_at = ? WHERE id = ?",
                (status, json.dumps(stats), error, now_ms(), run_id),
            )

    def fail_unfinished_runs(self) -> None:
        """Запуски, прерванные перезапуском сервиса."""
        with self._lock:
            self._conn.execute(
                "UPDATE maintenance_runs SET status = 'failed', error = 'Прервано перезапуском сервиса', "
                "finished_at = ? WHERE status = 'running'",
                (now_ms(),),
            )

    def list_runs(self, limit: int) -> list[MaintenanceRun]:
        with self._lock:
            rows = self._conn.execute("SELECT * FROM maintenance_runs ORDER BY id DESC LIMIT ?", (limit,)).fetchall()
        return [_run_from_row(r) for r in rows]

    def get_run(self, run_id: int) -> MaintenanceRun | None:
        with self._lock:
            row = self._conn.execute("SELECT * FROM maintenance_runs WHERE id = ?", (run_id,)).fetchone()
        return _run_from_row(row) if row else None

    def add_action(
        self,
        run_id: int,
        kind: str,
        applied: bool,
        details: str,
        *,
        artist: str | None = None,
        title: str | None = None,
        album: str | None = None,
        path: str | None = None,
        quarantine_id: int | None = None,
        planned: bool = False,
    ) -> int:
        with self._lock:
            cur = self._conn.execute(
                "INSERT INTO maintenance_actions (run_id, kind, applied, artist, title, album, path, details, "
                "quarantine_id, created_at, planned) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (
                    run_id,
                    kind,
                    int(applied),
                    artist,
                    title,
                    album,
                    path,
                    details,
                    quarantine_id,
                    now_ms(),
                    int(planned),
                ),
            )
            return int(cur.lastrowid)

    def list_actions(
        self, run_id: int | None, kind: str | None, limit: int, offset: int
    ) -> tuple[int, list[MaintenanceAction]]:
        where: list[str] = []
        params: list[object] = []
        if run_id is not None:
            where.append("a.run_id = ?")
            params.append(run_id)
        if kind == ATTENTION:
            where.append(f"a.applied = 0 AND a.planned = 0 AND a.kind IN ({','.join('?' for _ in ATTENTION_KINDS)})")
            params.extend(ATTENTION_KINDS)
        elif kind:
            where.append("a.kind = ?")
            params.append(kind)
        clause = " AND ".join(where) or "1 = 1"
        with self._lock:
            count_sql = f"SELECT COUNT(*) FROM maintenance_actions a WHERE {clause}"
            total = self._conn.execute(count_sql, params).fetchone()[0]
            rows = self._conn.execute(
                "SELECT a.*, (q.id IS NOT NULL AND q.restored_at IS NULL AND q.purged_at IS NULL) AS restorable "
                f"FROM maintenance_actions a LEFT JOIN quarantine q ON q.id = a.quarantine_id WHERE {clause} "
                "ORDER BY a.id DESC LIMIT ? OFFSET ?",
                (*params, limit, offset),
            ).fetchall()
        return total, [_action_from_row(r) for r in rows]

    def add_quarantine(self, original_path: str, stored_path: str, reason: str, size: int | None) -> int:
        with self._lock:
            cur = self._conn.execute(
                "INSERT INTO quarantine (original_path, stored_path, reason, size, created_at) VALUES (?, ?, ?, ?, ?)",
                (original_path, stored_path, reason, size, now_ms()),
            )
            return int(cur.lastrowid)

    def get_quarantine(self, entry_id: int) -> QuarantineEntry | None:
        with self._lock:
            row = self._conn.execute("SELECT * FROM quarantine WHERE id = ?", (entry_id,)).fetchone()
        return QuarantineEntry(**dict(row)) if row else None

    def mark_restored(self, entry_id: int) -> None:
        with self._lock:
            self._conn.execute("UPDATE quarantine SET restored_at = ? WHERE id = ?", (now_ms(), entry_id))
            self._conn.execute("DELETE FROM replacements WHERE quarantine_id = ?", (entry_id,))

    def mark_purged(self, entry_id: int) -> None:
        with self._lock:
            self._conn.execute("UPDATE quarantine SET purged_at = ? WHERE id = ?", (now_ms(), entry_id))

    def expired_quarantine(self, before_ms: int) -> list[QuarantineEntry]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT * FROM quarantine WHERE created_at < ? AND restored_at IS NULL AND purged_at IS NULL",
                (before_ms,),
            ).fetchall()
        return [QuarantineEntry(**dict(r)) for r in rows]

    def add_replacement(self, song_id: str, kept_path: str, quarantine_id: int | None) -> None:
        """Трек `song_id` убран как дубль или перенесён: его место занял файл `kept_path` (путь Navidrome)."""
        with self._lock:
            self._conn.execute(
                "INSERT INTO replacements (song_id, kept_path, quarantine_id, created_at) VALUES (?, ?, ?, ?) "
                "ON CONFLICT(song_id) DO UPDATE SET kept_path = excluded.kept_path, "
                "quarantine_id = excluded.quarantine_id",
                (song_id, kept_path, quarantine_id, now_ms()),
            )

    def replacements(self, song_ids: list[str]) -> dict[str, str]:
        result: dict[str, str] = {}
        with self._lock:
            for start in range(0, len(song_ids), 500):
                chunk = song_ids[start : start + 500]
                marks = ",".join("?" for _ in chunk)
                for r in self._conn.execute(
                    f"SELECT song_id, kept_path FROM replacements WHERE song_id IN ({marks})", chunk
                ):
                    result[r["song_id"]] = r["kept_path"]
        return result

    def checked(self, key: str) -> tuple[str, int] | None:
        """Подпись и время последней проверки файла или папки, для которых не нашлось исправления."""
        with self._lock:
            row = self._conn.execute(
                "SELECT signature, checked_at FROM maintenance_checked WHERE key = ?", (key,)
            ).fetchone()
        return (row["signature"], row["checked_at"]) if row else None

    def set_checked(self, key: str, signature: str) -> None:
        with self._lock:
            self._conn.execute(
                "INSERT INTO maintenance_checked (key, signature, checked_at) VALUES (?, ?, ?) "
                "ON CONFLICT(key) DO UPDATE SET signature = excluded.signature, checked_at = excluded.checked_at",
                (key, signature, now_ms()),
            )

    def clear_checked(self) -> int:
        with self._lock:
            return self._conn.execute("DELETE FROM maintenance_checked").rowcount

    # endregion

    # region Настройки (ключ → JSON)

    def get_value(self, key: str, default: Any = None) -> Any:
        with self._lock:
            row = self._conn.execute("SELECT value FROM kv WHERE key = ?", (key,)).fetchone()
        return json.loads(row["value"]) if row else default

    def set_value(self, key: str, value: Any) -> None:
        with self._lock:
            self._conn.execute(
                "INSERT INTO kv (key, value, updated_at) VALUES (?, ?, ?) "
                "ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at",
                (key, json.dumps(value, ensure_ascii=False), now_ms()),
            )

    # endregion

    # region Файлы, положенные bodrod (источник, кодек, lossy)

    def add_library_file(
        self,
        path: str,
        request_id: str | None,
        source: str,
        codec: str | None,
        lossy: bool,
        source_id: str | None = None,
    ) -> None:
        with self._lock:
            self._conn.execute(
                "INSERT INTO library_files (path, request_id, source, codec, lossy, source_id, created_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(path) DO UPDATE SET request_id = excluded.request_id, "
                "source = excluded.source, codec = excluded.codec, lossy = excluded.lossy, "
                "source_id = excluded.source_id",
                (path, request_id, source, codec, int(lossy), source_id, now_ms()),
            )

    def source_file_paths(self, source: str, source_id: str) -> list[str]:
        """Куда bodrod положил трек с этим id источника (повторная загрузка, выгрузка после перезапуска)."""
        with self._lock:
            rows = self._conn.execute(
                "SELECT path FROM library_files WHERE source = ? AND source_id = ?", (source, source_id)
            ).fetchall()
        return [r["path"] for r in rows]

    def library_file(self, path: str) -> dict[str, Any] | None:
        with self._lock:
            row = self._conn.execute("SELECT * FROM library_files WHERE path = ?", (path,)).fetchone()
        return dict(row) if row else None

    # endregion

    # region «Метки» Lidarr: недостающие альбомы, уже переданные в цепочку

    def wanted_seen(self, album_ids: list[int]) -> set[int]:
        if not album_ids:
            return set()
        placeholders = ",".join("?" for _ in album_ids)
        with self._lock:
            rows = self._conn.execute(
                f"SELECT album_id FROM wanted_seen WHERE album_id IN ({placeholders})", album_ids
            ).fetchall()
        return {r[0] for r in rows}

    def mark_wanted(self, album_id: int, request_id: str) -> None:
        with self._lock:
            self._conn.execute(
                "INSERT OR REPLACE INTO wanted_seen (album_id, request_id, created_at) VALUES (?, ?, ?)",
                (album_id, request_id, now_ms()),
            )

    def forget_failed_wanted(self, before_ms: int) -> int:
        """Альбомы, которые цепочка не нашла давно, — попробовать снова (в каталоге мог появиться релиз)."""
        with self._lock:
            return self._conn.execute(
                "DELETE FROM wanted_seen WHERE created_at < ? AND (request_id IS NULL OR request_id IN "
                "(SELECT id FROM requests WHERE status IN ('failed', 'cancelled')))",
                (before_ms,),
            ).rowcount

    # endregion

    # region Массовая выгрузка из Яндекса

    def export_add(
        self, track_id: str, artist: str, title: str, album: str | None, origin: str, meta: dict[str, Any]
    ) -> bool:
        """В очередь выгрузки (один раз на трек: тот же трек из лайков и плейлиста не качается дважды)."""
        with self._lock:
            cur = self._conn.execute(
                "INSERT OR IGNORE INTO export_items (track_id, artist, title, album, origin, meta, updated_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                (track_id, artist, title, album, origin, json.dumps(meta, ensure_ascii=False), now_ms()),
            )
            return cur.rowcount > 0

    def export_next(self) -> dict[str, Any] | None:
        with self._lock:
            row = self._conn.execute(
                "SELECT * FROM export_items WHERE status = 'pending' ORDER BY id LIMIT 1"
            ).fetchone()
        if row is None:
            return None
        item = dict(row)
        item["meta"] = json.loads(item["meta"] or "{}")
        return item

    def export_set(self, item_id: int, status: str, message: str | None = None) -> None:
        with self._lock:
            self._conn.execute(
                "UPDATE export_items SET status = ?, message = ?, updated_at = ? WHERE id = ?",
                (status, message, now_ms(), item_id),
            )

    def export_retry_failed(self) -> int:
        with self._lock:
            return self._conn.execute("UPDATE export_items SET status = 'pending' WHERE status = 'failed'").rowcount

    def export_counts(self) -> dict[str, int]:
        with self._lock:
            rows = self._conn.execute("SELECT status, COUNT(*) AS n FROM export_items GROUP BY status").fetchall()
        return {r["status"]: r["n"] for r in rows}

    def export_upsert_artist(self, artist_id: str, name: str, liked: int) -> None:
        with self._lock:
            self._conn.execute(
                "INSERT INTO export_artists (artist_id, name, liked) VALUES (?, ?, ?) "
                "ON CONFLICT(artist_id) DO UPDATE SET name = excluded.name, liked = excluded.liked",
                (artist_id, name, liked),
            )

    def export_artists(self) -> list[dict[str, Any]]:
        with self._lock:
            rows = self._conn.execute("SELECT * FROM export_artists ORDER BY liked DESC, name").fetchall()
        return [dict(r) for r in rows]

    def export_select_artists(self, selected: list[str]) -> None:
        with self._lock:
            self._conn.execute("UPDATE export_artists SET selected = 0")
            self._conn.executemany(
                "UPDATE export_artists SET selected = 1 WHERE artist_id = ?", [(a,) for a in selected]
            )

    def export_mark_collected(self, artist_id: str) -> None:
        with self._lock:
            self._conn.execute("UPDATE export_artists SET collected = 1 WHERE artist_id = ?", (artist_id,))

    # endregion

    # region Обслуживание: длительности скачанных треков

    def track_durations(self) -> dict[str, int]:
        """id трека в Navidrome → длительность по каталогу (мс) для треков, скачанных через bodrod."""
        with self._lock:
            rows = self._conn.execute(
                "SELECT song_id, duration_ms FROM requests WHERE kind = 'track' AND status = 'completed' "
                "AND song_id IS NOT NULL AND duration_ms IS NOT NULL"
            ).fetchall()
        return {r["song_id"]: int(r["duration_ms"]) for r in rows}

    # endregion


def _run_from_row(row: sqlite3.Row) -> MaintenanceRun:
    return MaintenanceRun(
        id=row["id"],
        trigger=row["trigger"],
        status=row["status"],
        dry_run=bool(row["dry_run"]),
        stats=json.loads(row["stats"] or "{}"),
        error=row["error"],
        started_at=row["started_at"],
        finished_at=row["finished_at"],
    )


def _action_from_row(row: sqlite3.Row) -> MaintenanceAction:
    return MaintenanceAction(
        id=row["id"],
        run_id=row["run_id"],
        kind=row["kind"],
        applied=bool(row["applied"]),
        artist=row["artist"],
        title=row["title"],
        album=row["album"],
        path=row["path"],
        details=row["details"],
        quarantine_id=row["quarantine_id"],
        created_at=row["created_at"],
        restorable=bool(row["restorable"]),
        planned=bool(row["planned"]),
    )


def _block_from_row(row: sqlite3.Row) -> BlockEntry:
    return BlockEntry(
        id=row["id"],
        artist=row["artist"],
        title=row["title"],
        album=row["album"],
        created_by=row["created_by"],
        created_at=row["created_at"],
        removed_count=row["removed_count"],
        last_removed_at=row["last_removed_at"],
    )


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
        chain=json.loads(row["chain"] or "[]"),
        chain_index=row["chain_index"],
        attempts=json.loads(row["attempts"] or "[]"),
        source_label=row["source_label"],
    )
