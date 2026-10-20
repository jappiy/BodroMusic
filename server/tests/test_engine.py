"""Движок кандидатов: скоринг, фильтры, лимит на артиста, доля новых артистов, самонастройка весов, постановка в пул."""

import asyncio
import dataclasses
from pathlib import Path

import httpx

from bodrod.clients.navidrome import NavidromeClient
from bodrod.db import Store
from bodrod.metadata.lastfm import LastFmClient
from bodrod.models import RequestStatus
from bodrod.reco.engine import (
    Candidate,
    CandidateEngine,
    LastFmAdapter,
    ListenBrainzAdapter,
    Seed,
    YandexArchiveAdapter,
    half_life_weight,
)
from bodrod.reco.keys import artist_key, track_key
from bodrod.reco.library import LibraryIndex, LibrarySnapshot
from bodrod.reco.pool import PoolService
from bodrod.reco.store import RecoStore
from bodrod.sources.yandex import YTrack

from .fakes import NAVIDROME, FakeWorld
from .test_maintenance import flac_bytes
from .test_worker import SETTINGS


class FakeAdapter:
    def __init__(self, name: str, items: list[Candidate], ok: bool = True) -> None:
        self.name = name
        self.items = items
        self.ok = ok
        self.calls = 0

    def available(self, username: str):
        return (True, None) if self.ok else (False, "нет")

    async def candidates(self, username: str, seeds):
        self.calls += 1
        return list(self.items)


class FixedLibrary(LibraryIndex):
    def __init__(self, snapshot: LibrarySnapshot) -> None:
        self._fixed = snapshot

    async def snapshot(self) -> LibrarySnapshot:
        return self._fixed

    def invalidate(self) -> None:
        pass

    def age(self) -> float:
        return 0.0


def c(artist: str, title: str, source: str = "lastfm", sim: float = 0.8, seed_title: str = "") -> Candidate:
    return Candidate(artist, title, source, sim, 1.0, seed_title=seed_title)


def make(tmp_path: Path, adapters, library: LibrarySnapshot | None = None):
    store = Store(str(tmp_path / "bodrod.sqlite3"))
    reco = RecoStore(store)
    settings = dataclasses.replace(SETTINGS, engine_new_artist_share=0.0)
    index = FixedLibrary(library or LibrarySnapshot())
    engine = CandidateEngine(store, reco, settings, index, None, adapters)
    return engine, reco, store


def test_half_life() -> None:
    assert half_life_weight(0, 90) == 1.0
    assert abs(half_life_weight(90 * 86_400_000, 90) - 0.5) < 1e-9


def test_scoring_bonus_filters_and_artist_cap(tmp_path: Path) -> None:
    lastfm = FakeAdapter(
        "lastfm",
        [
            c("A", "One"), c("A", "Two"), c("A", "Three"),  # не больше двух от артиста
            c("B", "Both", sim=0.5),
            c("Lib", "Have"),  # уже в библиотеке
            c("C", "Song (Live)"),  # лайв без лайв-семени
            c("D", "Song (Live)", seed_title="Seed (Live)"),  # семя само лайв — можно
            c("Blocked", "No"),
        ],
    )  # fmt: skip
    deezer = FakeAdapter("deezer", [c("B", "Both", source="deezer", sim=0.5)])
    library = LibrarySnapshot(library={track_key("Lib", "Have"): "s1"})
    engine, reco, store = make(tmp_path, [lastfm, deezer], library)
    reco.block_user("vasya", "Blocked", "No", "тест")
    result = asyncio.run(engine.recommend("vasya", 10))
    titles = [r.title for r in result]
    assert "Have" not in titles and "Song (Live)" in titles and "No" not in titles
    assert sum(r.artist == "A" for r in result) == 2
    both = next(r for r in result if r.title == "Both")
    assert both.sources == ["deezer", "lastfm"]
    assert abs(both.score - (0.5 + 0.5) * 1.3) < 1e-6  # ×1.3 за второй источник
    assert [r for r in result if r.artist == "C"] == []


