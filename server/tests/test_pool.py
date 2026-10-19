"""Discovery-пул: пополнение из резерва, promote / reject / expire, перенос и удаление файлов."""

import asyncio
import dataclasses
from pathlib import Path

from bodrod.clients.navidrome import NavidromeClient
from bodrod.db import Store
from bodrod.reco.keys import track_key
from bodrod.reco.library import LibraryIndex
from bodrod.reco.pool import CHARTS_OWNER, PoolService
from bodrod.reco.store import DAY_MS, RecoStore

from .fakes import NAVIDROME, FakeWorld
from .test_maintenance import flac_bytes
from .test_worker import SETTINGS


class Clock:
    def __init__(self) -> None:
        self.now = 1_800_000_000_000

    def __call__(self) -> int:
        return self.now


def make(tmp_path: Path):
    world = FakeWorld()
    settings = dataclasses.replace(
        SETTINGS,
        data_dir=str(tmp_path / "data"),
        music_dir=str(tmp_path / "music"),
        navidrome_music_dir=str(tmp_path / "music"),
        library_dir=str(tmp_path / "music" / "lib"),
        pool_size=3,
        pool_refill_below=2,
    )
    store = Store(str(tmp_path / "bodrod.sqlite3"))
    reco = RecoStore(store)
    navidrome = NavidromeClient(world.client(), NAVIDROME, "admin", "adminpass")
    clock = Clock()
    pool = PoolService(store, reco, settings, navidrome, None, LibraryIndex(None, navidrome, None), clock)
    return pool, reco, store, settings, clock, world


def reserve_track(reco: RecoStore, settings, user: str, n: int, score: float = 1.0) -> int:
    path = Path(settings.reserve_path) / f"Артист {n}" / "Альбом" / f"Артист {n} - Песня {n}.flac"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(flac_bytes(200))
    track_id = reco.add_pool_track(
        artist=f"Артист {n}", title=f"Песня {n}", album="Альбом", duration_ms=200_000, cover_url=None,
        yandex_id=str(n), location="reserve", path=str(path),
    )  # fmt: skip
    reco.set_owner(track_id, user, "candidate", "yandex_archive", score)
    return track_id


def in_pool(pool: PoolService, reco: RecoStore, settings, user: str, n: int) -> int:
    """Трек в пуле с id Navidrome `s<n>`."""
    track_id = reserve_track(reco, settings, user, n)
    asyncio.run(pool._from_reserve(user, 10))
    reco.update_pool_track(track_id, song_id=f"s{n}")
    return track_id


def test_refill_moves_best_reserve_tracks_into_discovery(tmp_path: Path) -> None:
    pool, reco, store, settings, clock, world = make(tmp_path)
    for n, score in ((1, 1.0), (2, 5.0), (3, 3.0), (4, 2.0)):
        reserve_track(reco, settings, "vasya", n, score)
    moved = asyncio.run(pool.refill("vasya"))
    assert moved == 3  # POOL_SIZE
    titles = {i["title"] for i in pool.items("vasya")}
    assert titles == {"Песня 2", "Песня 3", "Песня 4"}
    files = list(Path(settings.discovery_path).rglob("*.flac"))
    assert len(files) == 3
    assert world.scans == 1
    # Через сутки пул ещё полон (3 ≥ 2) — не пополняется.
    clock.now += DAY_MS
    assert asyncio.run(pool.refill("vasya")) == 0


def test_like_promotes_into_library_and_records_replacement(tmp_path: Path) -> None:
    pool, reco, store, settings, clock, world = make(tmp_path)
    track_id = in_pool(pool, reco, settings, "vasya", 1)
    changed = asyncio.run(pool.feedback("vasya", [{"song_id": "s1", "event": "liked"}]))
    assert changed == {"s1": "promoted"}
    track = reco.pool_track(track_id)
    assert track["location"] == "library"
    assert Path(track["path"]).is_relative_to(settings.library_dir) and Path(track["path"]).exists()
    assert not list(Path(settings.discovery_path).rglob("*.flac"))
    assert store.replacements(["s1"]) == {"s1": track["path"]}


