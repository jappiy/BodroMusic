"""Фоновая обработка заявок. Каждый шаг — одно короткое действие, состояние хранится в БД (переживает рестарт).

Трек: queued → (уже в библиотеке? → completed) → searching (slskd) → downloading → importing → completed.
Исполнитель: queued → downloading (Lidarr качает дискографию) → completed.
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
import time
from collections.abc import Callable

from .clients.lidarr import LidarrClient
from .clients.navidrome import NavidromeClient
from .clients.slskd import SlskdClient
from .config import Settings
from .db import DownloadRequest, Store
from .matching import rank_candidates
from .metadata.discover import find_in_library
from .models import RequestKind, RequestStatus
from .text import search_text

log = logging.getLogger(__name__)

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
    ) -> None:
        self._store = store
        self._settings = settings
        self._navidrome = navidrome
        self._slskd = slskd
        self._lidarr = lidarr
        self._clock = clock
        self._wakeup = asyncio.Event()

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
            log.warning("Заявка %s: ошибка шага (%s): %s", request.id, request.errors, e)
            if request.errors >= MAX_ERRORS:
                self._finish(request, RequestStatus.FAILED, f"Ошибка: {e}")
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
            case RequestStatus.IMPORTING:
                await self._track_import(r)

    async def _track_start(self, r: DownloadRequest) -> None:
        title = r.title or ""
        existing = await find_in_library(self._navidrome, r.artist, title)
        if existing:
            r.song_id = existing
            self._finish(r, RequestStatus.COMPLETED, "Уже есть в библиотеке")
            return
        if self._slskd is None:
            self._finish(r, RequestStatus.FAILED, "slskd не настроен на сервере")
            return
        search_id = await self._slskd.start_search(search_text(r.artist.split(",")[0], title), self._timeout())
        r.state = {"searchId": search_id, "searchStartedAt": self._clock()}
        r.status = RequestStatus.SEARCHING
        r.message = "Ищем в Soulseek"
        r.next_check_at = self._clock() + SEARCH_POLL_S

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
        log.info("Заявка %s: %s ответов, %s подходящих файлов", r.id, len(responses), len(candidates))
        if not candidates:
            self._finish(r, RequestStatus.FAILED, "Не нашли подходящий файл в Soulseek")
            return
        r.state = {"candidates": [c.to_state() for c in candidates], "index": -1}
        await self._next_candidate(r)

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
            r.status = RequestStatus.IMPORTING
            r.message = "Добавляем в библиотеку"
            r.state["importStartedAt"] = now
            await self._scan()
            r.next_check_at = now + IMPORT_POLL_S
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
            self._finish(r, RequestStatus.COMPLETED, "Файл скачан, но Navidrome пока не нашёл трек — проверьте теги")
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
        stats = await self._lidarr.stats(artist_id)
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
