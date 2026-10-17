import asyncio
import dataclasses

from bodrod.clients.lidarr import LidarrClient
from bodrod.clients.navidrome import NavidromeClient
from bodrod.clients.slskd import SlskdClient
from bodrod.config import Settings
from bodrod.db import Store
from bodrod.models import RequestKind, RequestStatus
from bodrod.text import search_queries
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


def setup(world: FakeWorld, tmp_path, settings: Settings = SETTINGS):
    http = world.client()
    store = Store(str(tmp_path / "db.sqlite3"))
    clock = FakeClock()
    worker = Worker(
        store,
        settings,
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


def test_track_not_found_retries_then_fails(tmp_path):
    world = FakeWorld(slskd_responses=[slskd_peer("p", r"X\Другой - Песня.mp3")])
    store, worker, clock = setup(world, tmp_path)
    request = add_track(store)
    queued = run_until(worker, clock, store, request.id, RequestStatus.QUEUED)
    # Попробовали оба запроса и отложили повтор на 6 ч; в сообщении видно, что ответы были, но не подошли.
    assert [s["searchText"] for s in world.slskd_searches.values()] == ["трофим город сочи", "trofim gorod sochi"]
    assert "повторим через 6 ч (повтор 1 из 3)" in queued.message
    assert "«трофим город сочи» — ответов 1, файлов 1, подходящих 0" in queued.message
    assert queued.next_check_at - clock.now > 5 * 3600
    for _ in range(3):
        clock.advance(SETTINGS.slskd_retry_interval_s)
        asyncio.run(worker.tick())
        current = run_until(worker, clock, store, request.id, store.get(request.id).status, steps=1)
        for _ in range(10):
            if current.status in (RequestStatus.QUEUED, RequestStatus.FAILED) and current.next_check_at > clock.now:
                break
            clock.advance(6)
            asyncio.run(worker.tick())
            current = store.get(request.id)
    failed = store.get(request.id)
    assert failed.status == RequestStatus.FAILED
    assert failed.message.startswith("Не нашли подходящий файл в Soulseek. Запросы: «трофим город сочи»")
    assert len(world.slskd_searches) == 8


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


def test_queries_for_hard_cases():
    assert search_queries("Филипп Киркоров", "Луиза") == ["филипп киркоров луиза", "filipp kirkorov luiza"]
    assert search_queries("Игорь Крутой, Ирина Аллегрова, Лариса Долина", "Ангел-хранитель мой") == [
        "игорь крутой ирина аллегрова лариса долина ангел хранитель мой",
        "игорь крутой ангел хранитель мой",
        "igor krutoy angel hranitel moy",
    ]
    assert search_queries("О! Марго, Рубеж Веков", "По тротуарам") == [
        "о марго рубеж веков по тротуарам",
        "о марго по тротуарам",
        "o margo po trotuaram",
    ]
    assert search_queries("Linkin Park feat. Jay-Z", "Numb / Encore (Live)") == [
        "linkin park numb encore",
        "numb encore",
    ]


def test_second_query_finds_file(tmp_path):
    filename = r"Music\Igor Krutoy\Igor Krutoy - Angel-hranitel moy.mp3"
    world = FakeWorld(slskd_responses_by_text={"igor krutoy angel hranitel moy": [slskd_peer("p", filename)]})
    store, worker, clock = setup(world, tmp_path)
    request, _ = store.create(
        username="vasya",
        kind=RequestKind.TRACK,
        artist="Игорь Крутой, Ирина Аллегрова",
        title="Ангел-хранитель мой",
        album=None,
        duration_ms=200_000,
        cover_url=None,
    )
    downloading = run_until(worker, clock, store, request.id, RequestStatus.DOWNLOADING)
    assert downloading.state["candidates"][0]["filename"] == filename
    assert [t["matched"] for t in downloading.state["tried"]] == [0, 0, 1]


def test_searches_are_queued(tmp_path):
    world = FakeWorld()
    store, worker, clock = setup(world, tmp_path, dataclasses.replace(SETTINGS, slskd_search_gap_s=0))
    titles = ["Луиза", "Зайка моя", "Мария", "Снег", "Жестокая любовь", "Цвет настроения синий"]
    ids = [
        store.create(
            username="vasya", kind=RequestKind.TRACK, artist="Филипп Киркоров", title=t, album=None,
            duration_ms=None, cover_url=None,
        )[0].id
        for t in titles
    ]  # fmt: skip
    peak = 0
    for _ in range(60):
        asyncio.run(worker.tick())
        searching = sum(store.get(i).status == RequestStatus.SEARCHING for i in ids)
        peak = max(peak, searching)
        clock.advance(2)
    assert peak == SETTINGS.slskd_parallel_searches
    starts = sorted(s["searchText"] for s in world.slskd_searches.values())
    assert len(starts) == len(titles) * 2  # каждая заявка дошла до обоих запросов
    assert all(store.get(i).status == RequestStatus.QUEUED for i in ids)


def test_deleted_lidarr_artist_fails_at_once(tmp_path):
    world = FakeWorld()
    store, worker, clock = setup(world, tmp_path)
    request, _ = store.create(
        username="vasya", kind=RequestKind.ARTIST, artist="Свят", title=None, album=None, duration_ms=None,
        cover_url=None,
    )  # fmt: skip
    run_until(worker, clock, store, request.id, RequestStatus.DOWNLOADING)
    world.lidarr_artists.clear()  # артиста удалили в Lidarr
    clock.advance(SETTINGS.lidarr_check_interval_s)
    asyncio.run(worker.tick())
    failed = store.get(request.id)
    assert failed.status == RequestStatus.FAILED and failed.message.startswith("Артист удалён из Lidarr")
    assert failed.errors == 0
    artist_gets = [r for r in world.requests if r.url.path == "/api/v1/artist/1"]
    assert len(artist_gets) == 1
