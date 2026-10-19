"""Источник «Яндекс Музыка»: поиск, FLAC (иначе AAC), альбомы и дискографии целиком, метаданные для перетегирования.

Работа с API — через библиотеку yandex-music (MarshalX, https://ym.marshal.dev) с экстрой `[crypto]`:
lossless отдаётся по `get-file-info` в транспорте `encraw` и расшифровывается библиотекой. Библиотека
синхронная, вызовы идут в потоке; между запросами — пауза 2–3 с (`YANDEX_DELAY_S`), общая для всех заявок и
массовой выгрузки. Для скачивания нужна подписка Плюс: она проверяется при старте и раз в сутки
(`YANDEX_CHECK_INTERVAL_S`); без подписки источник «недоступен» (не путать с ручным выключением).
"""

from __future__ import annotations

import asyncio
import logging
import random
import time
from collections.abc import Callable
from dataclasses import dataclass, field
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

PLUS_RETRY_ON_ERROR_S = 10 * 60
CODEC_LABELS = {"flac": "FLAC", "aac": "AAC", "mp3": "MP3"}
_EXTENSIONS = {"flac": ".flac", "flac-mp4": ".m4a", "aac-mp4": ".m4a", "he-aac-mp4": ".m4a", "mp3": ".mp3"}


@dataclass
class YTrack:
    id: str
    title: str
    artists: list[str]
    duration_ms: int | None = None
    album: str | None = None
    album_id: str | None = None
    album_artist: str | None = None
    year: int | None = None
    genre: str | None = None
    track_no: int | None = None
    disc_no: int | None = None
    cover_url: str | None = None
    artist_ids: list[str] = field(default_factory=list)
    available: bool = True

    @property
    def artist(self) -> str:
        return ", ".join(self.artists)

    def tags(self, lyrics: str | None = None) -> Tags:
        return Tags(
            artist=self.artist,
            title=self.title,
            album=self.album,
            album_artist=self.album_artist,
            track_no=self.track_no,
            disc_no=self.disc_no,
            year=self.year,
            genre=self.genre,
            lyrics=lyrics,
        )


@dataclass
class YAlbum:
    id: str
    title: str
    artists: list[str]
    year: int | None = None
    track_count: int | None = None

    @property
    def artist(self) -> str:
        return ", ".join(self.artists)


@dataclass
class YDownload:
    path: Path
    """flac / aac / mp3."""
    codec: str
    bitrate: int | None


class YandexApi(Protocol):
    """Нужное bodrod подмножество API Яндекс Музыки (в тестах — подделка)."""

    @property
    def has_token(self) -> bool: ...

    async def has_plus(self) -> bool: ...

    async def search_tracks(self, text: str) -> list[YTrack]: ...

    async def search_albums(self, text: str) -> list[YAlbum]: ...

    async def search_artists(self, text: str) -> list[tuple[str, str]]: ...

    async def album_tracks(self, album_id: str) -> list[YTrack]: ...

    async def artist_albums(self, artist_id: str) -> list[YAlbum]: ...

    async def download(self, track_id: str, dest_stem: Path) -> YDownload: ...

    async def lyrics(self, track_id: str) -> str | None: ...

    async def liked_tracks(self) -> list[YTrack]: ...

    async def playlists(self) -> list[tuple[str, list[YTrack]]]: ...


# region Настоящий API (yandex-music)


def _cover(uri: str | None) -> str | None:
    return "https://" + uri.replace("%%", "1000x1000") if uri else None


