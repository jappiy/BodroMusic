"""Цепочка источников загрузки: Яндекс Музыка → slskd → YouTube Music → Lidarr."""

import asyncio
import dataclasses
import shutil
from pathlib import Path

import mutagen

from bodrod.clients.lidarr import LidarrClient
from bodrod.clients.navidrome import NavidromeClient
from bodrod.clients.slskd import SlskdClient
from bodrod.db import Store
from bodrod.models import RequestKind, RequestStatus
from bodrod.sources.base import SourceMiss, track_score, unwanted_extras
from bodrod.sources.export import YandexExport
from bodrod.sources.placer import Placer
from bodrod.sources.registry import SourceRegistry
from bodrod.sources.wanted import WANTED_USER, LidarrWantedSync
from bodrod.sources.yandex import YAlbum, YandexMetadata, YandexSource, YDownload, YTrack
from bodrod.sources.youtube import YouTubeSource, YtAlbum, YtSong
from bodrod.worker import Worker

from .fakes import LIDARR, NAVIDROME, SLSKD, FakeClock, FakeWorld
from .test_maintenance import flac_bytes
from .test_worker import SETTINGS, slskd_peer

FIXTURES = Path(__file__).parent / "fixtures"
COVER = ((FIXTURES / "cover.jpg").read_bytes(), "image/jpeg")


def ytrack(track_id: str, title: str, artist: str = "Трофим", seconds: int = 200, **extra) -> YTrack:
    defaults = {
        "album": "Хиты",
        "album_id": "a1",
        "album_artist": artist,
        "year": 2001,
        "genre": "шансон",
        "track_no": int(track_id[-1]) if track_id[-1].isdigit() else 1,
        "disc_no": 1,
        "cover_url": "https://avatars.yandex.net/cover/1000x1000",
        "artist_ids": ["ar1"],
    }
    return YTrack(id=track_id, title=title, artists=[artist], duration_ms=seconds * 1000, **(defaults | extra))


class FakeYandexApi:
    def __init__(self, tracks: list[YTrack], plus: bool = True, token: bool = True) -> None:
        self.tracks = tracks
        self.plus = plus
        self.token = token
        self.albums: dict[str, list[YTrack]] = {}
        self.album_list: list[YAlbum] = []
        self.artists: list[tuple[str, str]] = []
        self.artist_albums_map: dict[str, list[YAlbum]] = {}
        """id трека → длительность скачанного файла (меньше — превью)."""
        self.file_seconds: dict[str, float] = {}
        self.lossless = True
        self.downloads: list[str] = []
        self.likes: list[YTrack] = []
        self.playlist_list: list[tuple[str, list[YTrack]]] = []
        self.plus_checks = 0

    @property
    def has_token(self) -> bool:
        return self.token

    async def has_plus(self) -> bool:
        self.plus_checks += 1
        return self.plus

    async def search_tracks(self, text: str) -> list[YTrack]:
        return list(self.tracks)

    async def search_albums(self, text: str) -> list[YAlbum]:
        return list(self.album_list)

    async def search_artists(self, text: str) -> list[tuple[str, str]]:
        return list(self.artists)

    async def album_tracks(self, album_id: str) -> list[YTrack]:
        return list(self.albums.get(album_id, []))

    async def artist_albums(self, artist_id: str) -> list[YAlbum]:
        return list(self.artist_albums_map.get(artist_id, []))

    async def download(self, track_id: str, dest_stem: Path) -> YDownload:
        self.downloads.append(track_id)
        track = next(t for group in [self.tracks, *self.albums.values(), self.likes] for t in group if t.id == track_id)
        seconds = self.file_seconds.get(track_id, (track.duration_ms or 0) / 1000)
        if self.lossless:
            path = dest_stem.with_suffix(".flac")
            path.write_bytes(flac_bytes(seconds))
            return YDownload(path, "flac", None)
        path = dest_stem.with_suffix(".m4a")
        shutil.copy(FIXTURES / "silence.m4a", path)
        return YDownload(path, "aac", 256)

    async def lyrics(self, track_id: str) -> str | None:
        return "Текст песни"

    async def liked_tracks(self) -> list[YTrack]:
        return list(self.likes)

    async def playlists(self) -> list[tuple[str, list[YTrack]]]:
        return list(self.playlist_list)


