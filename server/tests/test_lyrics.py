"""Сбор текстов: выбор источника, файлы рядом с аудио, статусы и повторы, LRCLIB."""

from __future__ import annotations

import asyncio
import sqlite3
from dataclasses import dataclass

import httpx

from bodrod.db import Store
from bodrod.lyrics import DAY_MS, Found, LrcLib, LyricsService, TrackRef, YandexLyrics, is_synced
from bodrod.navidrome_db import NavidromeDb

LRC = "[00:01.00] Первая строка\n[00:05.50] Вторая строка"


def make_db(path, music, songs) -> None:
    conn = sqlite3.connect(path)
    conn.executescript(
        """
        CREATE TABLE library (id INTEGER, path TEXT);
        CREATE TABLE media_file (id TEXT, title TEXT, artist TEXT, album TEXT, album_id TEXT, duration REAL,
            size INTEGER, suffix TEXT, bit_rate INTEGER, path TEXT, library_id INTEGER, lyrics TEXT,
            missing BOOL DEFAULT 0);
        """
    )
    conn.execute("INSERT INTO library VALUES (1, '/music')")
    for song_id, artist, title, rel, lyrics in songs:
        conn.execute(
            "INSERT INTO media_file VALUES (?, ?, ?, 'Альбом', 'al', 200, 1, 'mp3', 320, ?, 1, ?, 0)",
            (song_id, title, artist, rel, lyrics),
        )
        file = music / rel
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_bytes(b"ID3")
    conn.commit()
    conn.close()


class Script:
    """Источник с заранее заданными ответами по названию трека."""

    def __init__(self, name: str, answers: dict[str, Found], ready: bool = True) -> None:
        self.name = name
        self.answers = answers
        self.calls: list[str] = []
        self._ready = ready

    async def ready(self) -> bool:
        return self._ready

    async def find(self, track: TrackRef) -> Found:
        self.calls.append(track.title)
        return self.answers.get(track.title, Found("not_found"))


def service(tmp_path, songs, providers, clock=lambda: 1_000_000):
    music = tmp_path / "music"
    music.mkdir(exist_ok=True)
    make_db(tmp_path / "nd.db", music, songs)
    store = Store(str(tmp_path / "bodrod.db"))
    lyrics = LyricsService(
        store,
        NavidromeDb(str(tmp_path / "nd.db")),
        providers,
        music_dir=str(music),
        navidrome_music_dir="/music",
        skip_dirs=[str(music / ".bodrod-quarantine")],
        clock=clock,
    )
    return lyrics, music, store


SONGS = [
    ("s1", "Кино", "Группа крови", "Кино/Группа крови/01.mp3", None),
    ("s2", "Кино", "Звезда", "Кино/Звезда/01.mp3", None),
    ("s3", "Кино", "Кукушка", "Кино/Кукушка/01.mp3", None),
    ("s4", "Кино", "Пачка", "Кино/Пачка/01.mp3", '[{"lang":"rus","line":[{"start":0,"value":"x"}],"synced":true}]'),
]


def test_synced_preferred_and_files_written(tmp_path) -> None:
    yandex = Script("yandex", {"Группа крови": Found("plain", "Тёплое место", "yandex")})
    lrclib = Script(
        "lrclib",
        {"Группа крови": Found("synced", LRC, "lrclib"), "Звезда": Found("instrumental", source="lrclib")},
    )
    lyrics, music, store = service(tmp_path, SONGS, [yandex, lrclib])
    (music / "Кино/Кукушка/01.lrc").write_text("[00:00.00] своё", encoding="utf-8")

    progress = asyncio.run(lyrics.collect("full"))

    assert (music / "Кино/Группа крови/01.lrc").read_text(encoding="utf-8").startswith("[00:01.00]")
    assert not (music / "Кино/Группа крови/01.txt").exists()
    assert (music / "Кино/Кукушка/01.lrc").read_text(encoding="utf-8") == "[00:00.00] своё"
    # Уже есть текст (файл рядом или LRC в тегах) — источники не спрашиваем.
    assert "Кукушка" not in yandex.calls and "Пачка" not in yandex.calls
    assert (progress.synced, progress.instrumental, progress.total) == (1, 1, 2)
    statuses = {r["song_id"]: r["status"] for r in store.query("SELECT song_id, status FROM lyrics_status")}
    assert statuses == {"s1": "synced", "s2": "instrumental", "s3": "existing", "s4": "existing"}


