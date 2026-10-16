"""Раздел «На удаление»: окончательное удаление треков с сервера и чёрный список, чтобы они не скачались снова.

Удаление трека:
1. исполнитель + название попадают в чёрный список bodrod (новые заявки на них отклоняются);
2. если файл принадлежит Lidarr — удаляется через Lidarr (`DELETE /trackfile`), чтобы Lidarr не считал его
   «потерянным»; когда в альбоме не остаётся файлов, мониторинг альбома снимается (иначе Lidarr/Soularr скачают
   альбом заново);
3. иначе файл удаляется с диска; опустевшая папка альбома (без аудио) и пустая папка исполнителя — тоже;
4. строка убирается из очереди slsk-worker;
5. запускается сканирование Navidrome.

Фоновая зачистка раз в `BLOCKLIST_SWEEP_S` ищет в библиотеке файлы из чёрного списка, которые появились снова
(альбом Lidarr, сборник beets, подборка Explo), и удаляет их тем же способом.
"""

from __future__ import annotations

import asyncio
import logging
import os
import shutil
from dataclasses import dataclass
from pathlib import Path

from .clients.lidarr import LidarrClient
from .clients.navidrome import NavidromeClient
from .config import Settings
from .db import Store, block_key
from .navidrome_db import NavidromeDb, NdSong, NdUser

log = logging.getLogger(__name__)

AUDIO_SUFFIXES = {".flac", ".mp3", ".m4a", ".ogg", ".opus", ".aac", ".wav", ".alac", ".wma", ".ape", ".wv", ".mp4"}


class CleanupError(Exception):
    pass


@dataclass
class DeleteResult:
    id: str
    ok: bool
    message: str


def _remap(path: str, src_root: str, dst_root: str) -> str | None:
    src = src_root.rstrip("/")
    if path != src and not path.startswith(src + "/"):
        return None
    return dst_root.rstrip("/") + path[len(src) :]


