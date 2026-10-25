"""Тексты песен: сбор из Яндекс Музыки и LRCLIB в файлы `.lrc` / `.txt` рядом с аудио.

Navidrome 0.54+ читает внешние `.lrc` и `.txt` (настройка `LyricsPriority`, по умолчанию `.lrc,.txt,embedded`)
в момент запроса текста, поэтому файл начинает работать сразу; пересканирование после пачки — для порядка.

Источники по очереди: Яндекс Музыка (нужна подписка Плюс; сопоставление по id Яндекса, если трек скачан оттуда,
иначе поиском по исполнителю, названию и длительности ±2 с) → LRCLIB (`/api/get`, при промахе `/api/search`).
Синхронизированный текст (LRC) лучше обычного: обычный пишется в `.txt`, только если LRC нет ни в одном источнике.
Существующие тексты (файлы рядом и синхронизированный текст в тегах) не перезаписываются; аудиофайлы не меняются.

Статус по треку — таблица `lyrics_status`: synced / plain / instrumental / not_found / existing / error.
Повтор `not_found` (и `plain` — вдруг появится LRC): через 7 дней, затем через 30, затем раз в 90.
Ночной шаг — в регламенте обслуживания (`nightly`), разовая задача «по всей библиотеке» — `start_full`
(переживает перезапуск сервиса: незаконченная задача продолжается при старте).
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
import re
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Protocol

import httpx

from .cleanup import _remap
from .db import Store, now_ms
from .navidrome_db import NavidromeDb, NdSong
from .sources.base import MATCH_THRESHOLD, track_score
from .text import main_artist

log = logging.getLogger(__name__)

SCHEMA = """
CREATE TABLE IF NOT EXISTS lyrics_status (
    path TEXT PRIMARY KEY,
    song_id TEXT,
    status TEXT NOT NULL,
    source TEXT,
    misses INTEGER NOT NULL DEFAULT 0,
    tried TEXT,
    attempted_at INTEGER NOT NULL,
    next_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_lyrics_status ON lyrics_status(status, next_at);
"""

SYNCED = "synced"
PLAIN = "plain"
INSTRUMENTAL = "instrumental"
NOT_FOUND = "not_found"
EXISTING = "existing"
ERROR = "error"
FINAL = (SYNCED, INSTRUMENTAL, EXISTING)
"""Больше не ищем."""

DAY_MS = 24 * 3600 * 1000
RETRY_DAYS = (7, 30, 90)
"""Повтор после 1-го, 2-го и каждого следующего промаха."""
ERROR_RETRY_MS = DAY_MS
DURATION_TOLERANCE_S = 2.0
SCAN_EVERY = 200
"""Пересканирование Navidrome после стольких новых файлов (и в конце)."""
JOB_KEY = "lyrics.job"
_LRC_LINE = re.compile(r"^\s*\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?\]", re.MULTILINE)


@dataclass
class TrackRef:
    artist: str
    title: str
    album: str | None
    duration_s: float | None
    yandex_id: str | None = None


@dataclass
class Found:
    kind: str
    """synced / plain / instrumental / not_found / error."""
    text: str | None = None
    source: str | None = None


def is_synced(text: str | None) -> bool:
    """В тексте есть строки с метками времени `[мм:сс.хх]`."""
    return bool(text and _LRC_LINE.search(text))


def _clean(text: str | None) -> str | None:
    text = (text or "").replace("\r\n", "\n").strip()
    return text or None


def _duration_close(a: float | None, b: float | None) -> bool:
    return a is None or b is None or abs(a - b) <= DURATION_TOLERANCE_S


class LyricsProvider(Protocol):
    name: str

    async def ready(self) -> bool:
        """Можно ли сейчас спрашивать (для Яндекса — есть ли подписка Плюс)."""
        ...

    async def find(self, track: TrackRef) -> Found: ...


# region Яндекс Музыка


class YandexLyricsApi(Protocol):
    @property
    def has_token(self) -> bool: ...

    async def has_plus(self) -> bool: ...

    async def search_tracks(self, text: str) -> list[Any]: ...

    async def tracks_info(self, ids: list[str]) -> list[Any]: ...

    async def lyrics(self, track_id: str, synced: bool = False) -> str | None: ...


class YandexLyrics:
    """Текст из Яндекс Музыки: LRC, если есть, иначе обычный. Признаки наличия — из `lyrics_info` трека.

    Токенов может быть несколько (общий и пользователей): берётся первый с подпиской Плюс.
    """

    name = "yandex"

    def __init__(self, apis: list[YandexLyricsApi], plus_ttl_s: float = 3600) -> None:
        self._apis = [a for a in apis if a.has_token]
        self._current: YandexLyricsApi | None = None
        self._checked_until = 0.0
        self._plus_ttl_s = plus_ttl_s
        self._info: dict[str, Any] = {}

    def _api(self) -> YandexLyricsApi | None:
        return self._current

    async def ready(self) -> bool:
        if self._checked_until < time.monotonic():
            self._current = None
            for api in self._apis:
                try:
                    if await api.has_plus():
                        self._current = api
                        break
                except Exception as e:  # noqa: BLE001 — нет связи или токен недействителен: следующий
                    log.warning("Тексты: Яндекс недоступен: %s", e)
            if self._current is None and self._apis:
                log.info("Тексты: у токенов Яндекса нет подписки Плюс — Яндекс пропускается")
            self._checked_until = time.monotonic() + self._plus_ttl_s
        return self._current is not None

    async def prefetch(self, ids: list[str]) -> None:
        """Признаки текста для известных id одним запросом на сотню треков."""
        api = self._api()
        wanted = [i for i in dict.fromkeys(ids) if i not in self._info]
        if api is None or not wanted:
            return
        try:
            for track in await api.tracks_info(wanted):
                self._info[str(track.id)] = track
        except Exception as e:  # noqa: BLE001 — спросим по одному
            log.info("Тексты: не удалось получить треки Яндекса пачкой: %s", e)

    async def find(self, track: TrackRef) -> Found:
        api = self._api()
        if api is None:
            return Found(NOT_FOUND)
        try:
            found = await self._match(api, track)
            if found is None:
                return Found(NOT_FOUND)
            if found.lyrics_synced is not False:
                text = _clean(await api.lyrics(str(found.id), synced=True))
                if is_synced(text):
                    return Found(SYNCED, text, self.name)
            if found.lyrics_text is not False:
                text = _clean(await api.lyrics(str(found.id), synced=False))
                if text:
                    return Found(PLAIN, text, self.name)
            return Found(NOT_FOUND)
        except Exception as e:  # noqa: BLE001 — один трек не должен останавливать проход
            if _is_missing(e):
                return Found(NOT_FOUND)
            log.info("Тексты: Яндекс, %s — %s: %s", track.artist, track.title, e)
            return Found(ERROR)

    async def _match(self, api: YandexLyricsApi, track: TrackRef) -> Any | None:
        if track.yandex_id:
            info = self._info.pop(track.yandex_id, None)
            if info is None:
                items = await api.tracks_info([track.yandex_id])
                info = items[0] if items else None
            if info is not None:
                return info
        results = await api.search_tracks(f"{main_artist(track.artist)} {track.title}")
        wanted_ms = int(track.duration_s * 1000) if track.duration_s else None
        best, best_score = None, 0.0
        for item in results[:10]:
            found_s = item.duration_ms / 1000 if item.duration_ms else None
            if not _duration_close(track.duration_s, found_s):
                continue
            score = track_score(track.artist, track.title, wanted_ms, item.artist, item.title, item.duration_ms)
            if score > best_score:
                best, best_score = item, score
        return best if best_score >= MATCH_THRESHOLD else None


def _is_missing(error: BaseException) -> bool:
    """Яндекс отвечает 404 / «not found», когда текста у трека нет."""
    text = str(error).lower()
    return "not found" in text or "404" in text or "lyrics" in text and "unavailable" in text


# endregion

# region LRCLIB


class LrcLib:
    """lrclib.net: бесплатно и без ключа; свой User-Agent и не чаще запроса в секунду."""

    name = "lrclib"
    BASE = "https://lrclib.net/api"

    def __init__(self, http: httpx.AsyncClient, user_agent: str, interval_s: float = 1.0) -> None:
        self._http = http
        self._headers = {"User-Agent": user_agent}
        self._interval_s = interval_s
        self._lock = asyncio.Lock()
        self._last = 0.0

    async def ready(self) -> bool:
        return True

    async def _get(self, path: str, params: dict[str, Any]) -> httpx.Response:
        async with self._lock:
            wait = self._last + self._interval_s - time.monotonic()
            if wait > 0:
                await asyncio.sleep(wait)
            try:
                return await self._http.get(f"{self.BASE}{path}", params=params, headers=self._headers, timeout=20)
            finally:
                self._last = time.monotonic()

    async def find(self, track: TrackRef) -> Found:
        try:
            params: dict[str, Any] = {"artist_name": main_artist(track.artist), "track_name": track.title}
            if track.album:
                params["album_name"] = track.album
            if track.duration_s:
                params["duration"] = round(track.duration_s)
            response = await self._get("/get", params)
            if response.status_code == 200:
                found = self._result(response.json())
                if found.kind in (SYNCED, INSTRUMENTAL):
                    return found
            elif response.status_code != 404:
                response.raise_for_status()
            search = await self._get("/search", {"artist_name": params["artist_name"], "track_name": track.title})
            search.raise_for_status()
            candidates = [
                item
                for item in search.json() or []
                if isinstance(item, dict) and _duration_close(track.duration_s, item.get("duration"))
            ]
            results = [self._result(item) for item in candidates]
            for kind in (SYNCED, INSTRUMENTAL, PLAIN):
                hit = next((r for r in results if r.kind == kind), None)
                if hit is not None:
                    return hit
            return Found(NOT_FOUND)
        except Exception as e:  # noqa: BLE001 — сеть: повторим в другой раз
            log.info("Тексты: LRCLIB, %s — %s: %s", track.artist, track.title, e)
            return Found(ERROR)

    def _result(self, item: dict[str, Any]) -> Found:
        if item.get("instrumental"):
            return Found(INSTRUMENTAL, source=self.name)
        synced = _clean(item.get("syncedLyrics"))
        if is_synced(synced):
            return Found(SYNCED, synced, self.name)
        plain = _clean(item.get("plainLyrics"))
        return Found(PLAIN, plain, self.name) if plain else Found(NOT_FOUND)


# endregion


@dataclass
class Progress:
    mode: str
    total: int = 0
    done: int = 0
    synced: int = 0
    plain: int = 0
    instrumental: int = 0
    not_found: int = 0
    errors: int = 0
    started_at: int = field(default_factory=now_ms)
    finished_at: int | None = None

    def count(self, status: str) -> None:
        self.done += 1
        if status == SYNCED:
            self.synced += 1
        elif status == PLAIN:
            self.plain += 1
        elif status == INSTRUMENTAL:
            self.instrumental += 1
        elif status == NOT_FOUND:
            self.not_found += 1
        elif status == ERROR:
            self.errors += 1


@dataclass
class Candidate:
    song: NdSong
    local: Path
    row: dict[str, Any] | None
    """Рядом или в тегах уже есть обычный текст — ищем только LRC, `.txt` не пишем."""
    has_plain: bool = False


class LyricsService:
    def __init__(
        self,
        store: Store,
        db: NavidromeDb,
        providers: list[LyricsProvider],
        *,
        music_dir: str,
        navidrome_music_dir: str,
        skip_dirs: list[str],
        files_lock: asyncio.Lock | None = None,
        scan: Callable[[], Awaitable[None]] | None = None,
        clock: Callable[[], int] = now_ms,
    ) -> None:
        self._store = store
        self._db = db
        self.providers = providers
        self._music_dir = music_dir
        self._nd_dir = navidrome_music_dir
        self._skip = [Path(d) for d in skip_dirs]
        self._files_lock = files_lock or asyncio.Lock()
        self._scan = scan
        self._clock = clock
        self._lock = asyncio.Lock()
        self._task: asyncio.Task[Any] | None = None
        self.progress: Progress | None = None
        store.executescript(SCHEMA)

    # region Запуск

    @property
    def running(self) -> bool:
        return self._lock.locked()

    def start_full(self) -> bool:
        """Разовый проход по всей библиотеке в фоне. `False` — уже идёт."""
        if self.running or (self._task is not None and not self._task.done()):
            return False
        self._store.set_value(f"{JOB_KEY}.pending", True)
        self._task = asyncio.create_task(self._full())
        return True

    async def run_forever(self) -> None:
        """Первый запуск — сразу сбор по всей библиотеке (подписка Яндекса не вечна); после перезапуска —
        продолжение незаконченного. Ночной шаг вызывает регламент обслуживания."""
        first = self._store.get_value(f"{JOB_KEY}.pending") is None
        if first or self._store.get_value(f"{JOB_KEY}.pending", False):
            log.info("Тексты: %s сбор по всей библиотеке", "запускаю" if first else "продолжаю после перезапуска")
            self.start_full()

    async def _full(self) -> None:
        try:
            progress = await self.collect("full")
            log.info("Тексты: сбор по всей библиотеке завершён: %s", progress)
            self._store.set_value(f"{JOB_KEY}.pending", False)
        except Exception:  # noqa: BLE001 — повторим при следующем запуске
            log.exception("Тексты: ошибка сбора по всей библиотеке")

    async def nightly(self, limit: int, budget_s: float) -> Progress:
        """Шаг ночного регламента: новые треки без текста и подошедшие повторы, не больше `limit` за `budget_s`."""
        if self.running:
            log.info("Тексты: идёт сбор по всей библиотеке — ночной шаг пропущен")
            return Progress("nightly")
        return await self.collect("nightly", limit=limit, budget_s=budget_s)

    # endregion

    async def collect(self, mode: str, limit: int | None = None, budget_s: float | None = None) -> Progress:
        async with self._lock:
            progress = Progress(mode)
            self.progress = progress
            deadline = time.monotonic() + budget_s if budget_s else None
            providers = [p for p in self.providers if await p.ready()]
            log.info("Тексты (%s): источники %s", mode, ", ".join(p.name for p in providers) or "нет")
            yandex = any(p.name == YandexLyrics.name for p in providers)
            candidates = await asyncio.to_thread(self._candidates, mode == "full", yandex)
            if limit is not None:
                candidates = candidates[:limit]
            progress.total = len(candidates)
            self._save(progress)
            written = 0
            for start in range(0, len(candidates), 100):
                chunk = candidates[start : start + 100]
                yandex_ids = [i for c in chunk if (i := self._yandex_id(c.local))]
                for p in providers:
                    if isinstance(p, YandexLyrics) and yandex_ids:
                        await p.prefetch(yandex_ids)
                for c in chunk:
                    if deadline is not None and time.monotonic() > deadline:
                        break
                    status = await self._one(c, providers)
                    progress.count(status)
                    if status in (SYNCED, PLAIN):
                        written += 1
                        if written % SCAN_EVERY == 0:
                            await self._rescan()
                    if progress.done % 20 == 0:
                        self._save(progress)
                if deadline is not None and time.monotonic() > deadline:
                    log.info("Тексты (%s): время шага вышло, остальное — в следующий раз", mode)
                    break
            if written % SCAN_EVERY:
                await self._rescan()
            progress.finished_at = now_ms()
            self._save(progress)
            return progress

    # region Отбор треков

    def _candidates(self, full: bool, yandex: bool) -> list[Candidate]:
        now = self._clock()
        rows = {r["path"]: r for r in self._store.query("SELECT * FROM lyrics_status")}
        result: list[Candidate] = []
        for song in self._db.iter_songs():
            local_str = _remap(song.path, self._nd_dir, self._music_dir)
            if local_str is None:
                continue
            local = Path(local_str)
            if any(local.is_relative_to(d) for d in self._skip):
                continue
            row = rows.get(str(local))
            if row is not None:
                if row["status"] in FINAL:
                    continue
                due = row["next_at"] is None or row["next_at"] <= now
                # Разовый проход: не найденное без Яндекса (не было подписки) — ещё раз, теперь с ним.
                retry_yandex = full and yandex and "yandex" not in (row["tried"] or "").split(",")
                if not due and not retry_yandex:
                    continue
                result.append(Candidate(song, local, row, has_plain=row["status"] == PLAIN))
                continue
            if local.with_suffix(".lrc").exists() or song.embedded_lyrics == SYNCED:
                self._mark(local, song.id, EXISTING, "file" if song.embedded_lyrics != SYNCED else "tags")
                continue
            if not local.is_file():
                continue
            has_plain = local.with_suffix(".txt").exists() or song.embedded_lyrics == PLAIN
            result.append(Candidate(song, local, None, has_plain=has_plain))
        # Сначала новые (без записи), затем повторы.
        result.sort(key=lambda c: c.row is not None)
        return result

    def _yandex_id(self, local: Path) -> str | None:
        info = self._store.library_file(str(local))
        return info["source_id"] if info and info.get("source") == "yandex" and info.get("source_id") else None

    # endregion

    async def _one(self, c: Candidate, providers: list[LyricsProvider]) -> str:
        song = c.song
        track = TrackRef(song.artist, song.title, song.album, song.duration_s, self._yandex_id(c.local))
        plain: Found | None = None
        result: Found | None = None
        error = False
        tried: list[str] = []
        for provider in providers:
            found = await provider.find(track)
            tried.append(provider.name)
            if found.kind in (SYNCED, INSTRUMENTAL):
                result = found
                break
            if found.kind == PLAIN and plain is None:
                plain = found
            error = error or found.kind == ERROR
        if result is None and plain is not None and not c.has_plain:
            result = plain
        if result is None:
            if c.has_plain and c.row is None:
                status = EXISTING  # обычный текст уже есть, LRC не нашли
            else:
                status = ERROR if error and plain is None else (PLAIN if c.has_plain else NOT_FOUND)
            self._mark(c.local, song.id, status, None, tried, c.row)
            return status
        if result.kind in (SYNCED, PLAIN) and result.text and not await self._write(c.local, result):
            self._mark(c.local, song.id, EXISTING, "file", tried, c.row)
            return EXISTING
        self._mark(c.local, song.id, result.kind, result.source, tried, c.row)
        return result.kind

    async def _write(self, local: Path, found: Found) -> bool:
        """`False` — файл уже есть (не перезаписываем) или аудио пропало."""
        target = local.with_suffix(".lrc" if found.kind == SYNCED else ".txt")
        async with self._files_lock:

            def write() -> bool:
                if target.exists() or not local.is_file():
                    return False
                tmp = target.with_name(target.name + ".part")
                tmp.write_text(found.text + "\n", encoding="utf-8")
                tmp.replace(target)
                return True

            return await asyncio.to_thread(write)

    def _mark(
        self,
        local: Path,
        song_id: str,
        status: str,
        source: str | None,
        tried: list[str] | None = None,
        row: dict[str, Any] | None = None,
    ) -> None:
        now = self._clock()
        misses = (row["misses"] if row else 0) + (1 if status in (NOT_FOUND, PLAIN) else 0)
        if status in (NOT_FOUND, PLAIN):
            next_at = now + RETRY_DAYS[min(misses, len(RETRY_DAYS)) - 1] * DAY_MS
        elif status == ERROR:
            next_at = now + ERROR_RETRY_MS
        else:
            next_at = None
        self._store.execute(
            "INSERT INTO lyrics_status (path, song_id, status, source, misses, tried, attempted_at, next_at) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(path) DO UPDATE SET song_id = excluded.song_id, "
            "status = excluded.status, source = excluded.source, misses = excluded.misses, tried = excluded.tried, "
            "attempted_at = excluded.attempted_at, next_at = excluded.next_at",
            (str(local), song_id, status, source, misses, ",".join(tried or []) or None, now, next_at),
        )

    async def _rescan(self) -> None:
        if self._scan is None:
            return
        with contextlib.suppress(Exception):
            await self._scan()

    def _save(self, progress: Progress) -> None:
        self._store.set_value(JOB_KEY, progress.__dict__)

    def status(self) -> dict[str, Any]:
        """Прогресс текущего (или последнего) прохода и итог по библиотеке."""
        counts = {
            r["status"]: r["n"]
            for r in self._store.query("SELECT status, COUNT(*) AS n FROM lyrics_status GROUP BY status")
        }
        sources = {
            r["source"]: r["n"]
            for r in self._store.query(
                "SELECT source, COUNT(*) AS n FROM lyrics_status WHERE status IN (?, ?) GROUP BY source",
                (SYNCED, PLAIN),
            )
        }
        job = self.progress.__dict__ if self.progress else self._store.get_value(JOB_KEY)
        return {
            "running": self.running,
            "job": job,
            "counts": counts,
            "sources": sources,
            "providers": [p.name for p in self.providers],
        }