def test_new_artist_share(tmp_path: Path) -> None:
    items = [c(f"Known {i}", "x", sim=0.9) for i in range(6)] + [c(f"New {i}", "y", sim=0.5) for i in range(6)]
    library = LibrarySnapshot(artists={artist_key(f"Known {i}") for i in range(6)})
    engine, reco, store = make(tmp_path, [FakeAdapter("lastfm", items)], library)
    engine.update_config(None, None, 0.5)
    result = asyncio.run(engine.recommend("vasya", 4))
    assert sum(r.new_artist for r in result) == 2


def test_disabled_and_unavailable_sources_skipped(tmp_path: Path) -> None:
    on = FakeAdapter("lastfm", [c("A", "One")])
    off = FakeAdapter("deezer", [c("B", "Two")])
    broken = FakeAdapter("listenbrainz", [c("C", "Three")], ok=False)
    engine, reco, store = make(tmp_path, [on, off, broken])
    engine.update_config({"deezer": False}, {"lastfm": 2.0}, None)
    result = asyncio.run(engine.recommend("vasya", 10))
    assert [r.title for r in result] == ["One"]
    assert off.calls == 0 and broken.calls == 0
    assert abs(result[0].score - 1.6) < 1e-6  # ручной вес 2 × схожесть 0.8


def test_learned_weight_is_smoothed(tmp_path: Path) -> None:
    engine, reco, store = make(tmp_path, [FakeAdapter("lastfm", [])])
    assert engine.learned_weight(None) == 1.0
    three_failures = engine.learned_weight({"promoted": 0, "rejected": 3})
    assert 0.5 < three_failures < 0.6  # не обнуляется
    assert engine.learned_weight({"promoted": 6, "rejected": 0}) > 1.5


def test_weights_follow_pool_outcomes(tmp_path: Path) -> None:
    engine, reco, store = make(tmp_path, [FakeAdapter("lastfm", []), FakeAdapter("deezer", [])])
    for n, (source, status) in enumerate([("lastfm", "promoted"), ("lastfm", "promoted"), ("deezer", "rejected")]):
        track_id = reco.add_pool_track(
            artist=f"A{n}", title="t", album=None, duration_ms=None, cover_url=None, yandex_id=None, location="pool"
        )
        reco.set_owner(track_id, "vasya", status, source)
    weights = engine.weights()
    assert weights["lastfm"] > 1.0 > weights["deezer"]


def test_yandex_archive_adapter(tmp_path: Path) -> None:
    store = Store(str(tmp_path / "bodrod.sqlite3"))
    reco = RecoStore(store)
    t = YTrack(id="11", title="Похожая", artists=["Новый"], artist_ids=["ar3"], duration_ms=200_000)
    reco.upsert_tracks([t])
    reco.add_edges("vasya", "similar", "1", ["11"])
    reco.add_edges("vasya", "wave", "wave:x", ["11"])
    adapter = YandexArchiveAdapter(reco)
    assert adapter.available("vasya")[0] and not adapter.available("olga")[0]
    found = asyncio.run(adapter.candidates("vasya", []))
    assert found[0].title == "Похожая" and found[0].similarity > 0.5