class FakeYtMusic:
    def __init__(self, songs: list[YtSong]) -> None:
        self.songs = songs

    async def search_songs(self, query: str) -> list[YtSong]:
        return list(self.songs)

    async def search_albums(self, query: str) -> list[YtAlbum]:
        return []

    async def search_artist(self, name: str) -> list[tuple[str, str]]:
        return []

    async def album_songs(self, browse_id: str) -> list[YtSong]:
        return []

    async def artist_albums(self, channel_id: str) -> list[YtAlbum]:
        return []


class FakeYtDlp:
    def __init__(self) -> None:
        self.downloads: list[str] = []
        self.updates = 0

    async def version(self) -> str | None:
        return "2026.08.19"

    async def download(self, video_id: str, dest_stem: Path) -> Path:
        self.downloads.append(video_id)
        dest_stem.parent.mkdir(parents=True, exist_ok=True)
        path = dest_stem.with_suffix(".opus")
        shutil.copy(FIXTURES / "silence.opus", path)
        return path

    async def update(self) -> str | None:
        self.updates += 1
        return "2026.09.01"


class Env:
    def __init__(
        self,
        tmp_path: Path,
        yandex: FakeYandexApi | None = None,
        songs: list[YtSong] | None = None,
        world: FakeWorld | None = None,
    ) -> None:
        self.library = tmp_path / "music" / "library"
        self.library.mkdir(parents=True)
        (tmp_path / "music" / "slsk").mkdir()
        self.settings = dataclasses.replace(
            SETTINGS,
            library_dir=str(self.library),
            data_dir=str(tmp_path / "data"),
            slskd_downloads_dir=str(tmp_path / "music" / "slsk"),
        )
        self.world = world or FakeWorld()
        self.store = Store(str(tmp_path / "db.sqlite3"))
        self.clock = FakeClock()
        self.yandex_api = yandex or FakeYandexApi([], plus=False)
        self.ytmusic = FakeYtMusic(songs or [])
        self.ytdlp = FakeYtDlp()
        self.covers: list[str] = []
        http = self.world.client()
        self.navidrome = NavidromeClient(http, NAVIDROME, "admin", "adminpass")
        self.lidarr = LidarrClient(http, LIDARR, "lidarr-key")
        direct: dict = {}
        self.registry = SourceRegistry(self.store, self.settings, direct, True, True)
        self.yandex = YandexSource(self.yandex_api, True, clock=self.clock)
        # Подделка yt-dlp отдаёт 0,3 с тишины — проверку на превью для YouTube отключаем.
        self.youtube = YouTubeSource(self.ytmusic, self.ytdlp, True, preview_ratio=0.0, auto_update=False)
        direct.update({"yandex": self.yandex, "youtube": self.youtube})

        async def fetch_cover(url: str):
            self.covers.append(url)
            return COVER

        self.placer = Placer(
            self.settings, self.store, fetch_cover, YandexMetadata(self.yandex_api, self.registry.yandex_metadata)
        )
        self.worker = Worker(
            self.store,
            self.settings,
            self.navidrome,
            SlskdClient(http, SLSKD, "slskd-key"),
            self.lidarr,
            clock=self.clock,
            cover_fetcher=fetch_cover,
            registry=self.registry,
            placer=self.placer,
        )

    def add(self, kind: RequestKind = RequestKind.TRACK, artist: str = "Трофим", title: str | None = "Город Сочи",
            album: str | None = None, duration_ms: int | None = 200_000):  # fmt: skip
        request, created = self.store.create(
            username="vasya",
            kind=kind,
            artist=artist,
            title=title,
            album=album,
            duration_ms=duration_ms,
            cover_url=None,
        )
        assert created
        return request

    async def drive(self, request_id: str, until, steps: int = 60):
        """Шаги воркера в одном цикле событий (фоновые загрузки — задачи этого цикла)."""
        wanted = until if callable(until) else (lambda r: r.status == until)
        for _ in range(steps):
            await self.worker.tick()
            jobs = list(self.worker._jobs.values())
            if jobs:
                await asyncio.wait(jobs, timeout=10)
            current = self.store.get(request_id)
            if wanted(current):
                return current
            self.clock.advance(6)
        raise AssertionError(f"Заявка не дошла до нужного состояния: {self.store.get(request_id)}")

    def run(self, request_id: str, until, steps: int = 60):
        return asyncio.run(self.drive(request_id, until, steps))

    def library_files(self) -> list[Path]:
        return sorted(p for p in self.library.rglob("*") if p.is_file())


