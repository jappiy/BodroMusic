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

Результат хранится по пути файла и привязан к стабильному ключу звука: MBID записи (из БД Navidrome) и хэшу
аудиоданных без тегов (`audio_key.py`). Файл переехал или перетегирован (beets, регламент) — новый путь получает
готовый анализ по ключу, а не анализируется заново. Раз в час (`maintain`): ключи для старых записей (по файлу, копии в
карантине или MBID пропавшего трека в БД Navidrome), перенос анализа на новые пути, удаление записей треков, которых нет
ни в библиотеке, ни в пуле и резерве, ни в карантине. «Проанализировано» считается только по трекам библиотеки.
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

import numpy as np

from ..cleanup import _remap
from ..config import Settings
from ..db import Store
from ..navidrome_db import NavidromeDb
from .audio_key import audio_hash

log = logging.getLogger(__name__)

SCHEMA = """
CREATE TABLE IF NOT EXISTS audio_features (
    path TEXT PRIMARY KEY,
    embedding BLOB,
    features TEXT NOT NULL DEFAULT '{}',
    error TEXT,
    attempts INTEGER NOT NULL DEFAULT 0,
    analyzed_at INTEGER NOT NULL
);
"""

LEASE_MS = 30 * 60 * 1000
MAX_ATTEMPTS = 3
"""Неудачных попыток подряд, после которых трек больше не выдаётся воркеру («не анализировать»)."""
RETRY_DELAY_MS = 60 * 60 * 1000
"""Пауза перед повтором упавшего трека."""
LEASE_EXPIRED_ERROR = "Нет результата за 30 минут: воркер упал или завис"
INDEX_REFRESH_S = 600
MAINTAIN_S = 3600
"""Новые колонки `audio_features` (bodrod 0.15.0): MBID, хэш аудиоданных, ключи вычислены."""
_KEY_COLUMNS = (("mbid", "TEXT"), ("audio_hash", "TEXT"), ("keyed", "INTEGER NOT NULL DEFAULT 0"))
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


