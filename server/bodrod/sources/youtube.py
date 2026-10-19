"""Источник «YouTube Music»: поиск ytmusicapi (только «song» — официальное аудио, не клипы), загрузка yt-dlp.

yt-dlp запускается отдельным процессом: так обновлённая версия (в `<DATA_DIR>/ytdlp`, `pip --target`) действует
сразу, без перезапуска bodrod. Формат — bestaudio (opus или m4a) без перекодирования: при наличии ffmpeg поток
только перекладывается в свой контейнер (`.opus` / `.m4a`), чтобы на него можно было записать теги.
Файлы с YouTube всегда lossy — так и помечаются в базе bodrod. Прокси — `YOUTUBE_PROXY`.
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
import os
import re
import shutil
import sys
import time
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol

from ..models import RequestKind
from ..placement import Tags, sanitize_part
from ..text import normalize, same_artist, similarity_any
from .base import (
    MATCH_THRESHOLD,
    FetchedFile,
    FetchJob,
    FetchResult,
    SourceMiss,
    SourceStatus,
    is_preview,
    track_score,
    unwanted_extras,
)

log = logging.getLogger(__name__)

DOWNLOAD_TIMEOUT_S = 15 * 60
UPDATE_TIMEOUT_S = 5 * 60
_THUMB_SIZE = re.compile(r"=w\d+-h\d+")
CODECS = {".opus": "opus", ".ogg": "opus", ".webm": "opus", ".m4a": "aac", ".mp3": "mp3"}


@dataclass
class YtSong:
    video_id: str
    title: str
    artists: list[str]
    album: str | None = None
    duration_s: int | None = None
    """song / video — клипы ytmusicapi помечает как video."""
    result_type: str = "song"
    track_no: int | None = None
    year: int | None = None
    cover_url: str | None = None

    @property
    def artist(self) -> str:
        return ", ".join(self.artists)


@dataclass
class YtAlbum:
    browse_id: str
    title: str
    artists: list[str]
    year: int | None = None

    @property
    def artist(self) -> str:
        return ", ".join(self.artists)


class YouTubeMusicApi(Protocol):
    async def search_songs(self, query: str) -> list[YtSong]: ...

    async def search_albums(self, query: str) -> list[YtAlbum]: ...

    async def search_artist(self, name: str) -> list[tuple[str, str]]: ...

    async def album_songs(self, browse_id: str) -> list[YtSong]: ...

    async def artist_albums(self, channel_id: str) -> list[YtAlbum]: ...


class YtDlp(Protocol):
    async def version(self) -> str | None: ...

    async def download(self, video_id: str, dest_stem: Path) -> Path: ...

    async def update(self) -> str | None: ...


# region Настоящие ytmusicapi и yt-dlp


def _thumb(raw: dict[str, Any]) -> str | None:
    """Самая большая обложка; у googleusercontent размер в адресе — просим 1200×1200."""
    thumbs = raw.get("thumbnails") or []
    url = thumbs[-1].get("url") if thumbs else None
    return _THUMB_SIZE.sub("=w1200-h1200", url) if url else None


def _year(value: Any) -> int | None:
    try:
        return int(str(value)[:4]) if value else None
    except ValueError:
        return None


def _song(raw: dict[str, Any], album: str | None = None, year: int | None = None) -> YtSong | None:
    if not raw.get("videoId"):
        return None
    return YtSong(
        video_id=raw["videoId"],
        title=raw.get("title") or "",
        artists=[a.get("name") for a in raw.get("artists") or [] if a.get("name")],
        album=album or (raw.get("album") or {}).get("name"),
        duration_s=raw.get("duration_seconds"),
        result_type=raw.get("resultType") or ("video" if raw.get("videoType") == "MUSIC_VIDEO_TYPE_UGC" else "song"),
        track_no=raw.get("trackNumber"),
        year=year,
        cover_url=_thumb(raw),
    )


class YtMusicClient:
    """ytmusicapi (синхронный, requests) в потоке; без авторизации."""

    def __init__(self, proxy: str | None = None) -> None:
        self._proxy = proxy
        self._client: Any = None

    def _get(self) -> Any:
        if self._client is None:
            from ytmusicapi import YTMusic

            proxies = {"http": self._proxy, "https": self._proxy} if self._proxy else None
            # Язык по умолчанию (en): с другим ytmusicapi не разбирает локализованные категории выдачи.
            self._client = YTMusic(proxies=proxies)
        return self._client

    async def _call(self, fn: Callable[[Any], Any]) -> Any:
        return await asyncio.to_thread(lambda: fn(self._get()))

    async def search_songs(self, query: str) -> list[YtSong]:
        raw = await self._call(lambda c: c.search(query, filter="songs", limit=20)) or []
        return [s for s in (_song(r) for r in raw) if s]

    async def search_albums(self, query: str) -> list[YtAlbum]:
        raw = await self._call(lambda c: c.search(query, filter="albums", limit=10)) or []
        return [
            YtAlbum(
                browse_id=r["browseId"],
                title=r.get("title") or "",
                artists=[a.get("name") for a in r.get("artists") or [] if a.get("name")],
                year=_year(r.get("year")),
            )
            for r in raw
            if r.get("browseId")
        ]

    async def search_artist(self, name: str) -> list[tuple[str, str]]:
        raw = await self._call(lambda c: c.search(name, filter="artists", limit=5)) or []
        return [(r["browseId"], r.get("artist") or r.get("title") or "") for r in raw if r.get("browseId")]

    async def album_songs(self, browse_id: str) -> list[YtSong]:
        album = await self._call(lambda c: c.get_album(browse_id)) or {}
        title, year = album.get("title"), _year(album.get("year"))
        cover = _thumb(album)
        songs = []
        for i, raw in enumerate(album.get("tracks") or [], start=1):
            song = _song({**raw, "trackNumber": raw.get("trackNumber") or i}, title, year)
            if song:
                song.cover_url = cover or song.cover_url
                song.result_type = "song"
                songs.append(song)
        return songs

    async def artist_albums(self, channel_id: str) -> list[YtAlbum]:
        def run(c: Any) -> list[dict[str, Any]]:
            artist = c.get_artist(channel_id)
            section = artist.get("albums") or {}
            if section.get("params") and section.get("browseId"):
                return c.get_artist_albums(section["browseId"], section["params"], limit=None)
            return section.get("results") or []

        raw = await self._call(run) or []
        return [
            YtAlbum(browse_id=r["browseId"], title=r.get("title") or "", artists=[], year=_year(r.get("year")))
            for r in raw
            if r.get("browseId")
        ]


class YtDlpProcess:
    """yt-dlp отдельным процессом; свежая версия (если обновлялась) — из `target_dir`."""

    def __init__(self, target_dir: str, proxy: str | None = None, python: str = sys.executable) -> None:
        self._target = target_dir
        self._proxy = proxy
        self._python = python
        self._version: str | None = None

    def _env(self) -> dict[str, str]:
        env = dict(os.environ)
        if os.path.isdir(os.path.join(self._target, "yt_dlp")):
            env["PYTHONPATH"] = self._target + (os.pathsep + env["PYTHONPATH"] if env.get("PYTHONPATH") else "")
        return env

    async def _run(self, *args: str, limit_s: float) -> tuple[int, str, str]:
        proc = await asyncio.create_subprocess_exec(
            self._python,
            *args,
            env=self._env(),
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        try:
            out, err = await asyncio.wait_for(proc.communicate(), timeout=limit_s)
        except TimeoutError:
            proc.kill()
            await proc.wait()
            raise
        return proc.returncode or 0, out.decode("utf-8", "replace"), err.decode("utf-8", "replace")

    async def version(self) -> str | None:
        if self._version is None:
            code, out, _ = await self._run("-m", "yt_dlp", "--version", limit_s=60)
            self._version = out.strip() if code == 0 else None
        return self._version

    async def download(self, video_id: str, dest_stem: Path) -> Path:
        dest_stem.parent.mkdir(parents=True, exist_ok=True)
        args = ["-m", "yt_dlp", "--no-playlist", "--no-progress", "--quiet", "--no-warnings"]
        if shutil.which("ffmpeg"):
            # Без перекодирования: поток opus/aac только перекладывается в .opus/.m4a.
            args += ["-f", "bestaudio[acodec=opus]/bestaudio[ext=m4a]/bestaudio", "-x", "--audio-format", "best"]
        else:
            args += ["-f", "bestaudio[ext=m4a]/bestaudio"]
        if self._proxy:
            args += ["--proxy", self._proxy]
        args += ["-o", f"{dest_stem}.%(ext)s", "--print", "after_move:filepath"]
        args.append(f"https://music.youtube.com/watch?v={video_id}")
        code, out, err = await self._run(*args, limit_s=DOWNLOAD_TIMEOUT_S)
        lines = [line for line in out.splitlines() if line.strip()]
        if code != 0 or not lines or not Path(lines[-1]).is_file():
            message = (err.strip().splitlines() or ["нет файла"])[-1]
            raise SourceMiss(f"yt-dlp: {message[:300]}")
        return Path(lines[-1])

    async def update(self) -> str | None:
        """`pip install --upgrade --target` в папку данных: следующий запуск yt-dlp берёт новую версию."""
        os.makedirs(self._target, exist_ok=True)
        code, out, err = await self._run(
            "-m", "pip", "install", "--quiet", "--disable-pip-version-check", "--upgrade",
            "--target", self._target, "yt-dlp",
            limit_s=UPDATE_TIMEOUT_S,
        )  # fmt: skip
        if code != 0:
            raise RuntimeError(f"pip: {(err or out).strip()[-300:]}")
        self._version = None
        return await self.version()


# endregion


class YouTubeSource:
    name = "youtube"
    title = "YouTube Music"

    def __init__(
        self,
        api: YouTubeMusicApi,
        ytdlp: YtDlp,
        library_ready: bool,
        preview_ratio: float = 0.9,
        auto_update: bool = True,
        update_interval_s: int = 24 * 3600,
        clock: Callable[[], float] = time.time,
    ) -> None:
        self._api = api
        self._ytdlp = ytdlp
        self._library_ready = library_ready
        self._preview_ratio = preview_ratio
        self._auto_update = auto_update
        self._update_interval_s = update_interval_s
        self._clock = clock
        self._update_error: str | None = None
        self._updated_at = 0.0
        self._update_lock = asyncio.Lock()

    async def update_ytdlp(self) -> str | None:
        """Обновить yt-dlp (кнопка в приложении, при старте и раз в сутки). Возвращает новую версию."""
        async with self._update_lock:
            self._updated_at = self._clock()
            try:
                version = await self._ytdlp.update()
            except Exception as e:  # noqa: BLE001 — нет сети или pip: работаем на старой версии
                self._update_error = str(e) or type(e).__name__
                log.warning("yt-dlp не обновлён: %s", e)
                raise
            self._update_error = None
            log.info("yt-dlp обновлён: %s", version)
            return version

    async def auto_update(self) -> None:
        if self._auto_update and self._clock() - self._updated_at >= self._update_interval_s:
            with contextlib.suppress(Exception):  # причина уже в логе
                await self.update_ytdlp()

    async def status(self) -> SourceStatus:
        if not self._library_ready:
            return SourceStatus(False, "Не задана папка библиотеки LIBRARY_DIR")
        try:
            version = await self._ytdlp.version()
        except Exception as e:  # noqa: BLE001
            version, error = None, str(e)
        else:
            error = None
        if version is None:
            return SourceStatus(False, f"yt-dlp не запускается{': ' + error if error else ''}")
        details = f"yt-dlp {version}"
        if self._update_error:
            details += f"; обновление не удалось: {self._update_error}"
        return SourceStatus(True, None, details)

    async def find_song(self, artist: str, title: str, duration_ms: int | None) -> YtSong | None:
        best: tuple[float, YtSong] | None = None
        for s in await self._api.search_songs(f"{artist} {title}"):
            if s.result_type != "song":
                continue
            found_ms = s.duration_s * 1000 if s.duration_s else None
            score = track_score(artist, title, duration_ms, s.artist, s.title, found_ms)
            if score >= MATCH_THRESHOLD and (best is None or score > best[0]):
                best = (score, s)
        return best[1] if best else None

    async def fetch(self, job: FetchJob) -> FetchResult:
        if job.kind == RequestKind.TRACK:
            assert job.title is not None
            song = await self.find_song(job.artist, job.title, job.duration_ms)
            if song is None:
                raise SourceMiss("Нет уверенного совпадения в YouTube Music")
            return await self._download(job, [song], job.artist)
        if job.kind == RequestKind.ALBUM:
            assert job.album is not None
            album = await self._find_album(job.artist, job.album)
            if album is None:
                raise SourceMiss(f"Альбом «{job.album}» не найден в YouTube Music")
            return await self._download(job, await self._api.album_songs(album.browse_id), job.artist)
        artists = await self._api.search_artist(job.artist)
        channel = next((cid for cid, name in artists if same_artist(name, job.artist)), None)
        if channel is None:
            raise SourceMiss("Исполнитель не найден в YouTube Music")
        songs: list[YtSong] = []
        for album in await self._api.artist_albums(channel):
            songs.extend(await self._api.album_songs(album.browse_id))
        if not songs:
            raise SourceMiss("У исполнителя нет альбомов в YouTube Music")
        return await self._download(job, songs, job.artist)

    async def _find_album(self, artist: str, album: str) -> YtAlbum | None:
        best: tuple[float, YtAlbum] | None = None
        for a in await self._api.search_albums(f"{artist} {album}"):
            if (a.artists and not same_artist(a.artist, artist)) or unwanted_extras(a.title, album):
                continue
            score = 1.0 if normalize(a.title) == normalize(album) else similarity_any(a.title, album)
            if score >= MATCH_THRESHOLD and (best is None or score > best[0]):
                best = (score, a)
        return best[1] if best else None

    async def _download(self, job: FetchJob, songs: list[YtSong], artist: str) -> FetchResult:
        result = FetchResult(label=self.title)
        codecs: set[str] = set()
        reasons: list[str] = []
        total = len(songs)
        for i, s in enumerate(songs, start=1):
            song_artist = s.artist or artist
            job.progress((i - 1) / total if total else None, f"YouTube: {i} из {total} — {s.title}")
            if await job.exists(song_artist, s.title, s.video_id):
                result.skipped += 1
                continue
            try:
                file = await self._download_song(s, song_artist, job.workdir)
            except SourceMiss as e:
                result.failed += 1
                reasons.append(e.reason)
                continue
            dest = await job.place(file)
            result.placed += 1
            result.paths.append(str(dest))
            codecs.add(file.codec.upper() if file.codec != "opus" else "Opus")
        if result.placed == 0 and result.skipped == 0:
            raise SourceMiss("; ".join(dict.fromkeys(reasons)) or "YouTube не отдал ни одного файла")
        if codecs:
            result.label = f"{self.title}, {', '.join(sorted(codecs))}"
        return result

    async def _download_song(self, s: YtSong, artist: str, workdir: Path) -> FetchedFile:
        stem = workdir / sanitize_part(f"{artist} - {s.title} [{s.video_id}]")
        path = await self._ytdlp.download(s.video_id, stem)
        expected_ms = s.duration_s * 1000 if s.duration_s else None
        short = is_preview(path, expected_ms, self._preview_ratio)
        if short is not None:
            path.unlink(missing_ok=True)
            raise SourceMiss(f"YouTube отдал обрезанный файл ({short:.0f} с из {s.duration_s})")
        return FetchedFile(
            path=path,
            tags=Tags(artist=artist, title=s.title, album=s.album, track_no=s.track_no, year=s.year),
            cover_url=s.cover_url,
            codec=CODECS.get(path.suffix.lower(), path.suffix.lstrip(".").lower()),
            bitrate=None,
            lossy=True,
            duration_ms=expected_ms,
            source_id=s.video_id,
        )
