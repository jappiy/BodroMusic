"""Задание 020: отчёт по владельцам плейлистов и плейлисты выгрузки Яндекса для аккаунта пользователя."""

import asyncio
import sqlite3

from bodrod.navidrome_db import NavidromeDb
from bodrod.playlist_audit import build_audit

from .fakes import FakeWorld
from .test_api import auth
from .test_cleanup import make_client


def add_playlists(db_path) -> None:
    conn = sqlite3.connect(db_path)
    conn.executescript(
        """
        CREATE TABLE playlist (id TEXT, name TEXT, comment TEXT, public BOOL, owner_id TEXT, song_count INTEGER,
            path TEXT DEFAULT '', rules TEXT, created_at TEXT);
        """
    )
    conn.executemany(
        "INSERT INTO playlist VALUES (?, ?, '', ?, ?, ?, ?, ?, '2026-10-01')",
        [
            ("p1", "Моё", 0, "u-masha", 3, "", None),
            ("p2", "Дорога", 1, "u-vasya", 5, "", None),
            ("p3", "Любимое", 0, "u-admin", 10, "/music/library/Плейлисты Яндекса/Любимое.m3u8", None),
            ("p4", "Старое", 0, "u-admin", 7, "/music/collections/old.m3u", None),
            ("p5", "Свежее", 1, "u-admin", 20, "/music/smart/new.nsp", '{"all":[{"inTheLast":{"dateAdded":30}}]}'),
            ("p6", "Weekly Exploration", 0, "u-vasya", 50, "", None),
        ],
    )
    conn.commit()
    conn.close()


def test_navidrome_db_reads_owners(tmp_path):
    client, services, _, _ = make_client(FakeWorld(), tmp_path)
    add_playlists(tmp_path / "navidrome.db")
    playlists = {p.id: p for p in NavidromeDb(str(tmp_path / "navidrome.db")).playlists()}
    assert playlists["p1"].owner == "masha" and not playlists["p1"].public and playlists["p1"].path is None
    assert playlists["p5"].smart and playlists["p5"].public
    assert not playlists["p4"].smart and playlists["p4"].path.endswith("old.m3u")


def test_audit_flags_without_changes(tmp_path):
    client, services, _, _ = make_client(FakeWorld(), tmp_path)
    add_playlists(tmp_path / "navidrome.db")
    audit = build_audit(services.navidrome_db)
    reasons = {p.id: p.reasons for p in audit.playlists}
    assert reasons["p1"] == [] and reasons["p2"] == []
    assert reasons["p3"] == ["yandex_export"]
    assert reasons["p4"] == ["imported_file"]
    assert reasons["p5"] == ["smart_file"]
    assert reasons["p6"] == ["explo"]
    assert audit.review == 4
    assert audit.by_owner == {"admin": 3, "masha": 1, "vasya": 2}
    assert audit.admins == ["admin", "vasya"]
    # Только чтение: таблица не изменилась.
    conn = sqlite3.connect(tmp_path / "navidrome.db")
    assert conn.execute("SELECT COUNT(*) FROM playlist").fetchone()[0] == 6


def test_audit_endpoint_admin_only(tmp_path):
    client, _, _, _ = make_client(FakeWorld(), tmp_path)
    add_playlists(tmp_path / "navidrome.db")
    with client:
        body = client.get("/api/v1/playlists/audit", headers=auth()).json()
        assert body["review"] == 4
        assert {p["id"]: p["owner"] for p in body["playlists"]}["p3"] == "admin"
        assert body["byOwner"]["admin"] == 3
        assert client.get("/api/v1/playlists/audit", headers=auth("masha", "secret")).status_code in (401, 403)


def test_export_playlists_map_to_navidrome_songs(tmp_path):
    client, services, music, _ = make_client(FakeWorld(), tmp_path, library_dir=str(tmp_path / "music" / "library"))
    store = services.store
    path = f"{music}/library/Артист/Альбом/02 Песня.flac"  # как видит bodrod (music_dir + путь)
    store.add_library_file(path, None, "yandex", "flac", False, source_id="y2")
    store.set_value("export.playlists", {"Дорога": ["y1", "y2"]})
    store.set_value("export.state", {"state": "finished", "owner": "vasya"})

    async def song_by_path(p: str):
        return await services.library_index.song_by_path(p)

    assert services.export is not None
    result = asyncio.run(services.export.playlists(song_by_path))
    assert result == [{"title": "Дорога", "song_ids": ["s2"], "missing": 1, "owner": "vasya"}]
    assert services.settings.export_write_m3u is False