# region Сопоставление


def test_unwanted_versions_only_when_not_requested():
    assert unwanted_extras("Город Сочи (Remix)", "Город Сочи") == ["remix"]
    assert unwanted_extras("Город Сочи (Live)", "Город Сочи live") == []
    assert unwanted_extras("Город Сочи", "Город Сочи") == []
    assert unwanted_extras("Song (Official Video)", "Song") == ["video"]


def test_track_score_duration_and_normalization():
    assert track_score("Трофим", "Город Сочи", 200_000, "трофим", "Город Сочи (feat. Х)", 202_000) >= 0.85
    assert track_score("Трофим", "Город Сочи", 200_000, "Трофим", "Город Сочи", 204_000) == 0.0
    assert track_score("Ёлка", "Прованс", None, "Елка", "Прованс", 180_000) >= 0.85


# endregion

# region Яндекс


def test_yandex_first_downloads_flac_with_catalog_tags(tmp_path):
    api = FakeYandexApi([ytrack("y2", "Город Сочи")])
    env = Env(tmp_path, api)
    env.world.pending_library.append({"id": "nd-1", "title": "Город Сочи", "artist": "Трофим"})
    request = env.add()
    done = env.run(request.id, RequestStatus.COMPLETED)
    assert done.song_id == "nd-1"
    assert done.source_label == "Яндекс Музыка, FLAC"
    assert [(a["source"], a["result"]) for a in done.attempts] == [("yandex", "ok")]
    assert env.world.slskd_searches == {}  # до Soulseek дело не дошло
    (path,) = env.library_files()
    assert path.relative_to(env.library) == Path("Трофим/Хиты/Трофим - Город Сочи.flac")
    tags = mutagen.File(path)
    assert tags["album"] == ["Хиты"] and tags["tracknumber"] == ["2"] and tags["date"] == ["2001"]
    assert tags["lyrics"] == ["Текст песни"] and tags["genre"] == ["шансон"]
    assert tags.pictures and env.covers == ["https://avatars.yandex.net/cover/1000x1000"]
    row = env.store.library_file(str(path))
    assert row["source"] == "yandex" and row["lossy"] == 0 and row["source_id"] == "y2"
    assert not any((Path(env.settings.staging_dir)).rglob("*.flac"))  # временные файлы убраны


def test_yandex_aac_when_no_lossless_marked_lossy(tmp_path):
    api = FakeYandexApi([ytrack("y1", "Город Сочи", seconds=0)])
    api.lossless = False
    env = Env(tmp_path, api)
    request = env.add(duration_ms=None)
    done = env.run(request.id, RequestStatus.IMPORTING)
    assert done.source_label == "Яндекс Музыка, AAC 256"
    (path,) = env.library_files()
    assert path.suffix == ".m4a" and env.store.library_file(str(path))["lossy"] == 1


def test_no_plus_makes_yandex_unavailable_and_chain_goes_to_slskd(tmp_path):
    api = FakeYandexApi([ytrack("y1", "Город Сочи")], plus=False)
    env = Env(tmp_path, api, world=FakeWorld(slskd_responses=[slskd_peer("p", r"M\Трофим - Город Сочи.mp3")]))
    request = env.add()
    current = env.run(request.id, RequestStatus.DOWNLOADING)
    assert current.source == "slskd"
    assert current.attempts[0] | {"at": 0} == {
        "source": "yandex", "title": "Яндекс Музыка", "result": "skip", "reason": "Нет подписки Плюс", "at": 0
    }  # fmt: skip
    assert api.downloads == []
    status = asyncio.run(env.registry.describe())
    yandex = next(s for s in status.sources if s.name == "yandex")
    assert yandex.state == "unavailable" and yandex.reason == "Нет подписки Плюс" and yandex.enabled


