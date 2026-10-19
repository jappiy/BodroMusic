import asyncio

import httpx
import pytest

from bodrod.clients.navidrome import Credentials, NavidromeClient
from bodrod.config import Settings
from bodrod.maintenance import MaintenanceService, RunContext
from bodrod.metadata.discover import DiscoverService
from bodrod.metadata.lastfm import LastFmClient, LastFmError, LastFmLookup, LastFmProvider, LastFmUnavailable, image
from bodrod.metadata.lookup import build_chain
from bodrod.metadata.providers import DeezerProvider, YandexMusicProvider

from .fakes import NAVIDROME, FakeWorld

USER = Credentials.from_password("vasya", "secret")
COVER = "https://lastfm.freetls.fastly.net/i/u/300x300/abc.png"
PLACEHOLDER = "https://lastfm.freetls.fastly.net/i/u/300x300/2a96cbd8b46e442fc41c2b86b821562f.png"

TRACK_INFO = {
    "track": {
        "name": "Rape Me",
        "duration": "170000",
        "artist": {"name": "Nirvana"},
        "album": {"artist": "Nirvana", "title": "In Utero", "image": [{"#text": COVER, "size": "extralarge"}]},
    }
}


class FakeLastFm:
    def __init__(self, responses: dict[str, object] | None = None, down: bool = False) -> None:
        self.responses = responses or {}
        self.down = down
        self.calls: list[httpx.Request] = []

    def handle(self, request: httpx.Request) -> httpx.Response:
        self.calls.append(request)
        if self.down:
            return httpx.Response(503, text="Service Unavailable")
        body = self.responses.get(request.url.params["method"], {"error": 6, "message": "Track not found"})
        return httpx.Response(200, json=body)


def lastfm_client(fake: FakeLastFm, clock=None, **kwargs) -> LastFmClient:
    http = httpx.AsyncClient(transport=httpx.MockTransport(fake.handle))
    return LastFmClient(http, "key", min_interval_s=0, clock=clock or (lambda: 0.0), **kwargs)


def test_image_skips_placeholder_and_enlarges():
    assert image([{"#text": COVER}, {"#text": PLACEHOLDER}]) == "https://lastfm.freetls.fastly.net/i/u/770x0/abc.png"
    assert image([{"#text": PLACEHOLDER}, {"#text": ""}]) is None


def test_lookup_parses_track_info():
    fake = FakeLastFm({"track.getInfo": TRACK_INFO})
    match = asyncio.run(LastFmLookup(lastfm_client(fake)).lookup("Nirvana", "Rape Me", "In Utero", 171))[0]
    assert (match.artist, match.album, match.duration_s, match.score) == ("Nirvana", "In Utero", 170.0, 1.0)
    assert match.cover_url == "https://lastfm.freetls.fastly.net/i/u/770x0/abc.png"
    params = fake.calls[0].url.params
    assert params["api_key"] == "key" and params["autocorrect"] == "1" and params["format"] == "json"
    assert fake.calls[0].headers["User-Agent"].startswith("bodrod/")


def test_lookup_takes_album_cover_when_track_has_none():
    info = {"track": {**TRACK_INFO["track"], "album": {}}}
    album = {"album": {"name": "In Utero", "image": [{"#text": COVER}]}}
    fake = FakeLastFm({"track.getInfo": info, "album.getInfo": album})
    match = asyncio.run(LastFmLookup(lastfm_client(fake)).lookup("Nirvana", "Rape Me", "In Utero", None))[0]
    assert match.cover_url and match.album == "In Utero"


def test_not_found_is_empty_not_failure():
    client = lastfm_client(FakeLastFm())
    for _ in range(5):
        assert asyncio.run(LastFmLookup(client).lookup("Никто", "Ничего", None, None)) == []
    assert client.available


def test_breaker_opens_after_failures_and_recovers():
    now = [0.0]
    fake = FakeLastFm(down=True)
    client = lastfm_client(fake, clock=lambda: now[0], failures_to_trip=3, cooldown_s=300)
    for _ in range(3):
        with pytest.raises(LastFmError):
            asyncio.run(client.call("track.getInfo", artist="a", track="b"))
    assert not client.available
    with pytest.raises(LastFmUnavailable):
        asyncio.run(client.call("track.getInfo", artist="a", track="b"))
    assert len(fake.calls) == 3  # пока отключён — в сеть не ходим
    now[0] = 301
    fake.down = False
    fake.responses["track.getInfo"] = TRACK_INFO
    assert asyncio.run(client.call("track.getInfo", artist="a", track="b")) == TRACK_INFO


def test_rate_limit_error_counts_as_failure():
    fake = FakeLastFm({"track.getInfo": {"error": 29, "message": "Rate limit exceeded"}})
    client = lastfm_client(fake, failures_to_trip=1)
    with pytest.raises(LastFmError, match="Rate limit"):
        asyncio.run(client.call("track.getInfo", artist="a", track="b"))
    assert not client.available


