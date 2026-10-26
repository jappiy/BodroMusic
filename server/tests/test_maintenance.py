import asyncio
import sqlite3
import struct
import time

from fastapi.testclient import TestClient

from bodrod.main import build_services, create_app
from bodrod.maintenance import read_tags, title_key

from .fakes import FakeWorld, yandex_track
from .test_api import auth
from .test_worker import SETTINGS

# id, исполнитель, название, альбом, путь, суффикс, длительность, битрейт, размер, номер трека, встроенная обложка
SONGS = [
    # Альбом в FLAC и тот же трек синглом в MP3 — сингл уходит в карантин.
    ("a1", "Артист", "Песня", "Альбом", "Артист/Альбом/01 Песня.flac", "flac", 200.0, 900, 22_000_000, 1, 1),
    ("a2", "Артист", "Вторая", "Альбом", "Артист/Альбом/02 Вторая.flac", "flac", 180.0, 900, 20_000_000, 2, 1),
    ("a3", "Артист", "Третья", "Альбом", "Артист/Альбом/03 Третья.flac", "flac", 190.0, 900, 21_000_000, 3, 1),
    ("a4", "Артист", "Интро", "Альбом", "Артист/Альбом/04 Интро.flac", "flac", 6.0, 900, 600_000, 4, 1),
    (
        "s1",
        "Артист",
        "Песня (Remastered 2011)",
        "Singles",
        "library/Артист/Singles/Артист - Песня.mp3",
        "mp3",
        201.0,
        320,
        8_000_000,
        None,
        1,
    ),
    # Живая версия — другой трек, остаётся.
    (
        "l1",
        "Артист",
        "Песня (Live)",
        "Singles",
        "library/Артист/Singles/Артист - Песня (Live).mp3",
        "mp3",
        260.0,
        320,
        10_400_000,
        None,
        1,
    ),
    # Подозрительно короткий и обрезанный.
    ("t1", "Кто-то", "Мини", "Singles", "library/Кто-то/Singles/Кто-то - Мини.mp3", "mp3", 5.0, 320, 200_000, None, 1),
    (
        "t2",
        "Кто-то",
        "Обрезан",
        "Singles",
        "library/Кто-то/Singles/Кто-то - Обрезан.mp3",
        "mp3",
        240.0,
        320,
        1_000_000,
        None,
        1,
    ),
    # Один альбом в двух папках — собирается в одну.
    ("c1", "Группа", "Раз", "Лучшее", "Группа/Лучшее/01 Раз.mp3", "mp3", 100.0, 320, 4_000_000, 1, 1),
    ("c2", "Группа", "Два", "Лучшее", "Группа/Лучшее/02 Два.mp3", "mp3", 110.0, 320, 4_400_000, 2, 1),
    ("c3", "Группа", "Три", "Лучшее", "Группа/Лучшее/03 Три.mp3", "mp3", 120.0, 320, 4_800_000, 3, 1),
    (
        "c4",
        "Группа",
        "Четыре",
        "Лучшее",
        "library/Группа/Лучшее/Группа - Четыре.mp3",
        "mp3",
        130.0,
        320,
        5_200_000,
        None,
        1,
    ),
    # Без тегов и без обложки: Navidrome показывает «[Unknown Artist]» и название из имени файла.
    (
        "u1",
        "[Unknown Artist]",
        "01 - Настоящая",
        "Альбом",
        "Неизвестно/Альбом/01 - Настоящая.flac",
        "flac",
        180.0,
        900,
        20_000_000,
        None,
        0,
    ),
]


def flac_bytes(seconds: float) -> bytes:
    """Минимальный FLAC: только STREAMINFO, без аудиокадров — mutagen его читает и пишет в него теги."""
    rate, channels, bits = 44100, 2, 16
    samples = int(rate * seconds)
    info = struct.pack(">HH", 4096, 4096) + b"\x00\x00\x00" * 2
    packed = (rate << 44) | ((channels - 1) << 41) | ((bits - 1) << 36) | samples
    info += packed.to_bytes(8, "big") + b"\x00" * 16
    return b"fLaC" + bytes([0x80]) + len(info).to_bytes(3, "big") + info


