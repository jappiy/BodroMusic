"""«Моя волна» на сервере: режимы по признакам звучания и порядок треков с плавным темпоритмом.

Почему очередь собирает bodrod, а не приложение: признаки всех треков библиотеки, эмбеддинги, история и отметки
всех пользователей (БД Navidrome) и пул «На пробу» лежат здесь; приложению пришлось бы скачивать признаки тысяч
треков. Приложение просит порцию (`POST /api/v1/wave/next`, 20 треков) и шлёт с ней то, что было в сессии: последние
треки очереди и реакции (лайк, дизлайк, пропуск в первые 30 с). Без bodrod приложение собирает волну само, как раньше.

Подбор порции:
1. Кандидаты — треки библиотеки, подходящие под режим (пороги — `DEFAULT_MODES`, переопределяются JSON-файлом
   `WAVE_MODES_FILE`), язык и настроение из настроек волны. Исключаются: дизлайки пользователя (рейтинг 1), треки
   из последних 200 очереди, слушанные за последние `repeat_days` дней.
2. Оценка — характер волны (любимое / незнакомое / популярное), долговременные пропуски исполнителя, реакции сессии
   (лайк поднимает похожие по звуку, дизлайк и ранний пропуск опускают исполнителя и похожие), немного случайности.
3. Порядок — жадно от текущего трека: из лучших `candidates` берётся тот, у кого меньше «стоимость перехода»: темп
   в пределах ±10 % (с учётом удвоения и половины), энергия меняется не больше чем на `energy_step`, тональность
   совместима по Camelot. Один исполнитель — не чаще раза в `artist_gap` треков.
4. Мало кандидатов — правила ослабляются по шагам (сначала «не чаще раза в M дней», потом пороги режима на 20 %),
   подмешивается пул «На пробу», а ответ помечается `lowCoverage` — приложение предлагает докачать похожее.
"""

from __future__ import annotations

import asyncio
import json
import logging
import math
import random
import time
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any

import numpy as np

from ..db import Store
from ..metadata.lastfm import LastFmClient, LastFmError
from ..navidrome_db import NavidromeDb, NdAnnotation
from .keys import artist_key
from .traits import camelot_compatible, traits

log = logging.getLogger(__name__)

"""Режимы и пороги. Ключи порогов: `<признак>_min` / `<признак>_max` (energy, valence, bpm, aggressive, relaxed,
acoustic, electronic, instrumental, danceability), `any_min` — хотя бы один из признаков не ниже порога, `lang`,
`unheard` (не слушал), `favorite` (лайк или часто слушает), `not_favorite_artist`, `added_days`, `popular`,
`pool_share` — доля пула «На пробу»."""
DEFAULT_MODES: dict[str, dict[str, Any]] = {
    "default": {"title": "Моя волна", "pool_share": 0.15},
    "energetic": {"title": "Бодрое", "energy_min": 0.55, "bpm_min": 105, "bpm_max": 165, "valence_min": 0.35},
    "calm": {"title": "Спокойное", "energy_max": 0.45, "bpm_max": 105, "any_min": {"relaxed": 0.5, "acoustic": 0.5}},
    "happy": {"title": "Весёлое", "valence_min": 0.6},
    "sad": {"title": "Грустное", "valence_max": 0.4},
    "loud": {"title": "Пошумим", "aggressive_min": 0.5, "energy_min": 0.6},
    "discover": {"title": "Незнакомое", "unheard": True, "not_favorite_artist": True, "pool_share": 0.5},
    "favorites": {"title": "Любимое", "favorite": True},
    "popular": {"title": "Популярное", "popular": True},
    "new": {"title": "Новое", "added_days": 60, "pool_share": 0.2},
    "instrumental": {"title": "Без слов", "instrumental_min": 0.6},
    "ru": {"title": "На русском", "lang": "ru"},
    "en": {"title": "На английском", "lang": "en"},
}
"""Настроение из настроек волны — те же пороги, что у режимов."""
MOOD_MODES = {"energetic": "energetic", "calm": "calm", "happy": "happy", "sad": "sad"}
DEFAULT_TUNING: dict[str, Any] = {
    "bpm_tolerance": 0.10,
    "energy_step": 0.15,
    "artist_gap": 4,
    "repeat_days": 3,
    "candidates": 40,
    "min_coverage": 30,
    "favorite_plays": 3,
}
SNAPSHOT_TTL_S = 600
NOTES_TTL_S = 60
SIMILAR_TTL_S = 7 * 24 * 3600
SCHEMA = """
CREATE TABLE IF NOT EXISTS wave_artist_stats (
    username TEXT NOT NULL,
    artist TEXT NOT NULL,
    skips INTEGER NOT NULL DEFAULT 0,
    completes INTEGER NOT NULL DEFAULT 0,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (username, artist)
);
"""


