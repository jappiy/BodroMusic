"""Перенос лайков из Яндекс Музыки в Navidrome — отдельно для каждого пользователя приложения.

Лайки берутся токеном Яндекса самого пользователя (`YandexTokens.resolve(user, fallback=False)`), а ставятся от его
же учётки Navidrome: Subsonic `star` с теми учётными данными, с которыми приложение пришло в bodrod. Учётные данные
(token + salt, как в каждом запросе приложения) держатся только в памяти — чтобы поставить лайк треку, который
докачался позже; после перезапуска bodrod лайк ставится при следующем запросе пользователя.

Сопоставление с библиотекой: сначала по id трека Яндекса (bodrod запоминает его у файла при скачивании —
`library_files.source_id`), затем по ключу «исполнитель + название» (`reco/keys.py`: регистр, «ё», feat., скобки,
remaster) с допуском по длительности ±3 с.

Правила: лайк, который уже стоит, пропускается; существующие лайки, которых нет в Яндексе, не трогаются; ничего не
снимается; трек в «Не нравится» (оценка 1) не лайкается — это конфликт в отчёте. Повторный запуск безопасен:
считает всё заново и докидывает новое. Ненайденные можно поставить в загрузку (обычная цепочка источников) —
когда трек появится в Navidrome, лайк поставится сам.
"""

from __future__ import annotations

import asyncio
import logging
import time
from collections.abc import Awaitable, Callable, Iterable
from dataclasses import dataclass
from typing import Any

from .clients.navidrome import Credentials
from .db import Store
from .models import RequestKind, RequestStatus
from .navidrome_db import DISLIKE_RATING, NavidromeDb, NdSong
from .reco.keys import track_keys
from .sources.yandex import YTrack

log = logging.getLogger(__name__)

STATE_KEY = "likes_import.{user}"
DURATION_TOLERANCE_S = 3.0
SETTLE_EVERY_S = 60
STAR_BATCH = 50

ACTIVE_REQUEST = {
    RequestStatus.QUEUED.value: "queued",
    RequestStatus.SEARCHING.value: "downloading",
    RequestStatus.DOWNLOADING.value: "downloading",
    RequestStatus.PLACING.value: "downloading",
    RequestStatus.IMPORTING.value: "downloading",
}


class LikesImportError(Exception):
    """Запуск невозможен: нет токена Яндекса пользователя, нет БД Navidrome и т. п. (текст — для приложения)."""


@dataclass(frozen=True)
class Candidate:
    song_id: str
    duration_s: float | None


class LibraryMatcher:
    """Ключ трека (каждый исполнитель из списка) → песни библиотеки с длительностью."""

    def __init__(self, songs: Iterable[NdSong], skip_prefixes: Iterable[str] = ()) -> None:
        skip = tuple(p.rstrip("/") + "/" for p in skip_prefixes if p)
        self._by_key: dict[str, list[Candidate]] = {}
        for song in songs:
            if skip and song.path.startswith(skip):
                continue
            for key in track_keys(song.artist, song.title):
                self._by_key.setdefault(key, []).append(Candidate(song.id, song.duration_s))

    def find(self, artist: str, title: str, duration_ms: int | None) -> str | None:
        """Песня с тем же ключом и длительностью ±3 с (длительность неизвестна — подходит первая)."""
        for key in track_keys(artist, title):
            for candidate in self._by_key.get(key, []):
                if duration_fits(candidate.duration_s, duration_ms):
                    return candidate.song_id
        return None


def duration_fits(library_s: float | None, yandex_ms: int | None) -> bool:
    if library_s is None or not yandex_ms:
        return True
    return abs(library_s - yandex_ms / 1000) <= DURATION_TOLERANCE_S


def _item(track: YTrack) -> dict[str, Any]:
    return {
        "id": track.id,
        "artist": track.artist,
        "title": track.title,
        "album": track.album,
        "durationMs": track.duration_ms,
        "coverUrl": track.cover_url,
    }


