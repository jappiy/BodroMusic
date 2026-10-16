import asyncio
import dataclasses
import shutil
from pathlib import Path

import mutagen
import pytest

import bodrod.worker as worker_module
from bodrod.clients.lidarr import LidarrClient
from bodrod.clients.navidrome import NavidromeClient
from bodrod.clients.slskd import SlskdClient
from bodrod.db import Store
from bodrod.models import RequestKind, RequestStatus
from bodrod.worker import Worker

from .fakes import LIDARR, NAVIDROME, SLSKD, FakeClock, FakeWorld
from .test_worker import SETTINGS, run_until

FIXTURES = Path(__file__).parent / "fixtures"
REMOTE = r"Music\Трофим\02 - Город Сочи.mp3"
COVER = ((FIXTURES / "cover.jpg").read_bytes(), "image/jpeg")
EXPECTED = Path("Трофим/Хиты/Трофим - Город Сочи.mp3")


class Env:
    def __init__(self, tmp_path: Path, size: int | None = None) -> None:
        self.downloads = tmp_path / "music" / "slsk"
        self.library = tmp_path / "music" / "library"
        self.downloads.mkdir(parents=True)
        self.library.mkdir(parents=True)
        self.size = size or (FIXTURES / "silence.mp3").stat().st_size
        self.world = FakeWorld(
            slskd_responses=[
                {
                    "username": "peer1",
                    "hasFreeUploadSlot": True,
                    "queueLength": 0,
                    "uploadSpeed": 2_000_000,
                    "files": [
                        {"filename": REMOTE, "size": self.size, "extension": "mp3", "bitRate": 320, "length": 199}
                    ],
                }
            ]
        )
        self.settings = dataclasses.replace(
            SETTINGS, slskd_downloads_dir=str(self.downloads), library_dir=str(self.library)
        )
        self.store = Store(str(tmp_path / "db.sqlite3"))
        self.clock = FakeClock()
        self.covers: list[str] = []
        self.worker = self.make_worker()
        self.request, _ = self.store.create(
            username="vasya",
            kind=RequestKind.TRACK,
            artist="Трофим",
            title="Город Сочи",
            album="Хиты",
            duration_ms=200_000,
            cover_url="https://img/cover.jpg",
        )

    def make_worker(self) -> Worker:
        """Новый воркер на той же БД — как после перезапуска контейнера."""
        http = self.world.client()

        async def fetch(url: str):
            self.covers.append(url)
            return COVER

        return Worker(
            self.store,
            self.settings,
            NavidromeClient(http, NAVIDROME, "admin", "adminpass"),
            SlskdClient(http, SLSKD, "slskd-key"),
            LidarrClient(http, LIDARR, "lidarr-key"),
            clock=self.clock,
            cover_fetcher=fetch,
        )

    def download_finished(self) -> Path:
        """slskd скачал файл: кладём его туда, куда кладёт slskd, и помечаем передачу успешной."""
        run_until(self.worker, self.clock, self.store, self.request.id, RequestStatus.DOWNLOADING)
        local = self.downloads / "Трофим" / "02 - Город Сочи.mp3"
        local.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(FIXTURES / "silence.mp3", local)
        self.world.set_transfer("peer1", REMOTE, "Completed, Succeeded", 100)
        return local

    def tick(self) -> None:
        self.clock.advance(6)
        asyncio.run(self.worker.tick())


def library_files(env: Env) -> list[Path]:
    return sorted(p.relative_to(env.library) for p in env.library.rglob("*") if p.is_file())