class CleanupService:
    def __init__(
        self,
        settings: Settings,
        store: Store,
        navidrome: NavidromeClient,
        db: NavidromeDb,
        lidarr: LidarrClient | None,
        lock: asyncio.Lock | None = None,
    ) -> None:
        self._settings = settings
        self._store = store
        self._navidrome = navidrome
        self._db = db
        self._lidarr = lidarr if settings.lidarr_music_dir else None
        self._lock = lock or asyncio.Lock()  # общий с обслуживанием библиотеки: файлы не трогают одновременно

    # region Пользователи и списки

    async def users(self) -> list[NdUser]:
        """Люди (без служебных учёток)."""
        users = await asyncio.to_thread(self._db.users)
        return [u for u in users if not self._settings.is_service_user(u.username)]

    async def is_admin(self, username: str) -> bool:
        users = await asyncio.to_thread(self._db.users)
        return any(u.username == username and u.is_admin for u in users)

    async def disliked(self, username: str | None) -> list[NdSong]:
        """`username=None` — треки, которые не нравятся всем людям; иначе — одному пользователю."""
        people = await self.users()
        if username is None:
            return await asyncio.to_thread(self._db.disliked, [u.id for u in people], True)
        user = next((u for u in people if u.username == username), None)
        if user is None:
            raise CleanupError(f"Пользователь «{username}» не найден")
        return await asyncio.to_thread(self._db.disliked, [user.id], False)

    # endregion

    # region Удаление

    async def delete(self, ids: list[str], username: str) -> list[DeleteResult]:
        async with self._lock:
            songs = {s.id: s for s in await asyncio.to_thread(self._db.songs, ids)}
            results: list[DeleteResult] = []
            for song_id in ids:
                song = songs.get(song_id)
                if song is None:
                    results.append(DeleteResult(song_id, False, "Трек не найден в библиотеке"))
                    continue
                self._store.block(song.artist, song.title, song.album, username)
                await asyncio.to_thread(self._remove_from_worker_queue, song.artist, song.title)
                try:
                    await self._delete_file(song)
                    log.info("%s удалил трек %s — %s (%s)", username, song.artist, song.title, song.path)
                    results.append(DeleteResult(song_id, True, "Удалён"))
                except Exception as e:  # noqa: BLE001 — один сбой не должен останавливать остальные
                    log.warning("Не удалось удалить %s: %s", song.path, e, exc_info=True)
                    results.append(DeleteResult(song_id, False, f"Не удалось удалить файл: {e}"))
            if any(r.ok for r in results):
                await self._scan()
            return results

    async def _delete_file(self, song: NdSong) -> None:
        local = _remap(song.path, self._settings.navidrome_music_dir, self._settings.music_dir)
        if local is None:
            raise CleanupError(f"Файл вне папки музыки: {song.path}")
        if self._lidarr is not None:
            await self._delete_via_lidarr(local)
        await asyncio.to_thread(self._remove_local, Path(local))

    async def _delete_via_lidarr(self, local: str) -> None:
        """Файл из библиотеки Lidarr удаляем через Lidarr. Файл, которого Lidarr не знает, — не его забота."""
        assert self._lidarr is not None and self._settings.lidarr_music_dir is not None
        lidarr_path = _remap(local, self._settings.music_dir, self._settings.lidarr_music_dir)
        if lidarr_path is None:
            return
        artist = next(
            (
                a
                for a in await self._lidarr.artists()
                if a.get("path") and lidarr_path.startswith(a["path"].rstrip("/") + "/")
            ),
            None,
        )
        if artist is None:
            return
        files = await self._lidarr.track_files(int(artist["id"]))
        track_file = next((f for f in files if f.get("path") == lidarr_path), None)
        if track_file is None:
            return
        await self._lidarr.delete_track_file(int(track_file["id"]))
        album_id = track_file.get("albumId")
        # Вариант «Б»: мониторинг снимаем, только когда из альбома удалены все треки.
        if album_id and await self._lidarr.album_file_count(int(album_id)) == 0:
            await self._lidarr.unmonitor_album(int(album_id))
            log.info("Lidarr: в альбоме %s не осталось файлов, мониторинг снят", album_id)

    def _remove_local(self, path: Path) -> None:
        root = Path(self._settings.music_dir).resolve()
        resolved = path.resolve()
        if root not in resolved.parents:
            raise CleanupError(f"Файл вне папки музыки: {path}")
        if resolved.exists():
            resolved.unlink()
        album_dir = resolved.parent
        if album_dir == root or not album_dir.is_dir():
            return
        entries = list(album_dir.iterdir())
        if any(e.is_dir() or e.suffix.lower() in AUDIO_SUFFIXES for e in entries):
            return
        shutil.rmtree(album_dir)  # остались только обложка, .lrc, .nfo — папка альбома больше не нужна
        artist_dir = album_dir.parent
        if artist_dir != root and artist_dir.is_dir() and not any(artist_dir.iterdir()):
            artist_dir.rmdir()

    def _remove_from_worker_queue(self, artist: str, title: str) -> None:
        path = self._settings.slsk_worker_queue
        if not path or not os.path.isfile(path):
            return
        key = block_key(artist, title)
        with open(path, encoding="utf-8") as f:
            lines = f.readlines()
        kept = [line for line in lines if _queue_key(line) != key]
        if len(kept) != len(lines):
            tmp = f"{path}.bodrod"
            with open(tmp, "w", encoding="utf-8") as f:
                f.writelines(kept)
            os.replace(tmp, path)
            log.info("Из очереди slsk-worker убрано: %s - %s", artist, title)

    async def _scan(self) -> None:
        try:
            await self._navidrome.start_scan()
        except Exception as e:  # noqa: BLE001 — Navidrome сам пересканирует по расписанию
            log.warning("Не удалось запустить сканирование Navidrome: %s", e)

    # endregion

    # region Фоновая зачистка

    async def sweep(self) -> int:
        """Удалить файлы из чёрного списка, которые снова появились в библиотеке. Возвращает число удалённых."""
        keys = self._store.blocked_keys()
        if not keys:
            return 0
        found = await asyncio.to_thread(self._find_blocked, keys)
        if not found:
            return 0
        removed = 0
        async with self._lock:
            for song in found:
                try:
                    await self._delete_file(song)
                    self._store.mark_removed(block_key(song.artist, song.title))
                    removed += 1
                    log.info("Чёрный список: снова удалён %s — %s (%s)", song.artist, song.title, song.path)
                except Exception as e:  # noqa: BLE001
                    log.warning("Чёрный список: не удалось удалить %s: %s", song.path, e)
        if removed:
            await self._scan()
        return removed

    def _find_blocked(self, keys: set[str]) -> list[NdSong]:
        return [s for s in self._db.iter_songs() if block_key(s.artist, s.title) in keys]

    async def run_forever(self) -> None:
        while True:
            try:
                await self.sweep()
            except Exception:  # noqa: BLE001 — цикл не должен умирать
                log.exception("Ошибка зачистки по чёрному списку")
            await asyncio.sleep(self._settings.blocklist_sweep_s)

    # endregion


def _queue_key(line: str) -> str | None:
    artist, sep, title = line.strip().partition(" - ")
    return block_key(artist, title) if sep else None
