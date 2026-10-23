"""Задание 018: привязка аккаунта Яндекса, статусы кандидатов по источникам, зависшие заявки пула, ключ звука."""

import asyncio
import dataclasses
import sqlite3
from pathlib import Path

from bodrod.clients.navidrome import NavidromeClient
from bodrod.db import Store
from bodrod.models import RequestStatus
from bodrod.navidrome_db import NavidromeDb
from bodrod.reco.analysis import AnalysisService
from bodrod.reco.audio_key import audio_hash
from bodrod.reco.engine import CandidateEngine, ListenBrainzAdapter, YandexArchiveAdapter
from bodrod.reco.keys import track_key
from bodrod.reco.library import LibraryIndex, LibrarySnapshot
from bodrod.reco.pool import PoolService
from bodrod.reco.store import RecoStore, candidate_state
from bodrod.reco.yandex_links import NOT_LINKED, YandexLinks
from bodrod.sources.yandex_tokens import YandexTokens

from .fakes import NAVIDROME, FakeWorld
from .test_engine import FakeAdapter, FixedLibrary
from .test_harvest import FakeAccount
from .test_harvest import make as make_harvester
from .test_maintenance import flac_bytes
from .test_worker import SETTINGS

# region Привязка аккаунта Яндекса


def test_links_env_app_and_legacy_file_names(tmp_path: Path) -> None:
    store = Store(str(tmp_path / "b.sqlite3"))
    links = YandexLinks(store, {"nikolai": "jappi"}, ["jappi", "kalainidi", "olga"], lambda: ["nikolai", "olga"])
    assert links.yandex_login("nikolai") == "jappi"
    assert links.yandex_login("pool:nikolai") == "jappi"
    assert links.yandex_login("olga") == "olga"  # файл токена назван именем пользователя (как до 0.15.0)
    assert links.yandex_login("masha") is None
    assert links.navidrome_user("jappi") == "nikolai"
    assert links.navidrome_user("kalainidi") is None  # не привязан и не пользователь Navidrome
    links.set("masha", "kalainidi")
    assert links.yandex_login("masha") == "kalainidi" and links.navidrome_user("kalainidi") == "masha"
    links.set("nikolai", None)  # из приложения можно отвязать и то, что задано переменной
    assert links.yandex_login("nikolai") is None


def test_tokens_resolve_through_link() -> None:
    links = {"nikolai": "jappi"}
    tokens = YandexTokens({"jappi": "t-jappi"}, "t-global", lambda _t: None, link=lambda u: links.get(u))  # type: ignore[arg-type]
    ref = tokens.resolve("pool:nikolai")
    assert ref is not None and ref.token == "t-jappi" and ref.origin == "user" and ref.user == "nikolai"
    assert tokens.resolve("jappi").origin == "global"  # логин Яндекса — не пользователь приложения
    assert tokens.resolve("olga", fallback=False) is None


def test_archive_adapter_uses_link_and_explains_errors(tmp_path: Path) -> None:
    store = Store(str(tmp_path / "b.sqlite3"))
    reco = RecoStore(store)
    links = YandexLinks(store, {"nikolai": "jappi"}, ["jappi", "kalainidi"], lambda: ["nikolai", "olga"])
    adapter = YandexArchiveAdapter(reco, links)
    assert adapter.available("olga") == (False, NOT_LINKED)
    ok, reason = adapter.available("nikolai")
    assert not ok and "jappi" in reason and "пуст" in reason
    from bodrod.sources.yandex import YTrack

    reco.upsert_tracks([YTrack(id="11", title="Похожая", artists=["Новый"], artist_ids=["ar3"])])
    reco.add_edges("jappi", "similar", "1", ["11"])
    assert adapter.available("nikolai") == (True, None)
    found = asyncio.run(adapter.candidates("nikolai", []))
    assert [c.title for c in found] == ["Похожая"]


def test_harvester_reserve_and_blocklist_go_to_linked_user(tmp_path: Path) -> None:
    harvester, reco, store, settings, accounts = make_harvester(tmp_path, {"jappi": FakeAccount()})
    # Резерв, записанный под логином Яндекса до привязки, переезжает к пользователю приложения.
    old = reco.add_pool_track(
        artist="Старый", title="Резерв", album=None, duration_ms=None, cover_url=None, yandex_id="77",
        location="reserve",
    )  # fmt: skip
    reco.set_owner(old, "jappi", "candidate", "yandex_archive")
    harvester._links = YandexLinks(store, {"nikolai": "jappi"}, ["jappi"], lambda: ["nikolai"])
    harvester.relink()
    assert reco.owner(old, "nikolai") is not None and reco.owner(old, "jappi") is None
    asyncio.run(harvester.tick("jappi"))
    assert reco.edge_counts("jappi")["similar"] == 2  # архив — под логином Яндекса
    assert track_key("Скучный", "Плохая") in reco.user_blocked_keys("nikolai")
    assert reco.reserve_count("nikolai") > 1 and reco.reserve_count("jappi") == 0
    status = harvester.status()[0]
    assert status["navidromeUser"] == "nikolai"
    assert status["reserveStates"]["downloaded"] == reco.reserve_count("nikolai")


