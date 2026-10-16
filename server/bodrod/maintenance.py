"""Регламент «Обслуживание библиотеки»: раз в сутки проходит по всем трекам Navidrome и наводит порядок.

Что делает (по порядку):
1. **Короткие файлы** — короче `MAINTENANCE_MIN_TRACK_S` секунд (кроме коротких дорожек внутри альбомов), обрезанные
   (размер файла намного меньше, чем должен быть при его битрейте и длительности) и заметно короче полной копии
   того же трека или длительности из каталога (для скачанных через bodrod) — в карантин.
2. **Дубли треков** — один исполнитель, одно название (без «Remastered», «Deluxe», года и feat), длительность ±3 с.
   В одном альбоме остаётся лучшая копия (lossless, затем отметки пользователей, битрейт, размер); одиночные
   копии («Singles», папка с одним-двумя треками) убираются, если трек есть в альбоме.
3. **Дубли альбомов** — один альбом в нескольких папках: треки переносятся в папку, где их больше.
4. **Теги и обложки** — у треков без исполнителя, названия или альбома недостающие теги дописываются по каталогу
   (Яндекс Музыка → Deezer), если совпадение надёжное (исполнитель, название и длительность). Папке альбома
   без обложки (нет картинки в папке и встроенной в файлы) добавляется `cover.jpg` из каталога.
   Спорное — только в отчёт.

Файлы из папок Lidarr (если задан `LIDARR_MUSIC_DIR`) не переносятся и не убираются: Lidarr скачал бы их снова.
Убранное лежит в карантине `QUARANTINE_DIR` `QUARANTINE_DAYS` дней, его можно вернуть из приложения.
Чтобы лайки не терялись, bodrod запоминает, какой файл занял место убранного (`replacements`): приложение
переносит лайк на оставшуюся копию (`POST /api/v1/library/check`).
"""

from __future__ import annotations

import asyncio
import contextlib
import datetime as dt
import logging
import re
import shutil
import time
from collections import defaultdict
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .cleanup import AUDIO_SUFFIXES, _remap
from .clients.lidarr import LidarrClient
from .clients.navidrome import NavidromeClient
from .config import Settings
from .db import Store, now_ms
from .metadata.providers import MetadataProvider
from .models import TrackCandidate
from .navidrome_db import NavidromeDb, NdSong
from .text import normalize, similarity

log = logging.getLogger(__name__)

KIND_SHORT = "short"
KIND_DUPLICATE = "duplicate"
KIND_ALBUM = "album"
KIND_TAGS = "tags"
KIND_COVER = "cover"

LOSSLESS = {"flac", "alac", "wav", "ape", "wv", "aiff", "aif", "dsf"}
IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp"}
UNKNOWN_ARTISTS = {"", "unknown artist", "unknown", "неизвестный исполнитель"}
UNKNOWN_ALBUMS = {"", "unknown album", "неизвестный альбом"}
LOOSE_ALBUMS = {"", "singles", "single", "синглы", "unknown album", "треки"}

DUP_DURATION_S = 3.0
TRUNCATED_SHARE = 0.6
"""Копия короче полной на 40% и больше — обрезана."""
RECHECK_MS = 30 * 24 * 3600 * 1000
ALBUM_SHORT_TRACKS = 4
"""Если в папке столько треков с номерами, короткая дорожка — скорее интро или скит, а не обрезанный файл."""

# В скобках — пометки издания, которые не делают трек другим: (Remastered 2011), [Deluxe Edition], (2005).
_EDITION = re.compile(
    r"[\(\[][^\)\]]*\b(?:remaster\w*|deluxe|edition|bonus|explicit|clean|album version|digital|expanded|"
    r"anniversary|\d{4})\b[^\)\]]*[\)\]]",
    re.IGNORECASE,
)
_FEAT = re.compile(r"[\(\[]?\s*\b(?:feat|ft|featuring)\b\.?.*$", re.IGNORECASE)
_TRACK_PREFIX = re.compile(r"^\s*(?:\d{1,3}(?:[-.]\d{1,3})?)\s*(?:[-._)]\s*|\s+)")
_DISC_DIR = re.compile(r"^(?:cd|disc|disk|диск)\s*\d+$", re.IGNORECASE)


def title_key(title: str | None) -> str:
    """Название для поиска дублей: без пометок издания и feat, но «(Live)» и «(Remix)» остаются."""
    text = _EDITION.sub(" ", title or "")
    text = _FEAT.sub(" ", text)
    return normalize(text, strip_extras=False)


