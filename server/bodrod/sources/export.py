"""Массовая выгрузка из Яндекс Музыки (до конца подписки): «Мне нравится», мои плейлисты, дискографии артистов.

Отдельная очередь (`export_items`) с низким приоритетом: один трек за раз, пауза `EXPORT_DELAY_S`, уступает
заявкам из приложения, ждёт, пока Яндекс недоступен (нет Плюса) или выключен. Состояние в БД — после перезапуска
выгрузка продолжается с того же места. Уже имеющееся (положенное bodrod или найденное в Navidrome) пропускается.
Список артистов собирается из лайков; кого брать целиком, отмечает администратор. Плейлисты в конце записываются
файлами `.m3u8` в `<LIBRARY_DIR>/Плейлисты Яндекса/` — Navidrome импортирует их сам.
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
import os
import shutil
import time
from collections import Counter
from dataclasses import asdict, fields
from pathlib import Path
from typing import TYPE_CHECKING, Any

from ..clients.navidrome import NavidromeClient
from ..config import Settings
from ..db import Store
from ..metadata.discover import find_in_library
from ..models import ExportCounts, ExportStatus
from ..placement import sanitize_part
from .base import SourceMiss
from .placer import Placer
from .yandex import YandexSource, YTrack

if TYPE_CHECKING:
    from ..worker import Worker

log = logging.getLogger(__name__)

STATE_KEY = "export.state"
PLAYLISTS_KEY = "export.playlists"
IDLE_POLL_S = 30
WAIT_UNAVAILABLE_S = 60
WAIT_BUSY_S = 10
SCAN_EVERY = 50
PLAYLISTS_DIR = "Плейлисты Яндекса"
_YTRACK_FIELDS = {f.name for f in fields(YTrack)}


class YandexExport:
    def __init__(
        self,
        store: Store,
        settings: Settings,
        yandex: YandexSource,
        placer: Placer,
        worker: Worker,
        navidrome: NavidromeClient,
    ) -> None:
        self._store = store
        self._settings = settings
        self._yandex = yandex
        self._placer = placer
        self._worker = worker
        self._navidrome = navidrome
        self._wakeup = asyncio.Event()
        self._placed_since_scan = 0
        self._current: str | None = None

    # region Состояние

    def _state(self) -> dict[str, Any]:
        return self._store.get_value(STATE_KEY) or {"state": "idle"}

    def _set(self, **changes: Any) -> None:
        state = self._state() | changes | {"updatedAt": int(time.time() * 1000)}
        self._store.set_value(STATE_KEY, state)

    def status(self) -> ExportStatus:
        state = self._state()
        counts = self._store.export_counts()
        artists = self._store.export_artists()
        pending = counts.get("pending", 0)
        return ExportStatus(
            state=state.get("state", "idle"),
            message=state.get("message"),
            likes=state.get("likes", True),
            playlists=state.get("playlists", True),
            counts=ExportCounts(
                total=sum(counts.values()),
                done=counts.get("done", 0),
                skipped=counts.get("skipped", 0),
                failed=counts.get("failed", 0),
                pending=pending,
            ),
            artists_selected=sum(1 for a in artists if a["selected"]),
            artists_total=len(artists),
            current=self._current,
            updated_at=state.get("updatedAt"),
        )

    async def start(self, likes: bool, playlists: bool, artists: bool) -> None:
        """Начать или продолжить: собрать списки заново (повторы не добавляются) и качать."""
        retried = self._store.export_retry_failed()
        self._set(state="collecting", likes=likes, playlists=playlists, artists=artists, message=None)
        log.info(
            "Выгрузка из Яндекса: старт (лайки %s, плейлисты %s, артисты %s; повтор %s)",
            likes,
            playlists,
            artists,
            retried,
        )
        self._wakeup.set()

    def pause(self) -> None:
        if self._state().get("state") in ("collecting", "running"):
            self._set(state="paused", message="Пауза")
            log.info("Выгрузка из Яндекса: пауза")

    def select_artists(self, selected: list[str]) -> None:
        self._store.export_select_artists(selected)
        self._wakeup.set()

    async def refresh_artists(self) -> int:
        """Собрать артистов из «Мне нравится» (без скачивания) — для выбора, кого брать целиком."""
        tracks = await self._yandex.api.liked_tracks()
        self._tally_artists(tracks)
        return len(self._store.export_artists())

    # endregion

    async def run_forever(self) -> None:
        while True:
            try:
                delay = await self._tick()
            except Exception as e:  # noqa: BLE001 — сбой одного шага не останавливает выгрузку
                log.exception("Выгрузка из Яндекса: ошибка")
                self._set(message=f"Ошибка: {e}")
                delay = WAIT_UNAVAILABLE_S
            self._wakeup.clear()
            with contextlib.suppress(TimeoutError):
                await asyncio.wait_for(self._wakeup.wait(), timeout=delay)

    async def _tick(self) -> float:
        state = self._state()
        if state.get("state") not in ("collecting", "running"):
            return IDLE_POLL_S
        registry = self._worker.registry
        if not registry.is_enabled("yandex"):
            self._set(message="Ждём: «Яндекс: скачивание» выключен")
            return WAIT_UNAVAILABLE_S
        status = await self._yandex.status()
        if not status.available:
            self._set(message=f"Ждём: Яндекс недоступен — {status.reason}")
            return WAIT_UNAVAILABLE_S
        if state.get("state") == "collecting":
            await self._collect(state)
            self._set(state="running", message=None)
            return 0
        if state.get("artists", True):
            await self._collect_artists()
        if self._worker.direct_busy("yandex"):
            return WAIT_BUSY_S  # заявки из приложения важнее
        item = self._store.export_next()
        if item is None:
            await self._finish()
            return IDLE_POLL_S
        await self._process(item)
        return self._settings.export_delay_s

    # region Сбор очереди

    def _add(self, track: YTrack, origin: str) -> bool:
        return self._store.export_add(track.id, track.artist, track.title, track.album, origin, asdict(track))

    def _tally_artists(self, tracks: list[YTrack]) -> None:
        counts: Counter[str] = Counter()
        names: dict[str, str] = {}
        for t in tracks:
            for artist_id, name in zip(t.artist_ids, t.artists, strict=False):
                counts[artist_id] += 1
                names[artist_id] = name
        for artist_id, liked in counts.items():
            self._store.export_upsert_artist(artist_id, names[artist_id], liked)

    async def _collect(self, state: dict[str, Any]) -> None:
        added = 0
        if state.get("likes", True):
            self._set(message="Собираем «Мне нравится»")
            tracks = await self._yandex.api.liked_tracks()
            self._tally_artists(tracks)
            added += sum(self._add(t, "likes") for t in tracks)
        if state.get("playlists", True):
            self._set(message="Собираем плейлисты")
            playlists: dict[str, list[str]] = {}
            for title, tracks in await self._yandex.api.playlists():
                playlists[title] = [t.id for t in tracks]
                added += sum(self._add(t, f"playlist:{title}") for t in tracks)
            self._store.set_value(PLAYLISTS_KEY, playlists)
        log.info("Выгрузка из Яндекса: в очередь добавлено %s треков", added)

    async def _collect_artists(self) -> None:
        for artist in self._store.export_artists():
            if not artist["selected"] or artist["collected"]:
                continue
            self._set(message=f"Собираем дискографию: {artist['name']}")
            added = 0
            for album in await self._yandex.api.artist_albums(artist["artist_id"]):
                for t in await self._yandex.api.album_tracks(album.id):
                    added += self._add(t, f"artist:{artist['name']}")
            self._store.export_mark_collected(artist["artist_id"])
            log.info("Выгрузка из Яндекса: %s — %s треков в очередь", artist["name"], added)
            self._set(message=None)

    # endregion

    async def _process(self, item: dict[str, Any]) -> None:
        meta = {k: v for k, v in (item.get("meta") or {}).items() if k in _YTRACK_FIELDS}
        track = YTrack(**meta) if meta else YTrack(id=item["track_id"], title=item["title"], artists=[item["artist"]])
        self._current = f"{track.artist} — {track.title}"
        if any(Path(p).exists() for p in self._store.source_file_paths("yandex", track.id)):
            self._store.export_set(item["id"], "skipped", "Уже скачан")
            return
        if await find_in_library(self._navidrome, track.artist, track.title):
            self._store.export_set(item["id"], "skipped", "Есть в библиотеке")
            return
        workdir = Path(self._settings.staging_dir) / "export"
        try:
            file = await self._yandex.download_track(track, workdir)
            dest = await self._placer.place(file, "yandex", None)
        except SourceMiss as e:
            self._store.export_set(item["id"], "failed", e.reason)
            return
        except Exception as e:  # noqa: BLE001 — один трек не останавливает выгрузку
            log.warning("Выгрузка из Яндекса: %s — %s", self._current, e)
            self._store.export_set(item["id"], "failed", str(e))
            return
        finally:
            with contextlib.suppress(OSError):
                if workdir.exists() and not any(workdir.iterdir()):
                    workdir.rmdir()
        self._store.export_set(item["id"], "done", str(dest))
        self._placed_since_scan += 1
        if self._placed_since_scan >= SCAN_EVERY:
            self._placed_since_scan = 0
            await self._scan()

    async def _finish(self) -> None:
        self._current = None
        await asyncio.to_thread(self._write_playlists)
        await self._scan()
        counts = self._store.export_counts()
        self._set(
            state="finished",
            message=f"Готово: скачано {counts.get('done', 0)}, уже было {counts.get('skipped', 0)}, "
            f"не удалось {counts.get('failed', 0)}",
        )
        log.info("Выгрузка из Яндекса завершена: %s", counts)

    def _write_playlists(self) -> None:
        library = self._settings.library_dir
        playlists: dict[str, list[str]] = self._store.get_value(PLAYLISTS_KEY) or {}
        if not library or not playlists:
            return
        folder = Path(library) / PLAYLISTS_DIR
        folder.mkdir(parents=True, exist_ok=True)
        for title, ids in playlists.items():
            lines = ["#EXTM3U"]
            for track_id in ids:
                paths = [p for p in self._store.source_file_paths("yandex", track_id) if os.path.exists(p)]
                if paths:
                    lines.append(os.path.relpath(paths[0], folder))
            if len(lines) > 1:
                target = folder / f"{sanitize_part(title)}.m3u8"
                tmp = target.with_name(f".{target.name}.part")
                tmp.write_text("\n".join(lines) + "\n", encoding="utf-8")
                shutil.move(tmp, target)

    async def _scan(self) -> None:
        try:
            await self._navidrome.start_scan()
        except Exception as e:  # noqa: BLE001
            log.warning("Не удалось запустить сканирование Navidrome: %s", e)