def test_subscription_checked_once_a_day(tmp_path):
    api = FakeYandexApi([], plus=True)
    env = Env(tmp_path, api)
    for _ in range(3):
        assert asyncio.run(env.yandex.status()).available
    assert api.plus_checks == 1
    env.clock.advance(24 * 3600 + 1)
    api.plus = False
    assert asyncio.run(env.yandex.status()).reason == "Нет подписки Плюс"
    assert api.plus_checks == 2


def test_preview_is_deleted_and_next_source_used(tmp_path):
    api = FakeYandexApi([ytrack("y1", "Город Сочи")])
    api.file_seconds["y1"] = 30  # превью 30 с вместо 200
    song = YtSong("v1", "Город Сочи", ["Трофим"], album="Хиты", duration_s=200)
    env = Env(tmp_path, api, songs=[song])
    env.registry.update(None, {"slskd": False}, False)
    request = env.add()
    done = env.run(request.id, RequestStatus.IMPORTING)
    yandex_attempt = done.attempts[0]
    assert yandex_attempt["result"] == "fail" and "превью" in yandex_attempt["reason"]
    assert done.attempts[1]["result"] == "skip" and done.attempts[1]["reason"] == "Выключен"
    assert done.source_label == "YouTube Music, Opus"
    (path,) = env.library_files()
    assert path.suffix == ".opus" and env.store.library_file(str(path))["lossy"] == 1


def test_no_confident_match_is_a_miss(tmp_path):
    api = FakeYandexApi([ytrack("y1", "Город Сочи", seconds=230), ytrack("y2", "Город Сочи (Remix)")])
    env = Env(tmp_path, api)
    env.registry.update(None, {"slskd": False, "youtube": False}, None)
    request = env.add()
    queued = env.run(request.id, lambda r: r.status == RequestStatus.QUEUED and r.attempts)
    assert queued.attempts[0]["reason"] == "Нет уверенного совпадения в Яндекс Музыке"
    assert "повторим через" in queued.message
    assert api.downloads == []


# endregion

# region YouTube и порядок


def test_order_and_youtube_skips_videos_and_retags_by_yandex(tmp_path):
    api = FakeYandexApi([ytrack("y1", "Город Сочи", album="Настоящий альбом")])
    songs = [
        YtSong("clip", "Город Сочи", ["Трофим"], duration_s=200, result_type="video"),
        YtSong("remix", "Город Сочи (Remix)", ["Трофим"], duration_s=200),
        YtSong("good", "Город Сочи", ["Трофим"], album="YT", duration_s=201),
    ]
    env = Env(tmp_path, api, songs=songs)
    env.registry.update(["youtube", "yandex", "slskd", "lidarr"], None, True)
    request = env.add()
    done = env.run(request.id, RequestStatus.IMPORTING)
    assert env.ytdlp.downloads == ["good"] and api.downloads == []
    assert done.attempts[0]["source"] == "youtube"
    (path,) = env.library_files()
    tags = mutagen.File(path)
    # «Яндекс: метаданные» включены — теги и текст из Яндекса.
    assert tags["album"] == ["Настоящий альбом"] and tags["lyrics"] == ["Текст песни"]


def test_yandex_metadata_switch_off_keeps_source_tags(tmp_path):
    api = FakeYandexApi([ytrack("y1", "Город Сочи", album="Настоящий альбом")], plus=False)
    env = Env(tmp_path, api, songs=[YtSong("good", "Город Сочи", ["Трофим"], album="YT", duration_s=200)])
    env.registry.update(None, {"slskd": False}, False)
    request = env.add()
    env.run(request.id, RequestStatus.IMPORTING)
    (path,) = env.library_files()
    assert mutagen.File(path)["album"] == ["YT"]


