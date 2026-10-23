"""Движок кандидатов: раз в сутки по семенам пользователя собирает треки из нескольких источников и пополняет пул.

Семена — лайки, топ по прослушиваниям (БД Navidrome) и свежие promote пула; вес семени затухает с полураспадом
`ENGINE_SEED_HALF_LIFE_DAYS` (90). Источники (`SourceAdapter`) отдают кандидатов с общей схемой: исполнитель,
название, альбом, схожесть 0..1, источник, семя:

- `lastfm` — `track.getSimilar`; `artist.getSimilar` → `artist.getTopTracks` (3–5 треков похожих артистов, которых
  нет в библиотеке); теги семян → `tag.getTopTracks`;
- `deezer` — поиск артиста семени → `/artist/{id}/related` (топ похожих) и `/artist/{id}/radio`;
- `listenbrainz` — LB Radio по артистам и тегам семян; плейлисты Weekly Exploration / Weekly Jams пользователя;
- `yandex_archive` — рёбра `similar`, `wave` и `artist_similar` архива Яндекса (задача 1) того аккаунта, к которому
  привязан пользователь (`reco/yandex_links.py`).

Источник с ошибкой конфигурации (нет ключа, не привязан аккаунт) в подборе не участвует, даже если включён.
Статистика источника — по статусам кандидатов (`store.candidate_state`): в очереди, качается, скачано (резерв или пул),
не найдено, в библиотеку, отклонено.

Сопоставление — по `keys.track_key`; кандидат проходит, только если его нет в библиотеке, пуле, резерве и чёрных
списках, и если это не лайв/караоке/минусовка/кавер (если само семя не такое). Скоринг:
Σ (вес источника × схожесть × вес семени), ×1.3 за каждый следующий источник, предложивший тот же трек; штраф артистам
с reject; не больше двух треков одного артиста за пополнение; доля новых для пользователя артистов настраивается.
Вес источника = ручной (из приложения) × выученный по доле promote (сглаживание Beta(2, 2): три неудачи не обнуляют).
"""

from __future__ import annotations

import asyncio
import json
import logging
import math
import time
from collections import defaultdict
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Protocol

import httpx

from ..config import Settings
from ..db import Store
from ..metadata.lastfm import LastFmClient, LastFmError, _artist_name, _items
from ..navidrome_db import NavidromeDb
from ..sources.base import unwanted_extras
from .keys import artist_key, track_key
from .library import LibraryIndex
from .store import CANDIDATE_STATES, DAY_MS, RecoStore, candidate_state
from .yandex_links import NOT_LINKED, YandexLinks

log = logging.getLogger(__name__)

SOURCE_TITLES = {
    "lastfm": "Last.fm",
    "deezer": "Deezer",
    "listenbrainz": "ListenBrainz",
    "yandex_archive": "Архив Яндекса",
}
CONFIG_KEY = "engine.config"
RUN_KEY = "engine.run.{user}"
MULTI_SOURCE_BONUS = 1.3
ARTIST_PENALTY = 0.3
PRIOR_ALPHA = 2.0
PRIOR_BETA = 2.0
MAX_ARTIST_PER_REFILL = 2


@dataclass
class Seed:
    artist: str
    title: str
    weight: float
    """id трека в Navidrome (семя из библиотеки) или Яндекса."""
    song_id: str | None = None


@dataclass
class Candidate:
    artist: str
    title: str
    source: str
    similarity: float
    seed_weight: float = 1.0
    album: str | None = None
    duration_ms: int | None = None
    cover_url: str | None = None
    """Название семени — чтобы не отсеять лайв, если само семя лайв."""
    seed_title: str = ""
    ids: dict[str, str] = field(default_factory=dict)


@dataclass
class Scored:
    key: str
    artist: str
    title: str
    album: str | None
    duration_ms: int | None
    cover_url: str | None
    score: float
    sources: list[str]
    new_artist: bool


class SourceAdapter(Protocol):
    name: str

    def available(self, username: str) -> tuple[bool, str | None]: ...

    async def candidates(self, username: str, seeds: list[Seed]) -> list[Candidate]: ...


