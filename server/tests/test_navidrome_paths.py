"""БД Navidrome 0.55+: путь относительно библиотеки, поиск песни по пути файла, ISRC из тегов."""

import asyncio
import json
import sqlite3
from pathlib import Path

from bodrod.navidrome_db import NavidromeDb
from bodrod.reco.library import LibraryIndex


def nd_db(path: Path) -> NavidromeDb:
    conn = sqlite3.connect(path)
    conn.executescript(
        """
        CREATE TABLE library (id INTEGER, path TEXT);
        CREATE TABLE media_file (id TEXT, title TEXT, artist TEXT, album TEXT, album_id TEXT, duration REAL,
            size INTEGER, suffix TEXT, bit_rate INTEGER, path TEXT, missing INTEGER DEFAULT 0, library_id INTEGER,
            tags TEXT);
        INSERT INTO library VALUES (1, '/music');
        """
    )
    rows = [
        ("s1", "Nicole Kidman", "Adela", "library/Adela/PRIMA/01.flac", json.dumps({"isrc": ["czab12345678"]})),
        ("s2", "Other", "Someone, ADÉLA", "library/X/02.flac", json.dumps({"genre": [{"value": "Pop"}]})),
    ]
    for song_id, title, artist, rel, tags in rows:
        conn.execute(
            "INSERT INTO media_file VALUES (?, ?, ?, 'PRIMA', 'al', 200, 1, 'flac', 900, ?, 0, 1, ?)",
            (song_id, title, artist, rel, tags),
        )
    conn.commit()
    conn.close()
    return NavidromeDb(str(path))


def test_song_by_path_and_isrc(tmp_path: Path) -> None:
    db = nd_db(tmp_path / "navidrome.db")
    assert db.song_ids_by_paths(["/music/library/Adela/PRIMA/01.flac", "/music/nope.flac"]) == {
        "/music/library/Adela/PRIMA/01.flac": "s1"
    }
    index = LibraryIndex(db, None, None, path_map=lambda p: p.replace("/data/music", "/music"))  # type: ignore[arg-type]
    assert asyncio.run(index.song_by_path("/data/music/library/Adela/PRIMA/01.flac")) == "s1"
    snap = asyncio.run(index.snapshot())
    assert snap.find("ADÉLA", "Nicole Kidman") == "s1"
    assert snap.find("ADÉLA", "Other") == "s2"  # исполнитель чарта — второй в списке исполнителей трека
    assert snap.find("Неизвестно", "Неизвестно", "CZAB12345678") == "s1"
    assert snap.ids == {"s1", "s2"}