def album_key(album: str | None) -> str:
    return normalize(_EDITION.sub(" ", album or ""), strip_extras=False)


@dataclass
class LibraryFile:
    song: NdSong
    local: Path
    pinned: bool
    marks: int = 0

    @property
    def lossless(self) -> bool:
        return (self.song.suffix or self.local.suffix.lstrip(".")).lower() in LOSSLESS

    @property
    def folder(self) -> Path:
        return self.local.parent

    def rank(self) -> tuple[int, int, int, int]:
        return (int(self.lossless), int(self.marks > 0), self.song.bit_rate or 0, self.song.size or 0)

    def quality(self) -> str:
        suffix = (self.song.suffix or self.local.suffix.lstrip(".")).upper()
        return f"{suffix} {self.song.bit_rate} кбит/с" if self.song.bit_rate and not self.lossless else suffix


@dataclass
class RunContext:
    run_id: int
    dry_run: bool
    stats: dict[str, int] = field(default_factory=lambda: defaultdict(int))
    changed: bool = False
    catalog_budget: int = 0
    """Сколько аудиофайлов в каждой папке (по данным Navidrome) — отличать альбом от одиночного трека."""
    folder_counts: dict[Path, int] = field(default_factory=dict)


CoverFetcher = Callable[[str], Awaitable[tuple[bytes, str] | None]]


class MaintenanceError(Exception):
    pass


