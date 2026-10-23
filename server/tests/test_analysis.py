"""Анализ звучания: очередь (пул первым), сохранение, профиль вкуса, похожие, фильтр пула, токен воркера."""

import asyncio
import dataclasses
import sqlite3
from pathlib import Path

import numpy as np
from fastapi.testclient import TestClient

from bodrod.db import Store
from bodrod.navidrome_db import NavidromeDb
from bodrod.reco.analysis import AnalysisService, energy_of, kmeans
from bodrod.reco.store import RecoStore

from .test_worker import SETTINGS

DIM = 8


def nd_db(path: Path, songs: list[tuple[str, str]], starred: list[str]) -> NavidromeDb:
    """Мини-БД Navidrome: треки (id, путь) и лайки пользователя vasya."""
    conn = sqlite3.connect(path)
    conn.executescript(
        """
        CREATE TABLE media_file (id TEXT, title TEXT, artist TEXT, album TEXT, album_id TEXT, duration REAL,
            size INTEGER, suffix TEXT, bit_rate INTEGER, path TEXT, missing INTEGER DEFAULT 0);
        CREATE TABLE user (id TEXT, user_name TEXT, is_admin INTEGER);
        CREATE TABLE annotation (user_id TEXT, item_id TEXT, item_type TEXT, starred INTEGER, rating INTEGER,
            play_count INTEGER, play_date TEXT, starred_at TEXT);
        INSERT INTO user VALUES ('u1', 'vasya', 0);
        """
    )
    for song_id, rel in songs:
        conn.execute(
            "INSERT INTO media_file VALUES (?, ?, 'A', 'B', 'al', 200, 1, 'flac', 900, ?, 0)",
            (song_id, song_id, f"/music/{rel}"),
        )
    for song_id in starred:
        conn.execute("INSERT INTO annotation VALUES ('u1', ?, 'media_file', 1, 0, 1, NULL, NULL)", (song_id,))
    conn.commit()
    conn.close()
    return NavidromeDb(str(path))


def vector(direction: int, noise: float = 0.0, seed: int = 0) -> list[float]:
    rng = np.random.default_rng(seed)
    v = np.zeros(DIM)
    v[direction] = 1.0
    v += rng.normal(0, noise, DIM)
    return v.tolist()


def make(tmp_path: Path, songs, starred):
    settings = dataclasses.replace(
        SETTINGS, music_dir="/music", navidrome_music_dir="/music", analyzer_token="secret", data_dir=str(tmp_path)
    )
    store = Store(str(tmp_path / "bodrod.sqlite3"))
    reco = RecoStore(store)
    db = nd_db(tmp_path / "navidrome.db", songs, starred)
    return AnalysisService(store, settings, db), reco, store, settings


def test_kmeans_finds_modes() -> None:
    points = np.array([vector(0, 0.05, i) for i in range(10)] + [vector(3, 0.05, 100 + i) for i in range(10)])
    points = points / np.linalg.norm(points, axis=1, keepdims=True)
    centers = kmeans(points, 2)
    assert sorted(int(np.argmax(c)) for c in centers) == [0, 3]


def test_energy() -> None:
    calm = energy_of({"bpm": 70, "danceability": 0.1, "moods": {"aggressive": 0.0, "relaxed": 1.0}})
    loud = energy_of({"bpm": 170, "danceability": 0.9, "moods": {"aggressive": 0.9, "relaxed": 0.0}})
    assert calm is not None and loud is not None and calm < 0.1 < 0.9 < loud


def test_queue_pool_first_then_library_and_leases(tmp_path: Path) -> None:
    service, reco, store, _ = make(tmp_path, [("s1", "lib/a.flac"), ("s2", "lib/b.flac")], [])
    reco.add_pool_track(
        artist="P", title="Q", album=None, duration_ms=None, cover_url=None, yandex_id=None, location="pool",
        path="/music/_discovery/P/q.flac",
    )  # fmt: skip
    # До первого обслуживания (ключи старых записей) библиотека не выдаётся — только пул.
    assert asyncio.run(service.next_jobs(2)) == ["_discovery/P/q.flac"]
    service._leases.clear()
    asyncio.run(service.maintain())
    jobs = asyncio.run(service.next_jobs(2))
    assert jobs == ["_discovery/P/q.flac", "lib/a.flac"]
    # Выданное не выдаётся снова, пока не истекла аренда.
    assert asyncio.run(service.next_jobs(5)) == ["lib/b.flac"]
    service.save("lib/a.flac", {"bpm": 120}, vector(0), None)
    assert service.status()["analyzed"] == 1
    # Упавший трек (failed) повторяется через час, после трёх неудач подряд больше не выдаётся.
    service.save("lib/b.flac", None, None, "ValueError: не аудио")
    assert service.status()["errors"] == 1 and service.status()["skipped"] == 0
    assert asyncio.run(service.next_jobs(5)) == []  # q.flac ещё в аренде, b.flac — рано
    hour = 60 * 60 * 1000
    base = service._clock()
    service._clock = lambda: base + hour
    # Аренда q.flac истекла без результата — это неудачная попытка, повтор тоже через час.
    assert asyncio.run(service.next_jobs(5)) == ["lib/b.flac"]
    service.save("lib/b.flac", None, None, "ValueError: не аудио")
    service._clock = lambda: base + 2 * hour
    assert asyncio.run(service.next_jobs(5)) == ["_discovery/P/q.flac", "lib/b.flac"]
    service.save("lib/b.flac", None, None, "ValueError: не аудио")  # третья неудача
    service._clock = lambda: base + 10 * hour
    assert asyncio.run(service.next_jobs(5)) == []  # q.flac: вторая истёкшая аренда
    assert service.status()["skipped"] == 1
    service._clock = lambda: base + 20 * hour
    assert asyncio.run(service.next_jobs(5)) == ["_discovery/P/q.flac"]
    service.save("_discovery/P/q.flac", {"bpm": 90}, vector(1), None)  # успех обнуляет счётчик
    status = service.status()
    # Трек пула не в библиотеке — в «проанализировано» не считается.
    assert status == {**status, "analyzed": 1, "errors": 1, "skipped": 1, "library": 2}
    attempts = store.query_one("SELECT attempts FROM audio_features WHERE path = ?", ("_discovery/P/q.flac",))
    assert attempts == {"attempts": 0}