def test_two_full_plays_promote(tmp_path: Path) -> None:
    pool, reco, store, settings, clock, world = make(tmp_path)
    in_pool(pool, reco, settings, "vasya", 1)
    play = {"song_id": "s1", "event": "completed", "position_ms": 170_000, "duration_ms": 200_000}
    assert asyncio.run(pool.feedback("vasya", [play])) == {}
    short = play | {"position_ms": 100_000}
    assert asyncio.run(pool.feedback("vasya", [short])) == {}  # меньше 80 % — не полное
    assert asyncio.run(pool.feedback("vasya", [play])) == {"s1": "promoted"}


def test_dislike_rejects_blocks_and_deletes_file(tmp_path: Path) -> None:
    pool, reco, store, settings, clock, world = make(tmp_path)
    track_id = in_pool(pool, reco, settings, "vasya", 1)
    path = Path(reco.pool_track(track_id)["path"])
    assert asyncio.run(pool.feedback("vasya", [{"song_id": "s1", "event": "disliked"}])) == {"s1": "rejected"}
    assert not path.exists()
    assert reco.pool_track(track_id)["location"] == "gone"
    assert track_key("Артист 1", "Песня 1") in reco.user_blocked_keys("vasya", clock.now)
    # Через 180 дней — снова можно предлагать.
    assert track_key("Артист 1", "Песня 1") not in reco.user_blocked_keys("vasya", clock.now + 181 * DAY_MS)


def test_second_early_skip_rejects(tmp_path: Path) -> None:
    pool, reco, store, settings, clock, world = make(tmp_path)
    in_pool(pool, reco, settings, "vasya", 1)
    skip = {"song_id": "s1", "event": "skipped", "position_ms": 10_000, "duration_ms": 200_000}
    assert asyncio.run(pool.feedback("vasya", [skip])) == {}
    assert asyncio.run(pool.feedback("vasya", [skip])) == {"s1": "rejected"}


def test_file_kept_while_another_owner_holds_it(tmp_path: Path) -> None:
    pool, reco, store, settings, clock, world = make(tmp_path)
    track_id = in_pool(pool, reco, settings, "vasya", 1)
    reco.set_owner(track_id, CHARTS_OWNER, "in_pool", "chart")
    path = Path(reco.pool_track(track_id)["path"])
    asyncio.run(pool.feedback("vasya", [{"song_id": "s1", "event": "disliked"}]))
    assert path.exists()
    assert reco.pool_track(track_id)["location"] == "pool"


def test_expire_after_three_shows_and_after_days(tmp_path: Path) -> None:
    pool, reco, store, settings, clock, world = make(tmp_path)
    in_pool(pool, reco, settings, "vasya", 1)
    in_pool(pool, reco, settings, "vasya", 2)
    shown = {"song_id": "s1", "event": "shown", "context": "wave"}
    asyncio.run(pool.feedback("vasya", [shown, shown]))
    assert asyncio.run(pool.feedback("vasya", [shown])) == {"s1": "expired"}
    # Второй трек: 21 день без прослушиваний.
    clock.now += 22 * DAY_MS
    asyncio.run(pool.upkeep())
    assert pool.counts("vasya").get("expired") == 2


def test_status_and_hidden(tmp_path: Path) -> None:
    pool, reco, store, settings, clock, world = make(tmp_path)
    in_pool(pool, reco, settings, "vasya", 1)
    assert pool.status_of("vasya", ["s1", "s9"]) == {"s1": "in_pool"}
    assert pool.status_of("olga", ["s1"]) == {"s1": "in_pool"}
    assert asyncio.run(pool.hidden())["songIds"] == ["s1"]
