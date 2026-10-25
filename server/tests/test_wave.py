"""Задание 023: режимы волны по признакам звучания, темпоритм, реакции сессии, производные признаки."""

import asyncio
import sqlite3
import time

import numpy as np

from bodrod.db import Store
from bodrod.navidrome_db import NavidromeDb
from bodrod.reco.traits import camelot, camelot_compatible, traits
from bodrod.reco.wave import WaveService

NOW = 1_800_000_000.0


def song(i, artist, bpm, energy, happy=0.5, sad=0.5, aggressive=0.2, relaxed=0.5, voice=0.9, key="C", scale="major"):
    return {
        "id": f"s{i}", "artist": artist, "title": f"Песня {i}", "bpm": bpm,
        "features": {"bpm": bpm, "energy": energy, "key": key, "scale": scale, "voice": voice,
                     "moods": {"happy": happy, "sad": sad, "aggressive": aggressive, "relaxed": relaxed},
                     "danceability": 0.5, "genres": [["Rock---Punk", 0.6]]},
    }  # fmt: skip


SONGS = [
    *[song(i, f"Бодрый {i % 5}", 120 + i, 0.75, happy=0.7, sad=0.2) for i in range(20)],
    *[song(100 + i, f"Тихий {i % 4}", 80, 0.25, relaxed=0.8) for i in range(10)],
    song(200, "Грустный", 70, 0.3, happy=0.1, sad=0.9),
    song(201, "Шумный", 150, 0.9, aggressive=0.9, happy=0.3, sad=0.4),
    song(202, "Инструмент", 100, 0.5, voice=0.1),
]


class FakeAnalysis:
    def __init__(self, features, vectors):
        self._features = features
        self._vectors = vectors

    async def all_features(self):
        return self._features

    async def vectors_by_id(self, ids):
        return {i: self._vectors[i] for i in ids if i in self._vectors}


def make_db(path, annotations=()):
    conn = sqlite3.connect(path)
    conn.executescript(
        """
        CREATE TABLE user (id TEXT, user_name TEXT, is_admin BOOL);
        CREATE TABLE media_file (id TEXT, title TEXT, artist TEXT, album TEXT, album_id TEXT, duration REAL,
            size INTEGER, suffix TEXT, bit_rate INTEGER, path TEXT, missing BOOL DEFAULT 0, created_at TEXT);
        CREATE TABLE annotation (user_id TEXT, item_id TEXT, item_type TEXT, starred INTEGER, rating INTEGER,
            play_count INTEGER, play_date TEXT, starred_at TEXT);
        """
    )
    conn.execute("INSERT INTO user VALUES ('u1', 'vasya', 0)")
    for s in SONGS:
        conn.execute(
            "INSERT INTO media_file VALUES (?, ?, ?, 'Альбом', ?, 200, 1, 'mp3', 320, ?, 0, '2026-10-01T10:00:00')",
            (s["id"], s["title"], s["artist"], f"al-{s['artist']}", f"/music/library/{s['id']}.mp3"),
        )
    for item_id, starred, rating, plays, date in annotations:
        conn.execute(
            "INSERT INTO annotation VALUES ('u1', ?, 'media_file', ?, ?, ?, ?, NULL)",
            (item_id, starred, rating, plays, date),
        )
    conn.commit()
    conn.close()


def make_service(tmp_path, annotations=(), vectors=None):
    make_db(tmp_path / "nd.db", annotations)
    store = Store(str(tmp_path / "bodrod.sqlite3"))
    features = {s["id"]: s["features"] for s in SONGS}
    vectors = vectors or {}
    return WaveService(store, NavidromeDb(str(tmp_path / "nd.db")), FakeAnalysis(features, vectors), clock=lambda: NOW)


def by_id(sid):
    return next(s for s in SONGS if s["id"] == sid)


def test_traits_camelot_language_and_genres():
    assert camelot("A", "minor") == "8A" and camelot("C", "major") == "8B" and camelot("Bb", "major") == "6B"
    assert camelot_compatible("8A", "9A") and camelot_compatible("8A", "8B") and not camelot_compatible("8A", "3B")
    t = traits({"voice": 0.9, "genres": [["Electronic---Techno", 0.7]]}, "Песня", "Кино")
    assert t["lang"] == "ru" and t["electronic"] == 1.0 and t["instrumental"] == 0.1
    assert traits(None, "Song", "Band")["lang"] == "en"
    assert traits({"voice": 0.1}, "Song", "Band")["lang"] == "none"


def test_energetic_mode_tempo_flow_and_artist_gap(tmp_path):
    service = make_service(tmp_path)
    result = asyncio.run(service.next_batch("vasya", {"mode": "energetic", "count": 12}))
    ids = result["songIds"]
    assert len(ids) == 12 and result["matched"] == 21  # 20 бодрых + «Шумный» (BPM 150, энергия 0.9)
    assert all(by_id(i)["features"]["energy"] >= 0.55 for i in ids)
    artists = [by_id(i)["artist"] for i in ids]
    for n in range(len(artists)):
        assert artists[n] not in artists[max(0, n - 4) : n]  # один исполнитель — не чаще раза в 4 трека
    bpms = [by_id(i)["bpm"] for i in ids]
    assert all(abs(b - a) / a <= 0.1 for a, b in zip(bpms, bpms[1:], strict=False))