def make_db(path, music) -> None:
    conn = sqlite3.connect(path)
    conn.executescript(
        """
        CREATE TABLE user (id TEXT, user_name TEXT, is_admin BOOL);
        CREATE TABLE library (id INTEGER, path TEXT);
        CREATE TABLE media_file (id TEXT, title TEXT, artist TEXT, album TEXT, album_id TEXT, album_artist TEXT,
            duration REAL, size INTEGER, suffix TEXT, bit_rate INTEGER, path TEXT, library_id INTEGER,
            track_number INTEGER, disc_number INTEGER, has_cover_art BOOL, missing BOOL DEFAULT 0);
        CREATE TABLE annotation (user_id TEXT, item_id TEXT, item_type TEXT, rating INTEGER, starred BOOL,
            play_count INTEGER);
        """
    )
    conn.executemany("INSERT INTO user VALUES (?, ?, ?)", [("u-admin", "admin", 1), ("u-vasya", "vasya", 1)])
    conn.execute("INSERT INTO library VALUES (1, '/music')")
    for song_id, artist, title, album, rel, suffix, duration, bit_rate, size, track, cover in SONGS:
        conn.execute(
            "INSERT INTO media_file VALUES (?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, 1, ?, NULL, ?, 0)",
            (song_id, title, artist, album, f"al-{album}", duration, size, suffix, bit_rate, rel, track, cover),
        )
        file = music / rel
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_bytes(flac_bytes(duration) if suffix == "flac" else b"ID3" + bytes(64))
    (music / "Артист/Альбом/cover.jpg").write_bytes(b"img")
    (music / "library/Группа/Лучшее/cover.jpg").write_bytes(b"img")
    conn.execute("INSERT INTO annotation VALUES ('u-vasya', 's1', 'media_file', 0, 1, 3)")
    conn.commit()
    conn.close()


def make(world: FakeWorld, tmp_path, **overrides):
    music = tmp_path / "music"
    music.mkdir()
    db = tmp_path / "navidrome.db"
    make_db(db, music)
    settings = SETTINGS.__class__(
        **{
            **SETTINGS.__dict__,
            "data_dir": str(tmp_path),
            "navidrome_db": str(db),
            "navidrome_music_dir": "/music",
            "music_dir": str(music),
            "tag_providers": ("yandex",),
            "cover_providers": ("yandex", "deezer"),
            **overrides,
        }
    )
    services = build_services(settings, world.client())
    covers: list[str] = []

    async def fetch(url: str):
        covers.append(url)
        return b"\xff\xd8jpeg", "image/jpeg"

    services.maintenance._fetch_cover = fetch
    return services, music, db, covers


def catalog_world() -> FakeWorld:
    return FakeWorld(
        yandex_search={"tracks": {"results": [yandex_track(7, "Настоящая", "Неизвестно", 180_000)]}},
    )


def kinds(services) -> dict[tuple[str, str | None], bool]:
    _, actions = services.store.list_actions(None, None, 500, 0)
    return {(a.kind, a.title or a.album): a.applied for a in actions}


def test_title_key_ignores_editions_but_not_versions():
    assert title_key("Песня (Remastered 2011)") == title_key("песня") == title_key("Песня feat. Кто-то")
    assert title_key("Песня (Live)") != title_key("Песня")


def test_run_cleans_library(tmp_path):
    world = catalog_world()
    services, music, _, covers = make(world, tmp_path)
    run_id = asyncio.run(services.maintenance.run("manual"))

    run = services.store.get_run(run_id)
    assert run.status == "completed", run.error
    actions = kinds(services)
    # Сингл-дубль — в карантине, альбомная FLAC-копия и живая версия на месте.
    assert actions[("duplicate", "Песня (Remastered 2011)")] is True
    assert not (music / "library/Артист/Singles/Артист - Песня.mp3").exists()
    assert (music / "Артист/Альбом/01 Песня.flac").exists()
    assert (music / "library/Артист/Singles/Артист - Песня (Live).mp3").exists()
    # Обрезанный файл — в карантине; подозрительно короткий — только в «требует внимания»; интро в альбоме — нигде.
    assert actions[("short", "Мини")] is False
    assert (music / "library/Кто-то/Singles/Кто-то - Мини.mp3").exists()
    assert actions[("short", "Обрезан")] is True
    assert ("short", "Интро") not in actions
    quarantine = music / ".bodrod-quarantine"
    assert (quarantine / ".ndignore").exists()
    assert (quarantine / str(run_id) / "library/Кто-то/Singles/Кто-то - Обрезан.mp3").exists()
    # Альбом собран в одну папку, старая папка удалена.
    assert actions[("album", "Лучшее")] is True
    assert (music / "Группа/Лучшее/Группа - Четыре.mp3").exists()
    assert not (music / "library/Группа").exists()
    # Теги дописаны по каталогу, обложка добавлена.
    tags = read_tags(music / "Неизвестно/Альбом/01 - Настоящая.flac")
    assert tags["artist"] == "Неизвестно" and tags["title"] == "Настоящая"
    assert actions[("tags", "Настоящая")] is True
    assert (music / "Неизвестно/Альбом/cover.jpg").exists()
    assert covers and "1000x1000" in covers[0]
    assert run.stats["duplicates_removed"] == 1 and run.stats["short_removed"] == 1
    assert run.stats["short_found"] == 2 and run.stats["short_skipped"] == 1
    assert world.scans == 1

    # Повторный запуск ничего нового не находит.
    second = services.store.get_run(asyncio.run(services.maintenance.run("manual")))
    assert second.stats.get("duplicates_removed", 0) == 0 and second.stats.get("tags_fixed", 0) == 0