class MaintenanceService:
    def __init__(
        self,
        settings: Settings,
        store: Store,
        navidrome: NavidromeClient,
        db: NavidromeDb,
        lidarr: LidarrClient | None,
        catalogs: list[MetadataProvider],
        cover_fetcher: CoverFetcher | None,
        lock: asyncio.Lock | None = None,
    ) -> None:
        self._settings = settings
        self._store = store
        self._navidrome = navidrome
        self._db = db
        self._lidarr = lidarr if settings.lidarr_music_dir else None
        self._catalogs = catalogs
        self._fetch_cover = cover_fetcher
        self._lock = lock or asyncio.Lock()
        self._task: asyncio.Task[Any] | None = None
        self.running = False
        store.fail_unfinished_runs()

    @property
    def music_root(self) -> Path:
        return Path(self._settings.music_dir)

    @property
    def quarantine_root(self) -> Path:
        return Path(self._settings.quarantine_path)

    # region Расписание

    def next_run_at(self, now: float | None = None) -> float:
        """Ближайшее время запуска (секунды epoch, местное время контейнера)."""
        current = dt.datetime.fromtimestamp(now if now is not None else time.time())
        hours, _, minutes = self._settings.maintenance_at.partition(":")
        target = current.replace(hour=int(hours or 4), minute=int(minutes or 0), second=0, microsecond=0)
        if target <= current:
            target += dt.timedelta(days=1)
        return target.timestamp()

    async def run_forever(self) -> None:
        while True:
            await asyncio.sleep(max(1.0, self.next_run_at() - time.time()))
            try:
                await self.run("schedule")
            except Exception:  # noqa: BLE001 — цикл не должен умирать
                log.exception("Ошибка обслуживания библиотеки")

    def start(self, trigger: str = "manual") -> bool:
        """Запустить в фоне (кнопка в приложении). `False` — уже идёт."""
        if self.running:
            return False
        self.running = True  # сразу, чтобы второй запрос получил 409
        self._task = asyncio.create_task(self.run(trigger))
        return True

    # endregion

    async def run(self, trigger: str = "schedule") -> int:
        async with self._lock:
            self.running = True
            run = self._store.start_run(trigger, self._settings.maintenance_dry_run)
            ctx = RunContext(run.id, run.dry_run, catalog_budget=self._settings.maintenance_catalog_limit)
            log.info("Обслуживание библиотеки: запуск %s (%s)", run.id, trigger)
            try:
                await asyncio.to_thread(self._purge_quarantine, ctx)
                files = await self._load()
                ctx.stats["songs"] = len(files)
                ctx.folder_counts = _folder_counts(files)
                files = await self._short_files(ctx, files)
                files = await self._duplicates(ctx, files)
                await self._albums(ctx, files)
                await self._tags_and_covers(ctx, files)
                if ctx.changed and not ctx.dry_run:
                    await self._scan()
                self._store.finish_run(run.id, "completed", dict(ctx.stats))
                log.info("Обслуживание библиотеки: готово, %s", dict(ctx.stats))
            except Exception as e:
                log.exception("Обслуживание библиотеки: ошибка")
                self._store.finish_run(run.id, "failed", dict(ctx.stats), str(e))
                if ctx.changed and not ctx.dry_run:
                    await self._scan()
            finally:
                self.running = False
            return run.id

    # region Загрузка

    async def _load(self) -> list[LibraryFile]:
        pinned_dirs = await self._lidarr_dirs()
        songs = await asyncio.to_thread(lambda: list(self._db.iter_songs()))
        marks = await asyncio.to_thread(self._db.annotation_counts)
        files: list[LibraryFile] = []
        for song in songs:
            local = _remap(song.path, self._settings.navidrome_music_dir, self._settings.music_dir)
            if local is None:
                continue
            path = Path(local)
            if self._in_quarantine(path) or not path.is_file():
                continue
            pinned = any(path.is_relative_to(d) for d in pinned_dirs)
            files.append(LibraryFile(song, path, pinned, marks.get(song.id, 0)))
        return files

    async def _lidarr_dirs(self) -> list[Path]:
        if self._lidarr is None or not self._settings.lidarr_music_dir:
            return []
        try:
            artists = await self._lidarr.artists()
        except Exception as e:  # noqa: BLE001 — без Lidarr не трогаем ничего, что может быть его
            raise MaintenanceError(f"Lidarr недоступен, обслуживание отложено: {e}") from e
        dirs = []
        for artist in artists:
            local = _remap(artist.get("path") or "", self._settings.lidarr_music_dir, self._settings.music_dir)
            if local:
                dirs.append(Path(local))
        return dirs

    def _in_quarantine(self, path: Path) -> bool:
        return path.is_relative_to(self.quarantine_root)

    # endregion

    # region Короткие и обрезанные файлы

    async def _short_files(self, ctx: RunContext, files: list[LibraryFile]) -> list[LibraryFile]:
        expected = await asyncio.to_thread(self._store.track_durations)
        full: dict[tuple[str, str], list[tuple[float, str]]] = defaultdict(list)
        for f in files:
            key = (normalize(f.song.artist), title_key(f.song.title))
            full[key].append((f.song.duration_s or 0.0, album_key(f.song.album)))
        kept: list[LibraryFile] = []
        for f in files:
            reason = self._short_reason(ctx, f, expected, full)
            if reason is None:
                kept.append(f)
                continue
            if f.pinned:
                if self._report_once(f"short:{f.local}", _signature(f.local)):
                    ctx.stats["short_found"] += 1
                    self._action(ctx, KIND_SHORT, False, f"{reason}. Файл из папки Lidarr — не трогаем", f)
                kept.append(f)
                continue
            quarantine_id = await self._quarantine(ctx, f, reason)
            ctx.stats["short_removed"] += 1
            self._action(ctx, KIND_SHORT, True, f"{reason}. Убран в карантин", f, quarantine_id)
        return kept

    def _short_reason(
        self,
        ctx: RunContext,
        f: LibraryFile,
        expected: dict[str, int],
        full: dict[tuple[str, str], list[tuple[float, str]]],
    ) -> str | None:
        duration = f.song.duration_s or 0.0
        if duration <= 0:
            return None
        album_like = f.song.track_number and ctx.folder_counts.get(f.folder, 0) >= ALBUM_SHORT_TRACKS
        if duration < self._settings.maintenance_min_track_s and not album_like:
            return f"Подозрительно короткий: {_mmss(duration)}"
        if duration >= 30 and f.song.size and f.song.bit_rate:
            bytes_expected = f.song.bit_rate * 125 * duration
            if not f.lossless and f.song.size < 0.5 * bytes_expected:
                return f"Файл обрезан: {_mb(f.song.size)} при длительности {_mmss(duration)} и {f.song.bit_rate} кбит/с"
            if f.lossless and f.song.bit_rate < 160:
                return f"Файл обрезан: {f.song.bit_rate} кбит/с для {f.quality()} — меньше, чем бывает у lossless"
        catalog_ms = expected.get(f.song.id)
        if catalog_ms and catalog_ms >= 60_000 and duration * 1000 < TRUNCATED_SHARE * catalog_ms:
            return f"Короче, чем в каталоге: {_mmss(duration)} вместо {_mmss(catalog_ms / 1000)}"
        # Полная копия считается только в том же альбоме или для сингла: «Intro» на разных альбомах — разные треки.
        album = album_key(f.song.album)
        loose = self._is_loose(ctx, f)
        copies = full.get((normalize(f.song.artist), title_key(f.song.title)), [])
        longest = max((d for d, a in copies if loose or a == album), default=0.0)
        if longest >= 60 and duration < TRUNCATED_SHARE * longest:
            return f"Короче полной копии того же трека: {_mmss(duration)} вместо {_mmss(longest)}"
        return None

    # endregion

    # region Дубли треков

    async def _duplicates(self, ctx: RunContext, files: list[LibraryFile]) -> list[LibraryFile]:
        groups: dict[tuple[str, str], list[LibraryFile]] = defaultdict(list)
        for f in files:
            if f.song.duration_s:
                groups[(normalize(f.song.artist), title_key(f.song.title))].append(f)
        removed: set[Path] = set()
        for (artist, title), group in groups.items():
            if not artist or not title or len(group) < 2:
                continue
            for cluster in _by_duration(group):
                for drop, keep in self._resolve_cluster(ctx, cluster):
                    details = (
                        f"Дубль: оставлена копия «{self._rel(keep.local)}» ({keep.quality()}), "
                        f"убрана эта ({drop.quality()})"
                    )
                    quarantine_id = await self._quarantine(ctx, drop, details)
                    self._replace(ctx, drop, keep.local, quarantine_id)
                    removed.add(drop.local)
                    ctx.stats["duplicates_removed"] += 1
                    self._action(ctx, KIND_DUPLICATE, True, details, drop, quarantine_id)
        return [f for f in files if f.local not in removed]

    def _resolve_cluster(self, ctx: RunContext, cluster: list[LibraryFile]) -> list[tuple[LibraryFile, LibraryFile]]:
        """Пары (убрать, оставить). Копии в разных альбомах (сборники) не трогаем."""
        if len({f.local for f in cluster}) < 2:
            return []
        loose = [f for f in cluster if self._is_loose(ctx, f)]
        albums: dict[str, list[LibraryFile]] = defaultdict(list)
        for f in cluster:
            if f not in loose:
                albums[album_key(f.song.album)].append(f)
        pairs: list[tuple[LibraryFile, LibraryFile]] = []
        best_album_copy: LibraryFile | None = None
        for copies in albums.values():
            keep = _best(copies)
            if best_album_copy is None or keep.rank() > best_album_copy.rank():
                best_album_copy = keep
            pairs += [(f, keep) for f in copies if f is not keep and not f.pinned]
        if loose:
            if best_album_copy is not None:
                keep = best_album_copy
                # Lossless-сингл не убираем ради lossy-копии в альбоме.
                pairs += [(f, keep) for f in loose if not f.pinned and not (f.lossless and not keep.lossless)]
            else:
                keep = _best(loose)
                pairs += [(f, keep) for f in loose if f is not keep and not f.pinned]
        return pairs

    def _is_loose(self, ctx: RunContext, f: LibraryFile) -> bool:
        """Одиночный трек, а не часть альбома: «Singles», без альбома, папка с одним-двумя треками."""
        album = album_key(f.song.album)
        return album in LOOSE_ALBUMS or album == title_key(f.song.title) or ctx.folder_counts.get(f.folder, 0) <= 2

    # endregion

    # region Дубли альбомов

    async def _albums(self, ctx: RunContext, files: list[LibraryFile]) -> None:
        groups: dict[tuple[str, str], list[LibraryFile]] = defaultdict(list)
        for f in files:
            album = album_key(f.song.album)
            if album in LOOSE_ALBUMS:
                continue
            groups[(normalize(f.song.album_artist or f.song.artist), album)].append(f)
        for group in groups.values():
            folders: dict[Path, list[LibraryFile]] = defaultdict(list)
            for f in group:
                folders[f.folder].append(f)
            if len(folders) < 2 or self._is_multi_disc(folders):
                continue
            if any(f.pinned for f in group):
                continue  # в папки Lidarr не переносим, из них — тоже
            target = max(folders, key=lambda d: (ctx.folder_counts.get(d, 0), sum(f.rank()[0] for f in folders[d])))
            moved = 0
            sources = []
            for folder, items in folders.items():
                if folder == target:
                    continue
                sources.append(self._rel(folder))
                for f in items:
                    dest = await asyncio.to_thread(self._move_into, ctx, f.local, target)
                    if dest is not None:
                        self._replace(ctx, f, dest, None)
                        moved += 1
                if not ctx.dry_run:
                    await asyncio.to_thread(self._move_leftovers, folder, target)
            if moved:
                first = group[0].song
                ctx.stats["albums_merged"] += 1
                ctx.changed = True
                origin = ", ".join(f"«{s}»" for s in sources)
                details = f"Альбом собран в одну папку «{self._rel(target)}»: перенесено треков — {moved} из {origin}"
                self._store.add_action(
                    ctx.run_id,
                    KIND_ALBUM,
                    not ctx.dry_run,
                    _dry(ctx, details),
                    artist=first.album_artist or first.artist,
                    album=first.album,
                    path=self._rel(target),
                )

    @staticmethod
    def _is_multi_disc(folders: dict[Path, list[LibraryFile]]) -> bool:
        """Альбом из нескольких дисков в папках CD1/CD2 или с разными номерами дисков — не дубль."""
        if all(_DISC_DIR.match(d.name) for d in folders):
            return True
        discs = [{f.song.disc_number for f in items if f.song.disc_number} for items in folders.values()]
        if all(discs):
            return all(a.isdisjoint(b) for i, a in enumerate(discs) for b in discs[i + 1 :])
        return False

    def _move_into(self, ctx: RunContext, source: Path, target: Path) -> Path | None:
        dest = target / source.name
        n = 2
        while dest.exists():
            if dest.stat().st_size == source.stat().st_size:
                return None  # тот же файл уже на месте; останется дублем до следующего запуска
            dest = target / f"{source.stem} ({n}){source.suffix}"
            n += 1
        if not ctx.dry_run:
            shutil.move(str(source), str(dest))
            ctx.changed = True
        return dest

    def _move_leftovers(self, folder: Path, target: Path) -> None:
        """После переноса: обложку — в новую папку, если там её нет; папку без аудио — удалить."""
        if not folder.is_dir():
            return
        entries = list(folder.iterdir())
        if any(e.is_dir() or e.suffix.lower() in AUDIO_SUFFIXES for e in entries):
            return
        if not _has_image(target):
            for image in (e for e in entries if e.suffix.lower() in IMAGE_SUFFIXES):
                with contextlib.suppress(OSError):
                    shutil.move(str(image), str(target / image.name))
        shutil.rmtree(folder, ignore_errors=True)
        parent = folder.parent
        if parent != self.music_root and parent.is_dir() and not any(parent.iterdir()):
            parent.rmdir()

    # endregion

    # region Теги и обложки

    async def _tags_and_covers(self, ctx: RunContext, files: list[LibraryFile]) -> None:
        for f in files:
            if _tag_problem(f.song) is not None and f.local.exists():
                await self._fix_tags(ctx, f)
        folders: dict[Path, list[LibraryFile]] = defaultdict(list)
        for f in files:
            if f.local.exists():
                folders[f.folder].append(f)
        for folder, items in folders.items():
            await self._fix_cover(ctx, folder, items)

    async def _fix_tags(self, ctx: RunContext, f: LibraryFile) -> None:
        key = f"tags:{f.local}"
        signature = _signature(f.local)
        if self._recently_checked(key, signature):
            return
        current = await asyncio.to_thread(read_tags, f.local)
        if current is None:
            self._store.set_checked(key, signature)
            ctx.stats["tags_problems"] += 1
            self._action(ctx, KIND_TAGS, False, "Не удалось прочитать файл — возможно, он повреждён", f)
            return
        missing = [k for k in ("artist", "title", "album") if not current.get(k)]
        if not missing:
            self._store.set_checked(key, signature)  # теги на месте: Navidrome просто взял название из имени файла
            return
        guess = self._guess(f, current)
        match = await self._catalog_match(ctx, guess["artist"], guess["title"], f.song.duration_s)
        names = ", ".join(TAG_NAMES[k] for k in missing)
        if match is None:
            self._store.set_checked(key, signature)
            ctx.stats["tags_problems"] += 1
            self._action(ctx, KIND_TAGS, False, f"Нет тегов ({names}); надёжного совпадения в каталоге нет", f)
            return
        values = {"artist": match.artist, "title": match.title, "album": match.album or guess["album"]}
        update = {k: values[k] for k in missing if values.get(k)}
        if not update:
            return
        if not f.pinned and not ctx.dry_run:
            await asyncio.to_thread(write_missing_tags, f.local, update)
            ctx.changed = True
        written = "; ".join(f"{TAG_NAMES[k]}: {v}" for k, v in update.items())
        if f.pinned:
            self._store.set_checked(key, signature)
            ctx.stats["tags_problems"] += 1
            self._action(ctx, KIND_TAGS, False, f"Нет тегов ({names}). В каталоге: {written}. Папка Lidarr", f)
            return
        ctx.stats["tags_fixed"] += 1
        self._action(ctx, KIND_TAGS, True, f"Дописаны теги по каталогу — {written}", f)

    async def _fix_cover(self, ctx: RunContext, folder: Path, items: list[LibraryFile]) -> None:
        if ctx.folder_counts.get(folder, 0) == 0 or await asyncio.to_thread(_has_image, folder):
            return
        if any(f.song.has_cover_art for f in items):
            return
        if all(f.song.has_cover_art is None for f in items) and await asyncio.to_thread(
            has_embedded_cover, items[0].local
        ):
            return
        key = f"cover:{folder}"
        signature = str(len(items))
        if self._recently_checked(key, signature):
            return
        sample = items[0]
        song = sample.song
        # Теги могли только что дописаться — берём их из файла, а не из базы Navidrome.
        tags = await asyncio.to_thread(read_tags, sample.local) or {}
        artist = tags.get("artist") or song.album_artist or song.artist
        album = tags.get("album") or song.album or folder.name
        match = await self._catalog_match(ctx, artist, tags.get("title") or song.title, song.duration_s)
        if match is None or not match.cover_url or similarity(match.album, album) < 0.8:
            self._store.set_checked(key, signature)
            ctx.stats["covers_missing"] += 1
            self._store.add_action(
                ctx.run_id,
                KIND_COVER,
                False,
                "Нет обложки; в каталоге не найдена",
                artist=artist,
                album=album,
                path=self._rel(folder),
            )
            return
        if sample.pinned:
            self._store.set_checked(key, signature)
            ctx.stats["covers_missing"] += 1
            self._store.add_action(
                ctx.run_id,
                KIND_COVER,
                False,
                "Нет обложки; папка Lidarr — не трогаем",
                artist=artist,
                album=album,
                path=self._rel(folder),
            )
            return
        cover = None
        if self._fetch_cover is not None and not ctx.dry_run:
            try:
                cover = await self._fetch_cover(_large_cover(match.cover_url))
            except Exception as e:  # noqa: BLE001 — попробуем в следующий раз
                log.warning("Не удалось скачать обложку %s: %s", match.cover_url, e)
                return
            if cover is None:
                return
            data, mime = cover
            name = "cover.png" if mime == "image/png" else "cover.jpg"
            await asyncio.to_thread((folder / name).write_bytes, data)
            ctx.changed = True
        ctx.stats["covers_added"] += 1
        self._store.add_action(
            ctx.run_id,
            KIND_COVER,
            not ctx.dry_run,
            _dry(ctx, f"Добавлена обложка из каталога ({match.source})"),
            artist=artist,
            album=album,
            path=self._rel(folder),
        )

    def _guess(self, f: LibraryFile, current: dict[str, str]) -> dict[str, str]:
        """Исполнитель/название/альбом из тегов, а чего нет — из пути `Исполнитель/Альбом/01 Исполнитель - Название`."""
        song = f.song
        inside = f.folder != self.music_root and f.folder.parent != self.music_root
        folder_album = f.folder.name if inside else ""
        folder_artist = f.folder.parent.name if inside else ""
        artist = current.get("artist") or (song.album_artist or "") or folder_artist
        title = current.get("title") or _TRACK_PREFIX.sub("", f.local.stem)
        if artist and normalize(title).startswith(normalize(artist) + " ") and " - " in title:
            title = title.split(" - ", 1)[1]
        album = current.get("album") or folder_album
        return {"artist": artist.strip(), "title": title.strip(), "album": album.strip()}

    async def _catalog_match(
        self, ctx: RunContext, artist: str, title: str, duration_s: float | None
    ) -> TrackCandidate | None:
        """Надёжное совпадение в каталоге: исполнитель и название похожи, длительность ±3 с."""
        if not artist or not title or ctx.catalog_budget <= 0:
            return None
        for catalog in self._catalogs:
            ctx.catalog_budget -= 1
            try:
                hits = await catalog.search(f"{artist} {title}")
            except Exception as e:  # noqa: BLE001 — каталог недоступен: следующий
                log.debug("Каталог %s не ответил: %s", catalog.name, e)
                continue
            for track in hits.tracks:
                main = track.artist.split(",")[0]
                artist_ok = max(similarity(track.artist, artist), similarity(main, artist)) >= 0.85
                title_ok = similarity(track.title, title) >= 0.85
                duration_ok = (
                    duration_s is None
                    or track.duration_ms is None
                    or abs(track.duration_ms / 1000 - duration_s) <= DUP_DURATION_S
                )
                if artist_ok and title_ok and duration_ok:
                    return track
            if ctx.catalog_budget <= 0:
                break
        return None

    def _report_once(self, key: str, signature: str) -> bool:
        """Неисправимое не повторяется в отчёте каждую ночь: раз в 30 дней или после изменения файла."""
        if self._recently_checked(key, signature):
            return False
        self._store.set_checked(key, signature)
        return True

    def _recently_checked(self, key: str, signature: str) -> bool:
        checked = self._store.checked(key)
        return checked is not None and checked[0] == signature and now_ms() - checked[1] < RECHECK_MS

    # endregion

    # region Карантин

    async def _quarantine(self, ctx: RunContext, f: LibraryFile, reason: str) -> int | None:
        if ctx.dry_run:
            return None
        rel = f.local.relative_to(self.music_root)
        stored = self.quarantine_root / str(ctx.run_id) / rel
        size = f.song.size
        await asyncio.to_thread(self._move_to_quarantine, f.local, stored)
        ctx.changed = True
        return self._store.add_quarantine(str(f.local), str(stored), reason, size)

    def _move_to_quarantine(self, source: Path, stored: Path) -> None:
        root = self.quarantine_root
        root.mkdir(parents=True, exist_ok=True)
        marker = root / ".ndignore"  # Navidrome не сканирует папку с этим файлом
        if not marker.exists():
            marker.write_text("*\n", encoding="utf-8")
        stored.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(source), str(stored))
        self._remove_empty_album(source.parent)

    def _remove_empty_album(self, folder: Path) -> None:
        """Папка без аудио (остались обложка, .lrc) больше не нужна; пустая папка исполнителя — тоже."""
        root = self.music_root.resolve()
        resolved = folder.resolve()
        if resolved == root or root not in resolved.parents or not resolved.is_dir():
            return
        entries = list(resolved.iterdir())
        if any(e.is_dir() or e.suffix.lower() in AUDIO_SUFFIXES for e in entries):
            return
        shutil.rmtree(resolved, ignore_errors=True)
        parent = resolved.parent
        if parent != root and parent.is_dir() and not any(parent.iterdir()):
            parent.rmdir()

    async def restore(self, entry_id: int) -> None:
        entry = self._store.get_quarantine(entry_id)
        if entry is None or entry.purged_at is not None:
            raise MaintenanceError("Файл уже удалён из карантина")
        if entry.restored_at is not None:
            raise MaintenanceError("Файл уже возвращён")
        async with self._lock:
            await asyncio.to_thread(_restore_file, Path(entry.stored_path), Path(entry.original_path))
            self._store.mark_restored(entry_id)
        log.info("Из карантина возвращён %s", entry.original_path)
        await self._scan()

    def _purge_quarantine(self, ctx: RunContext) -> None:
        if ctx.dry_run:
            return
        before = now_ms() - self._settings.quarantine_days * 24 * 3600 * 1000
        for entry in self._store.expired_quarantine(before):
            with contextlib.suppress(FileNotFoundError):
                Path(entry.stored_path).unlink()
            self._store.mark_purged(entry.id)
            ctx.stats["quarantine_purged"] += 1
        root = self.quarantine_root
        if root.is_dir():
            for path in sorted((p for p in root.rglob("*") if p.is_dir()), key=lambda p: len(p.parts), reverse=True):
                with contextlib.suppress(OSError):
                    path.rmdir()  # только пустые

    # endregion

    # region Помощники

    def _replace(self, ctx: RunContext, old: LibraryFile, kept_local: Path, quarantine_id: int | None) -> None:
        if ctx.dry_run:
            return
        try:
            rel = kept_local.relative_to(self.music_root).as_posix()
        except ValueError:
            return
        kept = self._settings.navidrome_music_dir.rstrip("/") + "/" + rel
        self._store.add_replacement(old.song.id, kept, quarantine_id)

    def _action(
        self, ctx: RunContext, kind: str, applied: bool, details: str, f: LibraryFile, quarantine_id: int | None = None
    ) -> None:
        self._store.add_action(
            ctx.run_id,
            kind,
            applied and not ctx.dry_run,
            _dry(ctx, details) if applied else details,
            artist=f.song.artist,
            title=f.song.title,
            album=f.song.album,
            path=self._rel(f.local),
            quarantine_id=quarantine_id,
        )

    def _rel(self, path: Path) -> str:
        try:
            return path.relative_to(self.music_root).as_posix()
        except ValueError:
            return path.as_posix()

    async def _scan(self) -> None:
        try:
            await self._navidrome.start_scan()
        except Exception as e:  # noqa: BLE001 — Navidrome пересканирует по расписанию
            log.warning("Не удалось запустить сканирование Navidrome: %s", e)

    # endregion