def _convert_track(t: Any, disc: int | None = None, number: int | None = None) -> YTrack:
    albums = getattr(t, "albums", None) or []
    album = albums[0] if albums else None
    position = getattr(album, "track_position", None) if album else None
    version = getattr(t, "version", None)
    title = t.title or ""
    album_artists = [a.name for a in (getattr(album, "artists", None) or []) if getattr(a, "name", None)]
    return YTrack(
        id=str(t.id),
        title=f"{title} ({version})" if version else title,
        artists=[a.name for a in (t.artists or []) if a.name],
        artist_ids=[str(a.id) for a in (t.artists or []) if a.id is not None],
        duration_ms=t.duration_ms,
        album=album.title if album else None,
        album_id=str(album.id) if album and album.id is not None else None,
        album_artist=", ".join(album_artists) or None,
        year=getattr(album, "year", None) if album else None,
        genre=getattr(album, "genre", None) if album else None,
        track_no=getattr(position, "index", None) or number,
        disc_no=getattr(position, "volume", None) or disc,
        cover_url=_cover(getattr(t, "cover_uri", None) or (getattr(album, "cover_uri", None) if album else None)),
        available=getattr(t, "available", True) is not False,
    )


def _convert_album(a: Any) -> YAlbum:
    return YAlbum(
        id=str(a.id),
        title=(a.title or "") + (f" ({a.version})" if getattr(a, "version", None) else ""),
        artists=[x.name for x in (a.artists or []) if x.name],
        year=getattr(a, "year", None),
        track_count=getattr(a, "track_count", None),
    )


class YandexMusicApi:
    """yandex-music в потоке с паузой между запросами. Скачивание тоже под общей паузой — бережём аккаунт."""

    def __init__(self, token: str | None, delay: tuple[float, float] = (2.0, 3.0)) -> None:
        self._token = token
        self._delay = delay
        self._client: Any = None
        self._lock = asyncio.Lock()
        self._last = 0.0

    @property
    def has_token(self) -> bool:
        return bool(self._token)

    def _get(self) -> Any:
        if self._client is None:
            from yandex_music import Client

            self._client = Client(self._token, report_unknown_fields=False).init()
        return self._client

    async def _call(self, fn: Callable[[Any], Any]) -> Any:
        async with self._lock:
            wait = self._last + random.uniform(*self._delay) - time.monotonic()  # noqa: S311 — не криптография
            if wait > 0:
                await asyncio.sleep(wait)
            try:
                return await asyncio.to_thread(lambda: fn(self._get()))
            finally:
                self._last = time.monotonic()

    async def has_plus(self) -> bool:
        status = await self._call(lambda c: c.account_status())
        plus = getattr(status, "plus", None)
        return bool(plus and plus.has_plus)

    async def search_tracks(self, text: str) -> list[YTrack]:
        found = await self._call(lambda c: c.search(text, type_="track"))
        results = (found.tracks.results if found and found.tracks else None) or []
        return [_convert_track(t) for t in results]

    async def search_albums(self, text: str) -> list[YAlbum]:
        found = await self._call(lambda c: c.search(text, type_="album"))
        results = (found.albums.results if found and found.albums else None) or []
        return [_convert_album(a) for a in results]

    async def search_artists(self, text: str) -> list[tuple[str, str]]:
        found = await self._call(lambda c: c.search(text, type_="artist"))
        results = (found.artists.results if found and found.artists else None) or []
        return [(str(a.id), a.name) for a in results if a.name]

    async def album_tracks(self, album_id: str) -> list[YTrack]:
        album = await self._call(lambda c: c.albums_with_tracks(album_id))
        result = []
        for disc, volume in enumerate(getattr(album, "volumes", None) or [], start=1):
            for number, t in enumerate(volume, start=1):
                track = _convert_track(t, disc, number)
                # В треках альбома поле albums бывает пустым — берём данные самого альбома.
                track.album = track.album or album.title
                track.album_id = track.album_id or str(album.id)
                track.year = track.year or getattr(album, "year", None)
                track.genre = track.genre or getattr(album, "genre", None)
                track.album_artist = track.album_artist or ", ".join(a.name for a in album.artists or [] if a.name)
                track.cover_url = track.cover_url or _cover(getattr(album, "cover_uri", None))
                result.append(track)
        return result

    async def artist_albums(self, artist_id: str) -> list[YAlbum]:
        albums: list[YAlbum] = []
        for page in range(20):
            chunk = await self._call(lambda c, p=page: c.artists_direct_albums(artist_id, page=p, page_size=50))
            items = (chunk.albums if chunk else None) or []
            albums.extend(_convert_album(a) for a in items)
            pager = getattr(chunk, "pager", None)
            if not items or pager is None or (page + 1) * pager.per_page >= pager.total:
                break
        return albums

    async def download(self, track_id: str, dest_stem: Path) -> YDownload:
        """FLAC; если lossless нет — AAC (в MP4), в крайнем случае MP3."""

        def run(c: Any) -> YDownload:
            info = None
            try:
                info = c.tracks_file_info(track_id, quality="lossless", codecs=["flac"], transport="encraw")
            except Exception as e:  # noqa: BLE001 — нет lossless: берём AAC
                log.info("Яндекс: FLAC для %s недоступен: %s", track_id, e)
            d = info.download_info if info else None
            if d is None or not d.codec.startswith("flac"):
                info = c.tracks_file_info(track_id, quality="hq", codecs=["aac-mp4", "mp3"], transport="encraw")
                d = info.download_info if info else None
            if d is None:
                raise SourceMiss("Яндекс не отдал файл")
            ext = _EXTENSIONS.get(d.codec, ".bin")
            path = dest_stem.with_suffix(ext)
            d.download(str(path))
            codec = "flac" if d.codec.startswith("flac") else "aac" if "aac" in d.codec else d.codec
            return YDownload(path=path, codec=codec, bitrate=d.bitrate or None)

        return await self._call(run)

    async def lyrics(self, track_id: str) -> str | None:
        def run(c: Any) -> str | None:
            lyrics = c.tracks_lyrics(track_id, format_="TEXT")
            return lyrics.fetch_lyrics() if lyrics else None

        return await self._call(run)

    async def _tracks(self, ids: list[str]) -> list[YTrack]:
        result: list[YTrack] = []
        for start in range(0, len(ids), 100):
            batch = ids[start : start + 100]
            tracks = await self._call(lambda c, b=batch: c.tracks(b))
            result.extend(_convert_track(t) for t in tracks or [])
        return result

    async def liked_tracks(self) -> list[YTrack]:
        likes = await self._call(lambda c: c.users_likes_tracks())
        return await self._tracks([t.track_id for t in (likes.tracks if likes else None) or []])

    async def playlists(self) -> list[tuple[str, list[YTrack]]]:
        lists = await self._call(lambda c: c.users_playlists_list()) or []
        result = []
        for playlist in lists:
            shorts = await self._call(lambda c, p=playlist: p.fetch_tracks()) or []
            result.append((playlist.title or str(playlist.kind), await self._tracks([s.track_id for s in shorts])))
        return result