def test_registry_order_and_switches_persist(tmp_path):
    env = Env(tmp_path)
    assert env.registry.order() == ["yandex", "slskd", "youtube", "lidarr"]
    env.registry.update(["lidarr", "yandex"], {"youtube": False}, False)
    again = SourceRegistry(env.store, env.settings, {}, True, True)
    assert again.order() == ["lidarr", "yandex", "slskd", "youtube"]
    assert not again.is_enabled("youtube") and again.is_enabled("slskd")
    assert not again.yandex_metadata()
    assert again.chain_for(RequestKind.TRACK) == ["yandex", "slskd", "youtube"]
    assert again.chain_for(RequestKind.ALBUM) == ["lidarr", "yandex", "youtube"]


def test_nothing_usable_fails_with_reasons(tmp_path):
    env = Env(tmp_path)
    env.registry.update(None, {"slskd": False, "youtube": False}, None)
    request = env.add()
    failed = env.run(request.id, RequestStatus.FAILED)
    assert failed.message.startswith("Нет доступных источников.")
    assert "Яндекс Музыка: Нет подписки Плюс" in failed.message and "Soulseek: Выключен" in failed.message


# endregion

# region Альбом и исполнитель


def album_api() -> FakeYandexApi:
    api = FakeYandexApi([])
    api.album_list = [YAlbum("a1", "Хиты", ["Трофим"], 2001, 3), YAlbum("a2", "Хиты (Live)", ["Трофим"])]
    api.albums["a1"] = [ytrack(f"t{i}", f"Песня {i}", track_no=i) for i in (1, 2, 3)]
    return api


def test_album_downloaded_whole_skipping_existing(tmp_path):
    api = album_api()
    world = FakeWorld(library=[{"id": "nd-2", "title": "Песня 2", "artist": "Трофим"}])
    env = Env(tmp_path, api, world=world)
    request = env.add(RequestKind.ALBUM, title=None, album="Хиты", duration_ms=None)
    done = env.run(request.id, RequestStatus.COMPLETED)
    assert done.message == "Скачано 2 треков, 1 уже были"
    assert api.downloads == ["t1", "t3"]
    assert [p.name for p in env.library_files()] == ["Трофим - Песня 1.flac", "Трофим - Песня 3.flac"]


def test_album_falls_back_to_lidarr_last(tmp_path):
    world = FakeWorld()
    world.lidarr_albums = [
        {"id": 50, "artistId": 1, "title": "Хиты", "statistics": {"trackFileCount": 0, "totalTrackCount": 10}}
    ]
    env = Env(tmp_path, FakeYandexApi([], plus=True), world=world)
    request = env.add(RequestKind.ALBUM, title=None, album="Хиты", duration_ms=None)
    current = env.run(request.id, lambda r: r.source == "lidarr" and r.status == RequestStatus.DOWNLOADING)
    assert [(a["source"], a["result"]) for a in current.attempts] == [("yandex", "fail"), ("youtube", "fail")]
    assert world.lidarr_artists[0]["addOptions"] == {"monitor": "none", "searchForMissingAlbums": False}
    assert world.lidarr_monitored_albums == [50]
    assert {"name": "AlbumSearch", "albumIds": [50]} in world.lidarr_commands
    world.lidarr_albums[0]["statistics"]["trackFileCount"] = 10
    done = env.run(request.id, RequestStatus.COMPLETED, steps=40)
    assert done.source_label == "Lidarr" and done.message == "Скачано 10 из 10 треков"


def test_artist_via_yandex_then_followed_in_lidarr_for_new_releases(tmp_path):
    api = album_api()
    api.artists = [("ar1", "Трофим")]
    api.artist_albums_map["ar1"] = [YAlbum("a1", "Хиты", ["Трофим"])]
    env = Env(tmp_path, api)
    request = env.add(RequestKind.ARTIST, title=None, duration_ms=None)
    done = env.run(request.id, RequestStatus.COMPLETED)
    assert done.message == "Скачано 3 треков" and done.source_label == "Яндекс Музыка, FLAC"
    added = env.world.lidarr_artists[0]
    assert added["addOptions"] == {"monitor": "future", "searchForMissingAlbums": False}
    assert {"name": "RefreshArtist", "artistId": 1} in env.world.lidarr_commands