def test_dry_run_changes_nothing(tmp_path):
    services, music, _, covers = make(catalog_world(), tmp_path, maintenance_dry_run=True)
    run = services.store.get_run(asyncio.run(services.maintenance.run("manual")))
    assert run.dry_run and run.stats["duplicates_removed"] == 1
    # Пробный запуск ничего не запоминает: второй даёт тот же отчёт.
    second = services.store.get_run(asyncio.run(services.maintenance.run("manual")))
    assert second.stats == run.stats
    assert services.store.clear_checked() == 0
    _, actions = services.store.list_actions(run.id, None, 500, 0)
    duplicate = next(a for a in actions if a.kind == "duplicate")
    assert duplicate.planned and not duplicate.applied and "будет убрана" in duplicate.details
    assert (music / "library/Артист/Singles/Артист - Песня.mp3").exists()
    assert (music / "library/Группа/Лучшее/Группа - Четыре.mp3").exists()
    assert not (music / ".bodrod-quarantine").exists()
    assert not covers
    assert all(not applied for applied in kinds(services).values())


def test_lidarr_files_untouched(tmp_path):
    world = catalog_world()
    world.lidarr_artists = [{"id": 1, "path": "/volume2/music/library/Кто-то"}]
    services, music, _, _ = make(world, tmp_path, lidarr_music_dir="/volume2/music")
    run = services.store.get_run(asyncio.run(services.maintenance.run("manual")))
    assert (music / "library/Кто-то/Singles/Кто-то - Обрезан.mp3").exists()
    # Пропуск по правилу «папка Lidarr» — не проблема: ни в отчёте, ни во «Требует внимания», ни в сводке.
    assert ("short", "Обрезан") not in kinds(services) and ("short", "Мини") not in kinds(services)
    _, actions = services.store.list_actions(None, None, 500, 0)
    assert not any("Lidarr" in a.details for a in actions)
    assert run.stats["open_short_version"] == 0
    assert all(item.key != "short_version" for item in services.maintenance.summary().unresolved)


def test_lidarr_folder_gets_cover_from_cover_art_archive(tmp_path):
    world = catalog_world()
    world.lidarr_artists = [{"id": 1, "path": "/volume2/music/library/Кто-то"}]
    world.lidarr_track_files = [
        {"id": 5, "artistId": 1, "albumId": 9, "path": "/volume2/music/library/Кто-то/Singles/Кто-то - Мини.mp3"}
    ]
    world.lidarr_albums = [
        {
            "id": 9,
            "artistId": 1,
            "foreignAlbumId": "rg-1",
            "releases": [
                {"foreignReleaseId": "rel-0", "monitored": False},
                {"foreignReleaseId": "rel-1", "monitored": True},
            ],
        }
    ]
    services, music, db, covers = make(world, tmp_path, lidarr_music_dir="/volume2/music")
    conn = sqlite3.connect(db)
    conn.execute("UPDATE media_file SET has_cover_art = 0 WHERE id IN ('t1', 't2')")
    conn.commit()
    conn.close()
    before = (music / "library/Кто-то/Singles/Кто-то - Мини.mp3").read_bytes()
    run = services.store.get_run(asyncio.run(services.maintenance.run("manual")))
    folder = music / "library/Кто-то/Singles"
    assert (folder / "cover.jpg").read_bytes() == b"\xff\xd8jpeg"
    assert "https://coverartarchive.org/release/rel-1/front-1200" in covers
    assert not any("rel-0" in url or "rg-1" in url for url in covers)  # первый же MBID подошёл
    assert (folder / "Кто-то - Мини.mp3").read_bytes() == before  # файлы и теги не тронуты
    assert kinds(services)[("cover", "Singles")] is True
    assert run.stats["covers_added"] == 2  # и обычная папка «Неизвестно/Альбом»

    # Нет обложки в Cover Art Archive — причина в сводке; повторно в отчёт не пишется, но в сводке остаётся.
    (folder / "cover.jpg").unlink()

    async def missing(url: str):
        raise RuntimeError("404")

    services.maintenance._fetch_cover = missing
    second = services.store.get_run(asyncio.run(services.maintenance.run("manual")))
    assert second.stats["open_lidarr_cover_not_found"] == 1
    third = services.store.get_run(asyncio.run(services.maintenance.run("manual")))
    assert third.stats["open_lidarr_cover_not_found"] == 1
    _, actions = services.store.list_actions(third.id, None, 500, 0)
    assert not any(a.kind == "cover" for a in actions)