# endregion


class YandexSource:
    """Яндекс Музыка в цепочке источников: трек, альбом целиком, дискография исполнителя."""

    name = "yandex"
    title = "Яндекс Музыка"

    def __init__(
        self,
        api: YandexApi,
        library_ready: bool,
        check_interval_s: int = 24 * 3600,
        preview_ratio: float = 0.9,
        clock: Callable[[], float] = time.time,
    ) -> None:
        self._api = api
        self._library_ready = library_ready
        self._check_interval_s = check_interval_s
        self._preview_ratio = preview_ratio
        self._clock = clock
        self._plus: bool | None = None
        self._plus_error: str | None = None
        self._checked_at = 0.0
        self._next_check_at = 0.0
        self._check_lock = asyncio.Lock()

    @property
    def api(self) -> YandexApi:
        return self._api

    async def check_subscription(self, force: bool = False) -> None:
        """Статус Плюса: при старте, раз в сутки и по кнопке в приложении; при ошибке — повтор через 10 минут."""
        if not self._api.has_token:
            return
        async with self._check_lock:
            now = self._clock()
            if not force and now < self._next_check_at:
                return
            try:
                self._plus = await self._api.has_plus()
                self._plus_error = None
                self._next_check_at = now + self._check_interval_s
                log.info("Яндекс Музыка: подписка Плюс %s", "есть" if self._plus else "НЕТ — скачивание недоступно")
            except Exception as e:  # noqa: BLE001 — сеть или токен: источник недоступен до следующей проверки
                self._plus_error = str(e) or type(e).__name__
                self._next_check_at = now + PLUS_RETRY_ON_ERROR_S
                log.warning("Яндекс Музыка: не удалось проверить подписку: %s", e)
            self._checked_at = now

    async def status(self) -> SourceStatus:
        if not self._api.has_token:
            return SourceStatus(False, "Не задан токен YANDEX_MUSIC_TOKEN")
        if not self._library_ready:
            return SourceStatus(False, "Не задана папка библиотеки LIBRARY_DIR")
        await self.check_subscription()
        checked = time.strftime("%d.%m %H:%M", time.localtime(self._checked_at)) if self._checked_at else None
        details = f"Подписка проверена {checked}" if checked else None
        if self._plus_error is not None:
            return SourceStatus(False, f"Не удалось проверить подписку: {self._plus_error}", details)
        if not self._plus:
            return SourceStatus(False, "Нет подписки Плюс", details)
        return SourceStatus(True, None, details)

    # region Поиск

    async def find_track(self, artist: str, title: str, duration_ms: int | None) -> YTrack | None:
        """Лучшее уверенное совпадение «артист + название» с длительностью ±3 с; иначе `None`."""
        best: tuple[float, YTrack] | None = None
        for t in await self._api.search_tracks(f"{artist} {title}"):
            if not t.available:
                continue
            score = track_score(artist, title, duration_ms, t.artist, t.title, t.duration_ms)
            if score >= MATCH_THRESHOLD and (best is None or score > best[0]):
                best = (score, t)
        return best[1] if best else None

    async def find_album(self, artist: str, album: str) -> YAlbum | None:
        best: tuple[float, YAlbum] | None = None
        for a in await self._api.search_albums(f"{artist} {album}"):
            if not same_artist(a.artist, artist) or unwanted_extras(a.title, album):
                continue
            score = similarity_any(a.title, album)
            if normalize(a.title) == normalize(album):
                score = 1.0
            if score >= MATCH_THRESHOLD and (best is None or score > best[0]):
                best = (score, a)
        return best[1] if best else None

    async def find_artist(self, name: str) -> str | None:
        for artist_id, found in (await self._api.search_artists(name))[:5]:
            if same_artist(found, name):
                return artist_id
        return None

    # endregion

    async def fetch(self, job: FetchJob) -> FetchResult:
        if job.kind == RequestKind.TRACK:
            assert job.title is not None
            track = await self.find_track(job.artist, job.title, job.duration_ms)
            if track is None:
                raise SourceMiss("Нет уверенного совпадения в Яндекс Музыке")
            return await self._download_tracks(job, [track])
        if job.kind == RequestKind.ALBUM:
            assert job.album is not None
            album = await self.find_album(job.artist, job.album)
            if album is None:
                raise SourceMiss(f"Альбом «{job.album}» не найден в Яндекс Музыке")
            job.progress(None, f"Яндекс: альбом «{album.title}»")
            return await self._download_tracks(job, await self._api.album_tracks(album.id))
        artist_id = await self.find_artist(job.artist)
        if artist_id is None:
            raise SourceMiss("Исполнитель не найден в Яндекс Музыке")
        albums = await self._api.artist_albums(artist_id)
        if not albums:
            raise SourceMiss("У исполнителя нет альбомов в Яндекс Музыке")
        tracks: list[YTrack] = []
        seen: set[str] = set()
        for i, album in enumerate(albums, start=1):
            job.progress(None, f"Яндекс: собираем дискографию, альбом {i} из {len(albums)}")
            for t in await self._api.album_tracks(album.id):
                if t.id not in seen:
                    seen.add(t.id)
                    tracks.append(t)
        return await self._download_tracks(job, tracks)

    async def _download_tracks(self, job: FetchJob, tracks: list[YTrack]) -> FetchResult:
        result = FetchResult(label=self.title)
        codecs: set[str] = set()
        reasons: list[str] = []
        total = len(tracks)
        for i, t in enumerate(tracks, start=1):
            job.progress((i - 1) / total if total else None, f"Яндекс: {i} из {total} — {t.title}")
            if not t.available:
                result.failed += 1
                reasons.append(f"«{t.title}» недоступен")
                continue
            if await job.exists(t.artist, t.title, t.id):
                result.skipped += 1
                continue
            try:
                file = await self.download_track(t, job.workdir)
            except SourceMiss as e:
                result.failed += 1
                reasons.append(e.reason)
                continue
            dest = await job.place(file)
            result.placed += 1
            result.paths.append(str(dest))
            codecs.add(_codec_label(file.codec, file.bitrate))
        if result.placed == 0 and result.skipped == 0:
            raise SourceMiss("; ".join(dict.fromkeys(reasons)) or "Яндекс не отдал ни одного файла")
        if codecs:
            result.label = f"{self.title}, {', '.join(sorted(codecs))}"
        return result

    async def download_track(self, t: YTrack, workdir: Path, lyrics: bool = True) -> FetchedFile:
        """Скачать трек и проверить, что это не превью (короче 90 % длительности — удалить, неудача)."""
        workdir.mkdir(parents=True, exist_ok=True)
        stem = workdir / sanitize_part(f"{t.artist} - {t.title} [{t.id}]")
        try:
            got = await self._api.download(t.id, stem)
        except SourceMiss:
            raise
        except Exception as e:  # noqa: BLE001 — сеть, нет прав: следующий источник
            raise SourceMiss(f"Яндекс: ошибка загрузки — {e}") from e
        short = is_preview(got.path, t.duration_ms, self._preview_ratio)
        if short is not None:
            got.path.unlink(missing_ok=True)
            raise SourceMiss(f"Яндекс отдал превью ({short:.0f} с из {(t.duration_ms or 0) / 1000:.0f})")
        text = None
        if lyrics:
            try:
                text = await self._api.lyrics(t.id)
            except Exception as e:  # noqa: BLE001 — текста может не быть
                log.debug("Яндекс: текст %s не получен: %s", t.id, e)
        return FetchedFile(
            path=got.path,
            tags=t.tags(text),
            cover_url=t.cover_url,
            codec=got.codec,
            bitrate=got.bitrate,
            lossy=got.codec != "flac",
            duration_ms=t.duration_ms,
            source_id=t.id,
        )