TAG_NAMES = {"artist": "исполнитель", "title": "название", "album": "альбом"}


def _tag_problem(song: NdSong) -> str | None:
    """Признаки пустых тегов в данных Navidrome: «[Unknown Artist]», название из имени файла."""
    artist = normalize(song.artist)
    if artist in UNKNOWN_ARTISTS:
        return "artist"
    if normalize(song.album) in UNKNOWN_ALBUMS:
        return "album"
    stem = Path(song.path).stem
    if not song.title or song.title == stem:
        return "title"
    return None


def _best(files: list[LibraryFile]) -> LibraryFile:
    pinned = [f for f in files if f.pinned]
    return max(pinned or files, key=LibraryFile.rank)


def _by_duration(group: list[LibraryFile]) -> list[list[LibraryFile]]:
    """Копии с длительностью ±3 с друг от друга — один трек; заметно другая длительность — другая версия."""
    ordered = sorted(group, key=lambda f: f.song.duration_s or 0.0)
    clusters: list[list[LibraryFile]] = [[ordered[0]]]
    for f in ordered[1:]:
        if (f.song.duration_s or 0) - (clusters[-1][-1].song.duration_s or 0) <= DUP_DURATION_S:
            clusters[-1].append(f)
        else:
            clusters.append([f])
    return [c for c in clusters if len(c) > 1]