def test_calm_sad_loud_instrumental_modes(tmp_path):
    service = make_service(tmp_path)

    def batch(mode, count=5):
        return asyncio.run(service.next_batch("vasya", {"mode": mode, "count": count}))

    calm = {f"s{100 + i}" for i in range(10)} | {"s200"}  # «Грустный» тоже медленный и расслабленный
    assert batch("calm")["matched"] == 11 and set(batch("calm")["songIds"]) <= calm
    assert batch("sad", 1)["songIds"] == ["s200"]
    assert batch("loud", 1)["songIds"] == ["s201"]
    assert batch("instrumental", 1)["songIds"] == ["s202"]


def test_excludes_disliked_recent_and_recently_played(tmp_path):
    played = time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(NOW - 3600))
    service = make_service(tmp_path, [("s1", 0, 1, 0, None), ("s2", 0, 0, 5, played)])
    ids = asyncio.run(service.next_batch("vasya", {"mode": "energetic", "count": 8, "recent": ["s3"]}))["songIds"]
    assert "s1" not in ids and "s3" not in ids  # дизлайк, недавнее в очереди
    assert "s2" not in ids  # слушал час назад (правило «не чаще раза в 3 дня»), треков хватает


def test_small_mode_relaxes_but_never_repeats_recent(tmp_path):
    service = make_service(tmp_path)
    recent: list[str] = []
    for _ in range(3):
        result = asyncio.run(service.next_batch("vasya", {"mode": "loud", "count": 5, "recent": recent}))
        assert result["lowCoverage"] is True
        assert not set(result["songIds"]) & set(recent)
        recent += result["songIds"]
    assert len(recent) == len(set(recent))


def test_session_dislike_pushes_artist_and_similar_down(tmp_path):
    rng = np.random.default_rng(1)
    vectors = {}
    for s in SONGS:
        v = rng.normal(size=8)
        vectors[s["id"]] = v / np.linalg.norm(v)
    service = make_service(tmp_path, vectors=vectors)
    session = [{"id": "s0", "event": "dislike"}, {"id": "s5", "event": "skip"}]
    ids = asyncio.run(service.next_batch("vasya", {"mode": "energetic", "count": 8, "session": session}))["songIds"]
    assert "s0" not in ids
    assert all(by_id(i)["artist"] != "Бодрый 0" for i in ids[:4])  # исполнитель дизлайкнутого трека ушёл вниз
    stats = service._artist_stats("vasya")  # noqa: SLF001
    assert stats  # ранний пропуск запомнен как долговременный вес


def test_modes_and_report(tmp_path):
    service = make_service(tmp_path, [("s1", 1, 0, 10, None)])
    modes = {m["id"]: m for m in asyncio.run(service.modes_for("vasya"))}
    assert modes["energetic"]["count"] == 21 and modes["favorites"]["count"] == 1
    assert any(m.get("dynamic") for m in modes.values())  # «В стиле: Бодрый 1»
    report = asyncio.run(service.report())
    assert report["library"] == len(SONGS) and report["analyzed"] == len(SONGS)
    assert report["features"]["camelot"] == len(SONGS) and report["modes"]["calm"] == 11


def test_modes_file_overrides_thresholds(tmp_path):
    path = tmp_path / "modes.json"
    path.write_text('{"modes": {"calm": {"bpm_max": 60}}, "tuning": {"artist_gap": 2}}', encoding="utf-8")
    make_db(tmp_path / "nd.db")
    service = WaveService(Store(str(tmp_path / "b.sqlite3")), NavidromeDb(str(tmp_path / "nd.db")), None,
                          modes_file=str(path), clock=lambda: NOW)  # fmt: skip
    assert service.modes["calm"]["bpm_max"] == 60 and service.modes["calm"]["energy_max"] == 0.45
    assert service.tuning["artist_gap"] == 2


def test_report_counts_are_consistent(tmp_path):
    """Задание 034: «Популярное» и «Новое» — не вся библиотека, «без слов» = язык none, настроения сходятся."""
    service = make_service(tmp_path, [("s1", 1, 0, 10, None), ("s2", 0, 0, 3, None)])
    report = asyncio.run(service.report("vasya"))
    modes = {m["id"]: m for m in report["modeList"]}
    assert report["library"] == len(SONGS) and report["pending"] == 0 and report["failed"] == 0
    assert modes["popular"]["count"] == 2  # только треки с отметками, а не вся медиатека
    assert modes["new"]["count"] == 0  # всё добавлено одним импортом
    assert modes["favorites"]["count"] == 2 and modes["energetic"]["mood"] is True  # лайк и 3 прослушивания
    assert modes["instrumental"]["count"] == report["languages"].get("none", 0) == 1
    in_moods = {m: modes[m]["count"] for m in ("energetic", "calm", "happy", "sad", "loud")}
    assert report["noMood"] <= report["analyzed"] and sum(in_moods.values()) >= report["analyzed"] - report["noMood"]
    assert report["modes"]["popular"] == 2  # старое поле для клиентов до 0.29.0


def test_loudness_falls_back_to_rms():
    t = traits({"rmsDb": -12.0, "moods": {"relaxed": 0.5}, "danceability": 0.5}, "Song", "Band")
    assert t["loudness"] == -12.0 and t["loudness_estimated"] is True
    assert traits({"loudness": -9.0, "rmsDb": -12.0}, "Song", "Band")["loudness_estimated"] is False
