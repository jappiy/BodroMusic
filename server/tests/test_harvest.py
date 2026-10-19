"""Архив Яндекса для рекомендаций: сборщики, продолжение после обрыва, ошибки доступа, предзакачка в резерв."""

import asyncio
import dataclasses
import json
from pathlib import Path

from bodrod.clients.navidrome import NavidromeClient
from bodrod.db import Store
from bodrod.reco.harvest import WAVE_MODES, YandexHarvester
from bodrod.reco.keys import track_key
from bodrod.reco.library import LibraryIndex
from bodrod.reco.store import ALL_USERS, RecoStore
from bodrod.reco.yandex_account import AccessDenied, Playlist, WaveConfig
from bodrod.sources.placer import Placer
from bodrod.sources.yandex import YDownload, YTrack

from .fakes import NAVIDROME, FakeWorld
from .test_maintenance import flac_bytes
from .test_worker import SETTINGS


def yt(track_id: str, title: str, artist: str = "Артист", artist_id: str = "ar1", seconds: int = 200) -> YTrack:
    return YTrack(
        id=track_id, title=title, artists=[artist], artist_ids=[artist_id], duration_ms=seconds * 1000, album="Альбом"
    )


class FakeAccount:
    def __init__(self) -> None:
        self.likes = [yt("1", "Первая"), yt("2", "Вторая", "Другой", "ar2")]
        self.dislikes = [yt("9", "Плохая", "Скучный", "ar9")]
        self.similar = {"1": [yt("11", "Похожая 1", "Новый", "ar3"), yt("12", "Похожая 2", "Новый", "ar3")]}
        self.wave_tracks = [yt("21", "Волна 1", "Волна", "ar4"), yt("11", "Похожая 1", "Новый", "ar3")]
        self.config = WaveConfig("default", "all", "any")
        self.configs_set: list[WaveConfig] = []
        self.queues: list[str | None] = []
        self.plus = True
        self.denied: set[str] = set()
        self.downloads: list[str] = []
        self.calls: list[str] = []

    def _check(self, name: str) -> None:
        self.calls.append(name)
        if name in self.denied:
            raise AccessDenied("403 Forbidden")

    async def has_plus(self) -> bool:
        return self.plus

    async def liked_tracks(self):
        self._check("likes")
        return {"n": len(self.likes)}, [(t, 1_700_000_000_000) for t in self.likes]

    async def disliked_tracks(self):
        self._check("likes")
        return {}, list(self.dislikes)

    async def liked_artists(self):
        self._check("likes")
        return [], [("ar1", "Артист", None)]

    async def liked_albums(self):
        self._check("likes")
        return [], [("al1", "Альбом", "Артист", 2001, None)]

    async def similar_tracks(self, track_id: str):
        self._check("similar")
        return {"track": track_id}, list(self.similar.get(track_id, []))

    async def similar_artists(self, artist_id: str):
        self._check("artists")
        return {}, [("ar5", "Похожий артист")]

    async def wave_config(self):
        self._check("wave")
        return self.config

    async def set_wave_config(self, config: WaveConfig) -> None:
        self.configs_set.append(config)
        self.config = config

    async def wave_batch(self, queue: str | None):
        self.queues.append(queue)
        return {"queue": queue}, list(self.wave_tracks)

    async def personal_playlists(self):
        self._check("playlists")
        return [], [Playlist("Плейлист дня", "1", [yt("31", "Дня", "Плейлист", "ar6")])]

    async def history(self):
        self._check("history")
        return {}, [yt("1", "Первая")]

    async def chart(self):
        self._check("common")
        return {}, [yt("41", "Чарт", "Звезда", "ar7")]

    async def new_releases(self):
        return {}, [("al9", "Новинка", "Звезда", 2026)]

    async def download(self, track_id: str, dest_stem: Path) -> YDownload:
        self.downloads.append(track_id)
        path = dest_stem.with_suffix(".flac")
        path.write_bytes(flac_bytes(200))
        return YDownload(path, "flac", None)

    async def lyrics(self, track_id: str) -> str | None:
        return None


async def _no_cover(url: str):
    return None


def make(tmp_path: Path, accounts: dict | None = None, library: list | None = None):
    world = FakeWorld(library=library or [])
    http = world.client()
    settings = dataclasses.replace(
        SETTINGS,
        data_dir=str(tmp_path / "data"),
        music_dir=str(tmp_path / "music"),
        library_dir=str(tmp_path / "music" / "lib"),
        harvest_wave_batches=3,
        reserve_batch=5,
    )
    Path(settings.data_dir).mkdir(parents=True)
    store = Store(str(tmp_path / "bodrod.sqlite3"))
    reco = RecoStore(store)
    navidrome = NavidromeClient(http, NAVIDROME, "admin", "adminpass")
    index = LibraryIndex(None, navidrome, None)
    placer = Placer(settings, store, _no_cover, None)
    accounts = accounts if accounts is not None else {"vasya": FakeAccount()}
    harvester = YandexHarvester(store, reco, settings, accounts, index, placer)
    return harvester, reco, store, settings, accounts