def half_life_weight(age_ms: float, half_life_days: float) -> float:
    return 0.5 ** (max(0.0, age_ms) / (half_life_days * DAY_MS))


def _parse_time(value: Any) -> int | None:
    """Дата из БД Navidrome (`2026-10-01 12:00:00.123+00:00` и т. п.) → мс."""
    if not value:
        return None
    text = str(value).replace("Z", "+00:00").replace(" ", "T", 1)
    try:
        return int(datetime.fromisoformat(text).timestamp() * 1000)
    except ValueError:
        return None


# region Источники


class LastFmAdapter:
    name = "lastfm"

    def __init__(self, client: LastFmClient | None, library: LibraryIndex, seeds_limit: int = 25) -> None:
        self._client = client
        self._library = library
        self._seeds_limit = seeds_limit

    def available(self, username: str) -> tuple[bool, str | None]:
        if self._client is None:
            return False, "Не задан LASTFM_API_KEY"
        return (True, None) if self._client.available else (False, "Last.fm временно не отвечает")

    async def _call(self, method: str, **params: Any) -> dict[str, Any]:
        assert self._client is not None
        try:
            return await self._client.call(method, **params) or {}
        except LastFmError as e:
            log.info("Движок: Last.fm %s — %s", method, e)
            return {}

    async def candidates(self, username: str, seeds: list[Seed]) -> list[Candidate]:
        if self._client is None:
            return []
        result: list[Candidate] = []
        top = sorted(seeds, key=lambda s: -s.weight)[: self._seeds_limit]
        for seed in top:
            body = await self._call("track.getSimilar", artist=seed.artist, track=seed.title, limit=20, autocorrect=1)
            for raw in _items((body.get("similartracks") or {}).get("track")):
                artist, title = _artist_name(raw.get("artist")), raw.get("name") or ""
                if artist and title:
                    result.append(
                        Candidate(artist, title, self.name, float(raw.get("match") or 0.5), seed.weight,
                                  seed_title=seed.title)
                    )  # fmt: skip
        snapshot = await self._library.snapshot()
        artist_weight: dict[str, tuple[str, float]] = {}
        for seed in top:
            key = artist_key(seed.artist)
            if key not in artist_weight or artist_weight[key][1] < seed.weight:
                artist_weight[key] = (seed.artist, seed.weight)
        tags: dict[str, float] = defaultdict(float)
        for name, weight in list(artist_weight.values())[:10]:
            body = await self._call("artist.getSimilar", artist=name, limit=8, autocorrect=1)
            for raw in _items((body.get("similarartists") or {}).get("artist")):
                similar = raw.get("name") or ""
                if not similar or (snapshot.artists and artist_key(similar) in snapshot.artists):
                    continue
                match = float(raw.get("match") or 0.5)
                tops = await self._call("artist.getTopTracks", artist=similar, limit=4, autocorrect=1)
                for track in _items((tops.get("toptracks") or {}).get("track"))[:4]:
                    if track.get("name"):
                        result.append(Candidate(similar, track["name"], self.name, match * 0.8, weight))
            tag_body = await self._call("artist.getTopTags", artist=name, autocorrect=1)
            for raw in _items((tag_body.get("toptags") or {}).get("tag"))[:3]:
                if raw.get("name"):
                    tags[raw["name"].lower()] += weight
        for tag, weight in sorted(tags.items(), key=lambda kv: -kv[1])[:4]:
            body = await self._call("tag.getTopTracks", tag=tag, limit=25)
            for raw in _items((body.get("tracks") or {}).get("track")):
                artist, title = _artist_name(raw.get("artist")), raw.get("name") or ""
                if artist and title:
                    result.append(Candidate(artist, title, self.name, 0.4, min(1.0, weight)))
        return result


