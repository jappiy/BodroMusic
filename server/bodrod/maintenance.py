"""Регламент «Обслуживание библиотеки»: раз в сутки проходит по всем трекам Navidrome и наводит порядок.

Что делает (по порядку):
1. **Короткие файлы.** В карантин — только явные обрезки: файл меньше, чем должен быть при своём битрейте, или
   короче полной копии того же трека (или длительности из каталога) непропорционально — без признаков другой версии.
   Радио-версии, интервью, интро, ремиксы и просто короткие треки (< `MAINTENANCE_MIN_TRACK_S`) — в «требует внимания».
2. **Дубли треков** — один исполнитель, одно название (без «Remastered», «Deluxe», года и feat), длительность ±3 с.
   Файлы без тегов сравниваются по папке исполнителя и имени файла. Остаётся лучшая копия: качество (lossless,
   затем битрейт) → тип релиза (студийный альбом → EP/сингл → сборник/концерт) → полный альбом, а не россыпь →
   папка Lidarr → чистое имя без `.1`, `(1)`, ` copy`. Убираются россыпь и копии внутри того же альбома;
   трек из другого полного альбома (сборника) остаётся.
3. **Дубли альбомов** — один альбом в нескольких папках: треки переносятся в папку, где их больше; при равенстве —
   в `Исполнитель/ГГГГ - Альбом`; из альбомной папки в россыпь (`Треки/…`) — никогда. Сборники и Various Artists
   не собираются. Перенос между папками вариантов исполнителя (`X feat. Y` → `X`) — отдельное действие
   «Объединение исполнителя»; разные исполнители (Sting и The Police) не склеиваются.
4. **Чужой релиз** — исполнитель в тегах большинства треков папки не совпадает с папкой исполнителя
   (релиз скачан по совпадению названия). Только отчёт.
0. **Кодировка тегов** (идёт первой) — русский текст в Windows-1251, прочитанный как Latin-1
   («Ñåêðåò» → «Секрет»): название, исполнитель, альбом, исполнитель альбома, жанр, композитор (`encoding.py`).
   Исправленное пишется в файл (MP3 — ID3v2.4 UTF-8, ID3v1 удаляется), исходные значения — в журнал
   `tag_encoding_fixes` (откат из приложения). Невосстановимое («????», смешанный мусор) — поиск по AcoustID
   (если есть `fpcalc` и ключ), затем по цепочке `TAG_PROVIDERS` по имени файла и папке; не нашлось — в отчёт
   «требует ручной проверки».
5. **Теги и обложки** — у треков без исполнителя, названия или альбома недостающие теги дописываются по цепочке
   источников (`TAG_PROVIDERS`), если совпадение надёжное (оценка ≥ 0,85, длительность ±3 с). Папке альбома без
   обложки добавляется `cover.jpg` (`COVER_PROVIDERS`); в папках россыпи обложка вшивается в каждый файл по альбому
   этого трека. Сначала ищется локально: обложка соседнего трека того же альбома. Спорное — только в отчёт.

Пробный режим (`MAINTENANCE_DRY_RUN`) ничего не меняет и ничего не запоминает: два пробных запуска подряд дают
одинаковый отчёт. Отметки «не найдено» хранят версию набора источников и сбрасываются при его смене.
Папки Lidarr (если задан `LIDARR_MUSIC_DIR`): файлы не переносятся и не убираются (Lidarr скачал бы их снова),
теги не меняются. Разрешено одно — `cover.jpg` в папку альбома без обложки из Cover Art Archive по MBID релиза
(теги файла, база Navidrome, затем Lidarr). Пропуски по этому правилу — не проблема: в отчёт и сводку не попадают.
Не исправленное считается по причинам (`open_<причина>`, `REASONS`) — вместе с уже известным с прошлых запусков,
хотя в отчёт оно повторно не пишется.
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
from collections import Counter, defaultdict
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field, replace
from pathlib import Path
from typing import Any

from .cleanup import AUDIO_SUFFIXES, _remap
from .clients.lidarr import LidarrClient
from .clients.navidrome import NavidromeClient
from .config import Settings
from .db import Store, now_ms
from .encoding import FIELD_NAMES, EncodingJournal, acoustid_lookup, read_values, repair_fields, revert, write_values
from .metadata.lookup import MATCH_THRESHOLD, SOURCE_NAMES, LookupMatch, LookupProvider, chain_version
from .models import (
    EncodingFixOut,
    MaintenanceCount,
    MaintenanceRunOut,
    MaintenanceSummary,
    QuarantineItemOut,
    TagChangeOut,
)
from .navidrome_db import NavidromeDb, NdSong
from .placement import embed_cover, read_embedded_cover
from .text import main_artist, normalize, same_artist, similarity_any

log = logging.getLogger(__name__)

KIND_SHORT = "short"
KIND_DUPLICATE = "duplicate"
KIND_ALBUM = "album"
KIND_ARTIST = "artist_merge"
KIND_TAGS = "tags"
KIND_COVER = "cover"
KIND_MISMATCH = "mismatch"
KIND_ENCODING = "encoding"

REASONS = {
    "short_version": "Короткие файлы, не похожие на обрезок (другая версия, интро, интервью)",
    "tags_unreadable": "Файл не читается — возможно, повреждён",
    "tags_not_found": "Нет тегов, надёжного совпадения в источниках нет",
    "cover_not_found": "Нет обложки, в источниках не найдена",
    "lidarr_cover_not_found": "Папка Lidarr без обложки: нет MBID релиза или обложки в Cover Art Archive",
    "mismatch": "Чужой релиз в папке исполнителя",
    "encoding_manual": "Битая кодировка тегов: восстановить не удалось, нужна ручная проверка",
}
"""Почему не исправлено: код → заголовок для сводки в приложении."""
FIXED = {
    "duplicates_removed": "Дубли треков",
    "short_removed": "Обрезанные файлы",
    "albums_merged": "Альбомы, собранные в одну папку",
    "artists_merged": "Объединение исполнителя",
    "tags_fixed": "Дописаны теги",
    "covers_added": "Добавлены обложки",
    "encoding_fixed": "Исправлена кодировка тегов",
}
"""Что исправлено: ключ статистики → заголовок для сводки."""

STAT_KEYS = (
    "songs",
    "short_found",
    "short_removed",
    "short_skipped",
    "duplicates_removed",
    "albums_merged",
    "artists_merged",
    "tags_fixed",
    "tags_problems",
    "covers_added",
    "covers_missing",
    "encoding_fixed",
    "mismatches",
    "quarantine_purged",
    "attention",
    *(f"open_{code}" for code in REASONS),
)
"""Ключи статистики запуска: в каждом запуске все, включая нулевые. `attention` — сумма `open_*`."""

LOSSLESS = {"flac", "alac", "wav", "ape", "wv", "aiff", "aif", "dsf"}
IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp"}
UNKNOWN_ARTISTS = {"", "unknown artist", "unknown", "неизвестный исполнитель"}
UNKNOWN_ALBUMS = {"", "unknown album", "неизвестный альбом"}
LOOSE_ALBUMS = {"", "singles", "single", "синглы", "unknown album", "треки"}
LOOSE_DIRS = {"треки", "tracks", "singles", "синглы", "разное", "misc"}
"""Папки, которые не означают исполнителя: контейнеры библиотеки, сборники, россыпь."""
CONTAINER_DIRS = LOOSE_DIRS | {
    "collections",
    "library",
    "music",
    "музыка",
    "various artists",
    "various",
    "va",
    "сборники",
    "compilations",
    "soundtracks",
    "ost",
    "downloads",
    "slsk",
}
VARIOUS = {"various artists", "various", "va", "сборник", "разные исполнители"}

DUP_DURATION_S = 3.0
ENCODING_LIST_LIMIT = 300
"""Сколько исправлений кодировки и файлов на ручную проверку отдаёт сводка."""
TRUNCATED_SHARE = 0.6
"""Копия короче полной на 40% и больше — кандидат в обрезки."""
RADIO_SHARE = 0.5
RADIO_MIN_S = 150
"""Короткая копия ≥ 50% длины полной и ≥ 2:30 — похоже на радио-версию, а не на обрезок."""
RECHECK_MS = 30 * 24 * 3600 * 1000
ALBUM_SHORT_TRACKS = 4
"""Если в папке столько треков с номерами, короткая дорожка — скорее интро или скит, а не обрезанный файл."""
FULL_ALBUM_TRACKS = 3

# В скобках — пометки издания, которые не делают трек другим: (Remastered 2011), [Deluxe Edition], (2005).
_EDITION = re.compile(
    r"[\(\[][^\)\]]*\b(?:remaster\w*|deluxe|edition|bonus|explicit|clean|album version|digital|expanded|"
    r"anniversary|\d{4})\b[^\)\]]*[\)\]]",
    re.IGNORECASE,
)
_FEAT = re.compile(r"[\(\[]?\s*\b(?:feat|ft|featuring)\b\.?.*$", re.IGNORECASE)
_TRACK_PREFIX = re.compile(r"^\s*(?:\d{1,3}(?:[-.]\d{1,3})?)\s*(?:[-._)]\s*|\s+)")
_DISC_DIR = re.compile(r"^(?:cd|disc|disk|диск)\s*\d+$", re.IGNORECASE)
_YEAR_DIR = re.compile(r"^\d{4}\s*-\s*\S")
# Признаки другой версии трека: короткая копия с ними — не обрезок.
_VERSION_MARKERS = re.compile(
    r"\b(?:edit|radio|single|version|interview|live|remix|rmx|mix|intro|outro|skit|interlude|demo|acoustic|"
    r"unplugged|reprise|instrumental|интервью|ремикс|интро|скит|версия|концерт)\b"
)
_COMPILATION = re.compile(
    r"\b(?:best of|greatest hits|the very best|hits|collection|anthology|essential|now that s what|"
    r"сборник|лучшее|лучшие песни|избранное|антология|хиты)\b"
)
# Сборники разных исполнителей: такие альбомы не собираются в одну папку.
_VA_ALBUM = re.compile(r"\b(?:now that s what i call|сборник|various|bravo hits|hits 20\d\d|хиты 20\d\d|top \d+)\b")
_LIVE = re.compile(r"\b(?:live|concert|unplugged|концерт)\b")
_EP = re.compile(r"\b(?:e p|ep|single|сингл|maxi)\b")
_DIRTY_NAME = re.compile(r"(?:\.\d{1,2}|\s*\(\d{1,2}\)|[\s_-]+copy(?:\s*\d+)?|\s*-\s*копия(?:\s*\d+)?)$", re.IGNORECASE)


def title_key(title: str | None) -> str:
    """Название для поиска дублей: без пометок издания и feat, но «(Live)», «(Interview)», «(Remix)» остаются."""
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
    """Исполнитель и название для сравнения: из тегов, а у файлов без тегов — из папки и имени файла."""
    artist: str = ""
    title: str = ""
    untagged: bool = False

    def __post_init__(self) -> None:
        self.artist = self.artist or self.song.artist
        self.title = self.title or self.song.title

    @property
    def lossless(self) -> bool:
        return (self.song.suffix or self.local.suffix.lstrip(".")).lower() in LOSSLESS

    @property
    def folder(self) -> Path:
        return self.local.parent

    @property
    def album(self) -> str:
        return album_key(self.song.album)

    def quality_rank(self) -> tuple[int, int]:
        """lossless > lossy; среди lossy — битрейт (с шагом 32 кбит/с: VBR 317 и CBR 320 — одно качество)."""
        if self.lossless:
            return (1, 0)
        return (0, round((self.song.bit_rate or 0) / 32))

    def quality(self) -> str:
        suffix = (self.song.suffix or self.local.suffix.lstrip(".")).upper()
        return f"{suffix} {self.song.bit_rate} кбит/с" if self.song.bit_rate and not self.lossless else suffix


@dataclass
class RunContext:
    run_id: int
    dry_run: bool
    stats: dict[str, int] = field(default_factory=lambda: dict.fromkeys(STAT_KEYS, 0))
    changed: bool = False
    catalog_budget: int = 0
    """Сколько аудиофайлов в каждой папке (по данным Navidrome) — отличать альбом от одиночного трека."""
    folder_counts: dict[Path, int] = field(default_factory=dict)
    """(папка, альбом) → треков этого альбома в папке; (исполнитель, альбом) → больше всего в одной папке."""
    album_in_folder: dict[tuple[Path, str], int] = field(default_factory=dict)
    album_max: dict[tuple[str, str], int] = field(default_factory=dict)
    """Папки, где треки из разных альбомов (россыпь), даже если они не называются «Треки»."""
    mixed_folders: set[Path] = field(default_factory=set)
    cover_cache: dict[tuple[str, str], Any] = field(default_factory=dict)
    """id исполнителя Lidarr → его файлы (`/trackfile`), чтобы найти альбом папки без MBID в тегах."""
    lidarr_files: dict[int, list[dict[str, Any]]] = field(default_factory=dict)


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
        cover_chain: list[LookupProvider],
        tag_chain: list[LookupProvider],
        cover_fetcher: CoverFetcher | None,
        lock: asyncio.Lock | None = None,
    ) -> None:
        self._settings = settings
        self._store = store
        self._navidrome = navidrome
        self._db = db
        self._lidarr = lidarr if settings.lidarr_music_dir else None
        self.cover_chain = cover_chain
        self.tag_chain = [p for p in tag_chain if p.tags]
        self._fetch_cover = cover_fetcher
        self.encoding_journal = EncodingJournal(store)
        self._lidarr_artists: dict[Path, int] = {}
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

    @property
    def cover_version(self) -> str:
        return chain_version([p.name for p in self.cover_chain])

    @property
    def tag_version(self) -> str:
        return chain_version([p.name for p in self.tag_chain])

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
            log.info("Обслуживание библиотеки: запуск %s (%s%s)", run.id, trigger, ", пробный" if run.dry_run else "")
            try:
                await asyncio.to_thread(self._purge_quarantine, ctx)
                files = await self._load()
                ctx.stats["songs"] = len(files)
                await self._fix_encoding(ctx, files)
                self._index(ctx, files)
                files = await self._short_files(ctx, files)
                files = await self._duplicates(ctx, files)
                await self._albums(ctx, files)
                self._mismatches(ctx, files)
                await self._tags_and_covers(ctx, files)
                ctx.stats["attention"] = _open_total(ctx)
                if ctx.changed and not ctx.dry_run:
                    await self._scan()
                self._store.finish_run(run.id, "completed", dict(ctx.stats))
                log.info("Обслуживание библиотеки: готово, %s", dict(ctx.stats))
            except Exception as e:
                log.exception("Обслуживание библиотеки: ошибка")
                ctx.stats["attention"] = _open_total(ctx)
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
            if self._in_quarantine(path) or self._in_reco(path) or not path.is_file():
                continue
            pinned = any(path.is_relative_to(d) for d in pinned_dirs)
            f = LibraryFile(song, path, pinned, marks.get(song.id, 0))
            self._derive_names(f)
            files.append(f)
        return files

    def _derive_names(self, f: LibraryFile) -> None:
        """Файл без тегов: исполнитель — из папки исполнителя, название — из имени файла без номера и «X - »."""
        problem = _tag_problem(f.song)
        if problem not in ("artist", "title"):
            return
        folder_artist = self._artist_dir(f.folder) or ""
        artist = f.song.artist if normalize(f.song.artist) not in UNKNOWN_ARTISTS else folder_artist
        title = f.song.title if f.song.title and f.song.title != Path(f.song.path).stem else ""
        if not title:
            title = _TRACK_PREFIX.sub("", f.local.stem).strip()
            prefix, sep, rest = title.partition(" - ")
            if sep and rest and (not artist or same_artist(prefix, artist)):
                artist = artist or prefix
                title = rest.strip()
        f.artist, f.title, f.untagged = artist.strip(), title, True

    async def _lidarr_dirs(self) -> list[Path]:
        if self._lidarr is None or not self._settings.lidarr_music_dir:
            return []
        try:
            artists = await self._lidarr.artists()
        except Exception as e:  # noqa: BLE001 — без Lidarr не трогаем ничего, что может быть его
            raise MaintenanceError(f"Lidarr недоступен, обслуживание отложено: {e}") from e
        self._lidarr_artists = {}
        for artist in artists:
            local = _remap(artist.get("path") or "", self._settings.lidarr_music_dir, self._settings.music_dir)
            if local:
                self._lidarr_artists[Path(local)] = int(artist.get("id") or -1)
        return list(self._lidarr_artists)

    def _in_quarantine(self, path: Path) -> bool:
        return path.is_relative_to(self.quarantine_root)

    def _in_reco(self, path: Path) -> bool:
        """Discovery-пул и резерв рекомендаций обслуживает их владелец (`reco`), а не регламент."""
        roots = (self._settings.discovery_path, self._settings.reserve_path)
        return any(path.is_relative_to(Path(r)) for r in roots)

    def _index(self, ctx: RunContext, files: list[LibraryFile]) -> None:
        counts: dict[Path, int] = defaultdict(int)
        albums: dict[tuple[Path, str], int] = defaultdict(int)
        by_folder: dict[Path, Counter[str]] = defaultdict(Counter)
        for f in files:
            counts[f.folder] += 1
            albums[(f.folder, f.album)] += 1
            by_folder[f.folder][f.album] += 1
        ctx.folder_counts = dict(counts)
        ctx.album_in_folder = dict(albums)
        best: dict[tuple[str, str], int] = defaultdict(int)
        for f in files:
            key = (self._album_artist_key(f), f.album)
            best[key] = max(best[key], albums[(f.folder, f.album)])
        ctx.album_max = dict(best)
        for folder, album_counts in by_folder.items():
            total = sum(album_counts.values())
            if len(album_counts) >= 3 and album_counts.most_common(1)[0][1] < total / 2:
                ctx.mixed_folders.add(folder)

    # endregion

    # region Папки

    def _rel_parts(self, folder: Path) -> tuple[str, ...]:
        try:
            return folder.relative_to(self.music_root).parts
        except ValueError:
            return folder.parts

    def _in_loose_dir(self, folder: Path) -> bool:
        return any(normalize(p) in LOOSE_DIRS for p in self._rel_parts(folder))

    def _loose_folder(self, ctx: RunContext, folder: Path) -> bool:
        """Папка россыпи: `Треки/<исполнитель>`, «Singles» или папка, где треки из разных альбомов."""
        return self._in_loose_dir(folder) or folder in ctx.mixed_folders

    def _artist_dir(self, folder: Path) -> str | None:
        """Имя папки исполнителя: `Треки/<исполнитель>` или `<исполнитель>/<альбом>`; контейнеры — не исполнитель."""
        parts = self._rel_parts(folder)
        if not parts:
            return None
        loose = [i for i, p in enumerate(parts) if normalize(p) in LOOSE_DIRS]
        if loose:
            i = loose[-1]
            name = parts[i + 1] if i + 1 < len(parts) else None
        else:
            name = parts[-2] if len(parts) >= 2 else parts[-1]
        if name is None or normalize(name) in CONTAINER_DIRS:
            return None
        return name

    def _album_artist_key(self, f: LibraryFile) -> str:
        return normalize(main_artist(f.song.album_artist or f.artist))

    def _is_loose(self, ctx: RunContext, f: LibraryFile) -> bool:
        """Одиночный трек, а не часть альбома: «Singles», без альбома, папка россыпи или с одним-двумя треками."""
        album = f.album
        # Сингл с альбомом по своему названию — россыпь, а заглавный трек полного альбома — нет.
        single = album == title_key(f.title) and ctx.album_in_folder.get((f.folder, album), 0) < FULL_ALBUM_TRACKS
        return (
            album in LOOSE_ALBUMS
            or single
            or ctx.folder_counts.get(f.folder, 0) <= 2
            or self._loose_folder(ctx, f.folder)
        )

    def _in_full_album(self, ctx: RunContext, f: LibraryFile) -> bool:
        """Трек лежит в папке, где большинство треков его альбома, а не в россыпи."""
        if self._is_loose(ctx, f):
            return False
        here = ctx.album_in_folder.get((f.folder, f.album), 0)
        return here >= FULL_ALBUM_TRACKS and here >= ctx.album_max.get((self._album_artist_key(f), f.album), 0)

    def _release_type(self, ctx: RunContext, f: LibraryFile) -> int:
        """3 — студийный альбом, 2 — EP/сингл (и россыпь), 1 — сборник или концерт."""
        if normalize(f.song.album_artist) in VARIOUS:
            return 1
        text = f"{normalize(f.song.album, strip_extras=False)} {normalize(f.folder.name, strip_extras=False)}"
        if _COMPILATION.search(text) or _LIVE.search(text):
            return 1
        if _EP.search(text) or self._is_loose(ctx, f):
            return 2
        return 3

    # endregion

    # region Короткие и обрезанные файлы

    async def _short_files(self, ctx: RunContext, files: list[LibraryFile]) -> list[LibraryFile]:
        expected = await asyncio.to_thread(self._store.track_durations)
        full: dict[tuple[str, str], list[tuple[float, str]]] = defaultdict(list)
        for f in files:
            full[(normalize(f.artist), title_key(f.title))].append((f.song.duration_s or 0.0, f.album))
        kept: list[LibraryFile] = []
        for f in files:
            found = self._short_reason(ctx, f, expected, full)
            if found is None:
                kept.append(f)
                continue
            reason, auto = found
            kept_file = True
            if f.pinned:
                kept.append(f)  # папка Lidarr: не трогаем и не считаем проблемой
                continue
            if not auto:
                if self._report_once(ctx, f"short:{f.local}", _signature(f.local), "short_version"):
                    ctx.stats["short_found"] += 1
                    ctx.stats["short_skipped"] += 1
                    self._action(ctx, KIND_SHORT, False, reason, f)
            else:
                quarantine_id = await self._quarantine(ctx, f, reason)
                ctx.stats["short_found"] += 1
                ctx.stats["short_removed"] += 1
                self._action(
                    ctx, KIND_SHORT, True, f"{reason}. {_say(ctx, 'Убран в карантин', 'Будет убран в карантин')}", f,
                    quarantine_id,
                )  # fmt: skip
                kept_file = False
            if kept_file:
                kept.append(f)
        return kept

    def _short_reason(
        self,
        ctx: RunContext,
        f: LibraryFile,
        expected: dict[str, int],
        full: dict[tuple[str, str], list[tuple[float, str]]],
    ) -> tuple[str, bool] | None:
        """`(причина, убрать)`: убрать — явный обрезок; иначе только в «требует внимания»."""
        duration = f.song.duration_s or 0.0
        if duration <= 0:
            return None
        album_like = f.song.track_number and ctx.folder_counts.get(f.folder, 0) >= ALBUM_SHORT_TRACKS
        if duration < self._settings.maintenance_min_track_s and not album_like:
            return f"Подозрительно короткий: {_mmss(duration)}. Короткие треки бывают настоящими — проверьте", False
        if duration >= 30 and f.song.size and f.song.bit_rate:
            bytes_expected = f.song.bit_rate * 125 * duration
            if not f.lossless and f.song.size < 0.5 * bytes_expected:
                return (
                    f"Файл обрезан: {_mb(f.song.size)} при длительности {_mmss(duration)} и {f.song.bit_rate} кбит/с",
                    True,
                )
            if f.lossless and f.song.bit_rate < 160:
                return f"Файл обрезан: {f.song.bit_rate} кбит/с для {f.quality()} — меньше, чем бывает у lossless", True
        catalog_ms = expected.get(f.song.id)
        if catalog_ms and catalog_ms >= 60_000 and duration * 1000 < TRUNCATED_SHARE * catalog_ms:
            return self._shorter_than(f, duration, catalog_ms / 1000, "длительности в каталоге")
        # Полная копия считается только в том же альбоме или для сингла: «Intro» на разных альбомах — разные треки.
        loose = self._is_loose(ctx, f)
        copies = full.get((normalize(f.artist), title_key(f.title)), [])
        longest = max((d for d, a in copies if loose or a == f.album), default=0.0)
        if longest >= 60 and duration < TRUNCATED_SHARE * longest:
            return self._shorter_than(f, duration, longest, "полной копии того же трека")
        return None

    @staticmethod
    def _shorter_than(f: LibraryFile, duration: float, reference: float, what: str) -> tuple[str, bool]:
        base = f"Короче {what}: {_mmss(duration)} вместо {_mmss(reference)}"
        text = normalize(f"{f.title} {f.song.album or ''} {f.local.stem}", strip_extras=False)
        marker = _VERSION_MARKERS.search(text)
        if marker:
            return f"{base}. В названии «{marker.group(0)}» — похоже на другую версию, не трогаем", False
        if duration >= RADIO_SHARE * reference and duration >= RADIO_MIN_S:
            return f"{base}. Похоже на радио-версию — не трогаем", False
        return base, True

    # endregion

    # region Дубли треков

    async def _duplicates(self, ctx: RunContext, files: list[LibraryFile]) -> list[LibraryFile]:
        groups: dict[tuple[str, str], list[LibraryFile]] = defaultdict(list)
        for f in files:
            if f.song.duration_s:
                groups[(normalize(f.artist), title_key(f.title))].append(f)
        removed: set[Path] = set()
        for (artist, title), group in groups.items():
            if not artist or not title or len(group) < 2:
                continue
            for cluster in _by_duration(group):
                for drop, keep in self._resolve_cluster(ctx, cluster):
                    untagged = " Файл без тегов: сравнили по папке и имени файла." if drop.untagged else ""
                    details = _say(
                        ctx,
                        f"Дубль: оставлена копия «{self._rel(keep.local)}» ({keep.quality()}), "
                        f"убрана эта ({drop.quality()}).{untagged}",
                        f"Дубль: останется копия «{self._rel(keep.local)}» ({keep.quality()}), "
                        f"эта ({drop.quality()}) будет убрана.{untagged}",
                    )
                    quarantine_id = await self._quarantine(ctx, drop, details)
                    self._replace(ctx, drop, keep.local, quarantine_id)
                    removed.add(drop.local)
                    ctx.stats["duplicates_removed"] += 1
                    self._action(ctx, KIND_DUPLICATE, True, details, drop, quarantine_id)
        return [f for f in files if f.local not in removed]

    def keep_rank(self, ctx: RunContext, f: LibraryFile, stems: set[str]) -> tuple[Any, ...]:
        """Чем больше, тем лучше: качество → тип релиза → полный альбом → папка Lidarr → чистое имя."""
        dirty = _DIRTY_NAME.search(f.local.stem)
        clean = not (dirty and f.local.stem[: dirty.start()].rstrip() in stems)
        return (
            f.quality_rank(),
            self._release_type(ctx, f),
            int(self._in_full_album(ctx, f)),
            int(f.pinned),
            int(clean),
            int(f.marks > 0),
            f.song.size or 0,
        )

    def _resolve_cluster(self, ctx: RunContext, cluster: list[LibraryFile]) -> list[tuple[LibraryFile, LibraryFile]]:
        """Пары (убрать, оставить). Убирается россыпь и копии того же альбома; трек другого полного альбома остаётся."""
        if len({f.local for f in cluster}) < 2:
            return []
        stems = {f.local.stem for f in cluster}
        keep = max(cluster, key=lambda f: self.keep_rank(ctx, f, stems))
        pairs = []
        for f in cluster:
            if f is keep or f.pinned:
                continue
            same_album = not self._is_loose(ctx, keep) and f.album == keep.album
            if self._is_loose(ctx, f) or same_album:
                pairs.append((f, keep))
        return pairs

    # endregion

    # region Дубли альбомов

    async def _albums(self, ctx: RunContext, files: list[LibraryFile]) -> None:
        groups: dict[tuple[str, str], list[LibraryFile]] = defaultdict(list)
        for f in files:
            if f.album in LOOSE_ALBUMS or _VA_ALBUM.search(f.album):
                continue
            if normalize(f.song.album_artist) in VARIOUS:
                continue
            groups[(self._album_artist_key(f), f.album)].append(f)
        for group in groups.values():
            folders: dict[Path, list[LibraryFile]] = defaultdict(list)
            for f in group:
                folders[f.folder].append(f)
            if len(folders) < 2 or self._is_multi_disc(folders):
                continue
            if any(f.pinned for f in group):
                continue  # в папки Lidarr не переносим, из них — тоже
            if not self._one_artist(group, folders):
                continue  # сборник или совместный альбом разных исполнителей
            target = max(
                folders,
                key=lambda d: (
                    int(not self._loose_folder(ctx, d)),
                    len(folders[d]),
                    int(bool(_YEAR_DIR.match(d.name))),
                    sum(f.lossless for f in folders[d]),
                ),
            )
            target_artist = self._artist_dir(target)
            moved = 0
            sources: list[str] = []
            artist_dirs: set[str] = set()
            for folder, items in folders.items():
                if folder == target:
                    continue
                sources.append(self._rel(folder))
                source_artist = self._artist_dir(folder)
                if source_artist and target_artist and _dir_name(source_artist) != _dir_name(target_artist):
                    artist_dirs.add(source_artist)
                for f in items:
                    dest = await asyncio.to_thread(self._move_into, ctx, f.local, target)
                    if dest is not None:
                        self._replace(ctx, f, dest, None)
                        moved += 1
                if not ctx.dry_run:
                    await asyncio.to_thread(self._move_leftovers, folder, target)
            if not moved:
                continue
            first = group[0].song
            origin = ", ".join(f"«{s}»" for s in sources)
            if artist_dirs:
                kind = KIND_ARTIST
                ctx.stats["artists_merged"] += 1
                variants = ", ".join(f"«{a}»" for a in sorted(artist_dirs))
                details = _say(
                    ctx,
                    f"Объединение исполнителя: треки альбома из папок {variants} перенесены к «{target_artist}» — "
                    f"«{self._rel(target)}», треков — {moved}",
                    f"Объединение исполнителя: треки альбома из папок {variants} будут перенесены к "
                    f"«{target_artist}» — «{self._rel(target)}», треков — {moved}",
                )
            else:
                kind = KIND_ALBUM
                ctx.stats["albums_merged"] += 1
                details = _say(
                    ctx,
                    f"Альбом собран в одну папку «{self._rel(target)}»: перенесено треков — {moved} из {origin}",
                    f"Альбом будет собран в папку «{self._rel(target)}»: будет перенесено треков — {moved} из {origin}",
                )
            ctx.changed = ctx.changed or not ctx.dry_run
            self._store.add_action(
                ctx.run_id,
                kind,
                not ctx.dry_run,
                details,
                artist=first.album_artist or first.artist,
                album=first.album,
                path=self._rel(target),
                planned=ctx.dry_run,
            )

    def _one_artist(self, group: list[LibraryFile], folders: dict[Path, list[LibraryFile]]) -> bool:
        """У всех треков и папок один исполнитель (с точностью до feat./& и вариантов написания)."""
        mains = {normalize(main_artist(f.artist)) for f in group if normalize(f.artist) not in UNKNOWN_ARTISTS}
        names = sorted(mains)
        if any(not same_artist(a, b) for i, a in enumerate(names) for b in names[i + 1 :]):
            return False
        dirs = sorted({d for d in (self._artist_dir(folder) for folder in folders) if d})
        return all(same_artist(a, b) for i, a in enumerate(dirs) for b in dirs[i + 1 :])

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

    # region Чужой релиз

    def _mismatches(self, ctx: RunContext, files: list[LibraryFile]) -> None:
        """Исполнитель в тегах большинства треков папки — не тот, что в имени папки исполнителя. Только отчёт."""
        folders: dict[Path, list[LibraryFile]] = defaultdict(list)
        for f in files:
            folders[f.folder].append(f)
        for folder, items in folders.items():
            expected = self._artist_dir(folder)
            if not expected:
                continue
            known: list[tuple[LibraryFile, str]] = []
            for f in items:
                artist = f.artist if normalize(f.artist) not in UNKNOWN_ARTISTS else ""
                if not artist:
                    prefix, sep, _ = _TRACK_PREFIX.sub("", f.local.stem).partition(" - ")
                    artist = prefix if sep else ""
                if artist:
                    known.append((f, artist))
            if not known:
                continue
            foreign = [
                (f, artist)
                for f, artist in known
                if not same_artist(artist, expected) and not same_artist(f.song.album_artist, expected)
            ]
            if len(foreign) * 2 <= len(known):
                continue
            if not self._report_once(ctx, f"mismatch:{folder}", f"{len(items)}:{len(foreign)}", "mismatch"):
                continue
            counts = Counter(artist for _, artist in foreign)
            names = ", ".join(f"«{a}»" for a, _ in counts.most_common(3))
            album = Counter(f.song.album for f, _ in foreign if f.song.album).most_common(1)
            ctx.stats["mismatches"] += 1
            self._store.add_action(
                ctx.run_id,
                KIND_MISMATCH,
                False,
                f"Чужой релиз: в папке исполнителя «{expected}» треки {names} ({len(foreign)} из {len(items)}). "
                "Похоже, скачан по совпадению названия — проверьте и уберите вручную",
                artist=counts.most_common(1)[0][0],
                album=album[0][0] if album else folder.name,
                path=self._rel(folder),
            )

    # endregion

    # region Кодировка тегов

    async def _fix_encoding(self, ctx: RunContext, files: list[LibraryFile]) -> None:
        """Битая кодировка. Идёт первой: дубли и теги дальше сравниваются уже по исправленным названиям."""
        reverted = self.encoding_journal.reverted_paths()
        for f in files:
            if f.pinned or str(f.local) in reverted or not f.local.exists():
                continue
            key = f"encoding:{f.local}"
            signature = _signature(f.local)
            if self._known(ctx, key, signature):
                continue
            values = await asyncio.to_thread(read_values, f.local)
            if values is None:
                continue  # не читается — это отчёт шага «Теги и обложки»
            fixed, manual = repair_fields(values)
            sources = dict.fromkeys(fixed, "")
            if manual:
                found, source = await self._identify(ctx, f, values, manual)
                fixed.update(found)
                sources.update(dict.fromkeys(found, source))
                manual = [k for k in manual if k not in found]
            if fixed:
                await self._apply_encoding(ctx, f, values, fixed, sources)
                signature = _signature(f.local)
            if manual:
                shown = "; ".join(f"{FIELD_NAMES[k]}: «{'; '.join(values[k])}»" for k in manual)
                if self._report_once(ctx, key, signature, "encoding_manual"):
                    self._action(ctx, KIND_ENCODING, False, f"Битая кодировка, восстановить не удалось ({shown})", f)
            else:
                self._mark_checked(ctx, key, signature)

    async def _identify(
        self, ctx: RunContext, f: LibraryFile, values: dict[str, list[str]], manual: list[str]
    ) -> tuple[dict[str, list[str]], str]:
        """Невосстановимые название / исполнитель / альбом: AcoustID, затем цепочка тегов по имени файла и папке."""
        wanted = [k for k in manual if k in ("title", "artist", "album")]
        if not wanted:
            return {}, ""
        found = await acoustid_lookup(f.local, self._settings.acoustid_api_key or "")
        source = "AcoustID"
        if found is None:
            clean = {k: (values.get(k) or [""])[0] for k in ("artist", "title", "album") if k not in manual}
            guess = self._guess(f, clean)
            match = await self._lookup(
                ctx, self.tag_chain, guess["artist"], guess["title"], guess["album"], f.song.duration_s
            )
            if match is None:
                return {}, ""
            found = {"artist": match.artist, "title": match.title, "album": match.album or ""}
            source = match.source_name
        return {k: [found[k]] for k in wanted if found.get(k)}, source

    async def _apply_encoding(
        self,
        ctx: RunContext,
        f: LibraryFile,
        before: dict[str, list[str]],
        fixed: dict[str, list[str]],
        sources: dict[str, str],
    ) -> None:
        """`sources[поле]` — откуда значение: пусто — перекодировка, иначе имя источника (AcoustID, MusicBrainz…)."""
        if not ctx.dry_run:
            try:
                await asyncio.to_thread(write_values, f.local, fixed)
            except Exception as e:  # noqa: BLE001 — файл только для чтения или формат без поддержки
                log.warning("Не удалось исправить кодировку тегов %s: %s", f.local, e)
                return
            source = ", ".join(sorted({v or "cp1251" for v in sources.values()}))
            self.encoding_journal.add(ctx.run_id, str(f.local), {k: before.get(k) or [] for k in fixed}, fixed, source)
            ctx.changed = True
        song_fields = {"title": "title", "artist": "artist", "album": "album", "albumartist": "album_artist"}
        updates = {song_fields[k]: v[0] for k, v in fixed.items() if k in song_fields and v}
        if updates:
            f.song = replace(f.song, **updates)
            if not f.untagged:
                f.title = updates.get("title", f.title)
                f.artist = updates.get("artist", f.artist)
        ctx.stats["encoding_fixed"] += 1
        changes = "; ".join(
            f"{FIELD_NAMES[k]}: «{'; '.join(before.get(k) or [])}» → «{'; '.join(v)}»"
            + (f" ({sources[k]})" if sources.get(k) else "")
            for k, v in fixed.items()
        )
        self._action(
            ctx,
            KIND_ENCODING,
            True,
            _say(ctx, f"Исправлена кодировка тегов — {changes}", f"Будет исправлена кодировка тегов — {changes}"),
            f,
        )

    async def revert_encoding(self, fix_id: int) -> None:
        """Вернуть исходные теги из журнала. Файл больше не исправляется автоматически."""
        fix = self.encoding_journal.get(fix_id)
        if fix is None:
            raise MaintenanceError("Исправление не найдено")
        if fix.reverted_at is not None:
            raise MaintenanceError("Исправление уже отменено")
        async with self._lock:
            try:
                await asyncio.to_thread(revert, fix)
            except FileNotFoundError as e:
                raise MaintenanceError("Файла больше нет на прежнем месте") from e
            self.encoding_journal.mark_reverted(fix_id)
        log.info("Отменено исправление кодировки %s", fix.path)
        await self._scan()

    # endregion

    # region Теги и обложки

    async def _tags_and_covers(self, ctx: RunContext, files: list[LibraryFile]) -> None:
        for f in files:
            if _tag_problem(f.song) is not None and not f.pinned and f.local.exists():
                await self._fix_tags(ctx, f)
        folders: dict[Path, list[LibraryFile]] = defaultdict(list)
        for f in files:
            if f.local.exists():
                folders[f.folder].append(f)
        for folder, items in folders.items():
            if items[0].pinned:
                await self._fix_lidarr_cover(ctx, folder, items)
            elif self._loose_folder(ctx, folder):
                for f in items:
                    await self._fix_file_cover(ctx, f, items)
            else:
                await self._fix_cover(ctx, folder, items)

    async def _fix_tags(self, ctx: RunContext, f: LibraryFile) -> None:
        key = f"tags:{f.local}"
        signature = _signature(f.local)
        if self._known(ctx, key, signature) or self._known(ctx, key, f"{signature}|{self.tag_version}"):
            return
        current = await asyncio.to_thread(read_tags, f.local)
        if current is None:
            self._mark_checked(ctx, key, signature, "tags_unreadable")
            ctx.stats["tags_problems"] += 1
            self._action(ctx, KIND_TAGS, False, "Не удалось прочитать файл — возможно, он повреждён", f)
            return
        missing = [k for k in ("artist", "title", "album") if not current.get(k)]
        if not missing:
            self._mark_checked(ctx, key, signature)  # теги на месте: Navidrome просто взял название из имени файла
            return
        guess = self._guess(f, current)
        names = ", ".join(TAG_NAMES[k] for k in missing)
        match = await self._lookup(
            ctx, self.tag_chain, guess["artist"], guess["title"], guess["album"], f.song.duration_s
        )
        if match is None:
            self._mark_checked(ctx, key, f"{signature}|{self.tag_version}", "tags_not_found")
            ctx.stats["tags_problems"] += 1
            sources = ", ".join(SOURCE_NAMES.get(p.name, p.name) for p in self.tag_chain) or "нет источников"
            self._action(ctx, KIND_TAGS, False, f"Нет тегов ({names}); надёжного совпадения нет ({sources})", f)
            return
        values = {"artist": match.artist, "title": match.title, "album": match.album or guess["album"]}
        update = {k: values[k] for k in missing if values.get(k)}
        if not update:
            return
        if not ctx.dry_run:
            await asyncio.to_thread(write_missing_tags, f.local, update)
            ctx.changed = True
        written = "; ".join(f"{TAG_NAMES[k]}: {v}" for k, v in update.items())
        ctx.stats["tags_fixed"] += 1
        self._action(
            ctx,
            KIND_TAGS,
            True,
            _say(
                ctx,
                f"Дописаны теги ({match.source_name}, совпадение {match.score:.2f}) — {written}",
                f"Будут дописаны теги ({match.source_name}, совпадение {match.score:.2f}) — {written}",
            ),
            f,
        )

    async def _fix_cover(self, ctx: RunContext, folder: Path, items: list[LibraryFile]) -> None:
        """Папка альбома без обложки — `cover.jpg` из цепочки источников."""
        if ctx.folder_counts.get(folder, 0) == 0 or await asyncio.to_thread(_has_image, folder):
            return
        if any(f.song.has_cover_art for f in items):
            return
        if all(f.song.has_cover_art is None for f in items) and await asyncio.to_thread(
            has_embedded_cover, items[0].local
        ):
            return
        key = f"cover:{folder}"
        signature = f"{len(items)}|{self.cover_version}"
        if self._known(ctx, key, signature):
            return
        sample = items[0]
        song = sample.song
        # Теги могли только что дописаться — берём их из файла, а не из базы Navidrome.
        tags = await asyncio.to_thread(read_tags, sample.local) or {}
        artist = tags.get("artist") or song.album_artist or sample.artist
        album = tags.get("album") or song.album or folder.name
        title = tags.get("title") or sample.title

        def report(details: str) -> None:
            self._mark_checked(ctx, key, signature, "cover_not_found")
            ctx.stats["covers_missing"] += 1
            self._store.add_action(
                ctx.run_id, KIND_COVER, False, details, artist=artist, album=album, path=self._rel(folder)
            )

        cover = await self._find_cover(ctx, artist, title, album, song.duration_s, need_album=True)
        if cover is None:
            sources = ", ".join(SOURCE_NAMES.get(p.name, p.name) for p in self.cover_chain) or "нет источников"
            report(f"Нет обложки; не найдена ({sources})")
            return
        data, source = cover
        if data is not None and not ctx.dry_run:
            image, mime = data
            name = "cover.png" if mime == "image/png" else "cover.jpg"
            await asyncio.to_thread((folder / name).write_bytes, image)
            ctx.changed = True
        ctx.stats["covers_added"] += 1
        self._store.add_action(
            ctx.run_id,
            KIND_COVER,
            not ctx.dry_run,
            _say(ctx, f"Добавлена обложка папки ({source})", f"Будет добавлена обложка папки ({source})"),
            artist=artist,
            album=album,
            path=self._rel(folder),
            planned=ctx.dry_run,
        )

    async def _fix_file_cover(self, ctx: RunContext, f: LibraryFile, neighbours: list[LibraryFile]) -> None:
        """Папка россыпи: обложка вшивается в каждый файл по альбому этого трека, а не `cover.jpg` на всю папку."""
        if f.song.has_cover_art:
            return
        if f.song.has_cover_art is None and await asyncio.to_thread(has_embedded_cover, f.local):
            return
        key = f"cover:{f.local}"
        signature = f"{_signature(f.local)}|{self.cover_version}"
        if self._known(ctx, key, signature):
            return
        album = f.song.album if f.album not in LOOSE_ALBUMS else None

        def report(details: str) -> None:
            self._mark_checked(ctx, key, signature, "cover_not_found")
            ctx.stats["covers_missing"] += 1
            self._action(ctx, KIND_COVER, False, details, f)

        cover = await self._neighbour_cover(f, neighbours) if album else None
        if cover is None:
            cover = await self._find_cover(ctx, f.artist, f.title, album, f.song.duration_s, need_album=False)
        if cover is None:
            sources = ", ".join(SOURCE_NAMES.get(p.name, p.name) for p in self.cover_chain) or "нет источников"
            report(f"Нет обложки в файле; не найдена ({sources})")
            return
        data, source = cover
        if data is not None and not ctx.dry_run:
            try:
                await asyncio.to_thread(embed_cover, f.local, data)
            except Exception as e:  # noqa: BLE001 — формат без поддержки обложек или битый файл
                log.warning("Не удалось вшить обложку в %s: %s", f.local, e)
                return
            ctx.changed = True
        ctx.stats["covers_added"] += 1
        self._action(
            ctx,
            KIND_COVER,
            True,
            _say(
                ctx,
                f"Обложка вшита в файл ({source}) — папка россыпи, у треков разные альбомы",
                f"Обложка будет вшита в файл ({source}) — папка россыпи, у треков разные альбомы",
            ),
            f,
        )

    async def _fix_lidarr_cover(self, ctx: RunContext, folder: Path, items: list[LibraryFile]) -> None:
        """Папка Lidarr: теги и файлы не меняем, только `cover.jpg` из Cover Art Archive по MBID релиза."""
        if await asyncio.to_thread(_has_image, folder) or any(f.song.has_cover_art for f in items):
            return
        if all(f.song.has_cover_art is None for f in items) and await asyncio.to_thread(
            has_embedded_cover, items[0].local
        ):
            return
        key = f"cover:{folder}"
        signature = f"{len(items)}|caa"
        if self._known(ctx, key, signature):
            return
        sample = items[0]
        artist = sample.song.album_artist or sample.artist
        album = sample.song.album or folder.name

        def report(details: str) -> None:
            self._mark_checked(ctx, key, signature, "lidarr_cover_not_found")
            ctx.stats["covers_missing"] += 1
            self._store.add_action(
                ctx.run_id, KIND_COVER, False, details, artist=artist, album=album, path=self._rel(folder)
            )

        mbids = await self._album_mbids(ctx, folder, items)
        if not mbids:
            report("Папка Lidarr без обложки: MBID релиза нет ни в тегах, ни в Lidarr")
            return
        if not ctx.dry_run and self._fetch_cover is not None:
            found: tuple[bytes, str] | None = None
            for kind, mbid in mbids:
                try:
                    found = await self._fetch_cover(f"https://coverartarchive.org/{kind}/{mbid}/front-1200")
                except Exception as e:  # noqa: BLE001 — 404: у релиза нет обложки, пробуем следующий MBID
                    log.info("Cover Art Archive: нет обложки %s/%s: %s", kind, mbid, e)
                    continue
                if found is not None:
                    break
            if found is None:
                report("Папка Lidarr без обложки: в Cover Art Archive её нет")
                return
            image, mime = found
            name = "cover.png" if mime == "image/png" else "cover.jpg"
            await asyncio.to_thread((folder / name).write_bytes, image)
            ctx.changed = True
        ctx.stats["covers_added"] += 1
        self._store.add_action(
            ctx.run_id,
            KIND_COVER,
            not ctx.dry_run,
            _say(
                ctx,
                "Добавлена обложка папки Lidarr (Cover Art Archive)",
                "Будет добавлена обложка папки Lidarr (Cover Art Archive)",
            ),
            artist=artist,
            album=album,
            path=self._rel(folder),
            planned=ctx.dry_run,
        )

    async def _album_mbids(self, ctx: RunContext, folder: Path, items: list[LibraryFile]) -> list[tuple[str, str]]:
        """MBID для Cover Art Archive: `(release | release-group, id)` из тегов файла, базы Navidrome, затем Lidarr."""
        result: list[tuple[str, str]] = []

        def add(kind: str, mbid: str | None) -> None:
            if mbid and (kind, mbid) not in result:
                result.append((kind, mbid))

        tags = await asyncio.to_thread(read_album_mbids, items[0].local)
        add("release", tags.get("release"))
        add("release-group", tags.get("release-group"))
        for f in items:
            add("release", f.song.album_mbid)
        if result or self._lidarr is None:
            return result
        artist_id = next((i for d, i in self._lidarr_artists.items() if folder.is_relative_to(d)), -1)
        if artist_id < 0:
            return result
        try:
            if artist_id not in ctx.lidarr_files:
                ctx.lidarr_files[artist_id] = await self._lidarr.track_files(artist_id)
            album_id = next(
                (t.get("albumId") for t in ctx.lidarr_files[artist_id] if self._lidarr_folder(t) == folder), None
            )
            if album_id is None:
                return result
            album = await self._lidarr.album(int(album_id))
        except Exception as e:  # noqa: BLE001 — без Lidarr просто нет MBID
            log.info("Lidarr: не удалось узнать альбом папки %s: %s", folder, e)
            return result
        for release in album.get("releases") or []:
            if release.get("monitored"):
                add("release", release.get("foreignReleaseId"))
        add("release-group", album.get("foreignAlbumId"))
        return result

    def _lidarr_folder(self, track_file: dict[str, Any]) -> Path | None:
        local = _remap(track_file.get("path") or "", self._settings.lidarr_music_dir, self._settings.music_dir)
        return Path(local).parent if local else None

    async def _neighbour_cover(self, f: LibraryFile, neighbours: list[LibraryFile]) -> tuple[Any, str] | None:
        """Локальный фолбэк: обложка, вшитая в соседний трек того же альбома."""
        for other in neighbours:
            if other is f or other.album != f.album or other.song.has_cover_art is False:
                continue
            data = await asyncio.to_thread(read_embedded_cover, other.local)
            if data is not None:
                return data, f"{SOURCE_NAMES['local']} «{other.local.name}»"
        return None

    async def _find_cover(
        self,
        ctx: RunContext,
        artist: str,
        title: str,
        album: str | None,
        duration_s: float | None,
        *,
        need_album: bool,
    ) -> tuple[Any, str] | None:
        """Обложка по цепочке: `((картинка, mime) или None в пробном режиме, источник)`. Не скачалась — следующий."""
        cache_key = (normalize(main_artist(artist)), album_key(album) or title_key(title))
        if cache_key in ctx.cover_cache:
            return ctx.cover_cache[cache_key]
        result = None
        for provider in self.cover_chain:
            match = await self._lookup(ctx, [provider], artist, title, album, duration_s, need_cover=True)
            if match is None or not match.cover_url:
                continue
            if need_album and album and similarity_any(album_key(match.album), album_key(album)) < 0.8:
                continue
            if ctx.dry_run or self._fetch_cover is None:
                result = (None, match.source_name)
                break
            try:
                data = await self._fetch_cover(match.cover_url)
            except Exception as e:  # noqa: BLE001 — у источника нет картинки: пробуем следующий
                log.info("Обложка %s (%s) не скачалась: %s", match.cover_url, match.source, e)
                continue
            if data is not None:
                result = (data, match.source_name)
                break
        ctx.cover_cache[cache_key] = result
        return result

    def _guess(self, f: LibraryFile, current: dict[str, str]) -> dict[str, str]:
        """Исполнитель/название/альбом из тегов, а чего нет — из пути `Исполнитель/Альбом/01 Исполнитель - Название`."""
        song = f.song
        loose = self._in_loose_dir(f.folder)
        folder_album = f.folder.name if not loose and self._artist_dir(f.folder) else ""
        artist = current.get("artist") or (song.album_artist or "") or (f.artist if f.untagged else "")
        artist = artist or self._artist_dir(f.folder) or ""
        title = current.get("title") or (f.title if f.untagged else _TRACK_PREFIX.sub("", f.local.stem))
        if artist and normalize(title).startswith(normalize(artist) + " ") and " - " in title:
            title = title.split(" - ", 1)[1]
        album = current.get("album") or folder_album
        return {"artist": artist.strip(), "title": title.strip(), "album": album.strip()}

    async def _lookup(
        self,
        ctx: RunContext,
        chain: list[LookupProvider],
        artist: str,
        title: str,
        album: str | None,
        duration_s: float | None,
        *,
        need_cover: bool = False,
    ) -> LookupMatch | None:
        """Первое надёжное совпадение по цепочке: оценка ≥ 0,85 (исполнитель, название, длительность ±3 с)."""
        if not artist or not title:
            return None
        for provider in chain:
            if ctx.catalog_budget <= 0:
                break
            if not getattr(provider, "available", True):
                continue  # источник временно отключён после сбоев (Last.fm) — бюджет не тратим
            ctx.catalog_budget -= 1
            try:
                hits = await provider.lookup(artist, title, album or None, duration_s)
            except Exception as e:  # noqa: BLE001 — источник недоступен: следующий
                log.debug("Источник %s не ответил: %s", provider.name, e)
                continue
            good = [h for h in hits if h.score >= MATCH_THRESHOLD and (h.cover_url or not need_cover)]
            if good:
                # При равной оценке — релиз с тем же альбомом, а не сборник.
                return max(good, key=lambda h: (h.score, similarity_any(h.album, album), not h.compilation))
        return None

    # endregion

    # region Отметки «проверено»

    def _report_once(self, ctx: RunContext, key: str, signature: str, reason: str) -> bool:
        """Неисправимое не повторяется в отчёте каждую ночь: раз в 30 дней или после изменения файла.

        В пробном режиме отметки не пишутся — каждый пробный запуск показывает всё.
        """
        if self._known(ctx, key, signature):
            return False
        self._mark_checked(ctx, key, signature, reason)
        return True

    def _mark_checked(self, ctx: RunContext, key: str, signature: str, reason: str | None = None) -> None:
        """Отметка «проверено»; `reason` — почему не исправлено (считается в сводке как `open_<reason>`)."""
        if reason:
            ctx.stats[f"open_{reason}"] += 1
        if not ctx.dry_run:
            self._store.set_checked(key, signature, reason)

    def _known(self, ctx: RunContext, key: str, signature: str) -> bool:
        """Проверено недавно: в отчёт не пишется, но известная проблема остаётся в сводке."""
        checked = self._store.checked(key)
        if checked is None or checked[0] != signature or now_ms() - checked[1] >= RECHECK_MS:
            return False
        if checked[2]:
            ctx.stats[f"open_{checked[2]}"] += 1
        return True

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

    def summary(self) -> MaintenanceSummary:
        """Сводка для приложения: последний запуск в цифрах и всё, что сейчас в карантине."""
        runs = self._store.list_runs(1)
        run = runs[0] if runs else None
        fixed: list[MaintenanceCount] = []
        unresolved: list[MaintenanceCount] = []
        if run is not None:
            fixed = [
                MaintenanceCount(key=key, title=title, count=run.stats[key])
                for key, title in FIXED.items()
                if run.stats.get(key)
            ]
            if any(k.startswith("open_") for k in run.stats):
                counts = {code: run.stats.get(f"open_{code}", 0) for code in REASONS}
            else:  # запуск bodrod до 0.16.0: причин нет, считаем находки по видам
                found = self._store.open_findings(run.id)
                counts = {
                    "short_version": found.get(KIND_SHORT, 0),
                    "tags_not_found": found.get(KIND_TAGS, 0),
                    "cover_not_found": found.get(KIND_COVER, 0),
                    "mismatch": found.get(KIND_MISMATCH, 0),
                }
            unresolved = [MaintenanceCount(key=code, title=REASONS[code], count=n) for code, n in counts.items() if n]
        keep_ms = self._settings.quarantine_days * 24 * 3600 * 1000
        quarantine = [
            QuarantineItemOut(
                id=entry.id,
                path=self._rel(Path(entry.original_path)),
                reason=entry.reason,
                kind=action.kind if action else None,
                artist=action.artist if action else None,
                title=action.title if action else None,
                album=action.album if action else None,
                size=entry.size,
                created_at=entry.created_at,
                expires_at=entry.created_at + keep_ms,
            )
            for entry, action in self._store.active_quarantine()
        ]
        encoding = [
            EncodingFixOut(
                id=fix.id,
                path=self._rel(Path(fix.path)),
                changes=[TagChangeOut(field=FIELD_NAMES[k], before=b, after=a) for k, b, a in fix.changes()],
                source=fix.source,
                reverted=fix.reverted_at is not None,
            )
            for fix in (self.encoding_journal.for_run(run.id) if run else [])[:ENCODING_LIST_LIMIT]
        ]
        manual = [
            self._rel(Path(key.removeprefix("encoding:")))
            for key in self._store.checked_keys("encoding:", "encoding_manual")
        ]
        return MaintenanceSummary(
            run=MaintenanceRunOut(**run.__dict__) if run else None,
            fixed=fixed,
            unresolved=unresolved,
            quarantine=quarantine,
            encoding=encoding,
            encoding_manual=sorted(manual)[:ENCODING_LIST_LIMIT],
        )

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
        """`applied` — исправление (в пробном режиме — запланированное), иначе только находка для отчёта."""
        self._store.add_action(
            ctx.run_id,
            kind,
            applied and not ctx.dry_run,
            details,
            artist=f.artist or f.song.artist,
            title=f.title or f.song.title,
            album=f.song.album,
            path=self._rel(f.local),
            quarantine_id=quarantine_id,
            planned=applied and ctx.dry_run,
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


def _dir_name(name: str) -> str:
    """Имя папки для сравнения: регистр и «ё» не важны, а «feat. X» и «& Y» — важны (другая папка)."""
    return normalize(name, strip_extras=False)


def _open_total(ctx: RunContext) -> int:
    return sum(ctx.stats[f"open_{code}"] for code in REASONS)


def _say(ctx: RunContext, done: str, planned: str) -> str:
    """Формулировка действия: в пробном режиме — «будет сделано»."""
    return planned if ctx.dry_run else done


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


def _mmss(seconds: float) -> str:
    total = int(round(seconds))
    return f"{total // 60}:{total % 60:02d}"


def _mb(size: int) -> str:
    return f"{size / 1024 / 1024:.1f} МБ".replace(".", ",")


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


def read_album_mbids(path: Path) -> dict[str, str]:
    """MBID релиза и группы релизов из тегов (Picard, beets, Lidarr): `{"release": …, "release-group": …}`."""
    try:
        import mutagen

        audio = mutagen.File(path, easy=True)
    except Exception:  # noqa: BLE001 — битый файл
        return {}
    if audio is None or audio.tags is None:
        return {}
    result = {}
    for kind, tag in (("release", "musicbrainz_albumid"), ("release-group", "musicbrainz_releasegroupid")):
        with contextlib.suppress(Exception):
            value = str((audio.tags.get(tag) or [""])[0]).strip()
            if value:
                result[kind] = value
    return result


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