def test_track_key_ignores_alphabet_feat_and_remaster() -> None:
    assert track_key("Трофим feat. Кто-то", "Город Сочи (Remastered)") == track_key("Trofim", "Gorod Sochi")
    assert track_key("Queen", "Bohemian Rhapsody - Remastered 2011") == track_key("Queen", "Bohemian Rhapsody")
    assert track_key("Queen", "Bohemian Rhapsody") != track_key("Queen", "Another One")


def test_full_pass_collects_everything(tmp_path: Path) -> None:
    harvester, reco, store, settings, accounts = make(tmp_path)
    asyncio.run(harvester.tick("vasya"))

    assert reco.liked_ids("vasya") == ["1", "2"] or set(reco.liked_ids("vasya")) == {"1", "2"}
    # Дизлайк сразу в чёрном списке пользователя.
    assert track_key("Скучный", "Плохая") in reco.user_blocked_keys("vasya")
    edges = reco.edge_counts("vasya")
    assert edges["similar"] == 2
    assert edges["wave"] == 2  # одни и те же треки в трёх пачках — рёбра те же, растёт счётчик
    assert edges["artist_similar"] >= 1
    assert edges["playlist"] == 1
    assert reco.edge_counts(ALL_USERS) == {"chart": 1, "new_release": 1}
    # Волна: очередь — последний трек предыдущей пачки, настройки аккаунта восстановлены.
    account = accounts["vasya"]
    assert account.queues == [None, "11", "11"]
    assert account.configs_set[0] == WAVE_MODES[0]
    assert account.config == WaveConfig("default", "all", "any")
    # Сырые ответы — в архиве по схеме пользователь/дата/тип.
    files = list((Path(settings.archive_path) / "vasya").glob("*/*.json"))
    assert {f.stem for f in files} >= {"likes-tracks", "similar", "personal-playlists"}
    assert json.loads((files[0]).read_text(encoding="utf-8")) is not None


def test_similar_resumes_and_does_not_repeat(tmp_path: Path) -> None:
    harvester, reco, store, settings, accounts = make(tmp_path)
    asyncio.run(harvester.tick("vasya"))
    account = accounts["vasya"]
    first = account.calls.count("similar")
    asyncio.run(harvester.tick("vasya"))
    assert account.calls.count("similar") == first  # все лайки уже обработаны
    account.likes.append(yt("3", "Третья"))
    store.set_value("harvest.vasya", store.get_value("harvest.vasya") | {"last": {}})
    asyncio.run(harvester.tick("vasya"))
    assert account.calls.count("similar") == first + 1  # только новый лайк


def test_access_error_disables_only_that_collector(tmp_path: Path) -> None:
    harvester, reco, store, settings, accounts = make(tmp_path)
    accounts["vasya"].denied = {"wave"}
    asyncio.run(harvester.tick("vasya"))
    status = harvester.status()[0]
    by_name = {c["name"]: c for c in status["collectors"]}
    assert by_name["wave"]["disabled"].startswith("Нет доступа")
    assert by_name["likes"]["disabled"] is None and by_name["likes"]["lastAt"]
    assert reco.edge_counts("vasya").get("similar") == 2


def test_reserve_prefetch_skips_library_and_blocked(tmp_path: Path) -> None:
    harvester, reco, store, settings, accounts = make(tmp_path)
    asyncio.run(harvester.tick("vasya"))
    account = accounts["vasya"]
    # Самый частый кандидат — «Похожая 1» (similar + wave); лайкнутые и дизлайкнутые не качаются.
    assert account.downloads[0] == "11"
    assert "1" not in account.downloads and "9" not in account.downloads
    assert reco.reserve_count("vasya") == len(account.downloads)
    reserve = Path(settings.reserve_path)
    assert (reserve / ".ndignore").read_text(encoding="utf-8").strip() == "*"
    assert list(reserve.rglob("*.flac"))
    # Повторный проход не качает то же самое.
    before = list(account.downloads)
    asyncio.run(harvester.tick("vasya"))
    assert len(account.downloads) == len(set(account.downloads))
    assert account.downloads[: len(before)] == before


def test_no_plus_no_prefetch(tmp_path: Path) -> None:
    harvester, reco, store, settings, accounts = make(tmp_path)
    accounts["vasya"].plus = False
    asyncio.run(harvester.tick("vasya"))
    assert accounts["vasya"].downloads == []
    assert reco.edge_counts("vasya")["similar"] == 2  # остальное собирается и без Плюса


def test_common_only_from_first_account(tmp_path: Path) -> None:
    first, second = FakeAccount(), FakeAccount()
    harvester, reco, *_ = make(tmp_path, {"vasya": first, "olga": second})
    asyncio.run(harvester.tick("vasya"))
    asyncio.run(harvester.tick("olga"))
    assert "common" in first.calls and "common" not in second.calls
    # Данные пользователей раздельные.
    assert reco.user_blocked_keys("olga") == reco.user_blocked_keys("vasya")
    assert reco.edge_counts("olga")["similar"] == 2
