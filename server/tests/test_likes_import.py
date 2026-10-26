import asyncio
import sqlite3

from bodrod.clients.navidrome import Credentials
from bodrod.db import Store
from bodrod.likes_import import LibraryMatcher, LikesImport
from bodrod.models import RequestStatus
from bodrod.navidrome_db import NavidromeDb, NdSong
from bodrod.sources.yandex import YTrack

# id, исполнитель, название, длительность (с), путь
SONGS = [
    ("s1", "Трофим", "Город Сочи", 200.0, "/music/library/Трофим/01 Город Сочи.mp3"),
    ("s2", "Земфира", "Хочешь?", 210.0, "/music/library/Земфира/Хочешь.flac"),
    ("s3", "Ёлка", "Прованс", 180.0, "/music/library/Ёлка/Прованс.flac"),
    ("s4", "Noize MC", "Вселенная бесконечна", 240.0, "/music/library/Noize/Вселенная.flac"),
    ("s5", "Muse", "Uprising", 305.0, "/music/library/Muse/Uprising.flac"),
    ("s6", "Резерв", "Песня", 100.0, "/music/_reserve/Резерв/Песня.flac"),
]


def make_db(db_path, starred=(), disliked=()) -> NavidromeDb:
    conn = sqlite3.connect(db_path)
    conn.executescript(
        """
        CREATE TABLE user (id TEXT, user_name TEXT, is_admin BOOL);
        CREATE TABLE media_file (id TEXT, title TEXT, artist TEXT, album TEXT, album_id TEXT, duration REAL,
            size INTEGER, suffix TEXT, bit_rate INTEGER, path TEXT, missing BOOL DEFAULT 0);
        CREATE TABLE annotation (user_id TEXT, item_id TEXT, item_type TEXT, rating INTEGER, starred BOOL,
            play_count INTEGER);
        """
    )
    conn.execute("INSERT INTO user VALUES ('u1', 'vasya', 0)")
    for song_id, artist, title, duration, path in SONGS:
        conn.execute(
            "INSERT INTO media_file VALUES (?, ?, ?, 'A', 'al', ?, 1, 'flac', 900, ?, 0)",
            (song_id, title, artist, duration, path),
        )
    for song_id in starred:
        conn.execute("INSERT INTO annotation VALUES ('u1', ?, 'media_file', 0, 1, 0)", (song_id,))
    for song_id in disliked:
        conn.execute("INSERT INTO annotation VALUES ('u1', ?, 'media_file', 1, 0, 0)", (song_id,))
    conn.commit()
    conn.close()
    return NavidromeDb(str(db_path))


def yt(track_id: str, artist: str, title: str, seconds: float | None = None) -> YTrack:
    return YTrack(
        id=track_id, title=title, artists=artist.split(", "), duration_ms=int(seconds * 1000) if seconds else None
    )


LIKES = [
    yt("y1", "Трофим", "Город Сочи (Remastered 2011)", 201.5),  # ремастер, длительность в допуске
    yt("y2", "земфира", "Хочешь", 210),  # регистр, знак вопроса
    yt("y3", "Елка", "Прованс", 180),  # ё/е
    yt("y4", "Noize MC, Монеточка", "Вселенная бесконечна (feat. Монеточка)", 240),  # feat. — уже лайкнут
    yt("y5", "Muse", "Uprising", 330),  # длительность не та — другая версия
    yt("y6", "Нет", "В библиотеке", 100),
    yt("y7", "Muse", "Starlight", 240),  # нашёлся по id Яндекса (файл скачан bodrod)
    yt("y8", "Резерв", "Песня", 100),  # лежит только в резерве — не библиотека
]


class Fixture:
    def __init__(self, tmp_path, starred=("s4",), disliked=("s2",)):
        self.store = Store(str(tmp_path / "bodrod.sqlite3"))
        self.db = make_db(tmp_path / "navidrome.db", starred, disliked)
        self.stars: list[list[str]] = []
        self.likes = list(LIKES)
        # «Starlight» bodrod положил сам: файл помнит id трека Яндекса, хоть тегов и не узнать.
        self.store.add_library_file("/data/music/library/Muse/Uprising.flac", None, "yandex", "flac", False, "y7")
        self.service = LikesImport(
            self.store,
            self.db,
            self._liked,
            self._star,
            path_map=lambda p: p.replace("/data/music", "/music"),
            skip_prefixes=["/music/_reserve"],
        )
        self.creds = Credentials("vasya", "t", "s")

    async def _liked(self, user: str) -> list[YTrack]:
        assert user == "vasya"
        return self.likes

    async def _star(self, creds: Credentials, ids: list[str]) -> None:
        assert creds.username == "vasya"
        self.stars.append(ids)
        conn = sqlite3.connect(self.db._path)
        conn.executemany("INSERT INTO annotation VALUES ('u1', ?, 'media_file', 0, 1, 0)", [(i,) for i in ids])
        conn.commit()
        conn.close()

    async def run(self) -> dict:
        await self.service.start(self.creds)
        await self.service._tasks["vasya"]
        return self.service.status("vasya")