def _added_ts(value: str) -> float:
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00").split(".")[0]).timestamp()
    except ValueError:
        return 0.0


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
        """Путь → MBID (треки библиотеки); путь → (MBID, хэш) — вычисленные ключи."""
        self._mbids: dict[str, str] = {}
        self._identity: dict[str, tuple[str | None, str | None]] = {}
        """Путь → когда трек появился в библиотеке (для приоритета новых)."""
        self._added: dict[str, float] = {}
        """Ключи старых записей вычислены: до этого библиотека не выдаётся воркеру (иначе перенос не найдётся)."""
        self._maintained = False
        store.executescript(SCHEMA)
        columns = store.columns("audio_features")
        if "attempts" not in columns:
            # Ошибки до 0.14.0 — одна попытка: с декодированием через ffmpeg у них есть ещё две.
            store.execute("ALTER TABLE audio_features ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0")
            store.execute("UPDATE audio_features SET attempts = 1 WHERE error IS NOT NULL")
        for column, ddl in _KEY_COLUMNS:
            if column not in columns:
                store.execute(f"ALTER TABLE audio_features ADD COLUMN {column} {ddl}")
        store.execute("CREATE INDEX IF NOT EXISTS idx_audio_mbid ON audio_features(mbid)")
        store.execute("CREATE INDEX IF NOT EXISTS idx_audio_hash ON audio_features(audio_hash)")

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
            mbids = {}
            added: dict[str, float] = {}
            for song in self._db.iter_songs():
                local = _remap(song.path, self._settings.navidrome_music_dir, self._settings.music_dir)
                rel = self._relative(local) if local else None
                if rel:
                    result[song.id] = rel
                    if song.mbid:
                        mbids[rel] = song.mbid
                    if song.created_at:
                        added[rel] = _added_ts(song.created_at)
            self._mbids = mbids
            self._added = added
            return result

        return await asyncio.to_thread(build)

    # endregion

    # region Стабильный ключ звука (MBID, хэш аудиоданных)

    def _identity_of(self, rel: str) -> tuple[str | None, str | None]:
        """MBID и хэш аудиоданных файла (кэш в памяти; хэш читает до мегабайта файла)."""
        cached = self._identity.get(rel)
        if cached is None:
            cached = (self._mbids.get(rel), audio_hash(Path(self._local(rel))))
            self._identity[rel] = cached
        return cached

    def _adopt(self, rel: str, mbid: str | None, hashed: str | None) -> bool:
        """Тот же звук уже проанализирован под другим путём (перенос, перетегирование) — скопировать результат."""
        row = None
        for column, value in (("mbid", mbid), ("audio_hash", hashed)):
            if value and row is None:
                row = self._store.query_one(
                    f"SELECT * FROM audio_features WHERE {column} = ? AND path != ? AND error IS NULL "
                    "ORDER BY analyzed_at DESC LIMIT 1",
                    (value, rel),
                )
        if row is None:
            return False
        self._store.execute(
            "INSERT OR REPLACE INTO audio_features (path, embedding, features, error, attempts, analyzed_at, mbid, "
            "audio_hash, keyed) VALUES (?, ?, ?, NULL, 0, ?, ?, ?, 1)",
            (
                rel,
                row["embedding"],
                row["features"],
                row["analyzed_at"],
                mbid or row["mbid"],
                hashed or row["audio_hash"],
            ),
        )
        log.info("Анализ %s: взят у %s (тот же звук)", rel, row["path"])
        return True

    def _backfill_keys(self) -> int:
        """Ключи записей без них: по файлу, по копии в карантине, по MBID трека в БД Navidrome (в т. ч. пропавшего)."""
        rows = self._store.query("SELECT path FROM audio_features WHERE keyed = 0")
        if not rows:
            return 0
        quarantine: dict[str, str] = {}
        for q in self._store.query(
            "SELECT original_path, stored_path FROM quarantine WHERE restored_at IS NULL AND purged_at IS NULL"
        ):
            rel = self._relative(q["original_path"])
            if rel:
                quarantine[rel] = q["stored_path"]
        nd_paths: dict[str, str] = {}
        for r in rows:
            if r["path"] not in self._mbids:
                nd = _remap(self._local(r["path"]), self._settings.music_dir, self._settings.navidrome_music_dir)
                if nd:
                    nd_paths[nd] = r["path"]
        nd_mbids: dict[str, str] = {}
        if self._db is not None and nd_paths:
            nd_mbids = {nd_paths[p]: m for p, m in self._db.mbids_by_paths(list(nd_paths)).items() if p in nd_paths}
        for r in rows:
            rel = r["path"]
            mbid = self._mbids.get(rel) or nd_mbids.get(rel)
            local = Path(self._local(rel))
            stored = quarantine.get(rel)
            hashed = audio_hash(local) if local.exists() else audio_hash(Path(stored)) if stored else None
            self._store.execute(
                "UPDATE audio_features SET mbid = ?, audio_hash = ?, keyed = 1 WHERE path = ?", (mbid, hashed, rel)
            )
        log.info("Анализ: ключи звука вычислены для %s записей", len(rows))
        return len(rows)

    def _adopt_library(self) -> int:
        """Треки библиотеки без анализа, чей звук уже проанализирован под старым путём, — получают его результат."""
        known = {r["path"] for r in self._store.query("SELECT path FROM audio_features")}
        adopted = 0
        for rel in list(self._paths.values()):
            if rel not in known and rel not in self._leases and self._adopt(rel, *self._identity_of(rel)):
                adopted += 1
        return adopted

    def _keep_paths(self) -> set[str]:
        """Записи анализа, которые нужны: библиотека, пул и резерв (в т. ч. в загрузке), карантин, выданные воркеру."""
        keep = set(self._paths.values()) | set(self._leases)
        for r in self._store.query("SELECT path FROM pool_tracks WHERE path IS NOT NULL AND location != 'gone'"):
            rel = self._relative(r["path"])
            if rel:
                keep.add(rel)
        for q in self._store.query(
            "SELECT original_path FROM quarantine WHERE restored_at IS NULL AND purged_at IS NULL"
        ):
            rel = self._relative(q["original_path"])
            if rel:
                keep.add(rel)
        return keep

    def _cleanup(self) -> int:
        """Удалить записи треков, которых нет ни в библиотеке, ни в пуле и резерве, ни в карантине."""
        keep = self._keep_paths()
        gone = [r["path"] for r in self._store.query("SELECT path FROM audio_features") if r["path"] not in keep]
        for start in range(0, len(gone), 500):
            chunk = gone[start : start + 500]
            marks = ",".join("?" for _ in chunk)
            self._store.execute(f"DELETE FROM audio_features WHERE path IN ({marks})", chunk)
        if gone:
            log.info("Анализ: удалено %s записей треков, которых больше нет в библиотеке", len(gone))
        return len(gone)

    async def maintain(self) -> dict[str, int]:
        """Ключи старых записей → перенос анализа на новые пути → удаление лишнего. Без БД Navidrome — ничего."""
        paths = await self._song_paths()
        if not paths:
            return {}
        self._paths = paths
        keyed = await asyncio.to_thread(self._backfill_keys)
        adopted = await asyncio.to_thread(self._adopt_library)
        removed = await asyncio.to_thread(self._cleanup)
        self._maintained = True
        if (adopted or removed) and self._index is not None:
            self._index.built_at = 0.0
        return {"keyed": keyed, "adopted": adopted, "removed": removed}

    async def run_forever(self) -> None:
        while True:
            try:
                result = await self.maintain()
                if any(result.values()):
                    log.info("Анализ: обслуживание — %s", result)
            except Exception:  # noqa: BLE001 — повторим через час
                log.exception("Анализ: ошибка обслуживания")
            await asyncio.sleep(MAINTAIN_S)

    # endregion

    # region Очередь для воркера

    def _known(self) -> dict[str, dict[str, Any]]:
        """Пути с результатом — признаками или ошибкой: путь → `error`, `attempts`, `analyzed_at`."""
        rows = self._store.query("SELECT path, error, attempts, analyzed_at FROM audio_features")
        return {r["path"]: r for r in rows}

    def _expire_leases(self, now: int) -> None:
        """Аренды без результата за `LEASE_MS` — неудачная попытка (воркер упал вместе с треком или завис)."""
        expired = [p for p, t in self._leases.items() if now - t >= LEASE_MS]
        last_result_at = self._last_result_at
        for path in expired:
            log.warning("Анализ %s: %s", path, LEASE_EXPIRED_ERROR)
            self.save(path, None, None, LEASE_EXPIRED_ERROR)
        self._last_result_at = last_result_at  # это не результат воркера

    async def next_jobs(self, limit: int) -> list[str]:
        """Пути для анализа: сначала пул и резерв (новые), потом библиотека. Выданное держится 30 минут.

        Упавший трек (статус `failed` или аренда истекла без результата) повторяется не раньше чем через час;
        после `MAX_ATTEMPTS` неудач подряд он больше не выдаётся, чтобы воркер не зацикливался на битых файлах.
        """
        now = self._clock()
        self._expire_leases(now)
        known = self._known()

        def wanted(rel: str | None) -> bool:
            if not rel or rel in self._leases:
                return False
            row = known.get(rel)
            if row is None:
                return True
            return (
                row["error"] is not None
                and int(row["attempts"] or 0) < MAX_ATTEMPTS
                and now - int(row["analyzed_at"]) >= RETRY_DELAY_MS
            )

        async def fresh(rel: str) -> bool:
            """Новый путь: если тот же звук уже проанализирован (перенос, перетегирование) — взять готовое."""
            if rel in known:
                return True
            identity = await asyncio.to_thread(self._identity_of, rel)
            return not self._adopt(rel, *identity)

        jobs: list[str] = []
        pool = self._store.query(
            "SELECT path FROM pool_tracks WHERE location IN ('pool', 'reserve') AND path IS NOT NULL ORDER BY id DESC"
        )
        for r in pool:
            rel = self._relative(r["path"])
            if wanted(rel) and rel not in jobs and await fresh(rel):  # type: ignore[arg-type]
                jobs.append(rel)  # type: ignore[arg-type]
            if len(jobs) >= limit:
                break
        if len(jobs) < limit and self._maintained:
            # Новые треки библиотеки — первыми (только что импортированные анализируются сразу).
            for rel in sorted(self._paths.values(), key=lambda p: -self._added.get(p, 0.0)):
                if wanted(rel) and rel not in jobs and await fresh(rel):
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
        mbid, hashed = self._identity.get(relative, (self._mbids.get(relative), None))
        self._store.execute(
            "INSERT INTO audio_features (path, embedding, features, error, attempts, analyzed_at, mbid, audio_hash, "
            "keyed) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
            "ON CONFLICT(path) DO UPDATE SET embedding = excluded.embedding, features = excluded.features, "
            "error = excluded.error, analyzed_at = excluded.analyzed_at, "
            "mbid = COALESCE(excluded.mbid, audio_features.mbid), "
            "audio_hash = COALESCE(excluded.audio_hash, audio_features.audio_hash), "
            "keyed = MAX(excluded.keyed, audio_features.keyed), "
            "attempts = CASE WHEN excluded.error IS NULL THEN 0 "
            "WHEN audio_features.error IS NULL THEN 1 ELSE audio_features.attempts + 1 END",
            (
                relative, blob, json.dumps(stored, ensure_ascii=False), error, 0 if error is None else 1,
                self._clock(), mbid, hashed, int(hashed is not None),
            ),
        )  # fmt: skip
        if error is not None:
            row = self._store.query_one("SELECT attempts FROM audio_features WHERE path = ?", (relative,))
            if row and int(row["attempts"]) >= MAX_ATTEMPTS:
                log.warning("Анализ %s: %d неудач подряд — больше не анализируется (%s)", relative, MAX_ATTEMPTS, error)
        self._leases.pop(relative, None)
        self._last_result_at = self._clock()
        if self._index is not None and embedding:
            self._index.built_at = 0.0  # перестроить при следующем запросе (не чаще раза в 10 минут — ниже)

    def status(self) -> dict[str, Any]:
        """Счётчики — только по трекам, которые сейчас есть в библиотеке Navidrome (пул, резерв и старые пути — нет)."""
        library = set(self._paths.values())
        analyzed = errors = skipped = 0
        for r in self._store.query("SELECT path, error, attempts FROM audio_features"):
            if r["path"] not in library:
                continue
            if r["error"] is None:
                analyzed += 1
            else:
                errors += 1
                skipped += int(int(r["attempts"] or 0) >= MAX_ATTEMPTS)
        return {
            "enabled": bool(self.token),
            "analyzed": analyzed,
            "errors": errors,
            "skipped": skipped,
            "library": len(library),
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

    async def all_features(self) -> dict[str, dict[str, Any]]:
        """Признаки всех проанализированных треков библиотеки: id в Navidrome → признаки (для режимов волны)."""
        await self._ensure_index()
        by_path = {p: s for s, p in self._paths.items()}
        result: dict[str, dict[str, Any]] = {}
        for r in self._store.query("SELECT path, features FROM audio_features WHERE error IS NULL"):
            song_id = by_path.get(r["path"])
            if song_id:
                result[song_id] = json.loads(r["features"] or "{}")
        return result

    async def vectors_by_id(self, song_ids: list[str]) -> dict[str, np.ndarray]:
        """Нормированные эмбеддинги треков по id (у непроанализированных — нет записи)."""
        index = await self._ensure_index()
        positions = {s: i for i, s in enumerate(index.ids)}
        return {s: index.matrix[positions[s]] for s in song_ids if s in positions}

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
