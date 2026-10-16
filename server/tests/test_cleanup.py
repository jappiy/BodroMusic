import asyncio
import json
import sqlite3

import httpx
from fastapi.testclient import TestClient

from bodrod.main import build_services, create_app

from .fakes import FakeWorld
from .test_api import auth
from .test_worker import SETTINGS

SONGS = [
    # id, artist, title, album, путь относительно библиотеки
    ("s1", "Трофим", "Город Сочи", "Сборник", "library/Трофим/Сборник/01 Город Сочи.mp3"),
    ("s2", "Артист", "Песня", "Альбом", "library/Артист/Альбом/02 Песня.flac"),
    ("s3", "Другой", "Хит", "Синглы", "collections/Треки/Другой/Хит.mp3"),
]
# Дизлайки: s1 — оба человека, s2 — только vasya, s3 — только служебный admin.
DISLIKES = [("u-vasya", "s1"), ("u-masha", "s1"), ("u-vasya", "s2"), ("u-admin", "s3")]


def make_navidrome_db(path, music) -> None:
    conn = sqlite3.connect(path)
    conn.executescript(
        """
        CREATE TABLE user (id TEXT, user_name TEXT, is_admin BOOL);
        CREATE TABLE library (id INTEGER, path TEXT);
        CREATE TABLE media_file (id TEXT, title TEXT, artist TEXT, album TEXT, album_id TEXT, duration REAL,
            size INTEGER, suffix TEXT, bit_rate INTEGER, path TEXT, library_id INTEGER, missing BOOL DEFAULT 0);
        CREATE TABLE annotation (user_id TEXT, item_id TEXT, item_type TEXT, rating INTEGER);
        """
    )
    conn.executemany(
        "INSERT INTO user VALUES (?, ?, ?)",
        [("u-admin", "admin", 1), ("u-vasya", "vasya", 1), ("u-masha", "masha", 0)],
    )
    conn.execute("INSERT INTO library VALUES (1, '/music')")
    for song_id, artist, title, album, rel in SONGS:
        conn.execute(
            "INSERT INTO media_file VALUES (?, ?, ?, ?, ?, 200.5, 1000, 'mp3', 320, ?, 1, 0)",
            (song_id, title, artist, album, f"al-{song_id}", rel),
        )
        file = music / rel
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_bytes(b"audio")
    (music / "library/Трофим/Сборник/cover.jpg").write_bytes(b"img")
    conn.executemany("INSERT INTO annotation VALUES (?, ?, 'media_file', 1)", DISLIKES)
    conn.commit()
    conn.close()


def make_client(world: FakeWorld, tmp_path, **overrides):
    music = tmp_path / "music"
    music.mkdir()
    db = tmp_path / "navidrome.db"
    make_navidrome_db(db, music)
    queue = tmp_path / "queue.txt"
    queue.write_text("Трофим - Город сочи\nКто-то - Что-то\n", encoding="utf-8")
    settings = SETTINGS.__class__(
        **{
            **SETTINGS.__dict__,
            "data_dir": str(tmp_path),
            "navidrome_db": str(db),
            "navidrome_music_dir": "/music",
            "music_dir": str(music),
            "slsk_worker_queue": str(queue),
            **overrides,
        }
    )
    services = build_services(settings, world.client())
    return TestClient(create_app(services, run_worker=False)), services, music, queue


def test_lists_and_admin_only(tmp_path):
    client, _, _, _ = make_client(FakeWorld(), tmp_path)
    with client:
        info = client.get("/api/v1/info", headers=auth()).json()
        assert info["cleanup"] is True and info["admin"] is True

        users = client.get("/api/v1/cleanup/users", headers=auth()).json()
        assert [u["username"] for u in users] == ["masha", "vasya"]  # служебный admin скрыт

        everyone = client.get("/api/v1/cleanup/disliked", headers=auth()).json()
        assert [s["id"] for s in everyone] == ["s1"]
        assert everyone[0]["dislikedBy"] == ["masha", "vasya"]
        assert everyone[0]["path"] == "library/Трофим/Сборник/01 Город Сочи.mp3"

        mine = client.get("/api/v1/cleanup/disliked", params={"username": "vasya"}, headers=auth()).json()
        assert [s["id"] for s in mine] == ["s2", "s1"]
        unknown = client.get("/api/v1/cleanup/disliked", params={"username": "admin"}, headers=auth())
        assert unknown.status_code == 404