@dataclass
class WaveSong:
    id: str
    title: str
    artist: str
    artist_key: str
    album: str | None
    album_id: str | None
    added_ts: float | None
    pool: bool
    traits: dict[str, Any]


@dataclass
class _Snapshot:
    songs: list[WaveSong] = field(default_factory=list)
    by_id: dict[str, WaveSong] = field(default_factory=dict)
    popularity: dict[str, int] = field(default_factory=dict)
    built_at: float = 0.0


def _ts(value: str | None) -> float | None:
    if not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00").split(".")[0]).timestamp()
    except ValueError:
        return None


def _bpm_distance(a: float | None, b: float | None) -> float:
    """Разница темпа в долях с учётом удвоения и половины: 120 и 60 — одинаково."""
    if not a or not b:
        return 0.0
    return min(abs(math.log(b * k / a)) for k in (0.5, 1.0, 2.0))


class WaveService:
    def __init__(
        self,
        store: Store,
        db: NavidromeDb,
        analysis: Any,
        pool_prefix: str | None = None,
        modes_file: str | None = None,
        lastfm: LastFmClient | None = None,
        clock: Any = time.time,
    ) -> None:
        self._store = store
        self._db = db
        self._analysis = analysis
        self._pool_prefix = pool_prefix.rstrip("/") + "/" if pool_prefix else None
        self._lastfm = lastfm
        self._clock = clock
        self._snapshot = _Snapshot()
        self._notes: dict[str, tuple[float, list[NdAnnotation]]] = {}
        self._similar: dict[str, tuple[float, set[str]]] = {}
        self._lock = asyncio.Lock()
        self.modes, self.tuning = self._load_modes(modes_file)
        store.executescript(SCHEMA)

    # region Конфиг

    @staticmethod
    def _load_modes(path: str | None) -> tuple[dict[str, dict[str, Any]], dict[str, Any]]:
        modes = {k: dict(v) for k, v in DEFAULT_MODES.items()}
        tuning = dict(DEFAULT_TUNING)
        if path:
            try:
                with open(path, encoding="utf-8") as f:
                    custom = json.load(f)
                for name, value in (custom.get("modes") or {}).items():
                    modes[name] = {**modes.get(name, {}), **value}
                tuning.update(custom.get("tuning") or {})
                log.info("Режимы волны: пороги из %s", path)
            except (OSError, ValueError) as e:
                log.warning("Режимы волны: не прочитан %s (%s) — пороги по умолчанию", path, e)
        return modes, tuning

    # endregion

    # region Данные

    async def _library(self) -> _Snapshot:
        async with self._lock:
            if self._clock() - self._snapshot.built_at > SNAPSHOT_TTL_S or not self._snapshot.songs:
                features = await self._analysis.all_features() if self._analysis is not None else {}
                self._snapshot = await asyncio.to_thread(self._build, features)
        return self._snapshot

    def _build(self, features: dict[str, dict[str, Any]]) -> _Snapshot:
        snap = _Snapshot(built_at=self._clock())
        for s in self._db.iter_songs():
            pool = bool(self._pool_prefix and s.path.startswith(self._pool_prefix))
            key = artist_key(s.album_artist or s.artist) or s.artist.lower()
            song = WaveSong(
                id=s.id, title=s.title, artist=s.artist, artist_key=key,
                album=s.album, album_id=s.album_id, added_ts=_ts(s.created_at), pool=pool,
                traits={**traits(features.get(s.id), s.title, s.artist, s.album), "genre": s.genre},
            )  # fmt: skip
            snap.songs.append(song)
            snap.by_id[song.id] = song
        snap.popularity = self._db.annotation_counts()
        return snap

    async def _user_notes(self, username: str) -> dict[str, NdAnnotation]:
        cached = self._notes.get(username)
        if cached is None or self._clock() - cached[0] > NOTES_TTL_S:
            notes = await asyncio.to_thread(self._db.user_annotations, username)
            cached = (self._clock(), notes)
            self._notes[username] = cached
        return {n.item_id: n for n in cached[1]}

    async def _similar_artists(self, name: str) -> set[str]:
        """Ключи исполнителей, похожих на `name` (Last.fm `artist.getSimilar`), включая его самого."""
        key = artist_key(name) or name.lower()
        cached = self._similar.get(key)
        if cached and self._clock() - cached[0] < SIMILAR_TTL_S:
            return cached[1]
        result = {key}
        if self._lastfm is not None:
            try:
                body = await self._lastfm.call("artist.getSimilar", artist=name, limit=40, autocorrect=1)
                for item in ((body or {}).get("similarartists") or {}).get("artist") or []:
                    if isinstance(item, dict) and item.get("name"):
                        result.add(artist_key(item["name"]) or item["name"].lower())
            except LastFmError as e:
                log.info("Похожие на %s: Last.fm недоступен (%s)", name, e)
                return result
        self._similar[key] = (self._clock(), result)
        return result

    def _artist_stats(self, username: str) -> dict[str, float]:
        """Исполнитель → доля ранних пропусков (долговременный вес)."""
        rows = self._store.query(
            "SELECT artist, skips, completes FROM wave_artist_stats WHERE username = ?", (username,)
        )
        return {r["artist"]: r["skips"] / (r["skips"] + r["completes"] + 2) for r in rows}

    def _remember(self, username: str, snap: _Snapshot, session: list[dict[str, Any]]) -> None:
        now = int(self._clock() * 1000)
        for event in session:
            song = snap.by_id.get(str(event.get("id")))
            kind = event.get("event")
            if song is None or kind not in ("skip", "complete"):
                continue
            column = "skips" if kind == "skip" else "completes"
            self._store.execute(
                f"INSERT INTO wave_artist_stats (username, artist, {column}, updated_at) VALUES (?, ?, 1, ?) "
                f"ON CONFLICT(username, artist) DO UPDATE SET {column} = {column} + 1, "
                "updated_at = excluded.updated_at",
                (username, song.artist_key, now),
            )

    # endregion

    # region Режимы

    def _matches(
        self,
        song: WaveSong,
        rules: dict[str, Any],
        notes: dict[str, NdAnnotation],
        favorite_artists: set[str],
        relax: float = 1.0,
    ) -> bool:
        """Подходит ли трек под правила режима. `relax` < 1 — пороги мягче (мало треков под режим)."""
        return all(self._rule_ok(name, value, song, notes, favorite_artists, relax) for name, value in rules.items())

    def _rule_ok(
        self,
        name: str,
        value: Any,
        song: WaveSong,
        notes: dict[str, NdAnnotation],
        favorite_artists: set[str],
        relax: float,
    ) -> bool:
        t = song.traits
        note = notes.get(song.id)
        if name == "any_min":
            return any(t.get(k) is not None and t[k] >= v * relax for k, v in value.items())
        if name.endswith("_min"):
            feature = t.get(name[:-4])
            return feature is not None and feature >= value * relax
        if name.endswith("_max"):
            feature = t.get(name[:-4])
            return feature is not None and feature <= value * (2 - relax)
        if name == "lang":
            return t.get("lang") == value
        if name == "unheard":
            return not value or not (note and note.play_count > 0)
        if name == "not_favorite_artist":
            return not value or song.artist_key not in favorite_artists
        if name == "favorite":
            return not value or bool(note and (note.starred or note.play_count >= self.tuning["favorite_plays"]))
        if name == "added_days":
            return bool(song.added_ts) and self._clock() - song.added_ts <= value * 86400 / relax
        return True  # popular и неизвестные ключи — не фильтр, а вес

    def _rules(self, mode: str, mood: str | None, lang: str | None) -> dict[str, Any]:
        base = mode.split(":", 1)[0]
        rules = {
            k: v for k, v in self.modes.get(base, self.modes["default"]).items() if k not in ("title", "pool_share")
        }
        if mood and mood in MOOD_MODES and base not in MOOD_MODES:
            rules.update({k: v for k, v in self.modes[MOOD_MODES[mood]].items() if k != "title"})
        if lang in ("ru", "en", "none"):
            rules["lang"] = lang
        return rules

    # endregion

    async def next_batch(self, username: str, request: dict[str, Any]) -> dict[str, Any]:
        """Порция волны: `songIds` по порядку, `matched` — сколько треков подходит под режим, `lowCoverage`."""
        snap = await self._library()
        notes = await self._user_notes(username)
        session = list(request.get("session") or [])
        await asyncio.to_thread(self._remember, username, snap, session)
        mode = str(request.get("mode") or "default")
        count = max(1, min(int(request.get("count") or 20), 50))
        recent = [str(i) for i in request.get("recent") or []][-200:]
        current = snap.by_id.get(str(request.get("currentId") or "")) or (
            snap.by_id.get(recent[-1]) if recent else None
        )
        character = request.get("character")
        rules = self._rules(mode, request.get("mood"), request.get("lang"))
        disliked = {i for i, n in notes.items() if n.rating == 1} | {
            str(e["id"]) for e in session if e.get("event") == "dislike"
        }
        favorite_artists = {snap.by_id[i].artist_key for i, n in notes.items() if i in snap.by_id and n.starred}
        allowed_artists = await self._mode_artists(mode, snap)
        album_first = mode.split(":", 1)[1] if mode.startswith("release:") else None

        repeat_s = float(self.tuning["repeat_days"]) * 86400
        now = self._clock()
        excluded = set(recent) | disliked

        def eligible(relax: float, allow_repeat: bool) -> list[WaveSong]:
            result = []
            for song in snap.songs:
                if song.id in excluded or (song.pool and not self._pool_allowed(mode)):
                    continue
                if allowed_artists is not None and song.artist_key not in allowed_artists:
                    continue
                note = notes.get(song.id)
                if not allow_repeat and note and note.play_date and now - (_ts(note.play_date) or 0) < repeat_s:
                    continue
                if self._matches(song, rules, notes, favorite_artists, relax):
                    result.append(song)
            return result

        pool = eligible(1.0, False)
        matched = len(pool)
        for relax, repeat in ((1.0, True), (0.8, True), (0.6, True)):
            if len(pool) >= count:
                break
            pool = eligible(relax, repeat)
        scores = await self._scores(username, pool, snap, notes, session, character, mode, favorite_artists)
        ordered = self._sequence(pool, scores, current, recent, snap, count, album_first)
        return {
            "songIds": [s.id for s in ordered],
            "matched": matched,
            "lowCoverage": matched < int(self.tuning["min_coverage"]),
            "mode": mode,
        }

    def _pool_allowed(self, mode: str) -> bool:
        base = mode.split(":", 1)[0]
        return float(self.modes.get(base, {}).get("pool_share", 0)) > 0

    async def _mode_artists(self, mode: str, snap: _Snapshot) -> set[str] | None:
        """Динамические режимы: «В стиле X» — X и похожие на него; «Новый релиз» — исполнитель альбома и похожие."""
        if mode.startswith("artist:"):
            return await self._similar_artists(mode.split(":", 1)[1])
        if mode.startswith("release:"):
            album_id = mode.split(":", 1)[1]
            song = next((s for s in snap.songs if s.album_id == album_id), None)
            return await self._similar_artists(song.artist) if song else None
        return None

    async def _scores(self, username: str, pool: list[WaveSong], snap: _Snapshot, notes: dict[str, NdAnnotation],
                      session: list[dict[str, Any]], character: str | None, mode: str,
                      favorite_artists: set[str]) -> dict[str, float]:  # fmt: skip
        stats = await asyncio.to_thread(self._artist_stats, username)
        liked_ids = [str(e["id"]) for e in session if e.get("event") == "like"]
        bad_ids = [str(e["id"]) for e in session if e.get("event") in ("dislike", "skip")]
        bad_artists = {snap.by_id[i].artist_key for i in bad_ids if i in snap.by_id}
        vectors = await self._vectors([s.id for s in pool] + liked_ids + bad_ids)
        liked_v = [vectors[i] for i in liked_ids if i in vectors]
        bad_v = [vectors[i] for i in bad_ids if i in vectors]
        popularity = snap.popularity
        max_pop = max(popularity.values(), default=1) or 1
        rng = random.Random(f"{username}:{int(self._clock() // 60)}")
        result: dict[str, float] = {}
        for song in pool:
            note = notes.get(song.id)
            plays = note.play_count if note else 0
            score = rng.random() * 0.6
            if character == "favorites" or mode == "favorites":
                score += (1.0 if note and note.starred else 0) + math.log1p(plays) * 0.4
            elif character == "discover" or mode == "discover":
                score += (
                    (1.0 if plays == 0 else -0.5)
                    + (-0.6 if song.artist_key in favorite_artists else 0)
                    + (0.4 if song.pool else 0)
                )
            elif character == "popular" or mode == "popular":
                score += popularity.get(song.id, 0) / max_pop * 1.5 + math.log1p(plays) * 0.2
            else:
                score += (0.5 if note and note.starred else 0) + math.log1p(plays) * 0.25 + (0.2 if plays == 0 else 0)
            score -= stats.get(song.artist_key, 0) * 1.5
            if song.artist_key in bad_artists:
                score -= 1.0
            v = vectors.get(song.id)
            if v is not None:
                if liked_v:
                    score += max(float(v @ x) for x in liked_v) * 0.8
                if bad_v:
                    score -= max(0.0, max(float(v @ x) for x in bad_v) - 0.6) * 2.0
            result[song.id] = score
        return result

    async def _vectors(self, ids: list[str]) -> dict[str, np.ndarray]:
        if self._analysis is None or not hasattr(self._analysis, "vectors_by_id"):
            return {}
        return await self._analysis.vectors_by_id(ids)

    def transition_cost(self, a: WaveSong | None, b: WaveSong) -> float:
        """Стоимость перехода a → b: темп вне ±10 % (удвоение и половина — тот же темп), скачок энергии, тональность."""
        if a is None:
            return 0.0
        ta, tb = a.traits, b.traits
        cost = 0.0
        tolerance = math.log1p(float(self.tuning["bpm_tolerance"]))
        cost += max(0.0, _bpm_distance(ta.get("bpm"), tb.get("bpm")) - tolerance) * 6
        if ta.get("energy") is not None and tb.get("energy") is not None:
            cost += max(0.0, abs(ta["energy"] - tb["energy"]) - float(self.tuning["energy_step"])) * 4
        if not camelot_compatible(ta.get("camelot"), tb.get("camelot")):
            cost += 0.3
        return cost

    def _sequence(self, pool: list[WaveSong], scores: dict[str, float], current: WaveSong | None, recent: list[str],
                  snap: _Snapshot, count: int, album_first: str | None) -> list[WaveSong]:  # fmt: skip
        gap = int(self.tuning["artist_gap"])
        history = [snap.by_id[i].artist_key for i in recent[-gap:] if i in snap.by_id]
        remaining = sorted(pool, key=lambda s: -scores.get(s.id, 0))
        result: list[WaveSong] = []
        previous = current
        if album_first:
            # «Новый релиз»: сначала треки альбома по порядку, потом — похожее.
            album = [s for s in remaining if s.album_id == album_first][:count]
            result.extend(album)
            remaining = [s for s in remaining if s.album_id != album_first]
            previous = album[-1] if album else previous
            history.extend(s.artist_key for s in album)
        top = int(self.tuning["candidates"])
        while len(result) < count and remaining:
            blocked = set(history[-gap:]) if gap > 0 else set()
            options = [s for s in remaining[: top * 3] if s.artist_key not in blocked][:top] or remaining[:top]
            best = max(options, key=lambda s: scores.get(s.id, 0) - self.transition_cost(previous, s))
            result.append(best)
            remaining.remove(best)
            history.append(best.artist_key)
            previous = best
        return result

    # region Карточки и отчёт

    async def modes_for(self, username: str) -> list[dict[str, Any]]:
        """Режимы для главной: статические с числом подходящих треков и динамические («В стиле X», «Новый релиз X»)."""
        snap = await self._library()
        notes = await self._user_notes(username)
        favorite_artists = {snap.by_id[i].artist_key for i, n in notes.items() if i in snap.by_id and n.starred}
        result = []
        for name, config in self.modes.items():
            if name == "default":
                continue
            rules = self._rules(name, None, None)
            matched = sum(1 for s in snap.songs if not s.pool and self._matches(s, rules, notes, favorite_artists))
            result.append({"id": name, "title": config.get("title", name), "count": matched})
        weights: dict[str, float] = {}
        names: dict[str, str] = {}
        for item_id, note in notes.items():
            song = snap.by_id.get(item_id)
            if song is None or song.pool:
                continue
            weights[song.artist_key] = (
                weights.get(song.artist_key, 0) + (3 if note.starred else 0) + math.log1p(note.play_count)
            )
            names.setdefault(song.artist_key, song.artist)
        top = [k for k, _ in sorted(weights.items(), key=lambda kv: -kv[1])]
        for key in top[:3]:
            result.append(
                {"id": f"artist:{names[key]}", "title": f"В стиле: {names[key]}", "count": None, "dynamic": True}
            )
        fresh_cut = self._clock() - 30 * 86400
        seen: set[str] = set()
        for song in sorted(snap.songs, key=lambda s: -(s.added_ts or 0)):
            if len(seen) >= 2 or not song.added_ts or song.added_ts < fresh_cut:
                break
            if song.artist_key in top[:20] and song.album_id and song.album_id not in seen:
                seen.add(song.album_id)
                result.append({"id": f"release:{song.album_id}", "title": f"Новый релиз: {song.artist}",
                               "subtitle": song.album, "count": None, "dynamic": True})  # fmt: skip
        return result

    async def report(self) -> dict[str, Any]:
        """Какие признаки считаются, сколько треков проанализировано и сколько попадает в каждый режим."""
        snap = await self._library()
        library = [s for s in snap.songs if not s.pool]
        analyzed = [s for s in library if s.traits.get("analyzed")]
        counts = {}
        for name in self.modes:
            if name in ("default", "favorites", "discover"):
                continue  # зависят от пользователя
            rules = self._rules(name, None, None)
            counts[name] = sum(1 for s in library if self._matches(s, rules, {}, set()))
        with_feature = {
            name: sum(1 for s in analyzed if s.traits.get(name) is not None)
            for name in ("bpm", "energy", "valence", "aggressive", "relaxed", "danceability", "instrumental",
                         "acoustic", "electronic", "camelot", "loudness")
        }  # fmt: skip
        langs: dict[str, int] = {}
        for s in library:
            key = s.traits.get("lang") or "unknown"
            langs[key] = langs.get(key, 0) + 1
        return {
            "library": len(library),
            "analyzed": len(analyzed),
            "features": with_feature,
            "languages": langs,
            "modes": counts,
            "tuning": self.tuning,
        }

    # endregion