def test_unlinked_account_collects_archive_but_no_reserve(tmp_path: Path) -> None:
    harvester, reco, store, settings, accounts = make_harvester(tmp_path, {"kalainidi": FakeAccount()})
    harvester._links = YandexLinks(store, {}, ["kalainidi"], lambda: ["nikolai"])
    asyncio.run(harvester.tick("kalainidi"))
    assert reco.edge_counts("kalainidi")["similar"] == 2
    assert accounts["kalainidi"].downloads == []
    status = harvester.status()[0]
    assert status["navidromeUser"] is None
    reserve = next(c for c in status["collectors"] if c["name"] == "reserve")
    assert "привязки" in reserve["disabled"]


def test_listenbrainz_reason_per_user() -> None:
    adapter = ListenBrainzAdapter(None, None, {"nikolai": "nik_lb"})  # type: ignore[arg-type]
    assert adapter.available("nikolai") == (True, None)
    ok, reason = adapter.available("olga")
    assert not ok and "olga" in reason
    assert not ListenBrainzAdapter(None, None, {}).available("")[0]  # type: ignore[arg-type]


# endregion

# region Статусы кандидатов


def test_candidate_states() -> None:
    assert candidate_state("queued", "queued") == "queued"
    assert candidate_state("downloading", "downloading") == "downloading"
    assert candidate_state("in_pool", "pool") == "downloaded"
    assert candidate_state("candidate", "reserve") == "downloaded"
    assert candidate_state("candidate", "downloading") == "downloading"
    assert candidate_state("candidate", "gone") == "queued"
    assert candidate_state("failed", "failed") == "notFound"
    assert candidate_state("promoted", "library") == "promoted"
    assert candidate_state("expired", "gone") == "rejected"


def test_source_stats_break_down_by_state(tmp_path: Path) -> None:
    store = Store(str(tmp_path / "b.sqlite3"))
    reco = RecoStore(store)
    engine = CandidateEngine(
        store, reco, SETTINGS, FixedLibrary(LibrarySnapshot()), None,
        [FakeAdapter("deezer", []), FakeAdapter("yandex_archive", [])],
    )  # fmt: skip
    rows = [
        ("deezer", "queued", "queued"), ("deezer", "downloading", "downloading"), ("deezer", "in_pool", "pool"),
        ("deezer", "failed", "failed"), ("deezer", "promoted", "library"), ("deezer", "rejected", "gone"),
        ("deezer,yandex_archive", "in_pool", "pool"), ("yandex_archive", "candidate", "reserve"),
    ]  # fmt: skip
    for n, (source, status, location) in enumerate(rows):
        track_id = reco.add_pool_track(
            artist=f"A{n}", title="t", album=None, duration_ms=None, cover_url=None, yandex_id=None, location=location
        )
        reco.set_owner(track_id, "vasya", status, source)
    stats = engine.source_stats()
    assert stats["deezer"] == {
        "suggested": 7, "queued": 1, "downloading": 1, "downloaded": 2, "notFound": 1, "promoted": 1, "rejected": 1,
    }  # fmt: skip
    assert stats["yandex_archive"]["downloaded"] == 2
    described = {s["name"]: s for s in engine.describe("vasya")["sources"]}
    assert described["deezer"]["notFound"] == 1 and described["deezer"]["available"]
    assert [c["state"] for c in engine.candidates("deezer", "notFound", 10)] == ["notFound"]


def make_pool(tmp_path: Path):
    world = FakeWorld()
    settings = dataclasses.replace(
        SETTINGS,
        data_dir=str(tmp_path / "data"),
        music_dir=str(tmp_path / "music"),
        navidrome_music_dir=str(tmp_path / "music"),
        library_dir=str(tmp_path / "music" / "lib"),
        pool_stall_hours=1,
        pool_max_attempts=2,
    )
    store = Store(str(tmp_path / "bodrod.sqlite3"))
    reco = RecoStore(store)
    navidrome = NavidromeClient(world.client(), NAVIDROME, "admin", "adminpass")
    now = [1_800_000_000_000]
    pool = PoolService(store, reco, settings, navidrome, None, LibraryIndex(None, navidrome, None), lambda: now[0])
    return pool, reco, store, now