def test_non_admin_forbidden(tmp_path, monkeypatch):
    client, services, _, _ = make_client(FakeWorld(), tmp_path)

    async def not_admin(username: str) -> bool:
        return False

    monkeypatch.setattr(services.cleanup, "is_admin", not_admin)
    with client:
        assert client.get("/api/v1/cleanup/disliked", headers=auth()).status_code == 403
        assert client.post("/api/v1/cleanup/delete", json={"ids": ["s1"]}, headers=auth()).status_code == 403
        assert client.get("/api/v1/blocklist", headers=auth()).status_code == 403


def test_delete_blocks_and_cleans_up(tmp_path):
    world = FakeWorld()
    client, _, music, queue = make_client(world, tmp_path)
    with client:
        results = client.post("/api/v1/cleanup/delete", json={"ids": ["s1", "nope"]}, headers=auth()).json()
        assert [(r["id"], r["ok"]) for r in results] == [("s1", True), ("nope", False)]
        # Файл удалён, папка альбома с одной обложкой — тоже, пустая папка исполнителя — тоже.
        assert not (music / "library/Трофим").exists()
        assert (music / "library/Артист/Альбом/02 Песня.flac").exists()
        assert queue.read_text(encoding="utf-8") == "Кто-то - Что-то\n"
        assert world.scans == 1

        page = client.get("/api/v1/blocklist", headers=auth()).json()
        assert page["total"] == 1
        entry = page["items"][0]
        assert (entry["artist"], entry["title"], entry["createdBy"], entry["removedCount"]) == (
            "Трофим",
            "Город Сочи",
            "vasya",
            1,
        )
        assert client.get("/api/v1/blocklist", params={"q": "сочи"}, headers=auth()).json()["total"] == 1
        assert client.get("/api/v1/blocklist", params={"q": "песня"}, headers=auth()).json()["total"] == 0

        # Заявка на заблокированный трек отклоняется (сравнение без регистра и скобок).
        blocked = {"kind": "track", "artist": "трофим", "title": "Город Сочи (Remastered)"}
        assert client.post("/api/v1/requests", json=blocked, headers=auth()).status_code == 409

        assert client.delete(f"/api/v1/blocklist/{entry['id']}", headers=auth()).status_code == 204
        assert client.get("/api/v1/blocklist", headers=auth()).json()["total"] == 0
        assert client.post("/api/v1/requests", json=blocked, headers=auth()).status_code == 201


def test_sweep_removes_reappeared_files(tmp_path):
    _, services, music, _ = make_client(FakeWorld(), tmp_path)
    services.store.block("Другой", "Хит", None, "vasya")
    removed = asyncio.run(services.cleanup.sweep())
    assert removed == 1
    assert not (music / "collections/Треки/Другой").exists()
    assert services.store.list_blocked(None, 10, 0)[1][0].removed_count == 2


def test_delete_through_lidarr_unmonitors_empty_album(tmp_path):
    world = FakeWorld()
    lidarr_path = "/volume2/music/library/Артист/Альбом/02 Песня.flac"
    deleted: list[str] = []
    unmonitored: list[dict] = []
    original = world._lidarr

    def lidarr(request: httpx.Request) -> httpx.Response:
        path = request.url.path.removeprefix("/api/v1")
        if path == "/artist" and request.method == "GET":
            return httpx.Response(200, json=[{"id": 5, "path": "/volume2/music/library/Артист"}])
        if path == "/trackfile":
            return httpx.Response(200, json=[{"id": 77, "albumId": 9, "path": lidarr_path}])
        if path == "/trackfile/77" and request.method == "DELETE":
            deleted.append(path)
            return httpx.Response(200)
        if path == "/album/9":
            return httpx.Response(200, json={"id": 9, "statistics": {"trackFileCount": 0}})
        if path == "/album/monitor":
            unmonitored.append(json.loads(request.content))
            return httpx.Response(202, json=[])
        return original(request)

    world._lidarr = lidarr
    _, services, music, _ = make_client(world, tmp_path, lidarr_music_dir="/volume2/music")
    results = asyncio.run(services.cleanup.delete(["s2"], "vasya"))
    assert results[0].ok
    assert deleted == ["/trackfile/77"]
    assert unmonitored == [{"albumIds": [9], "monitored": False}]
    assert not (music / "library/Артист").exists()