def test_matcher_rules():
    songs = [NdSong(s[0], s[2], s[1], "A", "al", s[3], 1, "flac", 900, s[4]) for s in SONGS]
    matcher = LibraryMatcher(songs, ["/music/_reserve"])
    assert matcher.find("ТРОФИМ", "город сочи - remastered", 199_000) == "s1"
    assert matcher.find("Трофим", "Город Сочи", 196_000) is None  # 4 с — вне допуска
    assert matcher.find("Трофим", "Город Сочи", None) == "s1"
    assert matcher.find("Резерв", "Песня", 100_000) is None


def test_import_report_and_rules(tmp_path):
    f = Fixture(tmp_path)
    status = asyncio.run(f.run())
    assert status["state"] == "finished"
    assert status["report"] == {"total": 8, "already": 1, "starred": 3, "conflicts": 1, "notFound": 3}
    assert sorted(i for batch in f.stars for i in batch) == ["s1", "s3", "s5"]
    assert [c["songId"] for c in status["conflicts"]] == ["s2"]
    assert [i["id"] for i in status["notFound"]] == ["y5", "y6", "y8"]
    assert status["lastRunAt"]


def test_second_run_is_idempotent(tmp_path):
    f = Fixture(tmp_path)
    asyncio.run(f.run())
    f.stars.clear()
    f.likes.append(yt("y9", "Ёлка", "Прованс", 180))  # новый лайк на уже лайкнутый трек
    status = asyncio.run(f.run())
    assert f.stars == []
    assert status["report"]["starred"] == 0
    assert status["report"]["already"] == 5


def test_download_missing_then_star_on_settle(tmp_path):
    f = Fixture(tmp_path)

    async def scenario():
        await f.run()
        await f.service.download_missing("vasya", ["y6"])
        status = f.service.status("vasya")
        row = next(i for i in status["notFound"] if i["id"] == "y6")
        assert row["download"] == "queued"
        request = f.store.get(row["requestId"])
        # Трек скачан, Navidrome его просканировал.
        conn = sqlite3.connect(f.db._path)
        conn.execute(
            "INSERT INTO media_file VALUES ('s9', 'В библиотеке', 'Нет', 'A', 'al', 100, 1, 'flac', 900, "
            "'/music/library/Нет/x.flac', 0)"
        )
        conn.commit()
        conn.close()
        request.status = RequestStatus.COMPLETED
        request.song_id = "s9"
        f.store.save(request)
        f.stars.clear()
        assert await f.service.settle("vasya") == 1
        return f.service.status("vasya")

    status = asyncio.run(scenario())
    assert f.stars == [["s9"]]
    assert [i["id"] for i in status["notFound"]] == ["y5", "y8"]
    assert status["report"]["starred"] == 4 and status["report"]["notFound"] == 2


def test_dislike_conflict_after_download_is_not_starred(tmp_path):
    f = Fixture(tmp_path, disliked=("s2", "s9"))

    async def scenario():
        await f.run()
        await f.service.download_missing("vasya")
        row = next(i for i in f.service.status("vasya")["notFound"] if i["id"] == "y6")
        request = f.store.get(row["requestId"])
        conn = sqlite3.connect(f.db._path)
        conn.execute(
            "INSERT INTO media_file VALUES ('s9', 'В библиотеке', 'Нет', 'A', 'al', 100, 1, 'flac', 900, "
            "'/music/library/Нет/x.flac', 0)"
        )
        conn.commit()
        conn.close()
        request.status = RequestStatus.COMPLETED
        request.song_id = "s9"
        f.store.save(request)
        f.stars.clear()
        await f.service.settle("vasya")
        return f.service.status("vasya")

    status = asyncio.run(scenario())
    assert f.stars == []
    assert status["report"]["conflicts"] == 2


def test_no_token_fails_with_message(tmp_path):
    from bodrod.likes_import import LikesImportError

    f = Fixture(tmp_path)

    async def no_token(user: str):
        raise LikesImportError("Не задан токен Яндекса")

    f.service._liked_tracks = no_token
    status = asyncio.run(f.run())
    assert status["state"] == "failed"
    assert status["message"] == "Не задан токен Яндекса"