def test_track_is_placed_tagged_and_found(tmp_path):
    env = Env(tmp_path)
    local = env.download_finished()
    env.world.pending_library.append({"id": "nd-7", "title": "Город Сочи", "artist": "Трофим"})

    done = run_until(env.worker, env.clock, env.store, env.request.id, RequestStatus.COMPLETED)

    assert done.song_id == "nd-7"
    assert library_files(env) == [EXPECTED]
    audio = mutagen.File(env.library / EXPECTED, easy=True)
    assert (audio["artist"], audio["title"], audio["album"]) == (["Трофим"], ["Город Сочи"], ["Хиты"])
    assert env.covers == ["https://img/cover.jpg"]
    assert not local.exists()
    assert not local.parent.exists()  # пустая папка пира удалена
    assert done.state["relPath"] == str(EXPECTED)
    assert env.world.scans >= 1


def test_placing_shows_as_importing_for_clients(tmp_path):
    env = Env(tmp_path)
    env.download_finished()
    env.tick()  # downloading → placing
    current = env.store.get(env.request.id)
    assert current.status == RequestStatus.PLACING
    assert current.to_api().status == RequestStatus.IMPORTING


def test_restart_during_placing_does_not_duplicate(tmp_path, monkeypatch):
    env = Env(tmp_path)
    env.download_finished()
    real_place = worker_module.place_file
    calls = []

    def crash_after_rename(source, dest, tags, cover):
        calls.append(dest)
        real_place(source, dest, tags, cover)
        raise RuntimeError("контейнер перезапущен")

    monkeypatch.setattr(worker_module, "place_file", crash_after_rename)
    env.tick()  # → placing
    env.tick()  # копирует, «падает» после переименования
    assert len(calls) == 1
    assert env.store.get(env.request.id).status == RequestStatus.PLACING

    monkeypatch.setattr(worker_module, "place_file", real_place)
    env.worker = env.make_worker()
    env.clock.advance(60)
    importing = run_until(env.worker, env.clock, env.store, env.request.id, RequestStatus.IMPORTING)
    assert library_files(env) == [EXPECTED]
    assert importing.state["destPath"] == str(env.library / EXPECTED)


def test_restart_mid_copy_reuses_same_name(tmp_path, monkeypatch):
    env = Env(tmp_path)
    env.download_finished()

    def crash_mid_copy(source, dest, tags, cover):
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.with_name(f".{dest.name}.part").write_bytes(b"half")
        raise OSError("диск отвалился")

    monkeypatch.setattr(worker_module, "place_file", crash_mid_copy)
    env.tick()
    env.tick()
    monkeypatch.undo()
    env.worker = env.make_worker()
    env.clock.advance(60)
    run_until(env.worker, env.clock, env.store, env.request.id, RequestStatus.IMPORTING)
    names = [p.name for p in (env.library / EXPECTED.parent).iterdir() if not p.name.startswith(".")]
    assert names == [EXPECTED.name]


def test_local_file_not_found(tmp_path):
    env = Env(tmp_path)
    run_until(env.worker, env.clock, env.store, env.request.id, RequestStatus.DOWNLOADING)
    env.world.set_transfer("peer1", REMOTE, "Completed, Succeeded", 100)  # файла на диске нет
    failed = run_until(env.worker, env.clock, env.store, env.request.id, RequestStatus.FAILED)
    assert "не найден в папке загрузок" in failed.message
    assert library_files(env) == []


def test_keep_source_when_disabled(tmp_path):
    env = Env(tmp_path)
    env.settings = dataclasses.replace(env.settings, delete_source=False)
    env.worker = env.make_worker()
    local = env.download_finished()
    run_until(env.worker, env.clock, env.store, env.request.id, RequestStatus.IMPORTING)
    assert local.exists()
    assert library_files(env) == [EXPECTED]


@pytest.mark.parametrize("missing", ["slskd_downloads_dir", "library_dir"])
def test_without_dirs_old_behaviour(tmp_path, missing):
    env = Env(tmp_path)
    env.settings = dataclasses.replace(env.settings, **{missing: None})
    env.worker = env.make_worker()
    env.download_finished()
    env.tick()
    assert env.store.get(env.request.id).status == RequestStatus.IMPORTING
    assert library_files(env) == []