def test_plain_only_when_no_synced_anywhere(tmp_path) -> None:
    yandex = Script("yandex", {"Звезда": Found("plain", "Строка", "yandex")})
    lyrics, music, _ = service(tmp_path, SONGS[1:2], [yandex, Script("lrclib", {})])
    progress = asyncio.run(lyrics.collect("full"))
    assert progress.plain == 1
    assert (music / "Кино/Звезда/01.txt").read_text(encoding="utf-8") == "Строка\n"


def test_not_found_retries_after_7_30_90_days(tmp_path) -> None:
    now = [1_000_000]
    source = Script("lrclib", {})
    lyrics, _, store = service(tmp_path, SONGS[:1], [source], clock=lambda: now[0])

    asyncio.run(lyrics.collect("nightly"))
    row = store.query_one("SELECT * FROM lyrics_status")
    assert row["status"] == "not_found" and row["next_at"] == now[0] + 7 * DAY_MS

    now[0] += DAY_MS
    asyncio.run(lyrics.collect("nightly"))
    assert len(source.calls) == 1  # повтор ещё не подошёл

    for days in (7, 30, 90, 90):
        now[0] = store.query_one("SELECT next_at FROM lyrics_status")["next_at"]
        asyncio.run(lyrics.collect("nightly"))
        row = store.query_one("SELECT * FROM lyrics_status")
        assert row["next_at"] - now[0] == {7: 30, 30: 90, 90: 90}[days] * DAY_MS


def test_full_pass_retries_not_found_with_yandex(tmp_path) -> None:
    lrclib = Script("lrclib", {})
    lyrics, music, _ = service(tmp_path, SONGS[:1], [Script("yandex", {}, ready=False), lrclib])
    asyncio.run(lyrics.collect("nightly"))
    lyrics.providers = [Script("yandex", {"Группа крови": Found("synced", LRC, "yandex")}), lrclib]
    progress = asyncio.run(lyrics.collect("full"))
    assert progress.synced == 1
    assert (music / "Кино/Группа крови/01.lrc").exists()


def test_is_synced() -> None:
    assert is_synced(LRC)
    assert not is_synced("Просто текст\nбез меток")


@dataclass
class YT:
    id: str
    title: str
    artist: str
    duration_ms: int | None
    lyrics_synced: bool | None = True
    lyrics_text: bool | None = True


class FakeYandex:
    has_token = True

    def __init__(self) -> None:
        self.lyrics_calls: list[tuple[str, bool]] = []

    async def has_plus(self) -> bool:
        return True

    async def search_tracks(self, text: str) -> list[YT]:
        return [
            YT("1", "Группа крови", "Кино", 250_000),  # длительность не та
            YT("2", "Группа крови", "Кино", 201_000),
        ]

    async def tracks_info(self, ids: list[str]) -> list[YT]:
        return [YT(i, "Звезда", "Кино", 200_000, lyrics_synced=False) for i in ids]

    async def lyrics(self, track_id: str, synced: bool = False) -> str | None:
        self.lyrics_calls.append((track_id, synced))
        return LRC if synced else "Обычный текст"


def test_yandex_matches_by_duration_and_uses_known_id() -> None:
    api = FakeYandex()
    yandex = YandexLyrics([api])
    assert asyncio.run(yandex.ready())

    found = asyncio.run(yandex.find(TrackRef("Кино", "Группа крови", None, 200.0)))
    assert found.kind == "synced" and api.lyrics_calls == [("2", True)]

    found = asyncio.run(yandex.find(TrackRef("Кино", "Звезда", None, 200.0, yandex_id="77")))
    assert found.kind == "plain" and api.lyrics_calls[-1] == ("77", False)


def test_lrclib_get_then_search() -> None:
    seen: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request.url.path)
        assert request.headers["User-Agent"].startswith("BodroMusic/")
        if request.url.path == "/api/get":
            return httpx.Response(404, json={"message": "not found"})
        return httpx.Response(
            200,
            json=[
                {"duration": 260, "syncedLyrics": LRC},
                {"duration": 201, "syncedLyrics": None, "plainLyrics": "Текст"},
                {"duration": 199, "syncedLyrics": LRC},
            ],
        )

    async def run() -> Found:
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
            return await LrcLib(http, "BodroMusic/test", interval_s=0).find(TrackRef("Кино", "Звезда", None, 200.0))

    found = asyncio.run(run())
    assert seen == ["/api/get", "/api/search"]
    assert found.kind == "synced" and found.source == "lrclib"