def test_summary_counts_and_quarantine(tmp_path):
    services, music, _, _ = make(catalog_world(), tmp_path)
    asyncio.run(services.maintenance.run("manual"))
    with TestClient(create_app(services, run_worker=False)) as client:
        summary = client.get("/api/v1/maintenance/summary", headers=auth()).json()
    fixed = {c["key"]: c["count"] for c in summary["fixed"]}
    assert fixed["duplicates_removed"] == 1 and fixed["short_removed"] == 1 and fixed["tags_fixed"] == 1
    assert {c["key"]: c["count"] for c in summary["unresolved"]} == {"short_version": 1}
    paths = {q["path"]: q for q in summary["quarantine"]}
    assert set(paths) == {"library/Артист/Singles/Артист - Песня.mp3", "library/Кто-то/Singles/Кто-то - Обрезан.mp3"}
    item = paths["library/Кто-то/Singles/Кто-то - Обрезан.mp3"]
    assert item["kind"] == "short" and item["title"] == "Обрезан" and item["expiresAt"] > item["createdAt"]

    # Второй запуск: известное «короткое» в отчёт не пишется, но в сводке остаётся; вернули — ушло из карантина.
    second = services.store.get_run(asyncio.run(services.maintenance.run("manual")))
    assert second.stats["open_short_version"] == 1 and second.stats["attention"] == 1
    asyncio.run(services.maintenance.restore(item["id"]))
    assert len(services.maintenance.summary().quarantine) == 1


def test_api_restore_and_library_check(tmp_path):
    services, music, db, _ = make(catalog_world(), tmp_path)
    with TestClient(create_app(services, run_worker=False)) as client:
        status = client.get("/api/v1/maintenance", headers=auth()).json()
        assert status["running"] is False and status["lastRun"] is None and status["quarantineDays"] == 30

        assert client.post("/api/v1/maintenance/run", headers=auth()).status_code == 202
        for _ in range(100):
            status = client.get("/api/v1/maintenance", headers=auth()).json()
            if not status["running"] and status["lastRun"]:
                break
            time.sleep(0.05)
        assert status["lastRun"]["status"] == "completed"

        page = client.get("/api/v1/maintenance/actions", params={"kind": "duplicate"}, headers=auth()).json()
        assert page["total"] == 1
        action = page["items"][0]
        assert action["restorable"] is True and action["path"] == "library/Артист/Singles/Артист - Песня.mp3"

        # Navidrome пересканировал: сингл пропал, перенесённый трек получил новый id.
        conn = sqlite3.connect(db)
        conn.execute("UPDATE media_file SET missing = 1 WHERE id IN ('s1', 't2', 'c4')")
        conn.execute(
            "INSERT INTO media_file (id, title, artist, album, duration, path, library_id, missing) "
            "VALUES ('c4-new', 'Четыре', 'Группа', 'Лучшее', 130, 'Группа/Лучшее/Группа - Четыре.mp3', 1, 0)"
        )
        conn.commit()
        conn.close()
        check = client.post("/api/v1/library/check", json={"ids": ["s1", "t2", "c4", "a1"]}, headers=auth()).json()
        assert check == {"missing": ["t2"], "replaced": {"s1": "a1", "c4": "c4-new"}}

        restore = f"/api/v1/maintenance/quarantine/{action['quarantineId']}/restore"
        assert client.post(restore, headers=auth()).status_code == 204
        assert (music / "library/Артист/Singles/Артист - Песня.mp3").exists()
        assert client.post(restore, headers=auth()).status_code == 409
        check = client.post("/api/v1/library/check", json={"ids": ["s1"]}, headers=auth()).json()
        assert check == {"missing": ["s1"], "replaced": {}}  # до пересканирования Navidrome