def _folder_counts(files: list[LibraryFile]) -> dict[Path, int]:
    counts: dict[Path, int] = defaultdict(int)
    for f in files:
        counts[f.folder] += 1
    return counts


def _has_image(folder: Path) -> bool:
    try:
        return any(e.is_file() and e.suffix.lower() in IMAGE_SUFFIXES for e in folder.iterdir())
    except OSError:
        return False


def _signature(path: Path) -> str:
    try:
        st = path.stat()
    except OSError:
        return ""
    return f"{st.st_size}:{int(st.st_mtime)}"


def _restore_file(stored: Path, original: Path) -> None:
    if not stored.exists():
        raise MaintenanceError("Файла нет в карантине")
    if original.exists():
        raise MaintenanceError("На прежнем месте уже есть файл с таким именем")
    original.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(str(stored), str(original))


def _large_cover(url: str) -> str:
    return url.replace("400x400", "1000x1000").replace("/250x250-", "/1000x1000-")


def _mmss(seconds: float) -> str:
    total = int(round(seconds))
    return f"{total // 60}:{total % 60:02d}"


def _mb(size: int) -> str:
    return f"{size / 1024 / 1024:.1f} МБ".replace(".", ",")


def _dry(ctx: RunContext, details: str) -> str:
    return f"{details} (пробный запуск — ничего не изменено)" if ctx.dry_run else details