def test_chain_includes_lastfm_only_with_key():
    http = httpx.AsyncClient(transport=httpx.MockTransport(FakeLastFm().handle))
    assert [p.name for p in build_chain(("itunes", "lastfm"), http, [], None)] == ["itunes"]
    chain = build_chain(("itunes", "lastfm"), http, [], None, LastFmClient(http, "key"))
    assert [p.name for p in chain] == ["itunes", "lastfm"]


def test_maintenance_skips_unavailable_lastfm_without_spending_budget():
    client = lastfm_client(FakeLastFm(down=True), failures_to_trip=1)
    with pytest.raises(LastFmError):
        asyncio.run(client.call("track.getInfo", artist="a", track="b"))
    service = MaintenanceService.__new__(MaintenanceService)
    ctx = RunContext(1, True, catalog_budget=5)
    assert asyncio.run(service._lookup(ctx, [LastFmLookup(client)], "Nirvana", "Rape Me", None, None)) is None
    assert ctx.catalog_budget == 5


def world_with_lastfm(world: FakeWorld, fake: FakeLastFm) -> httpx.AsyncClient:
    def handle(request: httpx.Request) -> httpx.Response:
        if request.url.host == "ws.audioscrobbler.com":
            return fake.handle(request)
        return world.handle(request)

    return httpx.AsyncClient(transport=httpx.MockTransport(handle))


def discover_service(world: FakeWorld, fake: FakeLastFm) -> DiscoverService:
    http = world_with_lastfm(world, fake)
    lastfm = LastFmClient(http, "key", min_interval_s=0)
    providers = [YandexMusicProvider(http, None), DeezerProvider(http), LastFmProvider(lastfm)]
    return DiscoverService(providers, NavidromeClient(http, NAVIDROME, "admin", "adminpass"))


def test_discover_falls_back_to_lastfm_when_others_fail():
    fake = FakeLastFm(
        {
            "track.search": {
                "results": {"trackmatches": {"track": [{"name": "Город Сочи", "artist": "Трофим", "url": "u1"}]}}
            },
            "artist.search": {"results": {"artistmatches": {"artist": []}}},
        }
    )
    world = FakeWorld(yandex_fails=True, library=[{"id": "nd-1", "title": "Город Сочи", "artist": "Трофим"}])
    result = asyncio.run(discover_service(world, fake).discover("город сочи", USER))
    assert result.source == "lastfm" and result.intent == "track"
    assert (result.tracks[0].artist, result.tracks[0].source_id) == ("Трофим", "u1")
    assert result.tracks[0].library_song_id == "nd-1"


def test_artist_popular_from_lastfm():
    fake = FakeLastFm(
        {
            "track.search": {"results": {"trackmatches": {"track": []}}},
            # Один исполнитель Last.fm отдаёт объектом, а не списком.
            "artist.search": {"results": {"artistmatches": {"artist": {"name": "Трофим"}}}},
            "artist.getTopTracks": {
                "toptracks": {
                    "track": [
                        {"name": "Снегири", "artist": {"name": "Трофим"}, "mbid": "m1"},
                        {"name": "Город Сочи", "artist": {"name": "Трофим"}, "url": "u2"},
                    ]
                }
            },
        }
    )
    world = FakeWorld(yandex_fails=True)
    popular = asyncio.run(discover_service(world, fake).artist_popular("Трофим", USER))
    assert popular.source == "lastfm" and [t.title for t in popular.tracks] == ["Снегири", "Город Сочи"]
    assert [t.source_id for t in popular.tracks] == ["m1", "u2"]


def test_discover_works_when_lastfm_down():
    world = FakeWorld(
        yandex_search={"tracks": {"results": []}, "artists": {"results": []}},
        deezer_tracks=[{"id": 9, "title": "Город Сочи", "duration": 201, "artist": {"name": "Трофим"}, "album": {}}],
    )
    result = asyncio.run(discover_service(world, FakeLastFm(down=True)).discover("город сочи", USER))
    assert result.source == "deezer"


def test_settings_lastfm_key_from_secrets(monkeypatch, tmp_path):
    import bodrod.config as config

    for key in list(config.os.environ):
        if key.startswith(("NAVIDROME_", "LASTFM_", "DISCOVER_", "COVER_", "TAG_")):
            monkeypatch.delenv(key, raising=False)
    monkeypatch.setattr(config, "SECRETS_DIR", str(tmp_path))
    monkeypatch.setenv("NAVIDROME_URL", "http://navidrome:4533")
    monkeypatch.setenv("NAVIDROME_ADMIN_USER", "bodrod")
    monkeypatch.setenv("NAVIDROME_ADMIN_PASSWORD", "x")
    assert Settings.from_env().lastfm_api_key is None
    (tmp_path / "lastfm_api_key").write_text("abc123  # ключ last.fm\n", encoding="utf-8")
    settings = Settings.from_env()
    assert settings.lastfm_api_key == "abc123"
    assert settings.discover_providers[-1] == settings.cover_providers[-1] == settings.tag_providers[-1] == "lastfm"
