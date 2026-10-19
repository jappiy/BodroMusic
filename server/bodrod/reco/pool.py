"""Discovery-пул «на пробу»: новые треки живут в `DISCOVERY_DIR`, в библиотеку их переводят сигналы пользователя.

Трек хранится одной физической копией (`pool_tracks`), привязка к пользователям — `pool_owners` со статусом:
candidate (в резерве) → queued → downloading → in_pool → promoted | rejected | expired | failed.

Сигналы — события приложения (`POST /api/v1/feedback`) и отметки в БД Navidrome (лайк или дизлайк, поставленные в
другом клиенте):
- promote: лайк, или `POOL_PROMOTE_FULL_PLAYS` полных прослушиваний (≥ `POOL_FULL_PLAY_RATIO` длительности), или
  добавление в плейлист. Лайк любого пользователя переводит трек в библиотеку для всех (библиотека общая);
- reject: дизлайк — сразу, скип раньше `POOL_EARLY_SKIP_S` — на `POOL_REJECT_EARLY_SKIPS`-й раз. Трек — в чёрный
  список пользователя на `POOL_REJECT_BLOCK_DAYS` дней;
- expire: `POOL_EXPIRE_DAYS` без прослушиваний или `POOL_EXPIRE_SHOWN` показов в волне без запуска.

Promote переносит файл в библиотеку по `LIBRARY_LAYOUT`; старый id трека записывается в `replacements`, так что если
Navidrome выдаст файлу новый id, приложение перенесёт лайк на него (как после чистки дублей). Файл удаляется, только
когда трек не держит ни один владелец (другой пользователь или чарт).
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
import shutil
import time
from collections.abc import Callable
from pathlib import Path
from typing import Any

from ..cleanup import _remap
from ..clients.navidrome import NavidromeClient
from ..config import Settings
from ..db import Store
from ..navidrome_db import NavidromeDb
from ..placement import Tags, choose_destination, relative_path
from .library import LibraryIndex
from .store import DAY_MS, RecoStore

log = logging.getLogger(__name__)

SCHEMA = """
CREATE TABLE IF NOT EXISTS feedback_events (
    id INTEGER PRIMARY KEY,
    username TEXT NOT NULL,
    song_id TEXT NOT NULL,
    event TEXT NOT NULL,
    position_ms INTEGER,
    duration_ms INTEGER,
    context TEXT,
    at INTEGER NOT NULL,
    pool INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_feedback_user ON feedback_events(username, at);
"""

ACTIVE = ("candidate", "queued", "downloading", "in_pool")
FINAL = ("promoted", "rejected", "expired", "failed")
CHARTS_OWNER = "charts"
EVENTS = ("started", "skipped", "completed", "liked", "unliked", "disliked", "playlisted", "shown")
UPKEEP_S = 15 * 60
REFILL_EVERY_MS = DAY_MS - 30 * 60 * 1000


def ms() -> int:
    return int(time.time() * 1000)


class PoolService:
    def __init__(
        self,
        store: Store,
        reco: RecoStore,
        settings: Settings,
        navidrome: NavidromeClient,
        navidrome_db: NavidromeDb | None,
        library: LibraryIndex,
        clock: Callable[[], int] = ms,
    ) -> None:
        self._store = store
        self._reco = reco
        self._settings = settings
        self._navidrome = navidrome
        self._db = navidrome_db
        self._library = library
        self._clock = clock
        self._lock = asyncio.Lock()
        self._wakeup = asyncio.Event()
        """Пополнение из движка кандидатов (задача 3): `async (user, сколько) -> поставлено`."""
        self.engine_refill: Callable[[str, int], Any] | None = None
        """Разбудить воркер заявок после постановки в очередь."""
        self.wake_worker: Callable[[], None] = lambda: None
        store.executescript(SCHEMA)

    @property
    def root(self) -> Path:
        return Path(self._settings.discovery_path)

    def wake(self) -> None:
        self._wakeup.set()

    # region Запросы

    def items(self, username: str, statuses: tuple[str, ...] = ("in_pool",)) -> list[dict[str, Any]]:
        marks = ",".join("?" for _ in statuses)
        return self._store.query(
            f"SELECT t.*, o.status, o.source, o.score, o.shown, o.starts, o.full_plays, o.early_skips, "
            f"o.last_played_at, o.added_at, o.status_at FROM pool_owners o JOIN pool_tracks t ON t.id = o.track_id "
            f"WHERE o.username = ? AND o.status IN ({marks}) ORDER BY o.added_at DESC",
            (username, *statuses),
        )

    def counts(self, username: str) -> dict[str, int]:
        rows = self._store.query(
            "SELECT status, COUNT(*) AS n FROM pool_owners WHERE username = ? GROUP BY status", (username,)
        )
        return {r["status"]: r["n"] for r in rows}

    def status_of(self, username: str, song_ids: list[str]) -> dict[str, str]:
        """Статус в пуле для треков (только треки пула): своего владельца, иначе — `in_pool` (чужой или чарт)."""
        result: dict[str, str] = {}
        for start in range(0, len(song_ids), 500):
            chunk = song_ids[start : start + 500]
            marks = ",".join("?" for _ in chunk)
            rows = self._store.query(
                f"SELECT t.song_id, t.location, o.username, o.status FROM pool_tracks t "
                f"LEFT JOIN pool_owners o ON o.track_id = t.id WHERE t.song_id IN ({marks})",
                chunk,
            )
            for r in rows:
                if r["location"] != "pool":
                    continue
                if r["username"] == username or r["song_id"] not in result:
                    result[r["song_id"]] = r["status"] if r["username"] == username else "in_pool"
        return result

    async def hidden(self) -> dict[str, list[str]]:
        """Что приложение прячет из исполнителей, альбомов и поиска: треки пула и альбомы/артисты только из пула."""
        snap = await self._library.snapshot()
        songs = set(snap.pool_paths)
        rows = self._store.query("SELECT song_id FROM pool_tracks WHERE location = 'pool' AND song_id IS NOT NULL")
        songs.update(r["song_id"] for r in rows)
        return {
            "songIds": sorted(songs),
            "albumIds": sorted(snap.pool_only_albums),
            "artistIds": sorted(snap.pool_only_artists),
        }

    def artist_rejects(self, username: str) -> dict[str, int]:
        """Ключ артиста → сколько его треков пользователь отклонил (для понижающего коэффициента)."""
        from .keys import artist_key

        rows = self._store.query(
            "SELECT t.artist FROM pool_owners o JOIN pool_tracks t ON t.id = o.track_id "
            "WHERE o.username = ? AND o.status = 'rejected'",
            (username,),
        )
        result: dict[str, int] = {}
        for r in rows:
            key = artist_key(r["artist"])
            result[key] = result.get(key, 0) + 1
        return result

    def penalized_artists(self, username: str) -> set[str]:
        threshold = self._settings.artist_reject_threshold
        return {a for a, n in self.artist_rejects(username).items() if n >= threshold}

    # endregion

    # region События

    async def feedback(self, username: str, events: list[dict[str, Any]]) -> dict[str, str]:
        """Записать события и применить правила к трекам пула. Возвращает новые статусы затронутых треков."""
        changed: dict[str, str] = {}
        async with self._lock:
            for event in events:
                song_id, name = str(event["song_id"]), event["event"]
                if name not in EVENTS:
                    continue
                track = self._store.query_one(
                    "SELECT * FROM pool_tracks WHERE song_id = ? AND location = 'pool'", (song_id,)
                )
                self._store.execute(
                    "INSERT INTO feedback_events (username, song_id, event, position_ms, duration_ms, context, at, "
                    "pool) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    (
                        username,
                        song_id,
                        name,
                        event.get("position_ms"),
                        event.get("duration_ms"),
                        event.get("context"),
                        int(event.get("at") or self._clock()),
                        int(track is not None),
                    ),
                )
                if track is None:
                    continue
                status = await self._apply(username, track, event)
                if status:
                    changed[song_id] = status
        return changed

    async def _apply(self, username: str, track: dict[str, Any], event: dict[str, Any]) -> str | None:
        s = self._settings
        name = event["event"]
        track_id = int(track["id"])
        owner = self._reco.owner(track_id, username)
        mine = owner is not None and owner["status"] == "in_pool"
        at = int(event.get("at") or self._clock())
        if name in ("liked", "playlisted"):
            # Лайк любого пользователя переводит трек в библиотеку (она общая).
            if owner is None:
                self._reco.set_owner(track_id, username, "promoted", None)
            await self._promote(track, f"{username}: {name}")
            return "promoted"
        if name == "disliked":
            if owner is None:
                self._reco.set_owner(track_id, username, "rejected", None)
            else:
                self._set_status(track_id, username, "rejected")
            self._reco.block_user(
                username, track["artist"], track["title"], "Отклонён в пуле", at + s.reject_block_days * DAY_MS
            )
            await self._settle(track_id)
            return "rejected"
        if not mine:
            return None
        if name == "started":
            self._store.execute(
                "UPDATE pool_owners SET starts = starts + 1, last_played_at = ? WHERE track_id = ? AND username = ?",
                (at, track_id, username),
            )
            return None
        if name == "shown":
            self._store.execute(
                "UPDATE pool_owners SET shown = shown + 1 WHERE track_id = ? AND username = ?", (track_id, username)
            )
            owner = self._reco.owner(track_id, username) or owner
            if owner["shown"] >= s.expire_shown and owner["starts"] == 0:
                self._set_status(track_id, username, "expired")
                await self._settle(track_id)
                return "expired"
            return None
        position = int(event.get("position_ms") or 0)
        duration = int(event.get("duration_ms") or track.get("duration_ms") or 0)
        if name == "completed" or (name == "skipped" and duration and position >= duration * s.full_play_ratio):
            if duration and position < duration * s.full_play_ratio:
                return None
            self._store.execute(
                "UPDATE pool_owners SET full_plays = full_plays + 1, last_played_at = ? "
                "WHERE track_id = ? AND username = ?",
                (at, track_id, username),
            )
            if owner["full_plays"] + 1 >= s.promote_full_plays:
                await self._promote(track, f"{username}: {s.promote_full_plays} полных прослушивания")
                return "promoted"
            return None
        if name == "skipped" and position < s.early_skip_s * 1000:
            self._store.execute(
                "UPDATE pool_owners SET early_skips = early_skips + 1, last_played_at = ? "
                "WHERE track_id = ? AND username = ?",
                (at, track_id, username),
            )
            if owner["early_skips"] + 1 >= s.reject_early_skips:
                self._set_status(track_id, username, "rejected")
                self._reco.block_user(
                    username, track["artist"], track["title"], "Пропущен в пуле", at + s.reject_block_days * DAY_MS
                )
                await self._settle(track_id)
                return "rejected"
        return None

    def _set_status(self, track_id: int, username: str, status: str) -> None:
        self._store.execute(
            "UPDATE pool_owners SET status = ?, status_at = ? WHERE track_id = ? AND username = ?",
            (status, self._clock(), track_id, username),
        )
        log.info("Пул: трек %s у %s — %s", track_id, username, status)

    # endregion

    # region Файлы

    async def _promote(self, track: dict[str, Any], why: str) -> None:
        """Перенести файл пула в библиотеку; все владельцы — promoted."""
        track_id = int(track["id"])
        self._store.execute(
            "UPDATE pool_owners SET status = 'promoted', status_at = ? WHERE track_id = ? AND status IN (?, ?, ?, ?)",
            (self._clock(), track_id, *ACTIVE),
        )
        library = self._settings.library_dir
        path = Path(track["path"]) if track.get("path") else None
        if track["location"] != "pool" or library is None or path is None or not path.exists():
            self._reco.update_pool_track(track_id, location="library")
            return
        tags = Tags(artist=track["artist"], title=track["title"], album=track["album"])
        rel = relative_path(
            self._settings.library_layout, artist=tags.artist, title=tags.title, album=tags.album, extension=path.suffix
        )
        dest, already = await asyncio.to_thread(choose_destination, Path(library), rel, path, tags)
        if already:
            await asyncio.to_thread(path.unlink, True)
        else:
            dest.parent.mkdir(parents=True, exist_ok=True)
            await asyncio.to_thread(shutil.move, str(path), str(dest))
        await asyncio.to_thread(_remove_empty_parents, path.parent, self.root)
        record = self._store.library_file(str(path))
        self._store.add_library_file(
            str(dest),
            None,
            record["source"] if record else "pool",
            record["codec"] if record else dest.suffix.lstrip("."),
            bool(record["lossy"]) if record else False,
            record["source_id"] if record else track.get("yandex_id"),
        )
        nd_path = _remap(str(dest), self._settings.music_dir, self._settings.navidrome_music_dir)
        if track.get("song_id") and nd_path:
            # Если Navidrome даст файлу новый id, приложение перенесёт на него лайк (`/library/check`).
            self._store.add_replacement(track["song_id"], nd_path, None)
        self._reco.update_pool_track(track_id, location="library", path=str(dest))
        self._library.invalidate()
        log.info("Пул → библиотека (%s): %s — %s → %s", why, track["artist"], track["title"], dest)
        await self._scan()

    async def _settle(self, track_id: int) -> None:
        """Трек больше никто не держит — удалить файл."""
        if self._store.query_one(
            "SELECT 1 FROM pool_owners WHERE track_id = ? AND status IN (?, ?, ?, ?)", (track_id, *ACTIVE)
        ):
            return
        track = self._reco.pool_track(track_id)
        if track is None or track["location"] not in ("pool", "reserve"):
            return
        if track.get("path"):
            path = Path(track["path"])
            await asyncio.to_thread(path.unlink, True)
            root = self.root if track["location"] == "pool" else Path(self._settings.reserve_path)
            await asyncio.to_thread(_remove_empty_parents, path.parent, root)
        self._reco.update_pool_track(track_id, location="gone", song_id=None)
        log.info("Пул: файл удалён — %s — %s", track["artist"], track["title"])

    # endregion

    # region Обслуживание (раз в 15 минут)

    async def run_forever(self) -> None:
        while True:
            try:
                await self.upkeep()
            except Exception:  # noqa: BLE001 — повторим в следующий раз
                log.exception("Пул: ошибка обслуживания")
            self._wakeup.clear()
            with contextlib.suppress(TimeoutError):
                await asyncio.wait_for(self._wakeup.wait(), timeout=UPKEEP_S)

    async def enqueue(
        self, username: str, artist: str, title: str, *, album: str | None = None, duration_ms: int | None = None,
        cover_url: str | None = None, source: str | None = None, score: float = 0.0,
    ) -> bool:  # fmt: skip
        """Поставить трек в пул пользователя через цепочку источников (заявка `pool:<пользователь>`)."""
        from ..models import RequestKind
        from ..worker import POOL_USER_PREFIX

        if self._store.is_blocked(artist, title):
            return False
        track_id = self._reco.add_pool_track(
            artist=artist, title=title, album=album, duration_ms=duration_ms, cover_url=cover_url, yandex_id=None,
            location="queued",
        )  # fmt: skip
        track = self._reco.pool_track(track_id)
        assert track is not None
        if track["location"] in ("pool", "reserve", "library"):
            # Уже скачан (другим пользователем или чартом) — просто ещё один владелец.
            if track["location"] == "pool":
                self._reco.set_owner(track_id, username, "in_pool", source, score)
            return track["location"] == "pool"
        request, _ = self._store.create(
            username=f"{POOL_USER_PREFIX}{username}",
            kind=RequestKind.TRACK,
            artist=artist,
            title=title,
            album=album,
            duration_ms=duration_ms,
            cover_url=cover_url,
        )
        self._reco.update_pool_track(track_id, request_id=request.id, location="queued", message=None)
        self._reco.set_owner(track_id, username, "queued", source, score)
        self.wake_worker()
        return True

    async def _sync_requests(self) -> None:
        """Заявки пула: скачано — трек в пуле; не нашли — failed."""
        rows = self._store.query(
            "SELECT * FROM pool_tracks WHERE location IN ('queued', 'downloading') AND request_id IS NOT NULL"
        )
        for track in rows:
            request = self._store.get(track["request_id"])
            track_id = int(track["id"])
            if request is None or request.status.value in ("failed", "cancelled"):
                reason = (request.message if request else None) or "Заявка пропала"
                self._reco.update_pool_track(track_id, location="failed", message=reason)
                self._store.execute(
                    "UPDATE pool_owners SET status = 'failed', status_at = ? WHERE track_id = ? AND status IN "
                    "('queued', 'downloading')",
                    (self._clock(), track_id),
                )
                continue
            if request.status.value != "completed":
                if track["location"] == "queued" and request.status.value != "queued":
                    self._reco.update_pool_track(track_id, location="downloading")
                    self._store.execute(
                        "UPDATE pool_owners SET status = 'downloading' WHERE track_id = ? AND status = 'queued'",
                        (track_id,),
                    )
                continue
            dest = request.state.get("destPath")
            if dest and Path(dest).exists() and Path(dest).is_relative_to(self.root):
                self._reco.update_pool_track(track_id, location="pool", path=dest, song_id=request.song_id)
                self._store.execute(
                    "UPDATE pool_owners SET status = 'in_pool', added_at = ?, status_at = ? WHERE track_id = ? "
                    "AND status IN ('queued', 'downloading')",
                    (self._clock(), self._clock(), track_id),
                )
            else:
                # Трек оказался в библиотеке (нашёлся при проверке) — в пул не нужен.
                self._reco.update_pool_track(track_id, location="library", message=request.message)
                self._store.execute(
                    "UPDATE pool_owners SET status = 'failed', status_at = ? WHERE track_id = ? AND status IN "
                    "('queued', 'downloading')",
                    (self._clock(), track_id),
                )

    async def upkeep(self, force_refill: bool = False) -> None:
        async with self._lock:
            await self._sync_requests()
            await self._resolve_ids()
            await self._apply_annotations()
            await self._expire()
        for user in self.users():
            await self.refill(user, force_refill)

    def users(self) -> list[str]:
        rows = self._store.query("SELECT DISTINCT username FROM pool_owners WHERE username != ?", (CHARTS_OWNER,))
        return [r["username"] for r in rows]

    async def _resolve_ids(self) -> None:
        """id в Navidrome для треков пула (появляется после скана) — по пути файла."""
        rows = self._store.query("SELECT id, path FROM pool_tracks WHERE location = 'pool' AND song_id IS NULL")
        if not rows:
            return
        snap = await self._library.snapshot()
        by_path = {path: song_id for song_id, path in snap.pool_paths.items()}
        missing = False
        for r in rows:
            nd_path = _remap(r["path"] or "", self._settings.music_dir, self._settings.navidrome_music_dir)
            song_id = by_path.get(nd_path or "")
            if song_id:
                self._reco.update_pool_track(int(r["id"]), song_id=song_id)
            else:
                missing = True
        if missing:
            self._library.invalidate()  # следующий проход перечитает БД Navidrome

    async def _apply_annotations(self) -> None:
        """Лайк или дизлайк трека пула в любом клиенте Navidrome — тот же сигнал, что из приложения."""
        if self._db is None:
            return
        rows = self._store.query("SELECT * FROM pool_tracks WHERE location = 'pool' AND song_id IS NOT NULL")
        by_song = {r["song_id"]: r for r in rows}
        if not by_song:
            return
        notes = await asyncio.to_thread(self._db.annotations, list(by_song))
        for note in notes:
            track = by_song.get(note.item_id)
            if track is None or self._settings.is_service_user(note.username):
                continue
            current = self._reco.pool_track(int(track["id"]))
            if current is None or current["location"] != "pool":
                continue
            if note.starred:
                await self._apply(note.username, current, {"event": "liked"})
            elif note.rating == 1:
                owner = self._reco.owner(int(track["id"]), note.username)
                if owner is None or owner["status"] != "rejected":
                    await self._apply(note.username, current, {"event": "disliked"})

    async def _expire(self) -> None:
        before = self._clock() - self._settings.expire_days * DAY_MS
        rows = self._store.query(
            "SELECT track_id, username FROM pool_owners WHERE status = 'in_pool' "
            "AND MAX(added_at, COALESCE(last_played_at, 0), status_at) < ?",
            (before,),
        )
        for r in rows:
            self._set_status(int(r["track_id"]), r["username"], "expired")
            await self._settle(int(r["track_id"]))

    async def refill(self, username: str, force: bool = False) -> int:
        """Раз в сутки, если в пуле меньше `POOL_REFILL_BELOW`: сначала из резерва, затем — движок кандидатов."""
        s = self._settings
        key = f"pool.refill.{username}"
        last = int(self._store.get_value(key) or 0)
        if not force and self._clock() - last < REFILL_EVERY_MS:
            return 0
        counts = self.counts(username)
        active = sum(counts.get(st, 0) for st in ("in_pool", "queued", "downloading"))
        if active >= s.pool_refill_below and not force:
            self._store.set_value(key, self._clock())
            return 0
        want = max(0, s.pool_size - active)
        moved = await self._from_reserve(username, want)
        queued = 0
        if want - moved > 0 and self.engine_refill is not None:
            queued = int(await self.engine_refill(username, want - moved) or 0)
        self._store.set_value(key, self._clock())
        if moved:
            await self._scan()
        log.info("Пул (%s): пополнение — из резерва %s, в очередь загрузки %s", username, moved, queued)
        return moved + queued

    async def _from_reserve(self, username: str, limit: int) -> int:
        if limit <= 0:
            return 0
        rows = self._store.query(
            "SELECT t.* FROM pool_owners o JOIN pool_tracks t ON t.id = o.track_id WHERE o.username = ? "
            "AND o.status = 'candidate' AND t.location = 'reserve' ORDER BY o.score DESC, t.id LIMIT ?",
            (username, limit),
        )
        reserve = Path(self._settings.reserve_path)
        moved = 0
        for r in rows:
            src = Path(r["path"] or "")
            if not src.exists():
                self._reco.update_pool_track(int(r["id"]), location="gone")
                self._set_status(int(r["id"]), username, "failed")
                continue
            try:
                rel = src.relative_to(reserve)
            except ValueError:
                rel = Path(src.name)
            dest = self.root / rel
            dest.parent.mkdir(parents=True, exist_ok=True)
            await asyncio.to_thread(shutil.move, str(src), str(dest))
            await asyncio.to_thread(_remove_empty_parents, src.parent, reserve)
            self._reco.update_pool_track(int(r["id"]), location="pool", path=str(dest))
            self._store.execute(
                "UPDATE pool_owners SET status = 'in_pool', added_at = ?, status_at = ? "
                "WHERE track_id = ? AND username = ?",
                (self._clock(), self._clock(), int(r["id"]), username),
            )
            record = self._store.library_file(str(src))
            if record:
                self._store.add_library_file(
                    str(dest), None, record["source"], record["codec"], bool(record["lossy"]), record["source_id"]
                )
            moved += 1
        return moved

    async def _scan(self) -> None:
        try:
            await self._navidrome.start_scan()
        except Exception as e:  # noqa: BLE001
            log.warning("Не удалось запустить сканирование Navidrome: %s", e)

    # endregion


def _remove_empty_parents(folder: Path, root: Path) -> None:
    """Пустые папки исполнителя/альбома после переноса — убрать (сам корень остаётся)."""
    with contextlib.suppress(OSError, ValueError):
        while folder != root and folder.is_relative_to(root) and folder.exists() and not any(folder.iterdir()):
            folder.rmdir()
            folder = folder.parent