def test_quarantine_purged_after_retention(tmp_path):
    services, music, _, _ = make(catalog_world(), tmp_path, quarantine_days=0)
    first = asyncio.run(services.maintenance.run("manual"))
    stored = music / ".bodrod-quarantine" / str(first) / "library/Кто-то/Singles/Кто-то - Обрезан.mp3"
    assert stored.exists()
    time.sleep(0.01)
    second = services.store.get_run(asyncio.run(services.maintenance.run("manual")))
    assert second.stats["quarantine_purged"] == 2
    assert not stored.exists()


def test_artist_popular_marks_library(tmp_path):
    world = FakeWorld(
        yandex_search={"artists": {"results": [{"id": 5, "name": "Трофим"}]}},
        yandex_artist_tracks=[yandex_track(1, "Город Сочи", "Трофим"), yandex_track(2, "Новая", "Трофим")],
        library=[{"id": "lib-1", "title": "Город Сочи", "artist": "Трофим", "album": "Сборник"}],
    )
    services, _, _, _ = make(world, tmp_path)
    with TestClient(create_app(services, run_worker=False)) as client:
        popular = client.get("/api/v1/artists/popular", params={"name": "трофим"}, headers=auth()).json()
    assert popular["source"] == "yandex" and popular["artist"]["name"] == "Трофим"
    assert [(t["title"], t["librarySongId"]) for t in popular["tracks"]] == [("Город Сочи", "lib-1"), ("Новая", None)]


def test_lidarr_folder_without_mbid_gets_cover_from_providers(tmp_path):
    """Нет MBID (и Cover Art Archive) — `cover.jpg` из общей цепочки источников; файлы и теги не трогаются."""
    track = yandex_track(8, "Мини", "Кто-то", 5_000)
    track["albums"] = [{"id": 11, "title": "Singles"}]
    world = FakeWorld(yandex_search={"tracks": {"results": [track]}})
    world.lidarr_artists = [{"id": 1, "path": "/volume2/music/library/Кто-то"}]
    services, music, db, covers = make(world, tmp_path, lidarr_music_dir="/volume2/music")
    conn = sqlite3.connect(db)
    conn.execute("UPDATE media_file SET has_cover_art = 0 WHERE id IN ('t1', 't2')")
    conn.commit()
    conn.close()
    folder = music / "library/Кто-то/Singles"
    before = (folder / "Кто-то - Мини.mp3").read_bytes()
    run = services.store.get_run(asyncio.run(services.maintenance.run("manual")))
    assert (folder / "cover.jpg").read_bytes() == b"\xff\xd8jpeg"
    assert not any("coverartarchive" in url for url in covers)
    assert (folder / "Кто-то - Мини.mp3").read_bytes() == before
    _, actions = services.store.list_actions(run.id, None, 500, 0)
    assert any(a.kind == "cover" and a.applied and "Lidarr" in a.details and "Яндекс" in a.details for a in actions)
    assert run.stats["open_lidarr_cover_not_found"] == 0


def test_cover_stats_counts_albums_without_art(tmp_path):
    services, music, db, _ = make(catalog_world(), tmp_path)
    conn = sqlite3.connect(db)
    conn.executescript(
        """
        CREATE TABLE album (id TEXT, embed_art_path TEXT, folder_ids TEXT, missing BOOL DEFAULT 0);
        CREATE TABLE folder (id TEXT, image_files TEXT);
        CREATE TABLE artist (id TEXT, small_image_url TEXT, missing BOOL DEFAULT 0);
        INSERT INTO album VALUES ('al-Singles', '', '["f1"]', 0), ('al-Альбом', '', '["f2"]', 0);
        INSERT INTO folder VALUES ('f1', ''), ('f2', '["cover.jpg"]');
        INSERT INTO artist VALUES ('a1', '', 0), ('a2', 'http://x/img.jpg', 0);
        UPDATE media_file SET has_cover_art = 0;
        """
    )
    conn.commit()
    conn.close()
    stats = services.navidrome_db.cover_stats()
    assert stats["albums"] == 2
    assert stats["albumsWithoutCover"] == 1
    assert stats["songsWithoutCover"] >= 1
    assert stats["byFolder"].get("library", 0) >= 1
    assert (stats["artists"], stats["artistsWithoutImage"]) == (2, 1)