def _codec_label(codec: str, bitrate: int | None) -> str:
    name = CODEC_LABELS.get(codec, codec.upper())
    return f"{name} {bitrate}" if bitrate and codec != "flac" else name


@dataclass
class TrackMeta:
    tags: Tags
    cover_url: str | None
    source_id: str


class YandexMetadata:
    """«Яндекс: метаданные»: теги, обложка и текст из Яндекса для файлов из других источников.

    Работает и на бесплатном аккаунте (и без токена — тогда без текстов). Только при уверенном совпадении.
    """

    def __init__(self, api: YandexApi, enabled: Callable[[], bool]) -> None:
        self._api = api
        self._enabled = enabled

    @property
    def enabled(self) -> bool:
        return self._enabled()

    async def lookup(self, artist: str, title: str, duration_ms: int | None) -> TrackMeta | None:
        if not self._enabled():
            return None
        best: tuple[float, YTrack] | None = None
        for t in await self._api.search_tracks(f"{artist} {title}"):
            score = track_score(artist, title, duration_ms, t.artist, t.title, t.duration_ms)
            if score >= MATCH_THRESHOLD and (best is None or score > best[0]):
                best = (score, t)
        if best is None:
            return None
        track = best[1]
        lyrics = None
        if self._api.has_token:
            try:
                lyrics = await self._api.lyrics(track.id)
            except Exception as e:  # noqa: BLE001 — текста может не быть
                log.debug("Яндекс: текст %s не получен: %s", track.id, e)
        return TrackMeta(tags=track.tags(lyrics), cover_url=track.cover_url, source_id=track.id)
