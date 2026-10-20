"""Фоновая обработка заявок по цепочке источников. Состояние хранится в БД (переживает рестарт).

Для каждой заявки bodrod идёт по источникам сверху вниз (порядок и переключатели — `SourceRegistry`) и
останавливается на первом успешном. Выключенный или недоступный источник пропускается с записью в лог и в историю
попыток заявки (`attempts`). Итоговый источник («Яндекс Музыка, FLAC») — `source_label`.

Виды заявок и источники: трек — Яндекс, slskd, YouTube; альбом и исполнитель — Яндекс, YouTube, Lidarr.

- Прямые источники (Яндекс, YouTube): заявка в `downloading`, источник ищет и скачивает в фоне (`asyncio.Task`),
  каждый файл сразу кладётся в библиотеку (`Placer`) → importing → completed. После рестарта задача запускается
  заново; уже положенное пропускается по `library_files`.
- slskd (как до 0.7.0): queued → searching → downloading → [placing] → importing → completed. Поиски идут
  очередью: не больше `SLSKD_PARALLEL_SEARCHES` одновременно, с паузой между запусками; до трёх запросов
  (`text.search_queries`).
- Lidarr: исполнитель — дискография с мониторингом; альбом — мониторинг одного альбома и поиск.

Не нашли ни в одном источнике — заявка ждёт `SLSKD_RETRY_INTERVAL_S` и проходит цепочку заново, до
`SLSKD_RETRY_ROUNDS` раз (кроме «меток» Lidarr: их повторяет `LidarrWantedSync`).
После того как Яндекс или YouTube скачал дискографию, исполнитель добавляется в Lidarr только для новых релизов:
недостающее потом приходит «метками» (wanted) и снова идёт через цепочку.
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
import os
import shutil
import time
import traceback
from collections.abc import Awaitable, Callable
from pathlib import Path
from typing import Any

import httpx

from .clients.lidarr import LidarrClient, LidarrError
from .clients.navidrome import NavidromeClient
from .clients.slskd import SlskdClient
from .config import Settings
from .db import DownloadRequest, Store, now_ms
from .matching import LOSSLESS, rank_candidates
from .metadata.discover import find_in_library
from .models import RequestKind, RequestStatus
from .placement import (
    Tags,
    choose_destination,
    find_local_file,
    place_file,
    relative_path,
    remove_source,
)
from .sources.base import SOURCE_TITLES, FetchJob, FetchResult, SourceMiss
from .sources.placer import Placer
from .sources.registry import SourceRegistry
from .sources.yandex_tokens import POOL_USER_PREFIX, request_owner
from .text import search_queries

log = logging.getLogger(__name__)

CoverFetcher = Callable[[str], Awaitable[tuple[bytes, str] | None]]


async def _no_cover(url: str) -> tuple[bytes, str] | None:
    return None


def error_location(error: BaseException) -> str:
    """Где упало: последняя строка нашего кода и, если глубже, библиотека — для сообщения в приложении."""
    frames = traceback.extract_tb(error.__traceback__)
    ours = [f for f in frames if f"{os.sep}bodrod{os.sep}" in f.filename]
    where = f"{os.path.basename(ours[-1].filename)}:{ours[-1].lineno} {ours[-1].name}" if ours else "?"
    if frames and frames[-1] not in ours:
        lib = frames[-1].filename.split(f"site-packages{os.sep}")[-1]
        where += f" → {lib}:{frames[-1].lineno}"
    return where


MAX_ERRORS = 8
SEARCH_POLL_S = 3
DOWNLOAD_POLL_S = 5
DIRECT_POLL_S = 3
IMPORT_POLL_S = 10
RESCAN_AFTER_S = 90
TRANSFER_MISSING_S = 90
ARTIST_MIN_RUNTIME_S = 10 * 60
ARTIST_QUIET_S = 5 * 60
ARTIST_IDLE_CHECKS = 3
ARTIST_SCAN_THROTTLE_S = 120
"""Ключи состояния, которые переживают смену источника: номер повтора, данные «метки» Lidarr."""
"""Заявки движка рекомендаций и чартов: `pool:<пользователь>` — файлы кладутся в discovery-пул, а не в библиотеку."""
PERSIST_KEYS = ("retryRound", "roundStart", "wanted", "lidarrAlbumId", "lidarrArtistId", "excludeSources")


class Worker:
    def __init__(
        self,
        store: Store,
        settings: Settings,
        navidrome: NavidromeClient,
        slskd: SlskdClient | None,
        lidarr: LidarrClient | None,
        clock: Callable[[], float] = time.time,
        cover_fetcher: CoverFetcher = _no_cover,
        registry: SourceRegistry | None = None,
        placer: Placer | None = None,
    ) -> None:
        self._store = store
        self._settings = settings
        self._navidrome = navidrome
        self._slskd = slskd
        self._lidarr = lidarr
        self._clock = clock
        self._cover_fetcher = cover_fetcher
        self._registry = registry or SourceRegistry(store, settings, {}, slskd is not None, lidarr is not None)
        self._placer = placer or Placer(settings, store, cover_fetcher, None)
        self._wakeup = asyncio.Event()
        self._last_search_at = 0.0
        self._jobs: dict[str, asyncio.Task[FetchResult]] = {}
        self._job_progress: dict[str, tuple[float | None, str]] = {}
        """Путь файла → id песни Navidrome (по БД Navidrome); без неё — поиск по строкам."""
        self.song_by_path: Callable[[str], Awaitable[str | None]] | None = None
        """Заявка завершилась (успех или ошибка) — например, сбросить индекс библиотеки."""
        self.on_finish: list[Callable[[DownloadRequest], None]] = []

    def _root(self, r: DownloadRequest) -> str | None:
        """Куда класть файлы заявки: discovery-пул для заявок рекомендаций, иначе библиотека."""
        if r.username.startswith(POOL_USER_PREFIX):
            return self._settings.discovery_path
        return self._settings.library_dir

    @property
    def registry(self) -> SourceRegistry:
        return self._registry

    def wake(self) -> None:
        self._wakeup.set()

    def direct_busy(self, name: str) -> bool:
        """Идёт ли сейчас загрузка заявки через этот прямой источник (массовая выгрузка уступает ей)."""
        return any(not task.done() and task.get_name() == name for task in self._jobs.values())

    async def run_forever(self) -> None:
        while True:
            try:
                await self.tick()
            except Exception:  # noqa: BLE001 — цикл не должен умирать
                log.exception("Ошибка цикла обработки")
            self._wakeup.clear()
            with contextlib.suppress(TimeoutError):
                await asyncio.wait_for(self._wakeup.wait(), timeout=self._settings.worker_interval_s)

    async def tick(self) -> None:
        now = self._clock()
        for request in self._store.list_active():
            if request.next_check_at > now:
                continue
            await self.step(request)

    async def step(self, request: DownloadRequest) -> None:
        try:
            await self._step(request)
            request.errors = 0
        except Exception as e:  # noqa: BLE001 — сетевые сбои: повторяем с паузой
            request.errors += 1
            where = error_location(e)
            log.warning(
                "Заявка %s: ошибка шага %s/%s (%s/%s) в %s: %s",
                request.id,
                request.source or "-",
                request.status.value,
                request.errors,
                MAX_ERRORS,
                where,
                e,
                exc_info=True,
            )
            if request.errors >= MAX_ERRORS:
                # Запоминаем шаг: повторное нажатие «Скачать» продолжит с него, без новой загрузки.
                request.state["failedStatus"] = request.status.value
                self._finish(request, RequestStatus.FAILED, f"Ошибка ({where}): {e}")
            else:
                request.next_check_at = self._clock() + min(300, 5 * 2**request.errors)
        current = self._store.get(request.id)
        if current is None or current.status == RequestStatus.CANCELLED:
            return  # пока шаг выполнялся, заявку отменили или удалили
        self._store.save(request)

    async def _step(self, r: DownloadRequest) -> None:
        source = r.source
        if source is None:
            if r.status in (RequestStatus.PLACING, RequestStatus.IMPORTING):
                # Заявка до 0.7.0, ожившая после ошибки: файл уже скачан через slskd.
                r.chain, r.chain_index = ["slskd" if r.kind == RequestKind.TRACK else "lidarr"], 0
                source = r.source
            else:
                await self._begin(r)
                return
        if source == "slskd":
            await self._step_slskd(r)
        elif source == "lidarr":
            await self._step_lidarr(r)
        else:
            await self._step_direct(r, source)

    # region Цепочка

    async def _begin(self, r: DownloadRequest) -> None:
        """Начало (или повтор) цепочки: трек уже в библиотеке? иначе — первый подходящий источник."""
        if r.kind == RequestKind.TRACK:
            existing = await find_in_library(self._navidrome, r.artist, r.title or "")
            if existing:
                r.song_id = existing
                self._finish(r, RequestStatus.COMPLETED, "Уже есть в библиотеке")
                return
        exclude = set(r.state.get("excludeSources") or [])
        r.chain = [n for n in self._registry.chain_for(r.kind) if n not in exclude]
        r.chain_index = -1
        r.state["roundStart"] = len(r.attempts)
        await self._next_source(r)

    async def _next_source(self, r: DownloadRequest) -> None:
        while True:
            r.chain_index += 1
            name = r.source
            if name is None:
                self._chain_failed(r)
                return
            if not self._registry.is_enabled(name):
                self._attempt(r, name, "skip", "Выключен")
                continue
            status = await self._registry.status(name, request_owner(r.username))
            if not status.available:
                self._attempt(r, name, "skip", status.reason or "Недоступен")
                continue
            r.state = self._keep(r)
            r.progress = None
            log.info("Заявка %s (%s %s): источник %s", r.id, r.kind.value, _what(r), name)
            if name == "slskd":
                r.status = RequestStatus.QUEUED
                await self._slskd_start(r)
            elif name == "lidarr":
                r.status = RequestStatus.QUEUED
                await self._lidarr_start(r)
            else:
                r.status = RequestStatus.DOWNLOADING
                r.message = f"Ищем в {SOURCE_TITLES[name]}"
                self._start_direct(r, name)
                r.next_check_at = self._clock() + DIRECT_POLL_S
            return

    async def _source_failed(self, r: DownloadRequest, reason: str) -> None:
        """Текущий источник не нашёл или не отдал — следующий по цепочке."""
        name = r.source
        assert name is not None
        self._attempt(r, name, "fail", reason)
        await self._next_source(r)

    def _attempt(self, r: DownloadRequest, name: str, result: str, reason: str | None) -> None:
        title = SOURCE_TITLES.get(name, name)
        r.attempts.append({"source": name, "title": title, "result": result, "reason": reason, "at": now_ms()})
        verb = {"ok": "успех", "fail": "не нашёл", "skip": "пропущен"}.get(result, result)
        log.info("Заявка %s: %s — %s%s", r.id, title, verb, f": {reason}" if reason else "")

    def _chain_failed(self, r: DownloadRequest) -> None:
        start = int(r.state.get("roundStart", 0))
        tried = r.attempts[start:]
        failures = [a for a in tried if a["result"] == "fail"]
        summary = "; ".join(f"{a['title']}: {a['reason']}" for a in tried) or "нет источников для такой заявки"
        if r.state.get("wanted"):
            self._finish(r, RequestStatus.FAILED, f"Не нашли — оставлено Lidarr. {summary}")
            return
        if not failures:
            self._finish(r, RequestStatus.FAILED, f"Нет доступных источников. {summary}")
            return
        retry = int(r.state.get("retryRound", 0))
        rounds = self._settings.slskd_retry_rounds
        if retry < rounds:
            interval_s = self._settings.slskd_retry_interval_s
            interval = f"{interval_s / 3600:g} ч" if interval_s >= 3600 else f"{interval_s // 60} мин"
            r.state = self._keep(r) | {"retryRound": retry + 1}
            r.chain, r.chain_index = [], -1
            r.status = RequestStatus.QUEUED
            r.progress = None
            r.message = f"Не нашли, повторим через {interval} (повтор {retry + 1} из {rounds}). {summary}"
            r.next_check_at = self._clock() + interval_s
            return
        self._finish(r, RequestStatus.FAILED, f"Не нашли ни в одном источнике. {summary}")

    @staticmethod
    def _keep(r: DownloadRequest) -> dict[str, Any]:
        return {k: r.state[k] for k in PERSIST_KEYS if k in r.state}

    def _succeeded(self, r: DownloadRequest, label: str) -> None:
        name = r.source or "?"
        r.source_label = label
        self._attempt(r, name, "ok", label)

    # endregion

    # region Прямые источники (Яндекс, YouTube)

    def _start_direct(self, r: DownloadRequest, name: str) -> None:
        source = self._registry.direct(name)
        assert source is not None
        request_id = r.id
        root = self._root(r)
        workdir = Path(self._settings.staging_dir) / request_id

        async def exists(artist: str, title: str, source_id: str | None) -> bool:
            if source_id and any(Path(p).exists() for p in self._store.source_file_paths(name, source_id)):
                return True
            return await find_in_library(self._navidrome, artist, title) is not None

        async def place(file: Any) -> Path:
            return await self._placer.place(file, name, request_id, root=root)

        def progress(value: float | None, message: str) -> None:
            self._job_progress[request_id] = (value, message)

        job = FetchJob(
            kind=r.kind,
            artist=r.artist,
            title=r.title,
            album=r.album,
            duration_ms=r.duration_ms,
            workdir=workdir,
            exists=exists,
            place=place,
            progress=progress,
            owner=request_owner(r.username),
        )

        async def run() -> FetchResult:
            try:
                return await source.fetch(job)
            finally:
                await asyncio.to_thread(shutil.rmtree, workdir, True)

        self._jobs[request_id] = asyncio.create_task(run(), name=name)

    async def _step_direct(self, r: DownloadRequest, name: str) -> None:
        if r.status == RequestStatus.IMPORTING:
            await self._import(r)
            return
        now = self._clock()
        task = self._jobs.get(r.id)
        if task is None:
            # Рестарт или повтор после ошибки: начинаем заново, уже положенное источник пропустит.
            self._start_direct(r, name)
            r.next_check_at = now + DIRECT_POLL_S
            return
        if not task.done():
            value, message = self._job_progress.get(r.id, (None, None))
            if message:
                r.message = message
            r.progress = round(value, 3) if value is not None else r.progress
            r.next_check_at = now + DIRECT_POLL_S
            return
        self._jobs.pop(r.id, None)
        self._job_progress.pop(r.id, None)
        error = task.exception() if not task.cancelled() else SourceMiss("Отменено")
        if isinstance(error, SourceMiss):
            await self._source_failed(r, error.reason)
            return
        if error is not None:
            log.warning("Заявка %s: %s упал: %s", r.id, name, error, exc_info=error)
            await self._source_failed(r, f"ошибка: {error}")
            return
        result = task.result()
        self._succeeded(r, result.label)
        r.state.update({"placed": result.placed, "skipped": result.skipped, "failedTracks": result.failed})
        if result.paths:
            r.state["destPath"] = result.paths[0]
        root = self._root(r)
        if result.paths and root:
            try:
                r.state["relPath"] = str(Path(result.paths[0]).relative_to(root))
            except ValueError:
                r.state["relPath"] = result.paths[0]
        r.progress = 1.0
        await self._start_import(r)
        if r.kind == RequestKind.ARTIST:
            await self._follow_in_lidarr(r)

    async def _follow_in_lidarr(self, r: DownloadRequest) -> None:
        """Дискография скачана мимо Lidarr — отслеживать в нём только новые релизы («метки»)."""
        if self._lidarr is None or not self._registry.is_enabled("lidarr"):
            return
        try:
            artist_id = await self._lidarr.follow_artist(r.artist)
            r.state["lidarrArtistId"] = artist_id
            log.info("Заявка %s: %s отслеживается в Lidarr (новые релизы)", r.id, r.artist)
        except Exception as e:  # noqa: BLE001 — скачанное важнее отслеживания
            log.warning("Заявка %s: не удалось добавить %s в Lidarr: %s", r.id, r.artist, e)

    async def _import(self, r: DownloadRequest) -> None:
        """Импорт после прямого источника: трек — ждём его в Navidrome; альбом и исполнитель — конец скана."""
        if r.kind == RequestKind.TRACK:
            await self._track_import(r)
            return
        now = self._clock()
        if await self._navidrome.is_scanning():
            r.next_check_at = now + IMPORT_POLL_S
            return
        placed, skipped = int(r.state.get("placed", 0)), int(r.state.get("skipped", 0))
        failed = int(r.state.get("failedTracks", 0))
        message = f"Скачано {placed} треков" + (f", {skipped} уже были" if skipped else "")
        message += f", {failed} не отдались" if failed else ""
        if r.state.get("lidarrArtistId") and self._lidarr is not None:
            with contextlib.suppress(Exception):
                await self._lidarr.refresh_artist(int(r.state["lidarrArtistId"]))
        self._finish(r, RequestStatus.COMPLETED, message)

    # endregion

    # region slskd (трек)

    async def _step_slskd(self, r: DownloadRequest) -> None:
        match r.status:
            case RequestStatus.QUEUED:
                await self._slskd_start(r)
            case RequestStatus.SEARCHING:
                await self._track_search(r)
            case RequestStatus.DOWNLOADING:
                await self._track_download(r)
            case RequestStatus.PLACING:
                await self._track_place(r)
            case RequestStatus.IMPORTING:
                await self._track_import(r)

    async def _slskd_start(self, r: DownloadRequest) -> None:
        assert self._slskd is not None
        now = self._clock()
        wait = self._search_wait(r, now)
        if wait > 0:
            r.message = "В очереди на поиск в Soulseek"
            r.next_check_at = now + wait
            return
        r.state = self._keep(r) | {
            "queries": search_queries(r.artist, r.title or ""),
            "attempt": 0,
            "tried": [],
        }
        await self._start_search(r)

    def _search_wait(self, r: DownloadRequest, now: float) -> float:
        """Сколько подождать до запуска поиска: заняты все `SLSKD_PARALLEL_SEARCHES` или не прошла пауза."""
        busy = sum(
            1
            for other in self._store.list_active()
            if other.id != r.id and other.kind == RequestKind.TRACK and other.status == RequestStatus.SEARCHING
        )
        if busy >= self._settings.slskd_parallel_searches:
            return SEARCH_POLL_S
        return max(0.0, self._last_search_at + self._settings.slskd_search_gap_s - now)

    async def _start_search(self, r: DownloadRequest) -> None:
        assert self._slskd is not None
        queries = r.state["queries"]
        attempt = int(r.state.get("attempt", 0))
        query = queries[attempt]
        search_id = await self._slskd.start_search(query, self._timeout())
        now = self._clock()
        self._last_search_at = now
        r.state.update({"searchId": search_id, "searchStartedAt": now})
        r.status = RequestStatus.SEARCHING
        r.message = f"Ищем в Soulseek: «{query}»" + (f" (запрос {attempt + 1} из {len(queries)})" if attempt else "")
        r.next_check_at = now + SEARCH_POLL_S

    def _timeout(self) -> int:
        return self._settings.slskd_search_timeout_s

    async def _track_search(self, r: DownloadRequest) -> None:
        assert self._slskd is not None
        search_id = r.state["searchId"]
        elapsed = self._clock() - r.state.get("searchStartedAt", 0)
        if not await self._slskd.search_complete(search_id) and elapsed < self._timeout() + 15:
            r.next_check_at = self._clock() + SEARCH_POLL_S
            return
        responses = await self._slskd.search_responses(search_id)
        await self._slskd.delete_search(search_id)
        candidates = rank_candidates(
            responses,
            artist=r.artist,
            title=r.title or "",
            duration_s=r.duration_ms // 1000 if r.duration_ms else None,
            allowed_formats=self._settings.allowed_formats,
            min_bitrate=self._settings.min_bitrate,
            limit=self._settings.slskd_max_candidates,
        )
        queries = r.state.get("queries") or [""]
        attempt = int(r.state.get("attempt", 0))
        files = sum(len(p.files) for p in responses)
        tried = r.state.setdefault("tried", [])
        tried.append(
            {"query": queries[attempt], "responses": len(responses), "files": files, "matched": len(candidates)}
        )
        log.info(
            "Заявка %s: «%s» — %s ответов, %s файлов, %s подходящих",
            r.id, queries[attempt], len(responses), files, len(candidates),
        )  # fmt: skip
        if candidates:
            r.state = self._keep(r) | {"candidates": [c.to_state() for c in candidates], "index": -1, "tried": tried}
            await self._next_candidate(r)
            return
        if attempt + 1 < len(queries):
            r.state["attempt"] = attempt + 1
            await self._start_search(r)
            return
        summary = ", ".join(
            f"«{t['query']}» — ответов {t['responses']}, файлов {t['files']}, подходящих {t['matched']}" for t in tried
        )
        await self._source_failed(r, f"не нашли подходящий файл; запросы: {summary}")

    async def _next_candidate(self, r: DownloadRequest) -> None:
        assert self._slskd is not None
        candidates = r.state.get("candidates") or []
        index = int(r.state.get("index", -1)) + 1
        while index < len(candidates):
            c = candidates[index]
            try:
                await self._slskd.enqueue(c["username"], c["filename"], int(c["size"]))
            except Exception as e:  # noqa: BLE001 — пир недоступен: берём следующего
                log.info("Заявка %s: не удалось поставить %s: %s", r.id, c["filename"], e)
                index += 1
                continue
            now = self._clock()
            r.state.update({"index": index, "enqueuedAt": now, "lastProgressAt": now, "lastPercent": 0})
            r.status = RequestStatus.DOWNLOADING
            r.progress = 0.0
            r.message = "В очереди у пользователя Soulseek" if index == 0 else f"Пробуем источник {index + 1}"
            r.next_check_at = now + DOWNLOAD_POLL_S
            return
        await self._source_failed(r, "ни один пир не отдал файл")

    async def _track_download(self, r: DownloadRequest) -> None:
        assert self._slskd is not None
        c = r.state["candidates"][r.state["index"]]
        now = self._clock()
        transfer = await self._slskd.transfer(c["username"], c["filename"])
        if transfer is None:
            if now - r.state.get("enqueuedAt", now) > TRANSFER_MISSING_S:
                await self._next_candidate(r)
            else:
                r.next_check_at = now + DOWNLOAD_POLL_S
            return
        if transfer.succeeded:
            r.progress = 1.0
            r.message = "Добавляем в библиотеку"
            ext = Path(c["filename"].replace("\\", "/")).suffix.lstrip(".").upper()
            self._succeeded(r, f"Soulseek, {ext}" if ext else "Soulseek")
            if self._settings.placement_enabled:
                r.status = RequestStatus.PLACING
                r.state["placeStartedAt"] = now
                r.next_check_at = now
                return
            await self._start_import(r)
            return
        if transfer.failed:
            log.info("Заявка %s: загрузка %s завершилась: %s", r.id, c["filename"], transfer.state)
            await self._next_candidate(r)
            return
        percent = float(transfer.percent or 0)
        if percent > float(r.state.get("lastPercent", 0)):
            r.state["lastPercent"] = percent
            r.state["lastProgressAt"] = now
        if transfer.in_progress:
            r.message = "Скачиваем из Soulseek"
            r.progress = round(percent / 100, 3)
        if now - r.state.get("lastProgressAt", now) > self._settings.slskd_stall_timeout_s:
            log.info("Заявка %s: источник %s не отдаёт файл, пробуем следующий", r.id, c["username"])
            await self._slskd.cancel(c["username"], transfer.id)
            await self._next_candidate(r)
            return
        r.next_check_at = now + DOWNLOAD_POLL_S

    async def _start_import(self, r: DownloadRequest) -> None:
        now = self._clock()
        r.status = RequestStatus.IMPORTING
        r.message = "Добавляем в библиотеку"
        r.state["importStartedAt"] = now
        await self._scan()
        r.next_check_at = now + IMPORT_POLL_S

    async def _track_place(self, r: DownloadRequest) -> None:
        """Найти скачанный файл, положить в библиотеку под понятным именем, прописать теги и обложку."""
        settings = self._settings
        downloads, library = settings.slskd_downloads_dir, self._root(r)
        assert downloads is not None and library is not None
        c = r.state["candidates"][r.state["index"]]
        now = self._clock()

        dest_raw = r.state.get("destPath")
        if dest_raw and Path(dest_raw).exists():
            # Рестарт после переименования, но до сохранения состояния: файл уже на месте.
            log.info("Заявка %s: файл уже размещён в %s", r.id, dest_raw)
            await self._after_place(r, Path(dest_raw), None)
            return

        local = r.state.get("localPath")
        source = Path(local) if local and Path(local).is_file() else None
        if source is None:
            source = await asyncio.to_thread(find_local_file, downloads, c["filename"], int(c.get("size") or 0) or None)
            if source is None:
                if now - r.state.get("placeStartedAt", now) > settings.place_find_timeout_s:
                    self._finish(
                        r, RequestStatus.FAILED, f"Файл скачан, но не найден в папке загрузок slskd ({downloads})"
                    )
                else:
                    r.next_check_at = now + DOWNLOAD_POLL_S
                return
            r.state["localPath"] = str(source)
            log.info("Заявка %s: найден файл %s", r.id, source)

        tags = Tags(artist=r.artist, title=r.title or source.stem, album=r.album)
        tags, cover_url, retagged = await self._placer.retag(tags, r.cover_url, source, r.duration_ms)
        if retagged:
            r.source_label = (r.source_label or "Soulseek") + ", теги Яндекса"
        rel = relative_path(
            settings.library_layout,
            artist=tags.album_artist or tags.artist,
            title=tags.title,
            album=tags.album,
            extension=source.suffix,
        )
        dest, already = await asyncio.to_thread(choose_destination, Path(library), rel, source, tags)
        if already:
            log.info("Заявка %s: такой файл уже есть в библиотеке: %s", r.id, dest)
            await self._after_place(r, dest, source)
            return
        r.state["destPath"] = str(dest)
        self._store.save(r)  # путь назначения запомнен до копирования — рестарт не создаст дубль

        cover = None
        if cover_url:
            try:
                cover = await self._cover_fetcher(cover_url)
            except Exception as e:  # noqa: BLE001 — без обложки трек всё равно нужен
                log.warning("Заявка %s: обложку скачать не удалось: %s", r.id, e)
        written = await asyncio.to_thread(place_file, source, dest, tags, cover)
        log.info("Заявка %s: положен в %s, теги: %s", r.id, dest, ", ".join(written) or "нет")
        await self._after_place(r, dest, source)

    async def _after_place(self, r: DownloadRequest, dest: Path, source: Path | None) -> None:
        settings = self._settings
        assert settings.library_dir is not None and settings.slskd_downloads_dir is not None
        if source is not None and settings.delete_source and source.exists() and source != dest:
            await asyncio.to_thread(remove_source, source, settings.slskd_downloads_dir)
            log.info("Заявка %s: исходник удалён: %s", r.id, source)
        r.state["destPath"] = str(dest)
        try:
            r.state["relPath"] = str(dest.relative_to(self._root(r) or settings.library_dir))
        except ValueError:
            r.state["relPath"] = str(dest)
        codec = dest.suffix.lstrip(".").lower()
        self._store.add_library_file(str(dest), r.id, "slskd", codec, codec not in LOSSLESS)
        await self._start_import(r)

    async def _track_import(self, r: DownloadRequest) -> None:
        now = self._clock()
        if await self._navidrome.is_scanning():
            r.next_check_at = now + IMPORT_POLL_S
            return
        song_id = None
        dest = r.state.get("destPath")
        if dest and self.song_by_path is not None:
            song_id = await self.song_by_path(dest)  # точнее поиска: не зависит от написания тегов
        song_id = song_id or await find_in_library(self._navidrome, r.artist, r.title or "")
        if song_id:
            r.song_id = song_id
            self._finish(r, RequestStatus.COMPLETED, "Скачано")
            return
        started = r.state.get("importStartedAt", now)
        if now - started > self._settings.import_timeout_s:
            rel = r.state.get("relPath")
            message = (
                f"Файл положен в {rel}, но Navidrome его не нашёл"
                if rel
                else "Файл скачан, но Navidrome пока не нашёл трек — проверьте теги"
            )
            self._finish(r, RequestStatus.COMPLETED, message)
            return
        if now - started > RESCAN_AFTER_S and not r.state.get("rescanned"):
            r.state["rescanned"] = True
            await self._scan()
        r.next_check_at = now + IMPORT_POLL_S

    # endregion

    # region Lidarr (исполнитель, альбом)

    async def _step_lidarr(self, r: DownloadRequest) -> None:
        if r.status == RequestStatus.QUEUED:
            await self._lidarr_start(r)
        elif r.kind == RequestKind.ALBUM:
            await self._lidarr_album(r)
        else:
            await self._lidarr_artist(r)

    async def _lidarr_start(self, r: DownloadRequest) -> None:
        assert self._lidarr is not None
        now = self._clock()
        try:
            if r.kind == RequestKind.ALBUM:
                album_id, artist_id, title = await self._lidarr.add_album(r.artist, r.album or "")
                r.state.update({"lidarrAlbumId": album_id, "lidarrArtistId": artist_id})
                r.message = f"Lidarr ищет альбом «{title}»"
            else:
                artist_id, name = await self._lidarr.add_or_search(r.artist)
                r.state["lidarrId"] = artist_id
                r.message = f"Lidarr ищет альбомы «{name}»"
        except LidarrError as e:
            await self._source_failed(r, str(e))
            return
        r.state.update({"addedAt": now, "files": 0, "lastChangeAt": now, "idleChecks": 0})
        r.status = RequestStatus.DOWNLOADING
        r.next_check_at = now + self._settings.lidarr_check_interval_s

    async def _lidarr_artist(self, r: DownloadRequest) -> None:
        assert self._lidarr is not None
        now = self._clock()
        artist_id = int(r.state["lidarrId"])
        try:
            stats = await self._lidarr.stats(artist_id)
        except httpx.HTTPStatusError as e:
            if e.response.status_code != 404:
                raise
            # Исполнителя удалили в Lidarr: повторять запрос бессмысленно.
            self._finish(r, RequestStatus.FAILED, "Артист удалён из Lidarr — создайте заявку заново")
            return
        queue = await self._lidarr.queue_size(artist_id)
        files = stats.track_file_count
        await self._lidarr_progress(r, now, files, queue, stats.total_track_count, stats.progress)
        added = r.state.get("addedAt", now)
        quiet = now - r.state.get("lastChangeAt", now) >= ARTIST_QUIET_S
        total = f" из {stats.total_track_count}" if stats.total_track_count else ""
        if files > 0 and r.state["idleChecks"] >= ARTIST_IDLE_CHECKS and quiet and now - added >= ARTIST_MIN_RUNTIME_S:
            await self._scan()
            self._succeeded(r, "Lidarr")
            self._finish(r, RequestStatus.COMPLETED, f"Скачано {files}{total} треков")
            return
        if files == 0 and now - added > self._settings.lidarr_give_up_s:
            await self._source_failed(r, "Lidarr ничего не скачал за сутки")
            return
        r.next_check_at = now + self._settings.lidarr_check_interval_s

    async def _lidarr_album(self, r: DownloadRequest) -> None:
        assert self._lidarr is not None
        now = self._clock()
        album_id = r.state.get("lidarrAlbumId")
        if album_id is None:
            # Lidarr ещё не подтянул альбомы нового исполнителя — ищем снова.
            found, _, title = await self._lidarr.find_album(int(r.state["lidarrArtistId"]), r.album or "")
            if found is None:
                if now - r.state.get("addedAt", now) > 30 * 60:
                    await self._source_failed(r, f"В Lidarr нет альбома «{r.album}»")
                else:
                    r.next_check_at = now + self._settings.lidarr_check_interval_s
                return
            r.state["lidarrAlbumId"] = found
            await self._lidarr.monitor_album(found)
            r.message = f"Lidarr ищет альбом «{title}»"
            r.next_check_at = now + self._settings.lidarr_check_interval_s
            return
        try:
            stats = await self._lidarr.album_stats(int(album_id))
        except httpx.HTTPStatusError as e:
            if e.response.status_code != 404:
                raise
            self._finish(r, RequestStatus.FAILED, "Альбом удалён из Lidarr — создайте заявку заново")
            return
        queue = await self._lidarr.queue_size(int(r.state["lidarrArtistId"]))
        files = stats.track_file_count
        await self._lidarr_progress(r, now, files, queue, stats.total_track_count, stats.progress)
        complete = stats.total_track_count > 0 and files >= stats.total_track_count
        quiet = now - r.state.get("lastChangeAt", now) >= ARTIST_QUIET_S
        if files > 0 and (complete or (r.state["idleChecks"] >= ARTIST_IDLE_CHECKS and quiet)):
            await self._scan()
            self._succeeded(r, "Lidarr")
            self._finish(r, RequestStatus.COMPLETED, f"Скачано {files} из {stats.total_track_count} треков")
            return
        if files == 0 and now - r.state.get("addedAt", now) > self._settings.lidarr_give_up_s:
            await self._source_failed(r, "Lidarr ничего не скачал за сутки")
            return
        r.next_check_at = now + self._settings.lidarr_check_interval_s

    async def _lidarr_progress(
        self, r: DownloadRequest, now: float, files: int, queue: int, total_tracks: int, progress: float | None
    ) -> None:
        if files != r.state.get("files", 0):
            r.state["files"] = files
            r.state["lastChangeAt"] = now
            if now - r.state.get("scannedAt", 0) > ARTIST_SCAN_THROTTLE_S:
                r.state["scannedAt"] = now
                await self._scan()
        r.state["idleChecks"] = r.state.get("idleChecks", 0) + 1 if queue == 0 else 0
        r.progress = progress
        total = f" из {total_tracks}" if total_tracks else ""
        r.message = f"Lidarr: скачано {files}{total} треков" + (f", в очереди {queue}" if queue else "")

    # endregion

    async def cancel(self, r: DownloadRequest) -> None:
        task = self._jobs.pop(r.id, None)
        if task is not None:
            task.cancel()
        source = r.source
        if source == "slskd" and self._slskd is not None:
            if r.status == RequestStatus.SEARCHING and "searchId" in r.state:
                await self._slskd.delete_search(r.state["searchId"])
            if r.status == RequestStatus.DOWNLOADING and "candidates" in r.state:
                c = r.state["candidates"][r.state["index"]]
                transfer = await self._slskd.transfer(c["username"], c["filename"])
                if transfer is not None:
                    await self._slskd.cancel(c["username"], transfer.id)
        if source == "lidarr" and self._lidarr is not None:
            try:
                if r.kind == RequestKind.ARTIST and "lidarrId" in r.state:
                    removed = await self._lidarr.stop_artist(int(r.state["lidarrId"]))
                    log.info("Lidarr: исполнитель %s больше не отслеживается, из очереди убрано %s", r.artist, removed)
                elif r.kind == RequestKind.ALBUM and r.state.get("lidarrAlbumId"):
                    await self._lidarr.unmonitor_album(int(r.state["lidarrAlbumId"]))
            except Exception as e:  # noqa: BLE001 — заявку всё равно отменяем
                log.warning("Lidarr: не удалось остановить загрузку %s: %s", r.artist, e)
        self._finish(r, RequestStatus.CANCELLED, "Отменено")
        self._store.save(r)

    async def _scan(self) -> None:
        try:
            await self._navidrome.start_scan()
        except Exception as e:  # noqa: BLE001 — у Navidrome может быть своё автосканирование
            log.warning("Не удалось запустить сканирование Navidrome: %s", e)

    def _finish(self, r: DownloadRequest, status: RequestStatus, message: str) -> None:
        r.status = status
        r.message = message
        if status == RequestStatus.COMPLETED:
            r.progress = 1.0
        r.next_check_at = 0
        for callback in self.on_finish:
            try:
                callback(r)
            except Exception:  # noqa: BLE001 — подписчик не должен ломать заявку
                log.exception("Заявка %s: ошибка обработчика завершения", r.id)


def _what(r: DownloadRequest) -> str:
    if r.kind == RequestKind.TRACK:
        return f"{r.artist} — {r.title}"
    if r.kind == RequestKind.ALBUM:
        return f"{r.artist} — {r.album}"
    return r.artist