def test_restart_resumes_and_skips_placed_tracks(tmp_path):
    api = album_api()
    env = Env(tmp_path, api)
    request = env.add(RequestKind.ALBUM, title=None, album="Хиты", duration_ms=None)

    async def interrupted():
        await env.worker.tick()
        await asyncio.sleep(0)
        # «Перезапуск»: задача загрузки потеряна, один файл уже положен.
        for task in env.worker._jobs.values():
            task.cancel()
        env.worker._jobs.clear()

    first = api.albums["a1"][0]
    file = asyncio.run(env.yandex.download_track(first, tmp_path / "stage"))
    asyncio.run(env.placer.place(file, "yandex", request.id))
    asyncio.run(interrupted())
    api.downloads.clear()
    done = env.run(request.id, RequestStatus.COMPLETED)
    assert api.downloads == ["t2", "t3"]
    assert "1 уже были" in done.message


# endregion

# region «Метки» Lidarr


def test_wanted_albums_go_through_chain_without_lidarr(tmp_path):
    world = FakeWorld()
    artist = {"artistName": "Трофим"}
    world.lidarr_wanted = [
        {"id": 7, "title": "Хиты", "artistId": 3, "releaseDate": "2001-05-01T00:00:00Z", "artist": artist},
        {"id": 8, "title": "Будущий", "artistId": 3, "releaseDate": "2999-01-01", "artist": artist},
    ]
    env = Env(tmp_path, album_api(), world=world)
    sync = LidarrWantedSync(env.store, env.settings, env.lidarr, env.worker)
    assert asyncio.run(sync.sync()) == 1
    assert asyncio.run(sync.sync()) == 0  # повторно тот же альбом не берётся
    (request,) = env.store.list_for_user(WANTED_USER)
    assert request.kind == RequestKind.ALBUM and request.album == "Хиты"
    done = env.run(request.id, RequestStatus.COMPLETED)
    assert "lidarr" not in done.chain
    assert {"name": "RefreshArtist", "artistId": 3} in world.lidarr_commands


def test_wanted_not_found_left_to_lidarr_without_retry(tmp_path):
    world = FakeWorld()
    world.lidarr_wanted = [{"id": 9, "title": "Редкий", "artistId": 3, "artist": {"artistName": "Трофим"}}]
    env = Env(tmp_path, FakeYandexApi([], plus=True), world=world)
    sync = LidarrWantedSync(env.store, env.settings, env.lidarr, env.worker)
    asyncio.run(sync.sync())
    (request,) = env.store.list_for_user(WANTED_USER)
    failed = env.run(request.id, RequestStatus.FAILED)
    assert failed.message.startswith("Не нашли — оставлено Lidarr.")


# endregion

# region Массовая выгрузка


def test_export_likes_playlists_resume_and_m3u(tmp_path):
    api = FakeYandexApi([], plus=True)
    likes = [ytrack(f"l{i}", f"Лайк {i}", artist="Ёлка" if i == 3 else "Трофим") for i in (1, 2, 3)]
    likes[2].artist_ids = ["ar2"]
    api.likes = likes
    api.playlist_list = [("Дорога", [likes[0], likes[1]])]
    world = FakeWorld(library=[{"id": "nd", "title": "Лайк 2", "artist": "Трофим"}])
    env = Env(tmp_path, api, world=world)
    export = YandexExport(env.store, env.settings, env.yandex, env.placer, env.worker, env.navidrome)

    async def ticks(n: int, target: YandexExport) -> None:
        for _ in range(n):
            await target._tick()

    asyncio.run(export.start(likes=True, playlists=True, artists=True))
    asyncio.run(ticks(2, export))  # сбор + первый трек
    status = export.status()
    assert status.state == "running" and status.counts.total == 3 and status.counts.done == 1
    assert status.artists_total == 2
    # «Перезапуск»: новый объект на той же БД продолжает с места.
    again = YandexExport(env.store, env.settings, env.yandex, env.placer, env.worker, env.navidrome)
    asyncio.run(ticks(5, again))
    status = again.status()
    assert status.state == "finished"
    assert (status.counts.done, status.counts.skipped, status.counts.failed) == (2, 1, 0)
    assert api.downloads == ["l1", "l3"]
    playlist = (env.library / "Плейлисты Яндекса" / "Дорога.m3u8").read_text(encoding="utf-8").splitlines()
    assert playlist[0] == "#EXTM3U" and playlist[1].endswith("Трофим - Лайк 1.flac")


