import asyncio

from bodrod.clients.lidarr import LidarrClient
from bodrod.clients.navidrome import NavidromeClient
from bodrod.clients.slskd import SlskdClient
from bodrod.config import Settings
from bodrod.db import Store
from bodrod.models import RequestKind, RequestStatus
from bodrod.worker import Worker

from .fakes import LIDARR, NAVIDROME, SLSKD, FakeClock, FakeWorld

SETTINGS = Settings(
    navidrome_url=NAVIDROME,
    navidrome_admin_user="admin",
    navidrome_admin_password="adminpass",
    slskd_url=SLSKD,
    slskd_api_key="slskd-key",
    lidarr_url=LIDARR,
    lidarr_api_key="lidarr-key",
    data_dir="/tmp",
)


def setup(world: FakeWorld, tmp_path):
    http = world.client()
    store = Store(str(tmp_path / "db.sqlite3"))
    clock = FakeClock()
    worker = Worker(
        store,
        SETTINGS,
        NavidromeClient(http, NAVIDROME, "admin", "adminpass"),
        SlskdClient(http, SLSKD, "slskd-key"),
        LidarrClient(http, LIDARR, "lidarr-key"),
        clock=clock,
    )
    return store, worker, clock


def run_until(worker: Worker, clock: FakeClock, store: Store, request_id: str, status: RequestStatus, steps: int = 30):
    for _ in range(steps):
        asyncio.run(worker.tick())
        current = store.get(request_id)
        if current.status == status:
            return current
        clock.advance(6)
    raise AssertionError(f"Заявка не дошла до {status}: {store.get(request_id)}")


def add_track(store: Store):
    request, created = store.create(
        username="vasya",
        kind=RequestKind.TRACK,
        artist="Трофим",
        title="Город Сочи",
        album=None,
        duration_ms=200_000,
        cover_url=None,
    )
    assert created
    return request


def slskd_peer(username: str, filename: str) -> dict:
    return {
        "username": username,
        "hasFreeUploadSlot": True,
        "queueLength": 0,
        "uploadSpeed": 2_000_000,
        "files": [{"filename": filename, "size": 9_000_000, "extension": "mp3", "bitRate": 320, "length": 199}],
    }


def test_track_downloads_and_appears_in_library(tmp_path):
    filename = r"Music\Трофим\Трофим - Город Сочи.mp3"
    world = FakeWorld(slskd_responses=[slskd_peer("peer1", filename)])
    store, worker, clock = setup(world, tmp_path)
    request = add_track(store)

    downloading = run_until(worker, clock, store, request.id, RequestStatus.DOWNLOADING)
    assert downloading.state["candidates"][0]["username"] == "peer1"
    search = next(iter(world.slskd_searches.values()))
    assert search["searchText"] == "трофим город сочи"

    world.set_transfer("peer1", filename, "InProgress", 40)
    clock.advance(6)
    asyncio.run(worker.tick())
    assert store.get(request.id).progress == 0.4

    world.pending_library.append({"id": "nd-42", "title": "Город Сочи", "artist": "Трофим"})
    world.set_transfer("peer1", filename, "Completed, Succeeded", 100)
    done = run_until(worker, clock, store, request.id, RequestStatus.COMPLETED)
    assert done.song_id == "nd-42"
    assert world.scans >= 1


def test_track_already_in_library(tmp_path):
    world = FakeWorld(library=[{"id": "nd-1", "title": "Город Сочи", "artist": "Трофим"}])
    store, worker, clock = setup(world, tmp_path)
    request = add_track(store)
    done = run_until(worker, clock, store, request.id, RequestStatus.COMPLETED)
    assert done.song_id == "nd-1"
    assert world.slskd_searches == {}


def test_track_fails_over_to_next_peer(tmp_path):
    filename_a = r"A\Трофим - Город Сочи.mp3"
    filename_b = r"B\Трофим\Город Сочи.mp3"
    world = FakeWorld(
        slskd_responses=[slskd_peer("offline", filename_a), slskd_peer("errors", filename_b)],
        slskd_reject_users={"offline"},
    )
    store, worker, clock = setup(world, tmp_path)
    request = add_track(store)
    downloading = run_until(worker, clock, store, request.id, RequestStatus.DOWNLOADING)
    current = downloading.state["candidates"][downloading.state["index"]]
    assert current["username"] == "errors"

    world.set_transfer("errors", filename_b, "Completed, Errored")
    failed = run_until(worker, clock, store, request.id, RequestStatus.FAILED)
    assert failed.message == "Ни один источник не отдал файл"


def test_track_not_found_in_soulseek(tmp_path):
    world = FakeWorld(slskd_responses=[slskd_peer("p", r"X\Другой - Песня.mp3")])
    store, worker, clock = setup(world, tmp_path)
    request = add_track(store)
    failed = run_until(worker, clock, store, request.id, RequestStatus.FAILED)
    assert "Soulseek" in failed.message


def test_artist_goes_to_lidarr(tmp_path):
    world = FakeWorld()
    store, worker, clock = setup(world, tmp_path)
    request, _ = store.create(
        username="vasya",
        kind=RequestKind.ARTIST,
        artist="трофим",
        title=None,
        album=None,
        duration_ms=None,
        cover_url=None,
    )
    downloading = run_until(worker, clock, store, request.id, RequestStatus.DOWNLOADING)
    added = world.lidarr_artists[0]
    assert added["addOptions"] == {"monitor": "all", "searchForMissingAlbums": True}
    assert added["rootFolderPath"] == "/music"
    assert downloading.state["lidarrId"] == 1

    added["statistics"]["trackFileCount"] = 12
    for _ in range(20):
        clock.advance(60)
        asyncio.run(worker.tick())
    done = store.get(request.id)
    assert done.status == RequestStatus.COMPLETED
    assert done.message == "Скачано 12 из 40 треков"
    assert world.scans >= 1


def test_cancel_artist_stops_lidarr(tmp_path):
    world = FakeWorld()
    store, worker, clock = setup(world, tmp_path)
    request, _ = store.create(
        username="vasya",
        kind=RequestKind.ARTIST,
        artist="трофим",
        title=None,
        album=None,
        duration_ms=None,
        cover_url=None,
    )
    downloading = run_until(worker, clock, store, request.id, RequestStatus.DOWNLOADING)
    world.lidarr_queue = 2
    asyncio.run(worker.cancel(downloading))
    assert store.get(request.id).status == RequestStatus.CANCELLED
    assert world.lidarr_updates[-1]["monitored"] is False
    assert world.lidarr_removed_from_queue == [0, 1]


def test_existing_lidarr_artist_triggers_search(tmp_path):
    world = FakeWorld(
        lidarr_artists=[
            {"id": 3, "artistName": "Трофим", "foreignArtistId": "mb-трофим", "monitored": True, "statistics": {}}
        ]
    )
    store, worker, clock = setup(world, tmp_path)
    request, _ = store.create(
        username="vasya",
        kind=RequestKind.ARTIST,
        artist="Трофим",
        title=None,
        album=None,
        duration_ms=None,
        cover_url=None,
    )
    run_until(worker, clock, store, request.id, RequestStatus.DOWNLOADING)
    assert world.lidarr_commands == [{"name": "ArtistSearch", "artistId": 3}]
    assert len(world.lidarr_artists) == 1
