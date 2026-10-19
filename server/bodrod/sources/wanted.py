"""«Метки» Lidarr: недостающие альбомы отслеживаемых исполнителей сначала пробует цепочка bodrod.

Отслеживаемый в Lidarr исполнитель → Lidarr помечает недостающие альбомы как wanted → bodrod раз в
`LIDARR_WANTED_INTERVAL_S` берёт новые из `/wanted/missing` и создаёт заявки «альбом» (служебный пользователь
`lidarr`, в приложении не видны) с цепочкой без Lidarr. Скачанное Яндексом или YouTube кладётся в библиотеку,
Lidarr пересканирует папку исполнителя и снимает метку; не найденное остаётся Lidarr и Soularr (slskd), как раньше,
и через `LIDARR_WANTED_RETRY_DAYS` пробуется цепочкой снова.
"""

from __future__ import annotations

import asyncio
import logging
import time
from collections.abc import Callable
from typing import TYPE_CHECKING

from ..clients.lidarr import LidarrClient
from ..config import Settings
from ..db import Store, now_ms
from ..models import RequestKind

if TYPE_CHECKING:
    from ..worker import Worker

log = logging.getLogger(__name__)

WANTED_USER = "lidarr"
DIRECT_ALBUM_SOURCES = ("yandex", "youtube")
START_DELAY_S = 60


class LidarrWantedSync:
    def __init__(
        self,
        store: Store,
        settings: Settings,
        lidarr: LidarrClient,
        worker: Worker,
        clock: Callable[[], float] = time.time,
    ) -> None:
        self._store = store
        self._settings = settings
        self._lidarr = lidarr
        self._worker = worker
        self._clock = clock

    async def run_forever(self) -> None:
        await asyncio.sleep(START_DELAY_S)
        while True:
            try:
                await self.sync()
            except Exception:  # noqa: BLE001 — Lidarr мог быть недоступен, попробуем позже
                log.exception("«Метки» Lidarr: ошибка синхронизации")
            await asyncio.sleep(self._settings.lidarr_wanted_interval_s)

    async def _usable(self) -> bool:
        registry = self._worker.registry
        for name in DIRECT_ALBUM_SOURCES:
            if registry.is_enabled(name) and (await registry.status(name)).available:
                return True
        return False

    async def sync(self) -> int:
        """Создать заявки для новых недостающих альбомов. Возвращает число новых заявок."""
        if not await self._usable():
            log.debug("«Метки» Lidarr: нет доступных Яндекса и YouTube — альбомы остаются Lidarr")
            return 0
        retry_ms = self._settings.lidarr_wanted_retry_days * 24 * 3600 * 1000
        self._store.forget_failed_wanted(now_ms() - retry_ms)
        records = await self._lidarr.wanted_missing(self._settings.lidarr_wanted_batch * 5)
        seen = self._store.wanted_seen([int(r["id"]) for r in records if r.get("id") is not None])
        today = time.strftime("%Y-%m-%d", time.gmtime(self._clock()))
        created = 0
        for record in records:
            album_id = record.get("id")
            if album_id is None or int(album_id) in seen:
                continue
            release = (record.get("releaseDate") or "")[:10]
            if release and release > today:
                continue  # ещё не вышел
            artist = (record.get("artist") or {}).get("artistName")
            title = record.get("title")
            if not artist or not title:
                continue
            cover = next(
                (i.get("remoteUrl") for i in record.get("images") or [] if i.get("coverType") == "cover"), None
            )
            request, new = self._store.create(
                username=WANTED_USER,
                kind=RequestKind.ALBUM,
                artist=artist,
                title=None,
                album=title,
                duration_ms=None,
                cover_url=cover,
            )
            if new:
                request.state.update(
                    {
                        "wanted": True,
                        "lidarrAlbumId": int(album_id),
                        "lidarrArtistId": record.get("artistId"),
                        "excludeSources": ["lidarr"],
                    }
                )
                self._store.save(request)
                created += 1
                log.info("«Метки» Lidarr: %s — %s → цепочка источников", artist, title)
            self._store.mark_wanted(int(album_id), request.id)
            if created >= self._settings.lidarr_wanted_batch:
                break
        if created:
            self._worker.wake()
        return created
