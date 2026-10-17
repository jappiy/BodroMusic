import asyncio
import json
import shutil

import httpx
import pytest

import bodrod.config as config
from bodrod.config import Settings
from bodrod.metadata.lookup import (
    ITunesLookup,
    MusicBrainzLookup,
    build_chain,
    chain_version,
    match_score,
)
from bodrod.models import RequestStatus
from bodrod.text import main_artist, same_artist, translit

from .test_worker_placement import FIXTURES, Env, run_until

ENV = {"NAVIDROME_URL": "http://navidrome:4533", "NAVIDROME_ADMIN_USER": "bodrod"}


def env(monkeypatch, tmp_path, **values):
    for key in list(config.os.environ):
        if key.startswith(("NAVIDROME_", "SLSKD_", "LIDARR_", "DISCOGS_", "YANDEX_")):
            monkeypatch.delenv(key, raising=False)
    monkeypatch.setattr(config, "SECRETS_DIR", str(tmp_path / "secrets"))
    for key, value in {**ENV, **values}.items():
        monkeypatch.setenv(key, value)


def test_admin_password_read_from_secrets_dir(monkeypatch, tmp_path):
    env(monkeypatch, tmp_path)
    with pytest.raises(RuntimeError):
        Settings.from_env()
    (tmp_path / "secrets").mkdir()
    (tmp_path / "secrets" / "navidrome_password").write_text("пароль-с-кириллицей\n", encoding="utf-8")
    assert Settings.from_env().navidrome_admin_password == "пароль-с-кириллицей"


def test_api_key_comment_is_stripped(monkeypatch, tmp_path):
    key_file = tmp_path / "slskd_key"
    key_file.write_text("slskd-key  # ключ из slskd.yml\n", encoding="utf-8")
    env(monkeypatch, tmp_path, NAVIDROME_ADMIN_PASSWORD="x", SLSKD_API_KEY_FILE=str(key_file))
    assert Settings.from_env().slskd_api_key == "slskd-key"
    env(monkeypatch, tmp_path, NAVIDROME_ADMIN_PASSWORD="x", SLSKD_API_KEY="ключ")
    with pytest.raises(RuntimeError, match="SLSKD_API_KEY"):
        Settings.from_env()


def test_cyrillic_request_downloads_and_places(tmp_path):
    """Заявка «ЙОП ШОУ — По Стингу»: кириллица в запросе, пути, имени файла и тегах — без ASCII-ошибок."""
    e = Env(tmp_path)
    remote = r"Музыка\ЙОП ШОУ\ЙОП ШОУ - По Стингу.mp3"
    e.world.slskd_responses[0]["files"][0]["filename"] = remote
    request, _ = e.store.create(
        username="vasya",
        kind=e.request.kind,
        artist="ЙОП ШОУ",
        title="По Стингу",
        album="Хиты",
        duration_ms=200_000,
        cover_url="https://img/обложка.jpg",
    )
    e.store.delete(e.request.id)
    downloading = run_until(e.worker, e.clock, e.store, request.id, RequestStatus.DOWNLOADING)
    search = next(iter(e.world.slskd_searches.values()))
    assert search["searchText"] == "йоп шоу по стингу"
    local = e.downloads / "ЙОП ШОУ" / "ЙОП ШОУ - По Стингу.mp3"
    local.parent.mkdir(parents=True)
    shutil.copyfile(FIXTURES / "silence.mp3", local)
    e.world.pending_library.append({"id": "nd-yop", "title": "По Стингу", "artist": "ЙОП ШОУ"})
    e.world.set_transfer("peer1", remote, "Completed, Succeeded", 100)
    done = run_until(e.worker, e.clock, e.store, request.id, RequestStatus.COMPLETED)
    assert done.song_id == "nd-yop" and done.errors == 0 and downloading.errors == 0
    assert (e.library / "ЙОП ШОУ" / "Хиты" / "ЙОП ШОУ - По Стингу.mp3").exists()


def test_match_score_translit_and_duration():
    assert match_score("Филипп Киркоров", "Луиза", 200, "Filipp Kirkorov", "Luiza", 201) >= 0.85
    assert match_score("Каста", "Вокруг шум", 230, "Каста feat. Хамиль", "Вокруг шум", 231) >= 0.85
    assert match_score("Каста", "Вокруг шум", 230, "Каста", "Вокруг шум", 240) == 0.0
    assert translit("Цой") == translit("Tsoy") == translit("Tsoi")
    assert main_artist("Anacondaz & Кис-кис") == "Anacondaz" and main_artist("Каста, Рем Дигга") == "Каста"
    assert same_artist("Заточка feat. Ёлка", "заточка") and not same_artist("Sting", "The Police")
    assert chain_version(["yandex", "deezer"]) != chain_version(["yandex", "deezer", "itunes"])


def test_itunes_and_musicbrainz_parsing():
    seen: list[httpx.Request] = []

    def handle(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        if request.url.host == "itunes.apple.com":
            return httpx.Response(
                200,
                json={
                    "results": [
                        {
                            "artistName": "Nirvana",
                            "trackName": "Rape Me",
                            "collectionName": "In Utero",
                            "trackTimeMillis": 170_000,
                            "artworkUrl100": "https://is1.mzstatic.com/a/100x100bb.jpg",
                        }
                    ]
                },
            )
        body = {
            "recordings": [
                {
                    "title": "Rape Me",
                    "length": 169_500,
                    "artist-credit": [{"name": "Nirvana"}],
                    "releases": [
                        {"id": "r-best", "title": "Nirvana", "release-group": {"id": "rg-best", "primary-type": "Album",
                                                                              "secondary-types": ["Compilation"]}},
                        {"id": "r-utero", "title": "In Utero", "status": "Official",
                         "release-group": {"id": "rg-utero", "primary-type": "Album"}},
                    ],
                }
            ]
        }  # fmt: skip
        return httpx.Response(200, text=json.dumps(body))

    http = httpx.AsyncClient(transport=httpx.MockTransport(handle))
    itunes = asyncio.run(ITunesLookup(http).lookup("Nirvana", "Rape Me", "In Utero", 170))[0]
    assert itunes.cover_url == "https://is1.mzstatic.com/a/1200x1200bb.jpg" and itunes.score == 1.0
    brainz = asyncio.run(MusicBrainzLookup(http, min_interval_s=0).lookup("Nirvana", "Rape Me", "In Utero", 170))[0]
    assert (
        brainz.album == "In Utero"
        and brainz.cover_url == "https://coverartarchive.org/release-group/rg-utero/front-1200"
    )
    assert seen[-1].headers["User-Agent"].startswith("bodrod/") and "github.com" in seen[-1].headers["User-Agent"]
    chain = build_chain(("yandex", "itunes", "discogs", "musicbrainz", "nonsense"), http, [], None)
    assert [p.name for p in chain] == ["itunes", "musicbrainz"]