def test_stalled_request_is_requeued_then_not_found(tmp_path: Path) -> None:
    pool, reco, store, now = make_pool(tmp_path)
    cancelled: list[str] = []

    async def cancel(request) -> None:
        cancelled.append(request.id)
        request.status = RequestStatus.CANCELLED
        store.save(request)

    pool.cancel_request = cancel
    assert asyncio.run(pool.enqueue("vasya", "A", "One", source="deezer"))
    track = reco.pool_track_by_key(track_key("A", "One"))
    first = store.get(track["request_id"])
    first.status, first.chain, first.chain_index = RequestStatus.DOWNLOADING, ["slskd"], 0
    store.save(first)
    asyncio.run(pool._sync_requests())
    track = reco.pool_track(int(track["id"]))
    assert track["loader"] == "slskd" and track["attempt_at"] == now[0] and pool.counts("vasya") == {"downloading": 1}
    # Час без продвижения — зависла: отменена и поставлена снова.
    now[0] += 3600 * 1000 + 1
    asyncio.run(pool._sync_requests())
    track = reco.pool_track(int(track["id"]))
    assert cancelled == [first.id] and track["request_id"] != first.id and track["attempts"] == 2
    assert pool.counts("vasya") == {"queued": 1}
    second = store.get(track["request_id"])
    second.status, second.chain, second.chain_index = RequestStatus.SEARCHING, ["yandex"], 0
    store.save(second)
    asyncio.run(pool._sync_requests())
    now[0] += 3600 * 1000 + 1
    asyncio.run(pool._sync_requests())  # вторая попытка тоже зависла — «не найдено»
    track = reco.pool_track(int(track["id"]))
    assert track["location"] == "failed" and "зависала" in track["message"]
    assert pool.counts("vasya") == {"failed": 1}


def test_waiting_source_is_not_stalled(tmp_path: Path) -> None:
    import time

    pool, reco, store, now = make_pool(tmp_path)
    asyncio.run(pool.enqueue("vasya", "A", "One"))
    track = reco.pool_track_by_key(track_key("A", "One"))
    request = store.get(track["request_id"])
    request.status, request.next_check_at = RequestStatus.SEARCHING, time.time() + 3600
    store.save(request)
    asyncio.run(pool._sync_requests())
    now[0] += 5 * 3600 * 1000
    asyncio.run(pool._sync_requests())
    assert reco.pool_track(int(track["id"]))["request_id"] == request.id  # плановая пауза источника — не зависание


# endregion

# region Ключ звука и анализ


def mp3_bytes(tag: bytes, audio: bytes) -> bytes:
    size = len(tag)
    synchsafe = bytes([(size >> 21) & 0x7F, (size >> 14) & 0x7F, (size >> 7) & 0x7F, size & 0x7F])
    return b"ID3\x04\x00\x00" + synchsafe + tag + audio + b"TAG" + tag[:125].ljust(125, b"\x00")


def flac_with(md5: bytes, comment: bytes, frames: bytes = b"") -> bytes:
    base = bytearray(flac_bytes(200))
    base[4] = 0x00  # STREAMINFO больше не последний блок
    base[8 + 18 : 8 + 34] = md5
    block = bytes([0x84]) + len(comment).to_bytes(3, "big") + comment
    return bytes(base) + block + frames


def ogg_page(granule: int, body: bytes, seq: int) -> bytes:
    import struct

    return b"OggS\x00\x00" + struct.pack("<qII", granule, 1, seq) + b"\x00\x00\x00\x00" + bytes([1, len(body)]) + body


def test_audio_hash_ignores_tags(tmp_path: Path) -> None:
    def h(name: str, data: bytes) -> str | None:
        path = tmp_path / name
        path.write_bytes(data)
        return audio_hash(path)

    md5 = bytes(range(16))
    assert (
        h("a.flac", flac_with(md5, b"title=A"))
        == h("b.flac", flac_with(md5, b"title=B, longer"))
        == f"flac:{md5.hex()}"
    )
    zero = bytes(16)
    assert h("c.flac", flac_with(zero, b"x", b"FRAMES")) == h("d.flac", flac_with(zero, b"yyyy", b"FRAMES"))
    assert h("e.flac", flac_with(zero, b"x", b"FRAMES")) != h("f.flac", flac_with(zero, b"x", b"OTHER"))
    assert h("a.mp3", mp3_bytes(b"T" * 20, b"AUDIO" * 100)) == h("b.mp3", mp3_bytes(b"U" * 300, b"AUDIO" * 100))
    assert h("c.mp3", mp3_bytes(b"T" * 20, b"AUDIO" * 100)) != h("d.mp3", mp3_bytes(b"T" * 20, b"OTHER" * 100))
    head1 = ogg_page(0, b"OpusHead", 0) + ogg_page(0, b"OpusTags short", 1)
    head2 = ogg_page(0, b"OpusHead", 0) + ogg_page(0, b"OpusTags much longer comment", 1)
    assert h("a.opus", head1 + ogg_page(960, b"AUDIO", 2)) == h("b.opus", head2 + ogg_page(960, b"AUDIO", 2))
    assert audio_hash(tmp_path / "missing.flac") is None


