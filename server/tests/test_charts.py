"""Чарты: снимок с движением, скрытие недоступного, состояние треков, загрузка топа в пул «charts», удаление."""

import asyncio
import dataclasses
import time
from pathlib import Path

import httpx

from bodrod.clients.navidrome import NavidromeClient
from bodrod.db import Store
from bodrod.reco.charts import ChartSource, ChartsService, ChartTrack, build_sources
from bodrod.reco.keys import track_key
from bodrod.reco.library import LibrarySnapshot
from bodrod.reco.pool import CHARTS_OWNER, PoolService
from bodrod.reco.store import DAY_MS, RecoStore

from .fakes import NAVIDROME, FakeWorld
from .test_engine import FixedLibrary
from .test_worker import SETTINGS


class Clock:
    def __init__(self) -> None:
        self.now = int(time.time() * 1000)  # владельцы пишутся с настоящим временем

    def __call__(self) -> int:
        return self.now


def make(tmp_path: Path, tracks: dict[str, list[ChartTrack]], library: LibrarySnapshot | None = None):
    settings = dataclasses.replace(
        SETTINGS, data_dir=str(tmp_path), music_dir=str(tmp_path / "music"), charts_download_top=2
    )
    store = Store(str(tmp_path / "bodrod.sqlite3"))
    reco = RecoStore(store)
    navidrome = NavidromeClient(FakeWorld().client(), NAVIDROME, "admin", "adminpass")
    index = FixedLibrary(library or LibrarySnapshot())
    clock = Clock()
    pool = PoolService(store, reco, settings, navidrome, None, index, clock)

    def fetcher(chart_id: str):
        async def fetch() -> list[ChartTrack]:
            value = tracks[chart_id]
            if isinstance(value, Exception):
                raise value
            return list(value)

        return fetch

    sources = [ChartSource(cid, f"Чарт {cid}", "test", fetcher(cid)) for cid in tracks]
    charts = ChartsService(store, settings, sources, index, pool, clock)
    return charts, pool, reco, store, clock


def t(artist: str, title: str) -> ChartTrack:
    return ChartTrack(artist, title, cover_url=f"https://img/{artist}")


def test_snapshot_movement_and_hidden_failed_chart(tmp_path: Path) -> None:
    data = {"a": [t("A", "1"), t("B", "2"), t("C", "3")], "broken": RuntimeError("нет подписки")}
    charts, pool, reco, store, clock = make(tmp_path, data)
    assert asyncio.run(charts.update()) == 1
    assert [c["id"] for c in charts.list()] == ["a"]  # сломанный чарт скрыт
    assert charts.list()[0]["covers"][:2] == ["https://img/A", "https://img/B"]
    # Через сутки: B поднялся, A опустился, D — новинка.
    data["a"] = [t("B", "2"), t("A", "1"), t("D", "4")]
    clock.now += DAY_MS
    asyncio.run(charts.update())
    entries = asyncio.run(charts.entries("a", "vasya"))
    assert [(e["title"], e["movement"]) for e in entries] == [("2", "up"), ("1", "down"), ("4", "new")]
    # Не прошли сутки — не обновляется.
    assert asyncio.run(charts.update()) == 0


def test_states_and_top_goes_to_charts_pool(tmp_path: Path) -> None:
    library = LibrarySnapshot(library={track_key("A", "1"): "s1"})
    charts, pool, reco, store, clock = make(tmp_path, {"a": [t("A", "1"), t("B", "2"), t("C", "3")]}, library)
    asyncio.run(charts.update())
    # Топ-2: A уже в библиотеке, B — в очередь пула «charts»; C (3-е место) не качается.
    assert [r.artist for r in store.list_for_user("pool:charts")] == ["B"]
    assert pool.counts(CHARTS_OWNER) == {"queued": 1}
    states = {e["title"]: (e["state"], e["song_id"]) for e in asyncio.run(charts.entries("a", "vasya"))}
    assert states == {"1": ("library", "s1"), "2": ("downloading", None), "3": ("unavailable", None)}


def test_chart_track_removed_after_dropping_out_for_30_days(tmp_path: Path) -> None:
    data = {"a": [t("B", "2")]}
    charts, pool, reco, store, clock = make(tmp_path, data)
    path = Path(tmp_path / "music" / "_discovery" / "B - 2.flac")
    path.parent.mkdir(parents=True)
    path.write_bytes(b"x")
    track_id = reco.add_pool_track(
        artist="B", title="2", album=None, duration_ms=None, cover_url=None, yandex_id=None, location="pool",
        path=str(path),
    )  # fmt: skip
    reco.set_owner(track_id, CHARTS_OWNER, "in_pool", "chart:a")
    asyncio.run(charts.update())
    data["a"] = [t("Z", "9")]
    clock.now += DAY_MS
    asyncio.run(charts.update())
    asyncio.run(charts.upkeep())
    assert path.exists()  # выпал, но 30 дней ещё не прошло
    clock.now += 31 * DAY_MS
    asyncio.run(pool.upkeep())  # обычный срок пула (21 день) на чарты не действует
    assert path.exists()
    asyncio.run(charts.upkeep())
    assert not path.exists()
    assert reco.pool_track(track_id)["location"] == "gone"


def test_deezer_and_apple_parsers() -> None:
    def handle(request: httpx.Request) -> httpx.Response:
        if request.url.host == "api.deezer.com":
            return httpx.Response(200, json={"data": [{"title": "Song", "duration": 200, "artist": {"name": "Art"},
                                                       "album": {"title": "Alb", "cover_xl": "x"}}]})  # fmt: skip
        if request.url.host == "rss.applemarketingtools.com":
            result = {"artistName": "Ap", "name": "Ple", "artworkUrl100": "https://a/100x100bb.jpg"}
            return httpx.Response(200, json={"feed": {"results": [result]}})
        return httpx.Response(200, json={"payload": {"recordings": [{"artist_name": "L", "track_name": "B"}]}})

    http = httpx.AsyncClient(transport=httpx.MockTransport(handle))
    sources = {s.id: s for s in build_sources(http, None, None)}
    assert set(sources) == {"deezer_world", "deezer_russia", "apple_ru", "listenbrainz"}
    deezer = asyncio.run(sources["deezer_russia"].fetch())[0]
    assert (deezer.artist, deezer.title, deezer.duration_ms) == ("Art", "Song", 200_000)
    apple = asyncio.run(sources["apple_ru"].fetch())[0]
    assert apple.cover_url == "https://a/600x600bb.jpg"
    assert asyncio.run(sources["listenbrainz"].fetch())[0].title == "B"
