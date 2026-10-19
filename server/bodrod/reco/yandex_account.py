"""Аккаунт Яндекс Музыки пользователя Navidrome для архива рекомендаций.

Тот же клиент yandex-music, что и у источника загрузки, но со своим токеном, своей паузой (~1 запрос в секунду) и
повтором с бэкоффом при сетевых сбоях. Каждый метод возвращает `(raw, данные)`: `raw` — ответ API как JSON
(для архива на диске), данные — то, что пишется в таблицы.

Ошибка доступа (токен отозван, нет подписки) — `AccessDenied`: харвестер отключает только тот сборщик, на котором
она случилась, остальное продолжает собираться.
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol

from ..sources.yandex import YandexMusicApi, YDownload, YTrack, _convert_track

log = logging.getLogger(__name__)

WAVE_STATION = "user:onyourwave"
RETRIES = 3
BACKOFF_S = (5.0, 20.0, 60.0)


class AccessDenied(Exception):  # noqa: N818 — это состояние аккаунта, а не сбой
    """Яндекс отказал в доступе: токен недействителен или нужна подписка."""


@dataclass(frozen=True)
class WaveConfig:
    """Настройки «Моей волны»: характер (diversity), настроение (mood_energy), язык."""

    diversity: str
    mood_energy: str
    language: str

    @property
    def seed_id(self) -> str:
        return f"wave:{self.diversity}:{self.mood_energy}:{self.language}"


@dataclass
class Playlist:
    title: str
    kind: str
    tracks: list[YTrack]


class HarvestApi(Protocol):
    """Что харвестеру нужно от аккаунта (в тестах — подделка)."""

    async def has_plus(self) -> bool: ...

    async def liked_tracks(self) -> tuple[Any, list[tuple[YTrack, int | None]]]: ...

    async def disliked_tracks(self) -> tuple[Any, list[YTrack]]: ...

    async def liked_artists(self) -> tuple[Any, list[tuple[str, str, int | None]]]: ...

    async def liked_albums(self) -> tuple[Any, list[tuple[str, str, str | None, int | None, int | None]]]: ...

    async def similar_tracks(self, track_id: str) -> tuple[Any, list[YTrack]]: ...

    async def similar_artists(self, artist_id: str) -> tuple[Any, list[tuple[str, str]]]: ...

    async def wave_config(self) -> WaveConfig | None: ...

    async def set_wave_config(self, config: WaveConfig) -> None: ...

    async def wave_batch(self, queue: str | None) -> tuple[Any, list[YTrack]]: ...

    async def personal_playlists(self) -> tuple[Any, list[Playlist]]: ...

    async def history(self) -> tuple[Any, list[YTrack]]: ...

    async def chart(self) -> tuple[Any, list[YTrack]]: ...

    async def new_releases(self) -> tuple[Any, list[tuple[str, str, str | None, int | None]]]: ...

    async def download(self, track_id: str, dest_stem: Path) -> YDownload: ...

    async def lyrics(self, track_id: str) -> str | None: ...


def _raw(value: Any) -> Any:
    if value is None:
        return None
    if isinstance(value, list):
        return [_raw(v) for v in value]
    to_dict = getattr(value, "to_dict", None)
    return to_dict() if callable(to_dict) else value


def _is_access_error(error: BaseException) -> bool:
    from yandex_music.exceptions import UnauthorizedError

    if isinstance(error, UnauthorizedError):
        return True
    text = str(error).lower()
    return any(word in text for word in ("403", "forbidden", "unauthorized", "not-allowed", "subscription"))


def _is_retryable(error: BaseException) -> bool:
    from yandex_music.exceptions import NetworkError, TimedOutError

    return isinstance(error, NetworkError | TimedOutError | TimeoutError | ConnectionError)


class YandexAccountApi(YandexMusicApi):
    """yandex-music аккаунта пользователя: пауза ~1 с, повтор с бэкоффом, ошибки доступа → `AccessDenied`."""

    def __init__(
        self,
        username: str,
        token: str,
        delay: tuple[float, float] = (1.0, 1.2),
        sleep: Callable[[float], Any] = asyncio.sleep,
    ) -> None:
        super().__init__(token, delay)
        self.username = username
        self._sleep = sleep

    async def _safe(self, fn: Callable[[Any], Any]) -> Any:
        for attempt in range(RETRIES + 1):
            try:
                return await self._call(fn)
            except Exception as e:
                from yandex_music.exceptions import NotFoundError

                if isinstance(e, NotFoundError):
                    return None
                if _is_access_error(e):
                    raise AccessDenied(str(e) or type(e).__name__) from e
                if attempt < RETRIES and _is_retryable(e):
                    wait = BACKOFF_S[min(attempt, len(BACKOFF_S) - 1)]
                    log.info("Яндекс (%s): %s — повтор через %.0f с", self.username, e, wait)
                    await self._sleep(wait)
                    continue
                raise
        return None

    async def _full_tracks(self, ids: list[str]) -> tuple[list[Any], list[YTrack]]:
        raw: list[Any] = []
        result: list[YTrack] = []
        for start in range(0, len(ids), 100):
            batch = ids[start : start + 100]
            tracks = await self._safe(lambda c, b=batch: c.tracks(b)) or []
            raw.extend(_raw(t) for t in tracks)
            result.extend(_convert_track(t) for t in tracks)
        return raw, result

    async def has_plus(self) -> bool:
        status = await self._safe(lambda c: c.account_status())
        plus = getattr(status, "plus", None)
        return bool(plus and plus.has_plus)

    async def liked_tracks(self) -> tuple[Any, list[tuple[YTrack, int | None]]]:
        likes = await self._safe(lambda c: c.users_likes_tracks())
        shorts = (likes.tracks if likes else None) or []
        stamps = {str(s.track_id): _timestamp(getattr(s, "timestamp", None)) for s in shorts}
        raw, tracks = await self._full_tracks([str(s.track_id) for s in shorts])
        return {"likes": _raw(likes), "tracks": raw}, [(t, stamps.get(t.id)) for t in tracks]

    async def disliked_tracks(self) -> tuple[Any, list[YTrack]]:
        dislikes = await self._safe(lambda c: c.users_dislikes_tracks())
        shorts = (dislikes.tracks if dislikes else None) or []
        raw, tracks = await self._full_tracks([str(s.track_id) for s in shorts])
        return {"dislikes": _raw(dislikes), "tracks": raw}, tracks

    async def liked_artists(self) -> tuple[Any, list[tuple[str, str, int | None]]]:
        likes = await self._safe(lambda c: c.users_likes_artists()) or []
        result = []
        for like in likes:
            artist = getattr(like, "artist", None)
            if artist is not None and artist.id is not None and artist.name:
                result.append((str(artist.id), artist.name, _timestamp(getattr(like, "timestamp", None))))
        return _raw(likes), result

    async def liked_albums(self) -> tuple[Any, list[tuple[str, str, str | None, int | None, int | None]]]:
        likes = await self._safe(lambda c: c.users_likes_albums()) or []
        result = []
        for like in likes:
            album = getattr(like, "album", None)
            if album is not None and album.id is not None:
                artist = ", ".join(a.name for a in (album.artists or []) if a.name) or None
                stamp = _timestamp(getattr(like, "timestamp", None))
                result.append((str(album.id), album.title or "", artist, getattr(album, "year", None), stamp))
        return _raw(likes), result

    async def similar_tracks(self, track_id: str) -> tuple[Any, list[YTrack]]:
        similar = await self._safe(lambda c: c.tracks_similar(track_id))
        tracks = (getattr(similar, "similar_tracks", None) or []) if similar else []
        return _raw(similar), [_convert_track(t) for t in tracks]

    async def similar_artists(self, artist_id: str) -> tuple[Any, list[tuple[str, str]]]:
        similar = await self._safe(lambda c: c.artists_similar(artist_id))
        artists = (getattr(similar, "similar_artists", None) or []) if similar else []
        return _raw(similar), [(str(a.id), a.name) for a in artists if a.id is not None and a.name]

    async def wave_config(self) -> WaveConfig | None:
        info = await self._safe(lambda c: c.rotor_station_info(WAVE_STATION)) or []
        settings = getattr(info[0], "settings2", None) if info else None
        if settings is None:
            return None
        return WaveConfig(
            diversity=settings.diversity or "default",
            mood_energy=settings.mood_energy or "all",
            language=settings.language or "any",
        )

    async def set_wave_config(self, config: WaveConfig) -> None:
        await self._safe(
            lambda c: c.rotor_station_settings2(
                WAVE_STATION, mood_energy=config.mood_energy, diversity=config.diversity, language=config.language
            )
        )

    async def wave_batch(self, queue: str | None) -> tuple[Any, list[YTrack]]:
        # Только чтение: ни radioStarted, ни trackStarted/skip не отправляются — живая волна не меняется.
        result = await self._safe(lambda c: c.rotor_station_tracks(WAVE_STATION, settings2=True, queue=queue))
        sequence = (getattr(result, "sequence", None) or []) if result else []
        tracks = [_convert_track(s.track) for s in sequence if getattr(s, "track", None) is not None]
        return _raw(result), tracks

    async def personal_playlists(self) -> tuple[Any, list[Playlist]]:
        feed = await self._safe(lambda c: c.feed())
        generated = (getattr(feed, "generated_playlists", None) or []) if feed else []
        raw: list[Any] = []
        result: list[Playlist] = []
        for item in generated:
            playlist = getattr(item, "data", None)
            if playlist is None:
                continue
            shorts = getattr(playlist, "tracks", None)
            if not shorts:
                owner = playlist.uid or getattr(getattr(playlist, "owner", None), "uid", None)
                full = await self._safe(lambda c, p=playlist, o=owner: c.users_playlists(p.kind, o))
                shorts = (getattr(full, "tracks", None) or []) if full else []
            ids = [str(s.track_id) for s in shorts if getattr(s, "track_id", None) is not None]
            tracks_raw, tracks = await self._full_tracks(ids)
            raw.append({"playlist": _raw(playlist), "tracks": tracks_raw})
            result.append(Playlist(title=playlist.title or item.type or "", kind=str(playlist.kind), tracks=tracks))
        return raw, result

    async def history(self) -> tuple[Any, list[YTrack]]:
        """История прослушиваний (если API её отдаёт): треки по порядку, без дат."""
        history = await self._safe(lambda c: c.music_history(full_models_count=0))
        ids: list[str] = []
        for tab in (getattr(history, "history_tabs", None) or []) if history else []:
            for group in getattr(tab, "items", None) or []:
                for item in getattr(group, "tracks", None) or getattr(group, "items", None) or []:
                    data = getattr(item, "data", None) or item
                    item_id = getattr(data, "item_id", None) or getattr(data, "id", None)
                    track_id = getattr(item_id, "track_id", None) or (item_id if isinstance(item_id, str) else None)
                    if track_id:
                        ids.append(str(track_id))
        _, tracks = await self._full_tracks(list(dict.fromkeys(ids)))
        return _raw(history), tracks

    async def chart(self) -> tuple[Any, list[YTrack]]:
        info = await self._safe(lambda c: c.chart())
        playlist = getattr(info, "chart", None) if info else None
        shorts = (getattr(playlist, "tracks", None) or []) if playlist else []
        tracks = [_convert_track(s.track) for s in shorts if getattr(s, "track", None) is not None]
        return _raw(info), tracks

    async def new_releases(self) -> tuple[Any, list[tuple[str, str, str | None, int | None]]]:
        landing = await self._safe(lambda c: c.new_releases())
        ids = [str(i) for i in (getattr(landing, "new_releases", None) or [])] if landing else []
        albums = await self._safe(lambda c: c.albums(ids)) if ids else []
        result = []
        for album in albums or []:
            artist = ", ".join(a.name for a in (album.artists or []) if a.name) or None
            result.append((str(album.id), album.title or "", artist, getattr(album, "year", None)))
        return {"landing": _raw(landing), "albums": _raw(albums)}, result


def _timestamp(value: Any) -> int | None:
    """ISO-дата лайка из API → мс."""
    if not value:
        return None
    from datetime import datetime

    try:
        return int(datetime.fromisoformat(str(value).replace("Z", "+00:00")).timestamp() * 1000)
    except ValueError:
        return None