def test_migration_keeps_old_errors_retryable(tmp_path: Path) -> None:
    store = Store(str(tmp_path / "bodrod.sqlite3"))
    store.executescript(
        "CREATE TABLE audio_features (path TEXT PRIMARY KEY, embedding BLOB, features TEXT NOT NULL DEFAULT '{}', "
        "error TEXT, analyzed_at INTEGER NOT NULL);"
        "INSERT INTO audio_features VALUES ('lib/a.flac', NULL, '{}', 'Ошибка', 0);"
        "INSERT INTO audio_features VALUES ('lib/b.flac', NULL, '{}', NULL, 0);"
    )
    service, *_ = make(tmp_path, [("s1", "lib/a.flac"), ("s2", "lib/b.flac")], [])
    rows = {r["path"]: r["attempts"] for r in store.query("SELECT path, attempts FROM audio_features")}
    assert rows == {"lib/a.flac": 1, "lib/b.flac": 0}
    asyncio.run(service.maintain())
    assert asyncio.run(service.next_jobs(5)) == ["lib/a.flac"]


def test_similar_and_taste_filter(tmp_path: Path) -> None:
    songs = [(f"s{i}", f"lib/{i}.flac") for i in range(14)]
    service, reco, store, _ = make(tmp_path, songs, starred=[f"s{i}" for i in range(12)])
    for i in range(12):  # вкус: два направления
        service.save(f"lib/{i}.flac", {"bpm": 100}, vector(0 if i < 6 else 1, 0.05, i), None)
    service.save("lib/12.flac", {}, vector(0, 0.01, 99), None)
    service.save("lib/13.flac", {}, vector(5, 0.01, 98), None)
    similar = asyncio.run(service.similar("s12", 3))
    assert len(similar) == 3 and all(int(s[1:]) < 6 for s in similar)
    near = {"id": 1, "path": "/music/_discovery/near.flac"}
    far = {"id": 2, "path": "/music/_discovery/far.flac"}
    service.save("_discovery/near.flac", {}, vector(1, 0.01, 7), None)
    service.save("_discovery/far.flac", {}, vector(6, 0.01, 8), None)
    matches = asyncio.run(service.sound_matches("vasya", [near, far]))
    assert matches[1] > 0.9 > 0.2 > matches[2]
    features = asyncio.run(service.features(["s1", "s13"]))
    assert features["s1"]["bpm"] == 100 and "energy" in features["s1"]


def test_worker_api_requires_token(tmp_path: Path) -> None:
    from bodrod.main import build_services, create_app

    from .fakes import FakeWorld

    service, *_ = make(tmp_path, [("s1", "lib/a.flac")], [])
    services = build_services(dataclasses.replace(SETTINGS, data_dir=str(tmp_path / "svc")), FakeWorld().client())
    services.analysis = service
    asyncio.run(service.maintain())
    with TestClient(create_app(services, run_worker=False)) as client:
        assert client.get("/api/v1/analysis/next").status_code == 401
        jobs = client.get("/api/v1/analysis/next", headers={"X-Analyzer-Token": "secret"}).json()["jobs"]
        assert jobs == ["lib/a.flac"]
        body = {"path": "lib/a.flac", "features": {"bpm": 128}, "embedding": vector(2)}
        ok = client.post("/api/v1/analysis/result", json=body, headers={"X-Analyzer-Token": "secret"})
        assert ok.status_code == 204
        assert service.status()["analyzed"] == 1
        failed = {"path": "lib/b.flac", "status": "failed"}
        assert (
            client.post("/api/v1/analysis/result", json=failed, headers={"X-Analyzer-Token": "secret"}).status_code
            == 204
        )
        assert service.status()["errors"] == 0  # lib/b.flac нет в библиотеке — не считается
        assert store_row(service, "lib/b.flac")["error"] is not None


def store_row(service: AnalysisService, path: str) -> dict:
    return service._store.query_one("SELECT * FROM audio_features WHERE path = ?", (path,))