def test_export_waits_without_plus_and_collects_selected_artists(tmp_path):
    api = FakeYandexApi([], plus=False)
    api.likes = [ytrack("l1", "Лайк 1")]
    api.artist_albums_map["ar1"] = [YAlbum("a1", "Хиты", ["Трофим"])]
    api.albums["a1"] = [ytrack("t1", "Песня 1")]
    env = Env(tmp_path, api)
    export = YandexExport(env.store, env.settings, env.yandex, env.placer, env.worker, env.navidrome)
    asyncio.run(export.start(likes=True, playlists=False, artists=True))
    asyncio.run(export._tick())
    assert export.status().message == "Ждём: Яндекс недоступен — Нет подписки Плюс"
    api.plus = False
    env.clock.advance(25 * 3600)
    api.plus = True
    asyncio.run(export.refresh_artists())
    export.select_artists(["ar1"])
    for _ in range(5):
        asyncio.run(export._tick())
    assert sorted(api.downloads) == ["l1", "t1"]


# endregion


def test_source_miss_reason():
    assert SourceMiss("нет").reason == "нет"


# region API


def test_sources_api_admin_only_and_updates(tmp_path):
    from .test_api import auth
    from .test_cleanup import make_client

    client, services, _, _ = make_client(FakeWorld(), tmp_path)
    with client:
        assert client.get("/api/v1/sources", headers=auth("masha", "masha")).status_code in (401, 403)
        body = client.get("/api/v1/sources", headers=auth()).json()
        assert [s["name"] for s in body["sources"]] == ["yandex", "slskd", "youtube", "lidarr"]
        yandex = body["sources"][0]
        assert yandex["state"] == "unavailable" and yandex["reason"] == "Не задан токен YANDEX_MUSIC_TOKEN"
        assert body["sources"][1]["state"] == "ok" and body["yandexMetadata"] is True
        updated = client.put(
            "/api/v1/sources",
            json={"order": ["lidarr", "slskd"], "enabled": {"slskd": False}, "yandexMetadata": False},
            headers=auth(),
        ).json()
        assert [s["name"] for s in updated["sources"]] == ["lidarr", "slskd", "yandex", "youtube"]
        assert updated["sources"][1]["state"] == "disabled" and updated["yandexMetadata"] is False
        assert client.put("/api/v1/sources", json={"order": ["napster"]}, headers=auth()).status_code == 422
        info = client.get("/api/v1/info", headers=auth()).json()
        assert info["sources"] is True and info["trackDownloads"] is False and info["albumDownloads"] is True
        # Треки качать нечем (slskd выключен, Яндекса и YouTube нет), альбом уйдёт в Lidarr.
        track = {"kind": "track", "artist": "Трофим", "title": "Город Сочи"}
        assert client.post("/api/v1/requests", json=track, headers=auth()).status_code == 409
        album = client.post(
            "/api/v1/requests", json={"kind": "album", "artist": "Трофим", "album": "Хиты"}, headers=auth()
        )
        assert album.status_code == 201 and album.json()["kind"] == "album"
        assert (
            client.post("/api/v1/requests", json={"kind": "album", "artist": "Трофим"}, headers=auth()).status_code
            == 422
        )
        assert client.get("/api/v1/yandex/export", headers=auth()).status_code == 409  # нет LIBRARY_DIR


def test_request_out_has_source_and_attempts(tmp_path):
    api = FakeYandexApi([ytrack("y2", "Город Сочи")])
    env = Env(tmp_path, api)
    request = env.add()
    done = env.run(request.id, RequestStatus.IMPORTING)
    out = done.to_api().model_dump(by_alias=True)
    assert out["source"] == "Яндекс Музыка, FLAC"
    assert out["attempts"][0]["title"] == "Яндекс Музыка" and out["attempts"][0]["result"] == "ok"


# endregion