class DeezerAdapter:
    name = "deezer"
    BASE = "https://api.deezer.com"

    def __init__(self, http: httpx.AsyncClient, enabled: bool = True, artists_limit: int = 10) -> None:
        self._http = http
        self._enabled = enabled
        self._artists_limit = artists_limit
        self._failures = 0

    def available(self, username: str) -> tuple[bool, str | None]:
        if not self._enabled:
            return False, "Deezer выключен (DEEZER_ENABLED)"
        return (True, None) if self._failures < 3 else (False, "Deezer не отвечает (нужен VPN?)")

    async def _get(self, path: str, **params: Any) -> list[dict[str, Any]]:
        try:
            response = await self._http.get(self.BASE + path, params=params, timeout=15)
            response.raise_for_status()
            body = response.json()
        except (httpx.HTTPError, ValueError) as e:
            self._failures += 1
            log.info("Движок: Deezer %s — %s", path, e)
            return []
        self._failures = 0
        return body.get("data") or [] if isinstance(body, dict) and "error" not in body else []

    async def candidates(self, username: str, seeds: list[Seed]) -> list[Candidate]:
        self._failures = 0
        result: list[Candidate] = []
        artists: dict[str, Seed] = {}
        for seed in sorted(seeds, key=lambda s: -s.weight):
            artists.setdefault(artist_key(seed.artist), seed)
        for seed in list(artists.values())[: self._artists_limit]:
            found = await self._get("/search/artist", q=seed.artist, limit=1)
            if not found:
                continue
            artist_id = found[0].get("id")
            for raw in await self._get(f"/artist/{artist_id}/radio", limit=25):
                result.append(self._track(raw, 0.6, seed))
            for related in (await self._get(f"/artist/{artist_id}/related", limit=6))[:6]:
                for raw in await self._get(f"/artist/{related.get('id')}/top", limit=3):
                    result.append(self._track(raw, 0.5, seed))
        return [c for c in result if c.artist and c.title]

    def _track(self, raw: dict[str, Any], similarity: float, seed: Seed) -> Candidate:
        album = raw.get("album") or {}
        duration = raw.get("duration")
        return Candidate(
            artist=(raw.get("artist") or {}).get("name") or "",
            title=raw.get("title") or "",
            source=self.name,
            similarity=similarity,
            seed_weight=seed.weight,
            album=album.get("title"),
            duration_ms=duration * 1000 if duration else None,
            cover_url=album.get("cover_xl") or album.get("cover_big"),
            seed_title=seed.title,
            ids={"deezer": str(raw.get("id"))},
        )


class ListenBrainzAdapter:
    """LB Radio по артистам и тегам семян + плейлисты Weekly Exploration / Weekly Jams пользователя."""

    name = "listenbrainz"
    BASE = "https://api.listenbrainz.org/1"

    def __init__(self, http: httpx.AsyncClient, token: str | None, users: dict[str, str], seeds_limit: int = 6) -> None:
        self._http = http
        self._token = token
        self._users = users
        self._seeds_limit = seeds_limit

    def available(self, username: str) -> tuple[bool, str | None]:
        if not self._token and not self._users:
            return False, "Не задан LISTENBRAINZ_TOKEN и пользователь LB (LISTENBRAINZ_USERS)"
        if not self._token and username and username not in self._users:
            return False, f"Не задан LISTENBRAINZ_TOKEN и пользователь LB для {username} (LISTENBRAINZ_USERS)"
        return True, None

    async def _get(self, path: str, **params: Any) -> dict[str, Any]:
        headers = {"Authorization": f"Token {self._token}"} if self._token else {}
        try:
            response = await self._http.get(self.BASE + path, params=params, headers=headers, timeout=20)
            response.raise_for_status()
            body = response.json()
        except (httpx.HTTPError, ValueError) as e:
            log.info("Движок: ListenBrainz %s — %s", path, e)
            return {}
        return body if isinstance(body, dict) else {}

    @staticmethod
    def _jspf(body: dict[str, Any]) -> list[tuple[str, str]]:
        playlist = (body.get("payload") or {}).get("jspf", {}).get("playlist") or body.get("playlist") or {}
        return [(t.get("creator") or "", t.get("title") or "") for t in playlist.get("track") or []]

    async def candidates(self, username: str, seeds: list[Seed]) -> list[Candidate]:
        result: list[Candidate] = []
        if self._token:
            artists: dict[str, Seed] = {}
            for seed in sorted(seeds, key=lambda s: -s.weight):
                artists.setdefault(artist_key(seed.artist), seed)
            for seed in list(artists.values())[: self._seeds_limit]:
                body = await self._get("/explore/lb-radio", prompt=f"artist:({seed.artist})", mode="easy")
                for artist, title in self._jspf(body):
                    result.append(Candidate(artist, title, self.name, 0.55, seed.weight, seed_title=seed.title))
        lb_user = self._users.get(username)
        if lb_user:
            body = await self._get(f"/user/{lb_user}/playlists/createdfor", count=10)
            for item in (body.get("playlists") or [])[:10]:
                playlist = item.get("playlist") or {}
                title = (playlist.get("title") or "").lower()
                if "weekly exploration" not in title and "weekly jams" not in title:
                    continue
                mbid = (playlist.get("identifier") or "").rstrip("/").rsplit("/", 1)[-1]
                similarity = 0.75 if "exploration" in title else 0.5
                for artist, track in self._jspf(await self._get(f"/playlist/{mbid}")):
                    result.append(Candidate(artist, track, self.name, similarity))
        return [c for c in result if c.artist and c.title]


