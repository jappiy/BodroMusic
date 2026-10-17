"""Фоновая обработка заявок. Каждый шаг — одно короткое действие, состояние хранится в БД (переживает рестарт).

Трек: queued → (уже в библиотеке? → completed) → searching (slskd) → downloading → [placing] → importing → completed.
placing — bodrod сам кладёт файл в библиотеку с тегами (если заданы SLSKD_DOWNLOADS_DIR и LIBRARY_DIR).
Поиски в Soulseek идут очередью: не больше `SLSKD_PARALLEL_SEARCHES` одновременно, с паузой между запусками.
Каждая заявка пробует до трёх запросов (`text.search_queries`); не нашли ни по одному — заявка ждёт
`SLSKD_RETRY_INTERVAL_S` и пробует снова, до `SLSKD_RETRY_ROUNDS` раз, и только потом завершается с ошибкой.
Исполнитель: queued → downloading (Lidarr качает дискографию) → completed.
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
import os
import time
import traceback
from collections.abc import Awaitable, Callable
from pathlib import Path

import httpx

from .clients.lidarr import LidarrClient
from .clients.navidrome import NavidromeClient
from .clients.slskd import SlskdClient
from .config import Settings
from .db import DownloadRequest, Store
from .matching import rank_candidates
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
IMPORT_POLL_S = 10
RESCAN_AFTER_S = 90
TRANSFER_MISSING_S = 90
ARTIST_MIN_RUNTIME_S = 10 * 60
ARTIST_QUIET_S = 5 * 60
ARTIST_IDLE_CHECKS = 3
ARTIST_SCAN_THROTTLE_S = 120


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
    ) -> None:
        self._store = store
        self._settings = settings
        self._navidrome = navidrome
        self._slskd = slskd
        self._lidarr = lidarr
        self._clock = clock
        self._cover_fetcher = cover_fetcher
        self._wakeup = asyncio.Event()
        self._last_search_at = 0.0

    def wake(self) -> None:
        self._wakeup.set()

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
            if request.kind == RequestKind.TRACK:
                await self._step_track(request)
            else:
                await self._step_artist(request)
            request.errors = 0
        except Exception as e:  # noqa: BLE001 — сетевые сбои: повторяем с паузой
            request.errors += 1
            where = error_location(e)
            log.warning(
                "Заявка %s: ошибка шага %s (%s/%s) в %s: %s",
                request.id,
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

    # region Трек

    async def _step_track(self, r: DownloadRequest) -> None:
        match r.status:
            case RequestStatus.QUEUED:
                await self._track_start(r)
            case RequestStatus.SEARCHING:
                await self._track_search(r)
            case RequestStatus.DOWNLOADING:
                await self._track_download(r)
            case RequestStatus.PLACING:
                await self._track_place(r)
            case RequestStatus.IMPORTING:
                await self._track_import(r)

    async def _track_start(self, r: DownloadRequest) -> None:
        if self._slskd is None:
            self._finish(r, RequestStatus.FAILED, "slskd не настроен на сервере")
            return
        now = self._clock()
        wait = self._search_wait(r, now)
        if wait > 0:
            if not r.state.get("retryRound"):
                r.message = "В очереди на поиск в Soulseek"
            r.next_check_at = now + wait
            return
        title = r.title or ""
        existing = await find_in_library(self._navidrome, r.artist, title)
        if existing:
            r.song_id = existing
            self._finish(r, RequestStatus.COMPLETED, "Уже есть в библиотеке")
            return
        r.state = {
            "queries": search_queries(r.artist, title),
            "attempt": 0,
            "tried": [],
            "retryRound": r.state.get("retryRound", 0),
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
            r.state = {"candidates": [c.to_state() for c in candidates], "index": -1, "tried": tried}
            await self._next_candidate(r)
            return
        if attempt + 1 < len(queries):
            r.state["attempt"] = attempt + 1
            await self._start_search(r)
            return
        self._search_failed(r, tried)

    def _search_failed(self, r: DownloadRequest, tried: list[dict]) -> None:
        """Ни один запрос не дал подходящего файла: повторить позже или завершить с отчётом о запросах."""
        summary = "; ".join(
            f"«{t['query']}» — ответов {t['responses']}, файлов {t['files']}, подходящих {t['matched']}" for t in tried
        )
        retry = int(r.state.get("retryRound", 0))
        rounds = self._settings.slskd_retry_rounds
        if retry < rounds:
            interval_s = self._settings.slskd_retry_interval_s
            interval = f"{interval_s / 3600:g} ч" if interval_s >= 3600 else f"{interval_s // 60} мин"
            r.state = {"retryRound": retry + 1, "tried": tried}
            r.status = RequestStatus.QUEUED
            r.message = (
                f"Не нашли в Soulseek, повторим через {interval} (повтор {retry + 1} из {rounds}). Запросы: {summary}"
            )
            r.next_check_at = self._clock() + interval_s
            return
        self._finish(r, RequestStatus.FAILED, f"Не нашли подходящий файл в Soulseek. Запросы: {summary}")

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
        self._finish(r, RequestStatus.FAILED, "Ни один источник не отдал файл")

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
            r.message = "Скачиваем"
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
        downloads, library = settings.slskd_downloads_dir, settings.library_dir
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
        rel = relative_path(
            settings.library_layout, artist=tags.artist, title=tags.title, album=tags.album, extension=source.suffix
        )
        dest, already = await asyncio.to_thread(choose_destination, Path(library), rel, source, tags)
        if already:
            log.info("Заявка %s: такой файл уже есть в библиотеке: %s", r.id, dest)
            await self._after_place(r, dest, source)
            return
        r.state["destPath"] = str(dest)
        self._store.save(r)  # путь назначения запомнен до копирования — рестарт не создаст дубль

        cover = None
        if r.cover_url:
            try:
                cover = await self._cover_fetcher(r.cover_url)
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
            r.state["relPath"] = str(dest.relative_to(settings.library_dir))
        except ValueError:
            r.state["relPath"] = str(dest)
        await self._start_import(r)

    async def _track_import(self, r: DownloadRequest) -> None:
        now = self._clock()
        if await self._navidrome.is_scanning():
            r.next_check_at = now + IMPORT_POLL_S
            return
        song_id = await find_in_library(self._navidrome, r.artist, r.title or "")
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

    # region Исполнитель

    async def _step_artist(self, r: DownloadRequest) -> None:
        if self._lidarr is None:
            self._finish(r, RequestStatus.FAILED, "Lidarr не настроен на сервере")
            return
        now = self._clock()
        if r.status == RequestStatus.QUEUED:
            artist_id, name = await self._lidarr.add_or_search(r.artist)
            r.state = {"lidarrId": artist_id, "addedAt": now, "files": 0, "lastChangeAt": now, "idleChecks": 0}
            r.status = RequestStatus.DOWNLOADING
            r.message = f"Lidarr ищет альбомы «{name}»"
            r.next_check_at = now + self._settings.lidarr_check_interval_s
            return

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
        if files != r.state.get("files", 0):
            r.state["files"] = files
            r.state["lastChangeAt"] = now
            if now - r.state.get("scannedAt", 0) > ARTIST_SCAN_THROTTLE_S:
                r.state["scannedAt"] = now
                await self._scan()
        r.state["idleChecks"] = r.state.get("idleChecks", 0) + 1 if queue == 0 else 0
        r.progress = stats.progress
        total = f" из {stats.total_track_count}" if stats.total_track_count else ""
        r.message = f"Скачано {files}{total} треков" + (f", в очереди {queue}" if queue else "")

        added = r.state.get("addedAt", now)
        quiet = now - r.state.get("lastChangeAt", now) >= ARTIST_QUIET_S
        if files > 0 and r.state["idleChecks"] >= ARTIST_IDLE_CHECKS and quiet and now - added >= ARTIST_MIN_RUNTIME_S:
            await self._scan()
            self._finish(r, RequestStatus.COMPLETED, f"Скачано {files}{total} треков")
            return
        if files == 0 and now - added > self._settings.lidarr_give_up_s:
            self._finish(r, RequestStatus.FAILED, "Lidarr ничего не скачал за сутки")
            return
        r.next_check_at = now + self._settings.lidarr_check_interval_s

    # endregion

    async def cancel(self, r: DownloadRequest) -> None:
        if r.kind == RequestKind.TRACK and self._slskd is not None:
            if r.status == RequestStatus.SEARCHING and "searchId" in r.state:
                await self._slskd.delete_search(r.state["searchId"])
            if r.status == RequestStatus.DOWNLOADING:
                c = r.state["candidates"][r.state["index"]]
                transfer = await self._slskd.transfer(c["username"], c["filename"])
                if transfer is not None:
                    await self._slskd.cancel(c["username"], transfer.id)
        if r.kind == RequestKind.ARTIST and self._lidarr is not None and "lidarrId" in r.state:
            try:
                removed = await self._lidarr.stop_artist(int(r.state["lidarrId"]))
                log.info("Lidarr: исполнитель %s больше не отслеживается, из очереди убрано %s", r.artist, removed)
            except Exception as e:  # noqa: BLE001 — заявку всё равно отменяем
                log.warning("Lidarr: не удалось остановить загрузку %s: %s", r.artist, e)
        self._finish(r, RequestStatus.CANCELLED, "Отменено")
        self._store.save(r)

    async def _scan(self) -> None:
        try:
            await self._navidrome.start_scan()
        except Exception as e:  # noqa: BLE001 — у Navidrome может быть своё автосканирование
            log.warning("Не удалось запустить сканирование Navidrome: %s", e)

    @staticmethod
    def _finish(r: DownloadRequest, status: RequestStatus, message: str) -> None:
        r.status = status
        r.message = message
        if status == RequestStatus.COMPLETED:
            r.progress = 1.0
        r.next_check_at = 0