# region Теги файлов (mutagen)


def read_tags(path: Path) -> dict[str, str] | None:
    """artist / title / album из тегов файла. `None` — файл не читается."""
    try:
        import mutagen

        audio = mutagen.File(path, easy=True)
    except Exception:  # noqa: BLE001 — битый файл
        return None
    if audio is None:
        return None
    tags = audio.tags or {}
    return {k: str((tags.get(k) or [""])[0]).strip() for k in ("artist", "title", "album")}


def write_missing_tags(path: Path, values: dict[str, str]) -> None:
    """Дописать только недостающие теги; заполненные (и альбомный исполнитель сборников) не трогаем."""
    import mutagen

    audio = mutagen.File(path, easy=True)
    if audio is None:
        raise MaintenanceError(f"Неизвестный формат: {path.name}")
    if audio.tags is None:
        audio.add_tags()
    for key, value in values.items():
        audio[key] = [value]
    audio.save()


def has_embedded_cover(path: Path) -> bool:
    try:
        import mutagen

        audio = mutagen.File(path)
    except Exception:  # noqa: BLE001
        return False
    if audio is None:
        return False
    if getattr(audio, "pictures", None):
        return True
    tags = audio.tags
    if tags is None:
        return False
    if hasattr(tags, "getall"):
        return bool(tags.getall("APIC"))
    with contextlib.suppress(Exception):
        return bool(tags.get("covr") or tags.get("metadata_block_picture"))
    return False


# endregion
