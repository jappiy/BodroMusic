"""Раскладка файлов прямых источников (Яндекс, YouTube) в библиотеку — тем же способом, что и треки slskd.

Шаблон пути `LIBRARY_LAYOUT`, теги и обложка из каталога, копия через `.part` и атомарное переименование.
Если включены «Яндекс: метаданные», файлы не из Яндекса перетегируются по Яндексу при уверенном совпадении.
Каждый положенный файл записывается в `library_files` (источник, кодек, lossy, id в источнике).
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
from collections.abc import Awaitable, Callable
from dataclasses import replace
from pathlib import Path

from ..config import Settings
from ..db import Store
from ..placement import Tags, choose_destination, place_file, relative_path
from .base import FetchedFile
from .yandex import YandexMetadata

log = logging.getLogger(__name__)

CoverFetcher = Callable[[str], Awaitable[tuple[bytes, str] | None]]
LOSSLESS = {"flac", "wav", "alac", "ape", "wv", "aiff"}


def file_length_ms(path: Path) -> int | None:
    try:
        import mutagen

        audio = mutagen.File(path)
    except Exception:  # noqa: BLE001 — не аудио
        return None
    length = getattr(getattr(audio, "info", None), "length", None) if audio is not None else None
    return int(length * 1000) if length else None


class Placer:
    def __init__(
        self,
        settings: Settings,
        store: Store,
        cover_fetcher: CoverFetcher,
        metadata: YandexMetadata | None,
    ) -> None:
        self._settings = settings
        self._store = store
        self._cover_fetcher = cover_fetcher
        self._metadata = metadata

    async def retag(
        self, tags: Tags, cover_url: str | None, path: Path, duration_ms: int | None = None
    ) -> tuple[Tags, str | None, bool]:
        """Теги и обложка по Яндексу (если включены метаданные и совпадение уверенное). `True` — перетегировано.

        Длительность для сравнения — из каталога источника, а если её нет — самого файла.
        """
        if self._metadata is None or not self._metadata.enabled:
            return tags, cover_url, False
        try:
            duration_ms = duration_ms or await asyncio.to_thread(file_length_ms, path)
            meta = await self._metadata.lookup(tags.artist, tags.title, duration_ms)
        except Exception as e:  # noqa: BLE001 — каталог недоступен: оставляем свои теги
            log.warning("Яндекс: метаданные для «%s — %s» не получены: %s", tags.artist, tags.title, e)
            return tags, cover_url, False
        if meta is None:
            return tags, cover_url, False
        new_tags = meta.tags if meta.tags.album or not tags.album else replace(meta.tags, album=tags.album)
        log.info("Перетегировано по Яндексу: %s — %s", new_tags.artist, new_tags.title)
        return new_tags, meta.cover_url or cover_url, True

    async def place(self, file: FetchedFile, source: str, request_id: str | None) -> Path:
        library = self._settings.library_dir
        assert library is not None
        tags, cover_url = file.tags, file.cover_url
        if source != "yandex":
            tags, cover_url, _ = await self.retag(tags, cover_url, file.path, file.duration_ms)
        folder_artist = tags.album_artist or tags.artist
        rel = relative_path(
            self._settings.library_layout,
            artist=folder_artist,
            title=tags.title,
            album=tags.album,
            extension=file.path.suffix,
        )
        dest, already = await asyncio.to_thread(choose_destination, Path(library), rel, file.path, tags)
        if already:
            log.info("Уже есть в библиотеке: %s", dest)
        else:
            cover = None
            if cover_url:
                try:
                    cover = await self._cover_fetcher(cover_url)
                except Exception as e:  # noqa: BLE001 — без обложки трек всё равно нужен
                    log.warning("Обложку %s скачать не удалось: %s", cover_url, e)
            written = await asyncio.to_thread(place_file, file.path, dest, tags, cover)
            log.info("%s: положен %s, теги: %s", source, dest, ", ".join(written) or "нет")
        with contextlib.suppress(FileNotFoundError):
            file.path.unlink()
        lossy = file.lossy or file.codec.lower() not in LOSSLESS
        self._store.add_library_file(str(dest), request_id, source, file.codec, lossy, file.source_id)
        return dest
