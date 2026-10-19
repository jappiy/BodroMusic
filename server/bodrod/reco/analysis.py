"""Анализ звучания: задания для воркера (`analyzer/`), хранение признаков, профиль вкуса, фильтр пула, похожие по звуку.

Воркер берёт задания (`GET /api/v1/analysis/next`, заголовок `X-Analyzer-Token`): сначала новые треки пула и резерва,
потом вся библиотека. Путь в задании — относительно папки музыки (`MUSIC_DIR`), воркер читает файл из своей копии
монтирования. Результат (`POST /api/v1/analysis/result`): эмбеддинг discogs-effnet, жанры discogs400, настроения
happy / sad / aggressive / relaxed, danceability, голос/инструментал, BPM, тональность, громкость.

Применение:
- профиль вкуса — 5–10 кластеров (k-means) по эмбеддингам лайкнутых треков пользователя;
- фильтр пула — звуковая близость трека пула = максимальный косинус к центрам кластеров. Ниже `SOUND_MIN_MATCH` —
  трек не идёт в волну; ниже `SOUND_REJECT_MATCH` — удаляется и засчитывается источнику как неудача;
- настроение волны и плавные переходы — признаки треков отдаются приложению (`POST /api/v1/analysis/features`);
- «Похожие по звучанию» — ближайшие соседи по библиотеке (numpy-индекс в памяти, косинус).
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

import numpy as np

from ..cleanup import _remap
from ..config import Settings
from ..db import Store
from ..navidrome_db import NavidromeDb
from .store import DAY_MS

log = logging.getLogger(__name__)

SCHEMA = """
CREATE TABLE IF NOT EXISTS audio_features (
    path TEXT PRIMARY KEY,
    embedding BLOB,
    features TEXT NOT NULL DEFAULT '{}',
    error TEXT,
    analyzed_at INTEGER NOT NULL
);
"""

LEASE_MS = 30 * 60 * 1000
RETRY_ERROR_MS = 7 * DAY_MS
INDEX_REFRESH_S = 600
MIN_LIKED_FOR_PROFILE = 10
MOODS = ("happy", "sad", "aggressive", "relaxed")


def energy_of(features: dict[str, Any]) -> float | None:
    """«Энергия» 0..1 для плавных переходов: танцевальность, агрессия, не-расслабленность и темп."""
    moods = features.get("moods") or {}
    dance = features.get("danceability")
    if dance is None and not moods:
        return None
    bpm = features.get("bpm") or 110.0
    tempo = min(1.0, max(0.0, (float(bpm) - 70.0) / 100.0))
    parts = [
        float(dance or 0.5),
        float(moods.get("aggressive", 0.3)),
        1.0 - float(moods.get("relaxed", 0.5)),
        tempo,
    ]
    return round(sum(parts) / len(parts), 4)


def kmeans(points: np.ndarray, k: int, iterations: int = 25, seed: int = 7) -> np.ndarray:
    """Центры k кластеров (k-means++ на нормированных векторах, косинусная близость). Детерминирован."""
    n = len(points)
    k = max(1, min(k, n))
    rng = np.random.default_rng(seed)
    centers = [points[rng.integers(n)]]
    for _ in range(1, k):
        sims = np.max(points @ np.stack(centers).T, axis=1)
        distance = np.clip(1.0 - sims, 0.0, None) ** 2
        total = distance.sum()
        index = int(rng.choice(n, p=distance / total)) if total > 0 else int(rng.integers(n))
        centers.append(points[index])
    c = np.stack(centers)
    for _ in range(iterations):
        labels = np.argmax(points @ c.T, axis=1)
        updated = []
        for i in range(k):
            members = points[labels == i]
            center = members.mean(axis=0) if len(members) else c[i]
            norm = np.linalg.norm(center)
            updated.append(center / norm if norm > 0 else c[i])
        new = np.stack(updated)
        if np.allclose(new, c):
            break
        c = new
    return c


def _normalize(vector: np.ndarray) -> np.ndarray:
    norm = np.linalg.norm(vector)
    return vector / norm if norm > 0 else vector


@dataclass
class _Index:
    ids: list[str]
    matrix: np.ndarray
    built_at: float


class AnalysisService:
    def __init__(
        self,
        store: Store,
        settings: Settings,
        navidrome_db: NavidromeDb | None,
        clock: Callable[[], int] = lambda: int(time.time() * 1000),
    ) -> None:
        self._store = store
        self._settings = settings
        self._db = navidrome_db
        self._clock = clock
        self._leases: dict[str, int] = {}
        self._index: _Index | None = None
        self._paths: dict[str, str] = {}
        self._lock = asyncio.Lock()
        self._last_result_at: int | None = None
        store.executescript(SCHEMA)

    @property
    def token(self) -> str | None:
        return self._settings.analyzer_token

    # region Пути

    def _relative(self, local: str) -> str | None:
        root = self._settings.music_dir.rstrip("/")
        for sep in ("/", "\\"):
            if local.startswith(root + sep):
                return local[len(root) + 1 :].replace("\\", "/")
        return None

    def _local(self, relative: str) -> str:
        return self._settings.music_dir.rstrip("/") + "/" + relative.lstrip("/")

    async def _song_paths(self) -> dict[str, str]:
        """id трека в Navidrome → путь относительно папки музыки (кэш на время построения индекса)."""
        if self._db is None:
            return {}

        def build() -> dict[str, str]:
            result = {}
            for song in self._db.iter_songs():
                local = _remap(song.path, self._settings.navidrome_music_dir, self._settings.music_dir)
                rel = self._relative(local) if local else None
                if rel:
                    result[song.id] = rel
            return result

        return await asyncio.to_thread(build)

    # endregion

    # region Очередь для воркера

    def _known(self) -> dict[str, dict[str, Any]]:
        rows = self._store.query("SELECT path, error, analyzed_at FROM audio_features")
        return {r["path"]: r for r in rows}

    async def next_jobs(self, limit: int) -> list[str]:
        """Пути для анализа: сначала пул и резерв (новые), потом библиотека. Выданное держится 30 минут."""
        now = self._clock()
        self._leases = {p: t for p, t in self._leases.items() if now - t < LEASE_MS}
        known = self._known()

        def wanted(rel: str | None) -> bool:
            if not rel or rel in self._leases:
                return False
            row = known.get(rel)
            return row is None or (row["error"] is not None and now - int(row["analyzed_at"]) > RETRY_ERROR_MS)

        jobs: list[str] = []
        pool = self._store.query(
            "SELECT path FROM pool_tracks WHERE location IN ('pool', 'reserve') AND path IS NOT NULL ORDER BY id DESC"
        )
        for r in pool:
            rel = self._relative(r["path"])
            if wanted(rel) and rel not in jobs:
                jobs.append(rel)  # type: ignore[arg-type]
            if len(jobs) >= limit:
                break
        if len(jobs) < limit:
            if not self._paths:
                self._paths = await self._song_paths()
            for rel in self._paths.values():
                if wanted(rel) and rel not in jobs:
                    jobs.append(rel)
                    if len(jobs) >= limit:
                        break
        for rel in jobs:
            self._leases[rel] = now
        return jobs

    def save(
        self, relative: str, features: dict[str, Any] | None, embedding: list[float] | None, error: str | None
    ) -> None:
        blob = np.asarray(embedding, dtype=np.float32).tobytes() if embedding else None
        stored = dict(features or {})
        if features is not None:
            stored["energy"] = energy_of(stored)
        self._store.execute(
            "INSERT INTO audio_features (path, embedding, features, error, analyzed_at) VALUES (?, ?, ?, ?, ?) "
            "ON CONFLICT(path) DO UPDATE SET embedding = excluded.embedding, features = excluded.features, "
            "error = excluded.error, analyzed_at = excluded.analyzed_at",
            (relative, blob, json.dumps(stored, ensure_ascii=False), error, self._clock()),
        )
        self._leases.pop(relative, None)
        self._last_result_at = self._clock()
        if self._index is not None and embedding:
            self._index.built_at = 0.0  # перестроить при следующем запросе (не чаще раза в 10 минут — ниже)

    def status(self) -> dict[str, Any]:
        row = self._store.query_one(
            "SELECT COUNT(*) AS n, SUM(CASE WHEN error IS NOT NULL THEN 1 ELSE 0 END) AS errors FROM audio_features"
        )
        return {
            "enabled": bool(self.token),
            "analyzed": int((row or {}).get("n") or 0) - int((row or {}).get("errors") or 0),
            "errors": int((row or {}).get("errors") or 0),
            "library": len(self._paths),
            "lastResultAt": self._last_result_at,
        }

    # endregion

    # region Признаки и индекс

    async def _ensure_index(self) -> _Index:
        async with self._lock:
            index = self._index
            if index is not None and time.monotonic() - index.built_at < INDEX_REFRESH_S:
                return index
            self._paths = await self._song_paths()
            by_path = {p: s for s, p in self._paths.items()}
            ids: list[str] = []
            vectors: list[np.ndarray] = []
            for r in self._store.query("SELECT path, embedding FROM audio_features WHERE embedding IS NOT NULL"):
                song_id = by_path.get(r["path"])
                if song_id:
                    ids.append(song_id)
                    vectors.append(_normalize(np.frombuffer(r["embedding"], dtype=np.float32)))
            matrix = np.stack(vectors) if vectors else np.zeros((0, 1), dtype=np.float32)
            self._index = _Index(ids, matrix, time.monotonic())
            return self._index

    async def features(self, song_ids: list[str]) -> dict[str, dict[str, Any]]:
        """Признаки треков по id в Navidrome (без эмбеддинга)."""
        await self._ensure_index()
        result: dict[str, dict[str, Any]] = {}
        wanted = {self._paths[i]: i for i in song_ids if i in self._paths}
        if not wanted:
            return result
        paths = list(wanted)
        for start in range(0, len(paths), 500):
            chunk = paths[start : start + 500]
            marks = ",".join("?" for _ in chunk)
            for r in self._store.query(
                f"SELECT path, features FROM audio_features WHERE error IS NULL AND path IN ({marks})", chunk
            ):
                result[wanted[r["path"]]] = json.loads(r["features"] or "{}")
        return result

    async def similar(self, song_id: str, limit: int, exclude: set[str] | None = None) -> list[str]:
        """Ближайшие по звучанию треки библиотеки (косинус эмбеддингов)."""
        index = await self._ensure_index()
        if song_id not in index.ids or len(index.ids) < 2:
            return []
        query = index.matrix[index.ids.index(song_id)]
        sims = index.matrix @ query
        order = np.argsort(-sims)
        skip = (exclude or set()) | {song_id}
        return [index.ids[i] for i in order if index.ids[i] not in skip][:limit]

    async def _vectors(self, song_ids: list[str]) -> np.ndarray:
        index = await self._ensure_index()
        positions = {s: i for i, s in enumerate(index.ids)}
        rows = [index.matrix[positions[s]] for s in song_ids if s in positions]
        return np.stack(rows) if rows else np.zeros((0, 1), dtype=np.float32)

    async def taste(self, username: str) -> np.ndarray | None:
        """Центры 5–10 кластеров по эмбеддингам лайкнутых треков; `None` — мало данных."""
        if self._db is None:
            return None
        notes = await asyncio.to_thread(self._db.user_annotations, username)
        liked = [n.item_id for n in notes if n.starred]
        vectors = await self._vectors(liked)
        if len(vectors) < MIN_LIKED_FOR_PROFILE:
            return None
        k = max(5, min(10, len(vectors) // 10))
        return kmeans(vectors, k)

    async def path_vectors(self, paths: list[str]) -> dict[str, np.ndarray]:
        result: dict[str, np.ndarray] = {}
        rels = {self._relative(p): p for p in paths if p}
        rels.pop(None, None)
        keys = [k for k in rels if k]
        for start in range(0, len(keys), 500):
            chunk = keys[start : start + 500]
            marks = ",".join("?" for _ in chunk)
            for r in self._store.query(
                f"SELECT path, embedding FROM audio_features WHERE embedding IS NOT NULL AND path IN ({marks})", chunk
            ):
                result[rels[r["path"]]] = _normalize(np.frombuffer(r["embedding"], dtype=np.float32))
        return result

    async def sound_matches(self, username: str, tracks: list[dict[str, Any]]) -> dict[int, float]:
        """id трека пула → звуковая близость к вкусу пользователя (только проанализированные)."""
        centers = await self.taste(username)
        if centers is None:
            return {}
        vectors = await self.path_vectors([t["path"] for t in tracks if t.get("path")])
        result: dict[int, float] = {}
        for t in tracks:
            vector = vectors.get(t.get("path") or "")
            if vector is not None and vector.shape[0] == centers.shape[1]:
                result[int(t["id"])] = round(float(np.max(centers @ vector)), 4)
        return result

    # endregion
