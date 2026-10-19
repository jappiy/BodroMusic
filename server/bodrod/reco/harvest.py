"""Архив Яндекс Музыки для рекомендаций: всё, что пригодится движку кандидатов, пока жива подписка (до 20.11.2026).

По каждому аккаунту (пользователю Navidrome) раз в 10 минут проверяется, каким сборщикам пора работать:

| Сборщик | Как часто | Что пишет |
|---|---|---|
| `likes` | раз в сутки | лайки треков, альбомов, артистов; дизлайки → чёрный список пользователя |
| `similar` | пока есть необработанные лайки | рёбра `similar`: лайкнутый трек → похожие |
| `artists` | топ-200 артистов, раз в 30 дней | рёбра `artist_similar`: артист → похожие артисты |
| `wave` | 2–3 раза в сутки | рёбра `wave`: режим волны → треки (несколько пачек подряд) |
| `playlists` | раз в сутки | рёбра `playlist`: персональный плейлист → треки (снимок дня) |
| `history` | раз в сутки | рёбра `history` (если API отдаёт историю) |
| `common` | раз в сутки, с первого аккаунта | рёбра `chart` и `new_release` для всех (`username = '*'`) |
| `reserve` | каждый проход, пока есть Плюс | предзакачка лучших кандидатов в `_reserve` (до 500 на пользователя) |

Сырые ответы — в `<YANDEX_ARCHIVE_DIR>/<пользователь>/<дата>/<тип>.json`, нормализованное — в таблицы
`reco.store`. Ошибка доступа отключает только свой сборщик (через 7 дней — новая попытка): то, что Яндекс отдаёт
без Плюса, собирается и дальше. Разовые проходы (`similar`, `artists`) продолжаются с места обрыва.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import logging
import os
import time
from collections.abc import Callable
from datetime import datetime
from pathlib import Path
from typing import Any

from ..config import Settings
from ..db import Store
from ..placement import sanitize_part
from ..sources.base import SourceMiss, is_preview, unwanted_extras
from ..sources.placer import Placer
from ..sources.yandex import YTrack
from .keys import track_key
from .library import LibraryIndex
from .store import ALL_USERS, DAY_MS, RecoStore
from .yandex_account import AccessDenied, HarvestApi, WaveConfig

log = logging.getLogger(__name__)

TICK_S = 600
STATE_KEY = "harvest.{user}"
DISABLED_RETRY_MS = 7 * DAY_MS
SIMILAR_CHUNK = 150
ARTISTS_CHUNK = 40
ARTISTS_REFRESH_MS = 30 * DAY_MS
DAILY_MS = DAY_MS - 30 * 60 * 1000
NDIGNORE = ".ndignore"

DIVERSITIES = ("favorite", "discover", "popular")
MOODS = ("active", "fun", "calm", "sad")
LANGUAGES = ("russian", "not-russian")
"""Все 24 режима волны: характер × настроение × язык; прогоны идут по кругу."""
WAVE_MODES = tuple(WaveConfig(d, m, lang) for d in DIVERSITIES for m in MOODS for lang in LANGUAGES)

COLLECTORS = ("likes", "similar", "artists", "wave", "playlists", "history", "common", "reserve")
COLLECTOR_TITLES = {
    "likes": "Лайки и дизлайки",
    "similar": "Похожие треки",
    "artists": "Похожие артисты",
    "wave": "Моя волна",
    "playlists": "Персональные плейлисты",
    "history": "История",
    "common": "Чарт и новинки",
    "reserve": "Предзакачка в резерв",
}


def ms() -> int:
    return int(time.time() * 1000)


class YandexHarvester:
    def __init__(
        self,
        store: Store,
        reco: RecoStore,
        settings: Settings,
        accounts: dict[str, HarvestApi],
        library: LibraryIndex,
        placer: Placer | None,
        clock: Callable[[], int] = ms,
    ) -> None:
        self._store = store
        self._reco = reco
        self._settings = settings
        self._accounts = accounts
        self._library = library
        self._placer = placer
        self._clock = clock
        self._wakeup = asyncio.Event()
        self._running: dict[str, str] = {}

    @property
    def usernames(self) -> list[str]:
        return list(self._accounts)

    # region Состояние

    def _state(self, user: str) -> dict[str, Any]:
        return self._store.get_value(STATE_KEY.format(user=user)) or {"last": {}, "disabled": {}, "counts": {}}

    def _save(self, user: str, state: dict[str, Any]) -> None:
        self._store.set_value(STATE_KEY.format(user=user), state)

    def _due(self, state: dict[str, Any], name: str, interval_ms: int) -> bool:
        now = self._clock()
        disabled = state["disabled"].get(name)
        if disabled is not None:
            if now - int(disabled.get("at", 0)) < DISABLED_RETRY_MS:
                return False
            state["disabled"].pop(name, None)
        return now - int(state["last"].get(name, 0)) >= interval_ms

    def status(self) -> list[dict[str, Any]]:
        """Состояние по аккаунтам для экрана в приложении."""
        result = []
        for user in self._accounts:
            state = self._state(user)
            result.append(
                {
                    "username": user,
                    "plus": state.get("plus"),
                    "running": self._running.get(user),
                    "collectors": [
                        {
                            "name": name,
                            "title": COLLECTOR_TITLES[name],
                            "lastAt": state["last"].get(name),
                            "disabled": (state["disabled"].get(name) or {}).get("reason"),
                            "count": state["counts"].get(name),
                        }
                        for name in COLLECTORS
                        if name != "common" or user == self.usernames[0]
                    ],
                    "likes": self._reco.like_counts(user),
                    "edges": self._reco.edge_counts(user),
                    "reserve": self._reco.reserve_count(user),
                    "blocked": self._reco.user_blocked_count(user),
                    "similarDone": len(self._reco.done_ids(user, "similar")),
                    "error": state.get("error"),
                }
            )
        return result

    def wake(self) -> None:
        self._wakeup.set()

    # endregion

    async def run_forever(self) -> None:
        while True:
            for user in list(self._accounts):
                try:
                    await self.tick(user)
                except Exception as e:  # noqa: BLE001 — один аккаунт не останавливает остальные
                    log.exception("Архив Яндекса (%s): ошибка прохода", user)
                    state = self._state(user)
                    state["error"] = f"{type(e).__name__}: {e}"
                    self._save(user, state)
                finally:
                    self._running.pop(user, None)
            self._wakeup.clear()
            with contextlib.suppress(TimeoutError):
                await asyncio.wait_for(self._wakeup.wait(), timeout=TICK_S)

    async def tick(self, user: str) -> None:
        """Один проход по аккаунту: все сборщики, которым пора."""
        api = self._accounts[user]
        state = self._state(user)
        state.pop("error", None)
        wave_every = DAY_MS // self._settings.harvest_wave_runs_per_day - 10 * 60 * 1000
        plan: list[tuple[str, int, Callable[[], Any]]] = [
            ("likes", DAILY_MS, lambda: self._likes(user, api, state)),
            ("similar", 0, lambda: self._similar(user, api, state)),
            ("artists", 0, lambda: self._artists(user, api, state)),
            ("wave", wave_every, lambda: self._wave(user, api, state)),
            ("playlists", DAILY_MS, lambda: self._playlists(user, api)),
            ("history", DAILY_MS, lambda: self._history(user, api)),
        ]
        if user == self.usernames[0]:
            plan.append(("common", DAILY_MS, lambda: self._common(api)))
        plan.append(("reserve", 0, lambda: self._reserve(user, api, state)))
        for name, interval, run in plan:
            if not self._due(state, name, interval):
                continue
            self._running[user] = name
            try:
                count = await run()
            except AccessDenied as e:
                log.warning("Архив Яндекса (%s): «%s» отключён — нет доступа: %s", user, COLLECTOR_TITLES[name], e)
                state["disabled"][name] = {"reason": f"Нет доступа: {e}", "at": self._clock()}
                self._save(user, state)
                continue
            if count is not None:
                state["counts"][name] = count
                state["last"][name] = self._clock()
            self._save(user, state)

    # region Архив на диске

    def _archive(self, user: str, kind: str, raw: Any) -> None:
        if raw is None:
            return
        now = datetime.fromtimestamp(self._clock() / 1000)
        folder = Path(self._settings.archive_path) / sanitize_part(user) / now.strftime("%Y-%m-%d")
        try:
            folder.mkdir(parents=True, exist_ok=True)
            path = folder / f"{sanitize_part(kind)}.json"
            if path.exists():
                path = folder / f"{sanitize_part(kind)}-{now.strftime('%H%M%S')}.json"
            tmp = path.with_name(f".{path.name}.part")
            tmp.write_text(json.dumps(raw, ensure_ascii=False, default=str), encoding="utf-8")
            os.replace(tmp, path)
        except OSError as e:
            log.warning("Архив Яндекса (%s): не записан %s: %s", user, kind, e)

    # endregion

    # region Сборщики

    async def _likes(self, user: str, api: HarvestApi, state: dict[str, Any]) -> int:
        raw, liked = await api.liked_tracks()
        self._archive(user, "likes-tracks", raw)
        self._reco.upsert_tracks([t for t, _ in liked])
        new = self._reco.replace_likes(user, "track", 1, [(t.id, at) for t, at in liked])
        raw, disliked = await api.disliked_tracks()
        self._archive(user, "dislikes-tracks", raw)
        self._reco.upsert_tracks(disliked)
        self._reco.replace_likes(user, "track", -1, [(t.id, None) for t in disliked])
        for t in disliked:
            self._reco.block_user(user, t.artist, t.title, "Дизлайк в Яндекс Музыке")
        raw, artists = await api.liked_artists()
        self._archive(user, "likes-artists", raw)
        self._reco.upsert_artists([(a, n) for a, n, _ in artists])
        self._reco.replace_likes(user, "artist", 1, [(a, at) for a, _, at in artists])
        raw, albums = await api.liked_albums()
        self._archive(user, "likes-albums", raw)
        self._reco.upsert_albums([(i, t, a, y) for i, t, a, y, _ in albums])
        self._reco.replace_likes(user, "album", 1, [(i, at) for i, _, _, _, at in albums])
        log.info(
            "Архив Яндекса (%s): лайков %s (новых %s), дизлайков %s, артистов %s, альбомов %s",
            user, len(liked), new, len(disliked), len(artists), len(albums),
        )  # fmt: skip
        return len(liked)

    async def _similar(self, user: str, api: HarvestApi, state: dict[str, Any]) -> int | None:
        """Похожие для лайкнутых треков, порциями; каждый трек — один раз (новые лайки догоняются)."""
        done = self._reco.done_ids(user, "similar")
        pending = [i for i in self._reco.liked_ids(user) if i not in done][:SIMILAR_CHUNK]
        if not pending:
            return None
        raw_chunk: dict[str, Any] = {}
        for track_id in pending:
            raw, tracks = await api.similar_tracks(track_id)
            raw_chunk[track_id] = raw
            self._reco.upsert_tracks(tracks)
            self._reco.add_edges(user, "similar", track_id, [t.id for t in tracks])
            self._reco.mark_done(user, "similar", [track_id])
        self._archive(user, "similar", raw_chunk)
        total = len(done) + len(pending)
        log.info("Архив Яндекса (%s): похожие треки — %s лайков обработано", user, total)
        return total

    async def _artists(self, user: str, api: HarvestApi, state: dict[str, Any]) -> int | None:
        fresh_since = self._clock() - ARTISTS_REFRESH_MS
        done = self._reco.done_ids(user, "artists", fresh_since)
        top = self._reco.top_artists(user, self._settings.harvest_top_artists)
        pending = [a for a in top if a not in done][:ARTISTS_CHUNK]
        if not pending:
            return None
        raw_chunk: dict[str, Any] = {}
        for artist_id in pending:
            raw, similar = await api.similar_artists(artist_id)
            raw_chunk[artist_id] = raw
            self._reco.upsert_artists(similar)
            self._reco.add_edges(user, "artist_similar", artist_id, [a for a, _ in similar])
            self._reco.mark_done(user, "artists", [artist_id])
        self._archive(user, "similar-artists", raw_chunk)
        return len(done) + len(pending)

    async def _wave(self, user: str, api: HarvestApi, state: dict[str, Any]) -> int:
        """Один прогон волны в следующем режиме; настройки аккаунта сохраняются и восстанавливаются."""
        index = int(state.get("waveIndex", 0)) % len(WAVE_MODES)
        mode = WAVE_MODES[index]
        saved = await api.wave_config()
        total = 0
        raw_batches: list[Any] = []
        try:
            await api.set_wave_config(mode)
            queue: str | None = None
            for _ in range(self._settings.harvest_wave_batches):
                raw, tracks = await api.wave_batch(queue)
                raw_batches.append(raw)
                if not tracks:
                    break
                self._reco.upsert_tracks(tracks)
                self._reco.add_edges(user, "wave", mode.seed_id, [t.id for t in tracks])
                total += len(tracks)
                queue = tracks[-1].id
        finally:
            if saved is not None and saved != mode:
                with contextlib.suppress(Exception):
                    await api.set_wave_config(saved)
        state["waveIndex"] = index + 1
        self._archive(user, f"wave-{mode.diversity}-{mode.mood_energy}-{mode.language}", raw_batches)
        log.info("Архив Яндекса (%s): волна %s — %s треков", user, mode.seed_id, total)
        return total

    async def _playlists(self, user: str, api: HarvestApi) -> int:
        raw, playlists = await api.personal_playlists()
        self._archive(user, "personal-playlists", raw)
        for playlist in playlists:
            self._reco.upsert_tracks(playlist.tracks)
            self._reco.add_edges(user, "playlist", f"playlist:{playlist.title}", [t.id for t in playlist.tracks])
        return sum(len(p.tracks) for p in playlists)

    async def _history(self, user: str, api: HarvestApi) -> int:
        raw, tracks = await api.history()
        self._archive(user, "history", raw)
        self._reco.upsert_tracks(tracks)
        self._reco.add_edges(user, "history", "history", [t.id for t in tracks])
        return len(tracks)

    async def _common(self, api: HarvestApi) -> int:
        raw, chart = await api.chart()
        self._archive(ALL_USERS, "chart", raw)
        self._reco.upsert_tracks(chart)
        day = datetime.fromtimestamp(self._clock() / 1000).strftime("%Y-%m-%d")
        self._reco.add_edges(ALL_USERS, "chart", f"chart:{day}", [t.id for t in chart])
        raw, albums = await api.new_releases()
        self._archive(ALL_USERS, "new-releases", raw)
        self._reco.upsert_albums(albums)
        self._reco.add_edges(ALL_USERS, "new_release", f"new:{day}", [a[0] for a in albums])
        return len(chart)

    # endregion

    # region Предзакачка в резерв

    async def _reserve(self, user: str, api: HarvestApi, state: dict[str, Any]) -> int | None:
        if self._placer is None:
            return None
        settings = self._settings
        have = self._reco.reserve_count(user)
        if have >= settings.reserve_limit:
            return have
        now = self._clock()
        if now - int(state.get("plusCheckedAt", 0)) > 6 * 3600 * 1000:
            state["plus"] = await api.has_plus()
            state["plusCheckedAt"] = now
        if not state.get("plus"):
            return None
        picked = await self.reserve_candidates(user, min(settings.reserve_batch, settings.reserve_limit - have))
        if not picked:
            return have
        root = Path(settings.reserve_path)
        await asyncio.to_thread(_ensure_ignored, root)
        workdir = Path(settings.staging_dir) / f"reserve-{sanitize_part(user)}"
        placed = 0
        try:
            for track, score in picked:
                if await self._prefetch(user, api, track, score, root, workdir):
                    placed += 1
        finally:
            await asyncio.to_thread(_remove_empty, workdir)
        log.info("Архив Яндекса (%s): в резерв положено %s (всего %s)", user, placed, have + placed)
        return have + placed

    async def reserve_candidates(self, user: str, limit: int) -> list[tuple[YTrack, int]]:
        """Лучшие кандидаты по частоте в волне и похожих, которых нет в библиотеке, пуле, резерве и чёрных списках."""
        if limit <= 0:
            return []
        snapshot = await self._library.snapshot()
        blocked = self._reco.user_blocked_keys(user) | _global_blocked(self._store)
        taken = self._reco.pool_keys()
        in_pool = self._reco.yandex_ids_in_pool()
        liked = set(self._reco.liked_ids(user))
        ranked = self._reco.candidate_frequency(user, ("wave", "similar"), limit * 20)
        infos = self._reco.tracks([c for c, _ in ranked])
        result: list[tuple[YTrack, int]] = []
        for cand_id, freq in ranked:
            info = infos.get(cand_id)
            if info is None or not info.available or cand_id in in_pool or cand_id in liked:
                continue
            key = track_key(info.artist, info.title)
            if key in blocked or key in taken or key in snapshot.library or unwanted_extras(info.title, ""):
                continue
            if not snapshot.library and await self._library.in_library(info.artist, info.title):
                continue
            taken.add(key)
            result.append((_ytrack(info), freq))
            if len(result) >= limit:
                break
        return result

    async def _prefetch(self, user: str, api: HarvestApi, track: YTrack, score: int, root: Path, workdir: Path) -> bool:
        assert self._placer is not None
        track_id = self._reco.add_pool_track(
            artist=track.artist,
            title=track.title,
            album=track.album,
            duration_ms=track.duration_ms,
            cover_url=track.cover_url,
            yandex_id=track.id,
            location="downloading",
        )
        self._reco.set_owner(track_id, user, "candidate", "yandex_archive", float(score))
        try:
            file = await _download(api, track, workdir, self._settings.preview_ratio)
            dest = await self._placer.place(file, "yandex", None, root=root)
        except AccessDenied:
            self._reco.update_pool_track(track_id, location="gone", message="Нет доступа к Яндексу")
            raise
        except SourceMiss as e:
            self._reco.update_pool_track(track_id, location="failed", message=e.reason)
            self._reco.set_owner(track_id, user, "failed", None)
            return False
        except Exception as e:  # noqa: BLE001 — один трек не останавливает предзакачку
            from .yandex_account import _is_access_error

            if _is_access_error(e):
                self._reco.update_pool_track(track_id, location="gone", message=str(e))
                raise AccessDenied(str(e)) from e
            log.warning("Резерв (%s): %s — %s: %s", user, track.artist, track.title, e)
            self._reco.update_pool_track(track_id, location="failed", message=str(e))
            self._reco.set_owner(track_id, user, "failed", None)
            return False
        self._reco.update_pool_track(track_id, location="reserve", path=str(dest))
        return True

    # endregion


async def _download(api: HarvestApi, track: YTrack, workdir: Path, preview_ratio: float) -> Any:
    """Скачать трек аккаунтом пользователя (FLAC, иначе AAC) с проверкой на превью."""
    from ..sources.base import FetchedFile

    workdir.mkdir(parents=True, exist_ok=True)
    stem = workdir / sanitize_part(f"{track.artist} - {track.title} [{track.id}]")
    got = await api.download(track.id, stem)
    short = is_preview(got.path, track.duration_ms, preview_ratio)
    if short is not None:
        got.path.unlink(missing_ok=True)
        raise SourceMiss(f"Яндекс отдал превью ({short:.0f} с)")
    lyrics = None
    with contextlib.suppress(Exception):
        lyrics = await api.lyrics(track.id)
    return FetchedFile(
        path=got.path,
        tags=track.tags(lyrics),
        cover_url=track.cover_url,
        codec=got.codec,
        bitrate=got.bitrate,
        lossy=got.codec != "flac",
        duration_ms=track.duration_ms,
        source_id=track.id,
    )


def _ytrack(info: Any) -> YTrack:
    return YTrack(
        id=info.id,
        title=info.title,
        artists=info.artists,
        artist_ids=info.artist_ids,
        duration_ms=info.duration_ms,
        album=info.album,
        album_id=info.album_id,
        year=info.year,
        genre=info.genre,
        cover_url=info.cover_url,
        available=info.available,
    )


def _global_blocked(store: Store) -> set[str]:
    """Общий чёрный список (удалённые с сервера) — в ключах рекомендаций."""
    return {track_key(r["artist"], r["title"]) for r in store.query("SELECT artist, title FROM blocklist")}


def _ensure_ignored(root: Path) -> None:
    """Резерв не должен попасть в библиотеку: `.ndignore` в корне папки."""
    root.mkdir(parents=True, exist_ok=True)
    marker = root / NDIGNORE
    if not marker.exists():
        marker.write_text("*\n", encoding="utf-8")  # шаблон «всё»: подходит старым и новым версиям Navidrome


def _remove_empty(path: Path) -> None:
    with contextlib.suppress(OSError):
        if path.exists() and not any(path.iterdir()):
            path.rmdir()