class YandexArchiveAdapter:
    """Рёбра архива Яндекса пользователя: похожие на лайки, волна, похожие артисты."""

    name = "yandex_archive"

    def __init__(self, reco: RecoStore, links: YandexLinks | None = None) -> None:
        self._reco = reco
        self._links = links

    def login(self, username: str) -> str | None:
        """Логин Яндекса пользователя приложения (архив хранится под ним)."""
        return self._links.yandex_login(username) if self._links is not None else username

    def available(self, username: str) -> tuple[bool, str | None]:
        if not username and self._links is not None:
            if not self._links.accounts:
                return False, "Нет токенов аккаунтов Яндекса (/secrets/yandex_accounts)"
            return True, None
        login = self.login(username)
        if login is None:
            return False, NOT_LINKED
        if not self._reco.edge_counts(login):
            return False, f"Архив Яндекса аккаунта {login} пуст: сбор ещё не прошёл"
        return True, None

    async def candidates(self, username: str, seeds: list[Seed]) -> list[Candidate]:
        login = self.login(username)
        if login is None:
            return []
        ranked = self._reco.candidate_frequency(login, ("similar", "wave"), 2000)
        infos = self._reco.tracks([c for c, _ in ranked])
        liked = set(self._reco.liked_ids(login))
        result = []
        for cand_id, freq in ranked:
            info = infos.get(cand_id)
            if info is None or cand_id in liked:
                continue
            result.append(
                Candidate(
                    info.artist, info.title, self.name, min(1.0, 0.35 + 0.15 * freq), 1.0,
                    album=info.album, duration_ms=info.duration_ms, cover_url=info.cover_url,
                    ids={"yandex": info.id},
                )
            )  # fmt: skip
        # Похожие артисты: треки этих артистов, которые встречались в архиве (волна, плейлисты, чарт).
        similar_artists = {e["cand_id"] for e in self._reco.edges(login, ("artist_similar",))}
        if similar_artists:
            rows = self._reco.db.query("SELECT * FROM y_tracks LIMIT 50000")
            per_artist: dict[str, int] = defaultdict(int)
            for row in rows:
                ids = json.loads(row["artist_ids"] or "[]")
                hit = next((a for a in ids if a in similar_artists), None)
                if hit is None or per_artist[hit] >= 3 or row["id"] in liked:
                    continue
                per_artist[hit] += 1
                artists = json.loads(row["artists"] or "[]")
                result.append(
                    Candidate(", ".join(artists), row["title"], self.name, 0.45, 1.0, album=row["album"],
                              duration_ms=row["duration_ms"], cover_url=row["cover_url"], ids={"yandex": row["id"]})
                )  # fmt: skip
        return result


# endregion