class LikesImport:
    def __init__(
        self,
        store: Store,
        db: NavidromeDb,
        liked_tracks: Callable[[str], Awaitable[list[YTrack]]],
        star: Callable[[Credentials, list[str]], Awaitable[None]],
        path_map: Callable[[str], str | None] = lambda p: p,
        skip_prefixes: Iterable[str] = (),
        can_download: Callable[[str], Awaitable[bool]] | None = None,
        wake_worker: Callable[[], None] | None = None,
        clock: Callable[[], float] = time.time,
    ) -> None:
        """`liked_tracks(user)` — лайки Яндекса токеном пользователя (нет токена — `LikesImportError`);
        `star(creds, ids)` — Subsonic `star` от имени пользователя; `path_map` — путь bodrod → путь Navidrome;
        `skip_prefixes` — папки Navidrome, треки из которых не считаются библиотекой (резерв)."""
        self._store = store
        self._db = db
        self._liked_tracks = liked_tracks
        self._star = star
        self._path_map = path_map
        self._skip = list(skip_prefixes)
        self._can_download = can_download
        self._wake_worker = wake_worker
        self._clock = clock
        self._creds: dict[str, Credentials] = {}
        self._tasks: dict[str, asyncio.Task[None]] = {}
        self._locks: dict[str, asyncio.Lock] = {}

    # region Состояние

    def remember(self, creds: Credentials) -> None:
        """Учётные данные пользователя из последнего запроса (только в памяти) — для отложенных лайков."""
        self._creds[creds.username] = creds

    def _key(self, user: str) -> str:
        return STATE_KEY.format(user=user)

    def _state(self, user: str) -> dict[str, Any]:
        return self._store.get_value(self._key(user)) or {"state": "idle"}

    def _set(self, user: str, **changes: Any) -> dict[str, Any]:
        state = self._state(user) | changes | {"updatedAt": self._now_ms()}
        self._store.set_value(self._key(user), state)
        return state

    def _now_ms(self) -> int:
        return int(self._clock() * 1000)

    def running(self, user: str) -> bool:
        task = self._tasks.get(user)
        return task is not None and not task.done()

    def status(self, user: str) -> dict[str, Any]:
        """Состояние, прогресс, итог последнего запуска и ненайденные со статусом загрузки."""
        state = self._state(user)
        if state.get("state") == "running" and not self.running(user):
            state = self._set(user, state="failed", message="Прервано перезапуском bodrod — запустите ещё раз")
        not_found = []
        for item in state.get("notFound") or []:
            not_found.append(item | {"download": self._download_state(item.get("requestId"))})
        return {
            "state": state.get("state", "idle"),
            "message": state.get("message"),
            "done": state.get("done", 0),
            "total": state.get("total", 0),
            "lastRunAt": state.get("lastRunAt"),
            "report": state.get("report"),
            "conflicts": state.get("conflicts") or [],
            "notFound": not_found,
        }

    def _download_state(self, request_id: str | None) -> str:
        """none / queued / downloading / done / notFound / failed — для строки ненайденного трека."""
        if not request_id:
            return "none"
        request = self._store.get(request_id)
        if request is None:
            return "none"
        status = request.status.value
        if status in ACTIVE_REQUEST:
            return ACTIVE_REQUEST[status]
        if status == RequestStatus.COMPLETED.value:
            return "done"
        if status == RequestStatus.CANCELLED.value:
            return "none"
        message = (request.message or "").lower()
        return "notFound" if message.startswith("не нашли") else "failed"

    # endregion

    # region Запуск

    async def start(self, creds: Credentials) -> dict[str, Any]:
        """Запустить перенос (уже идёт — ничего не делать). Ошибки старта — `LikesImportError`."""
        user = creds.username
        self.remember(creds)
        if self.running(user):
            return self.status(user)
        self._set(user, state="running", message="Получаем лайки из Яндекс Музыки", done=0, total=0)
        self._tasks[user] = asyncio.create_task(self._run(user))
        return self.status(user)

    async def _run(self, user: str) -> None:
        lock = self._locks.setdefault(user, asyncio.Lock())
        async with lock:
            try:
                await self._import(user)
            except LikesImportError as e:
                self._set(user, state="failed", message=str(e))
            except Exception as e:  # noqa: BLE001 — сбой видно в приложении, повтор — кнопкой
                log.exception("Перенос лайков (%s)", user)
                self._set(user, state="failed", message=f"Ошибка: {e}")

    async def _import(self, user: str) -> None:
        tracks = await self._liked_tracks(user)
        log.info("Перенос лайков (%s): в Яндексе %s", user, len(tracks))
        self._set(user, message="Сопоставляем с библиотекой", total=len(tracks), done=0)
        matched = await self._match(tracks, lambda i: self._set(user, done=i) if i % 200 == 0 else None)
        starred, disliked = await asyncio.to_thread(self._annotations, user)
        previous = {i["id"]: i for i in self._state(user).get("notFound") or []}
        already = 0
        to_star: list[str] = []
        conflicts: list[dict[str, Any]] = []
        not_found: list[dict[str, Any]] = []
        for track in tracks:
            song_id = matched.get(track.id)
            if song_id is None:
                item = _item(track)
                if track.id in previous and previous[track.id].get("requestId"):
                    item["requestId"] = previous[track.id]["requestId"]
                not_found.append(item)
            elif song_id in disliked:
                conflicts.append(_item(track) | {"songId": song_id})
            elif song_id in starred or song_id in to_star:
                already += 1
            else:
                to_star.append(song_id)
        self._set(user, message="Ставим лайки", done=0, total=len(to_star))
        await self._star_all(user, to_star, progress=True)
        report = {
            "total": len(tracks),
            "already": already,
            "starred": len(to_star),
            "conflicts": len(conflicts),
            "notFound": len(not_found),
        }
        self._set(
            user,
            state="finished",
            message=None,
            done=len(to_star),
            total=len(to_star),
            lastRunAt=self._now_ms(),
            report=report,
            conflicts=conflicts,
            notFound=not_found,
        )
        log.info("Перенос лайков (%s): %s", user, report)

    async def _star_all(self, user: str, ids: list[str], progress: bool = False) -> None:
        creds = self._creds.get(user)
        if creds is None:
            raise LikesImportError("Нет учётных данных пользователя — откройте приложение и запустите ещё раз")
        for start in range(0, len(ids), STAR_BATCH):
            await self._star(creds, ids[start : start + STAR_BATCH])
            if progress:
                self._set(user, done=min(start + STAR_BATCH, len(ids)))

    def _annotations(self, user: str) -> tuple[set[str], set[str]]:
        starred: set[str] = set()
        disliked: set[str] = set()
        for a in self._db.user_annotations(user):
            if a.starred:
                starred.add(a.item_id)
            if a.rating == DISLIKE_RATING:
                disliked.add(a.item_id)
        return starred, disliked

    async def _match(self, tracks: list[YTrack], progress: Callable[[int], Any] | None = None) -> dict[str, str]:
        """id трека Яндекса → id песни Navidrome: по id Яндекса у скачанных файлов, затем по ключу и длительности."""
        result = await asyncio.to_thread(self._by_yandex_id, [t.id for t in tracks])
        rest = [t for t in tracks if t.id not in result]
        if rest:
            matcher = await asyncio.to_thread(LibraryMatcher, self._db.iter_songs(), self._skip)
            offset = len(tracks) - len(rest)
            for i, track in enumerate(rest, 1):
                song_id = matcher.find(track.artist, track.title, track.duration_ms)
                if song_id:
                    result[track.id] = song_id
                if progress:
                    progress(offset + i)
        return result

    def _by_yandex_id(self, ids: list[str]) -> dict[str, str]:
        paths = self._store.source_paths("yandex", ids)
        nd_paths: dict[str, str] = {}
        for track_id, track_paths in paths.items():
            for path in track_paths:
                nd_paths[self._path_map(path) or path] = track_id
        if not nd_paths:
            return {}
        songs = self._db.song_ids_by_paths(list(nd_paths))
        present = set(self._db.present_ids(list(songs.values()))) if songs else set()
        skip = tuple(p.rstrip("/") + "/" for p in self._skip if p)
        result: dict[str, str] = {}
        for path, song_id in songs.items():
            if song_id in present and not (skip and path.startswith(skip)):
                result.setdefault(nd_paths[path], song_id)
        return result

    # endregion

    # region Загрузка ненайденных

    async def download_missing(self, user: str, ids: list[str] | None = None) -> dict[str, Any]:
        """Поставить ненайденные (все или `ids`) в обычную цепочку загрузки. Упавшие — повторно."""
        if self._can_download is not None and not await self._can_download(user):
            raise LikesImportError("Нет доступных источников, которые скачивают треки")
        state = self._state(user)
        wanted = set(ids) if ids else None
        items = state.get("notFound") or []
        queued = 0
        for item in items:
            if wanted is not None and item["id"] not in wanted:
                continue
            if self._download_state(item.get("requestId")) in ("queued", "downloading", "done"):
                continue
            if self._store.is_blocked(item["artist"], item["title"]):
                continue
            request, _ = self._store.create(
                username=user,
                kind=RequestKind.TRACK,
                artist=item["artist"],
                title=item["title"],
                album=item.get("album"),
                duration_ms=item.get("durationMs"),
                cover_url=item.get("coverUrl"),
            )
            item["requestId"] = request.id
            queued += 1
        self._set(user, notFound=items)
        if queued and self._wake_worker is not None:
            self._wake_worker()
        log.info("Перенос лайков (%s): в загрузку %s треков", user, queued)
        return self.status(user)

    async def settle(self, user: str) -> int:
        """Докачанные ненайденные: поставить лайк (кроме «Не нравится») и перенести в отчёт. Сколько поставлено."""
        state = self._state(user)
        items = state.get("notFound") or []
        ready = [i for i in items if self._download_state(i.get("requestId")) == "done"]
        if not ready or user not in self._creds or self.running(user):
            return 0
        lock = self._locks.setdefault(user, asyncio.Lock())
        async with lock:
            song_ids: dict[str, str] = {}
            for item in ready:
                request = self._store.get(item["requestId"])
                if request is not None and request.song_id:
                    song_ids[item["id"]] = request.song_id
            missing = [i for i in ready if i["id"] not in song_ids]
            if missing:
                tracks = [
                    YTrack(id=i["id"], title=i["title"], artists=[i["artist"]], duration_ms=i.get("durationMs"))
                    for i in missing
                ]
                song_ids |= await self._match(tracks)
            present = await asyncio.to_thread(self._db.present_ids, list(song_ids.values()))
            starred, disliked = await asyncio.to_thread(self._annotations, user)
            report = dict(state.get("report") or {})
            conflicts = list(state.get("conflicts") or [])
            to_star: list[str] = []
            settled: set[str] = set()
            for item in ready:
                song_id = song_ids.get(item["id"])
                if song_id is None or song_id not in present:
                    continue  # Navidrome ещё не просканировал
                settled.add(item["id"])
                if song_id in disliked:
                    conflicts.append({k: v for k, v in item.items() if k != "requestId"} | {"songId": song_id})
                    report["conflicts"] = report.get("conflicts", 0) + 1
                elif song_id in starred or song_id in to_star:
                    report["already"] = report.get("already", 0) + 1
                else:
                    to_star.append(song_id)
            if not settled:
                return 0
            await self._star_all(user, to_star)
            report["starred"] = report.get("starred", 0) + len(to_star)
            report["notFound"] = max(0, report.get("notFound", 0) - len(settled))
            # Состояние перечитывается: пока шли запросы, приложение могло поставить ещё загрузки.
            current = self._state(user).get("notFound") or []
            self._set(
                user,
                report=report,
                conflicts=conflicts,
                notFound=[i for i in current if i["id"] not in settled],
            )
            log.info("Перенос лайков (%s): докачано и отмечено %s", user, len(to_star))
            return len(to_star)

    async def run_forever(self) -> None:
        while True:
            for user in list(self._creds):
                try:
                    await self.settle(user)
                except Exception:  # noqa: BLE001 — повтор через минуту
                    log.exception("Перенос лайков (%s): отложенные лайки", user)
            await asyncio.sleep(SETTLE_EVERY_S)

    # endregion