def test_lastfm_adapter_chain(tmp_path: Path) -> None:
    def handle(request: httpx.Request) -> httpx.Response:
        method = request.url.params["method"]
        if method == "track.getSimilar":
            return httpx.Response(
                200, json={"similartracks": {"track": [{"name": "Sim", "artist": {"name": "X"}, "match": "0.9"}]}}
            )
        if method == "artist.getSimilar":
            return httpx.Response(
                200, json={"similarartists": {"artist": [{"name": "Y", "match": "0.7"}, {"name": "Lib"}]}}
            )
        if method == "artist.getTopTracks":
            return httpx.Response(200, json={"toptracks": {"track": [{"name": f"{request.url.params['artist']} hit"}]}})
        if method == "artist.getTopTags":
            return httpx.Response(200, json={"toptags": {"tag": [{"name": "rock"}]}})
        if method == "tag.getTopTracks":
            return httpx.Response(200, json={"tracks": {"track": [{"name": "Tagged", "artist": {"name": "Z"}}]}})
        return httpx.Response(200, json={})

    http = httpx.AsyncClient(transport=httpx.MockTransport(handle))
    client = LastFmClient(http, "key", min_interval_s=0)
    library = FixedLibrary(LibrarySnapshot(artists={"lib"}))
    found = asyncio.run(LastFmAdapter(client, library).candidates("vasya", [Seed("Seed", "Song", 1.0)]))
    titles = {(f.artist, f.title) for f in found}
    assert ("X", "Sim") in titles and ("Y", "Y hit") in titles and ("Z", "Tagged") in titles
    assert ("Lib", "Lib hit") not in titles  # артист уже в библиотеке


def test_listenbrainz_weekly_playlists() -> None:
    def handle(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/createdfor"):
            return httpx.Response(200, json={"playlists": [
                {"playlist": {"title": "Weekly Exploration for nik", "identifier": "https://listenbrainz.org/playlist/abc"}},
                {"playlist": {"title": "Daily Jams", "identifier": "https://listenbrainz.org/playlist/zzz"}},
            ]})  # fmt: skip
        if request.url.path.endswith("/playlist/abc"):
            return httpx.Response(200, json={"playlist": {"track": [{"creator": "Q", "title": "Explore"}]}})
        return httpx.Response(404)

    http = httpx.AsyncClient(transport=httpx.MockTransport(handle))
    adapter = ListenBrainzAdapter(http, None, {"vasya": "nik"})
    found = asyncio.run(adapter.candidates("vasya", []))
    assert [(f.artist, f.title, f.similarity) for f in found] == [("Q", "Explore", 0.75)]


def test_engine_refill_goes_through_pool_requests(tmp_path: Path) -> None:
    world = FakeWorld()
    settings = dataclasses.replace(
        SETTINGS,
        data_dir=str(tmp_path / "data"),
        music_dir=str(tmp_path / "music"),
        navidrome_music_dir=str(tmp_path / "music"),
        library_dir=str(tmp_path / "music" / "lib"),
    )
    store = Store(str(tmp_path / "bodrod.sqlite3"))
    reco = RecoStore(store)
    navidrome = NavidromeClient(world.client(), NAVIDROME, "admin", "adminpass")
    pool = PoolService(store, reco, settings, navidrome, None, LibraryIndex(None, navidrome, None))
    engine = CandidateEngine(
        store, reco, settings, FixedLibrary(LibrarySnapshot()), None, [FakeAdapter("lastfm", [c("A", "One")])]
    )

    async def enqueue(owner, item):
        await pool.enqueue(owner, item.artist, item.title, source=",".join(item.sources), score=item.score)

    assert asyncio.run(engine.refill("vasya", 5, enqueue)) == 1
    requests = store.list_for_user("pool:vasya")
    assert len(requests) == 1 and store.list_for_user("vasya") == []
    assert pool.counts("vasya") == {"queued": 1}
    # Заявка скачана в discovery-пул → трек в пуле.
    request = requests[0]
    dest = Path(settings.discovery_path) / "A" / "A - One.flac"
    dest.parent.mkdir(parents=True)
    dest.write_bytes(flac_bytes(200))
    request.status, request.state["destPath"], request.song_id = RequestStatus.COMPLETED, str(dest), "s1"
    store.save(request)
    asyncio.run(pool.upkeep())
    assert pool.counts("vasya") == {"in_pool": 1}
    assert pool.items("vasya")[0]["source"] == "lastfm"
    # Тот же трек больше не предлагается.
    assert asyncio.run(engine.recommend("vasya", 5)) == []