def nd(path: Path, songs: list[tuple[str, str, str | None, int]]) -> NavidromeDb:
    """Мини-БД Navidrome: (id, путь относительно /music, MBID, missing)."""
    conn = sqlite3.connect(path)
    conn.executescript(
        """
        CREATE TABLE media_file (id TEXT, title TEXT, artist TEXT, album TEXT, album_id TEXT, duration REAL,
            size INTEGER, suffix TEXT, bit_rate INTEGER, path TEXT, missing INTEGER DEFAULT 0,
            mbz_recording_id TEXT);
        CREATE TABLE user (id TEXT, user_name TEXT, is_admin INTEGER);
        CREATE TABLE annotation (user_id TEXT, item_id TEXT, item_type TEXT, starred INTEGER, rating INTEGER,
            play_count INTEGER, play_date TEXT, starred_at TEXT);
        """
    )
    for song_id, rel, mbid, missing in songs:
        conn.execute(
            "INSERT INTO media_file VALUES (?, ?, 'A', 'B', 'al', 200, 1, 'flac', 900, ?, ?, ?)",
            (song_id, song_id, f"/music/{rel}", missing, mbid),
        )
    conn.commit()
    conn.close()
    return NavidromeDb(str(path))


def test_analysis_follows_moved_files_counts_library_and_cleans_up(tmp_path: Path) -> None:
    music = tmp_path / "music"
    settings = dataclasses.replace(
        SETTINGS, music_dir=str(music), navidrome_music_dir="/music", analyzer_token="t", data_dir=str(tmp_path),
        quarantine_dir=str(tmp_path / "quarantine"),
    )  # fmt: skip
    store = Store(str(tmp_path / "bodrod.sqlite3"))
    RecoStore(store)

    def write(rel: str, data: bytes) -> None:
        (music / rel).parent.mkdir(parents=True, exist_ok=True)
        (music / rel).write_bytes(data)

    # Старые записи: перетегированный и перенесённый файл (хэш), переехавший трек с MBID (пропавшая строка в БД
    # Navidrome), трек в карантине, удалённый трек.
    write("new/retagged.flac", flac_with(bytes([1] * 16), b"new tags"))
    write("new/mbid.mp3", mp3_bytes(b"x", b"MBIDAUDIO"))
    stored = tmp_path / "quarantine" / "1" / "q.flac"
    stored.parent.mkdir(parents=True)
    stored.write_bytes(flac_with(bytes([2] * 16), b"q"))
    store.execute(
        "INSERT INTO quarantine (original_path, stored_path, reason, size, created_at) VALUES (?, ?, 'дубль', 1, 0)",
        (str(music / "old/q.flac"), str(stored)),
    )
    db = nd(
        tmp_path / "navidrome.db",
        [
            ("s1", "new/retagged.flac", None, 0),
            ("s2", "new/mbid.mp3", "mb-2", 0),
            ("s2old", "old/mbid.mp3", "mb-2", 1),
            ("s3", "new/fresh.flac", None, 0),
        ],
    )
    service = AnalysisService(store, settings, db)
    for rel in ("old/retagged.flac", "old/mbid.mp3", "old/q.flac", "old/deleted.flac"):
        service.save(rel, {"bpm": 100}, [1.0, 0.0], None)
    # Старый файл ушёл, звук тот же: хэш старой записи — по старому содержимому, которого уже нет. Положим копию.
    write("old/retagged.flac", flac_with(bytes([1] * 16), b"old tags"))
    store.execute("UPDATE audio_features SET keyed = 0")
    assert service.status()["analyzed"] == 0  # ни одной из этих записей нет в библиотеке

    result = asyncio.run(service.maintain())
    (music / "old/retagged.flac").unlink()
    asyncio.run(service.maintain())
    paths = {r["path"] for r in store.query("SELECT path FROM audio_features")}
    assert {"new/retagged.flac", "new/mbid.mp3"} <= paths  # анализ перенесён, не повторяется
    assert "old/q.flac" in paths  # в карантине — не трогаем
    assert "old/deleted.flac" not in paths and "old/mbid.mp3" not in paths
    assert result["adopted"] == 2
    status = service.status()
    assert status["analyzed"] == 2 and status["library"] == 3
    assert asyncio.run(service.next_jobs(5)) == ["new/fresh.flac"]


# endregion