class CandidateEngine:
    def __init__(
        self,
        store: Store,
        reco: RecoStore,
        settings: Settings,
        library: LibraryIndex,
        navidrome_db: NavidromeDb | None,
        adapters: list[SourceAdapter],
        penalized_artists: Callable[[str], set[str]] = lambda _u: set(),
        clock: Callable[[], int] = lambda: int(time.time() * 1000),
    ) -> None:
        self._store = store
        self._reco = reco
        self._settings = settings
        self._library = library
        self._db = navidrome_db
        self._adapters = {a.name: a for a in adapters}
        self._penalized = penalized_artists
        self._clock = clock
        self._lock = asyncio.Lock()

    # region Настройки источников (из приложения)

    def config(self) -> dict[str, Any]:
        stored = self._store.get_value(CONFIG_KEY) or {}
        sources = stored.get("sources") or {}
        return {
            "sources": {
                name: {
                    "enabled": bool((sources.get(name) or {}).get("enabled", True)),
                    "weight": float((sources.get(name) or {}).get("weight", 1.0)),
                }
                for name in self._adapters
            },
            "newArtistShare": float(stored.get("newArtistShare", self._settings.engine_new_artist_share)),
        }

    def update_config(
        self, enabled: dict[str, bool] | None, weights: dict[str, float] | None, new_artist_share: float | None
    ) -> None:
        config = self.config()
        for name, value in (enabled or {}).items():
            if name not in self._adapters:
                raise ValueError(f"Неизвестный источник: {name}")
            config["sources"][name]["enabled"] = bool(value)
        for name, value in (weights or {}).items():
            if name not in self._adapters:
                raise ValueError(f"Неизвестный источник: {name}")
            config["sources"][name]["weight"] = max(0.0, min(3.0, float(value)))
        if new_artist_share is not None:
            config["newArtistShare"] = max(0.0, min(1.0, float(new_artist_share)))
        self._store.set_value(CONFIG_KEY, config)

    def source_stats(self, username: str | None = None) -> dict[str, dict[str, int]]:
        """По источникам: сколько треков предложено и в каком они статусе (по всем или одному пользователю):
        `suggested` и счётчики `CANDIDATE_STATES` (queued, downloading, downloaded, notFound, promoted, rejected)."""
        where, params = ("WHERE o.username = ?", (username,)) if username else ("WHERE o.username != 'charts'", ())
        rows = self._store.query(
            f"SELECT o.source, o.status, t.location FROM pool_owners o JOIN pool_tracks t ON t.id = o.track_id {where}",
            params,
        )
        stats: dict[str, dict[str, int]] = defaultdict(_empty_stats)
        for r in rows:
            state = candidate_state(r["status"], r["location"])
            for source in (r["source"] or "").split(","):
                if source not in self._adapters:
                    continue
                stats[source]["suggested"] += 1
                stats[source][state] += 1
        return dict(stats)

    def candidates(self, source: str, state: str | None, limit: int) -> list[dict[str, Any]]:
        """Кандидаты источника (для диагностики): статус, загрузчик, время и число попыток."""
        rows = self._store.query(
            "SELECT t.artist, t.title, t.location, t.loader, t.attempt_at, t.attempts, t.message, o.username, "
            "o.status, o.status_at FROM pool_owners o JOIN pool_tracks t ON t.id = o.track_id "
            "WHERE o.username != 'charts' AND (',' || o.source || ',') LIKE ? ORDER BY o.status_at DESC",
            (f"%,{source},%",),
        )
        result = []
        for r in rows:
            name = candidate_state(r["status"], r["location"])
            if state and name != state:
                continue
            result.append(
                {
                    "artist": r["artist"], "title": r["title"], "username": r["username"], "state": name,
                    "loader": r["loader"], "attemptAt": r["attempt_at"], "attempts": int(r["attempts"] or 0),
                    "message": r["message"], "statusAt": r["status_at"],
                }
            )  # fmt: skip
            if len(result) >= limit:
                break
        return result

    def learned_weight(self, stats: dict[str, int] | None) -> float:
        """Доля promote со сглаживанием Beta(2, 2), нормированная так, что без данных вес 1.0."""
        promoted = (stats or {}).get("promoted", 0)
        decided = promoted + (stats or {}).get("rejected", 0)
        prior = PRIOR_ALPHA / (PRIOR_ALPHA + PRIOR_BETA)
        return ((promoted + PRIOR_ALPHA) / (decided + PRIOR_ALPHA + PRIOR_BETA)) / prior

    def weights(self) -> dict[str, float]:
        config = self.config()["sources"]
        stats = self.source_stats()
        return {name: config[name]["weight"] * self.learned_weight(stats.get(name)) for name in self._adapters}

    def describe(self, viewer: str = "") -> dict[str, Any]:
        """Источники для экрана «Рекомендации»: ошибки конфигурации — для того, кто смотрит."""
        config = self.config()
        stats = self.source_stats()
        sources = []
        for name, adapter in self._adapters.items():
            ok, reason = adapter.available(viewer)
            sources.append(
                {
                    "name": name,
                    "title": SOURCE_TITLES.get(name, name),
                    "enabled": config["sources"][name]["enabled"],
                    "weight": config["sources"][name]["weight"],
                    "learnedWeight": round(self.learned_weight(stats.get(name)), 3),
                    "available": ok,
                    "reason": reason,
                    **stats.get(name, _empty_stats()),
                }
            )
        return {"sources": sources, "newArtistShare": config["newArtistShare"]}

    def last_runs(self, users: list[str]) -> dict[str, Any]:
        return {u: self._store.get_value(RUN_KEY.format(user=u)) for u in users}

    # endregion

    # region Семена

    async def seeds(self, username: str) -> list[Seed]:
        now = self._clock()
        half = self._settings.engine_seed_half_life_days
        weights: dict[str, float] = defaultdict(float)
        names: dict[str, tuple[str, str, str | None]] = {}
        if self._db is not None:
            notes = await asyncio.to_thread(self._db.user_annotations, username)
            plays = [n for n in notes if n.play_count > 0]
            top = max((n.play_count for n in plays), default=1)
            interesting = {n.item_id: n for n in notes if n.starred or n.play_count > 0}
            songs = {s.id: s for s in await asyncio.to_thread(self._db.songs, list(interesting))}
            for item_id, note in interesting.items():
                song = songs.get(item_id)
                if song is None or note.rating == 1:
                    continue
                weight = 0.0
                if note.starred:
                    weight += half_life_weight(now - (_parse_time(note.starred_at) or now), half)
                if note.play_count:
                    played = _parse_time(note.play_date) or now
                    weight += 0.7 * math.log1p(note.play_count) / math.log1p(top) * half_life_weight(now - played, half)
                key = track_key(song.artist, song.title)
                weights[key] += weight
                names[key] = (song.artist, song.title, song.id)
        for row in self._store.query(
            "SELECT t.artist, t.title, t.song_id, o.status_at FROM pool_owners o "
            "JOIN pool_tracks t ON t.id = o.track_id WHERE o.username = ? AND o.status = 'promoted'",
            (username,),
        ):
            key = track_key(row["artist"], row["title"])
            weights[key] += 1.2 * half_life_weight(now - int(row["status_at"]), half)
            names.setdefault(key, (row["artist"], row["title"], row["song_id"]))
        seeds = [Seed(names[k][0], names[k][1], round(w, 4), names[k][2]) for k, w in weights.items() if w > 0.01]
        return sorted(seeds, key=lambda s: -s.weight)[: self._settings.engine_max_seeds]

    # endregion

    async def recommend(self, username: str, limit: int) -> list[Scored]:
        """Лучшие `limit` кандидатов для пользователя (без записи в пул)."""
        config = self.config()
        weights = self.weights()
        seeds = await self.seeds(username)
        if seeds:
            top = max(s.weight for s in seeds)
            for s in seeds:
                s.weight = s.weight / top
        raw: list[Candidate] = []
        for name, adapter in self._adapters.items():
            if not config["sources"][name]["enabled"] or weights[name] <= 0:
                continue
            ok, reason = adapter.available(username)
            if not ok:
                log.info("Движок (%s): %s пропущен — %s", username, SOURCE_TITLES.get(name, name), reason)
                continue
            try:
                found = await adapter.candidates(username, seeds)
            except Exception:  # noqa: BLE001 — один источник не останавливает остальные
                log.exception("Движок (%s): источник %s упал", username, name)
                continue
            log.info("Движок (%s): %s — %s кандидатов", username, SOURCE_TITLES.get(name, name), len(found))
            raw.extend(found)
        return await self._score(username, raw, weights, limit, float(config["newArtistShare"]))

    async def _score(
        self, username: str, raw: list[Candidate], weights: dict[str, float], limit: int, new_share: float
    ) -> list[Scored]:
        snapshot = await self._library.snapshot()
        blocked = self._reco.user_blocked_keys(username) | {
            track_key(r["artist"], r["title"]) for r in self._store.query("SELECT artist, title FROM blocklist")
        }
        taken = self._reco.pool_keys()
        penalized = self._penalized(username)
        grouped: dict[str, list[Candidate]] = defaultdict(list)
        for c in raw:
            if unwanted_extras(c.title, c.seed_title):
                continue
            key = track_key(c.artist, c.title)
            if key in snapshot.library or key in taken or key in blocked:
                continue
            grouped[key].append(c)
        scored: list[Scored] = []
        for key, items in grouped.items():
            best_by_source: dict[str, float] = {}
            for c in items:
                value = weights.get(c.source, 1.0) * max(0.0, min(1.0, c.similarity)) * c.seed_weight
                best_by_source[c.source] = best_by_source.get(c.source, 0.0) + value
            score = sum(best_by_source.values()) * MULTI_SOURCE_BONUS ** (len(best_by_source) - 1)
            first = max(items, key=lambda c: (c.album is not None, c.duration_ms is not None))
            akey = artist_key(first.artist)
            if akey in penalized:
                score *= ARTIST_PENALTY
            scored.append(
                Scored(key, first.artist, first.title, first.album, first.duration_ms, first.cover_url,
                       round(score, 5), sorted(best_by_source), akey not in snapshot.artists)
            )  # fmt: skip
        scored.sort(key=lambda s: -s.score)
        return _pick(scored, limit, new_share)

    async def refill(self, username: str, count: int, enqueue: Callable[[str, Scored], Any]) -> int:
        """Подобрать `count` треков и поставить в очередь загрузки (в пул пользователя)."""
        async with self._lock:
            picked = await self.recommend(username, count)
            for item in picked:
                await enqueue(username, item)
            by_source: dict[str, int] = defaultdict(int)
            for item in picked:
                for source in item.sources:
                    by_source[source] += 1
            self._store.set_value(
                RUN_KEY.format(user=username),
                {"at": self._clock(), "picked": len(picked), "bySource": dict(by_source)},
            )
            log.info("Движок (%s): в пул поставлено %s (%s)", username, len(picked), dict(by_source))
            return len(picked)


def _empty_stats() -> dict[str, int]:
    return {"suggested": 0, **dict.fromkeys(CANDIDATE_STATES, 0)}


def _pick(scored: list[Scored], limit: int, new_share: float) -> list[Scored]:
    """Не больше двух треков одного артиста; новые для пользователя артисты — примерно `new_share` от выбранного."""
    want_new = round(limit * new_share)
    per_artist: dict[str, int] = defaultdict(int)
    new = [s for s in scored if s.new_artist]
    known = [s for s in scored if not s.new_artist]
    result: list[Scored] = []

    def take(pool: list[Scored], count: int) -> None:
        for s in pool:
            if len(result) >= limit or count <= 0:
                return
            akey = s.key.split("\x1f", 1)[0]
            if s in result or per_artist[akey] >= MAX_ARTIST_PER_REFILL:
                continue
            per_artist[akey] += 1
            result.append(s)
            count -= 1

    take(new, want_new)
    take(known, limit - len(result))
    take(new, limit - len(result))  # знакомых не хватило — добираем новыми
    return sorted(result, key=lambda s: -s.score)
