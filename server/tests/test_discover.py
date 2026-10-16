import asyncio

from bodrod.clients.navidrome import Credentials, NavidromeClient
from bodrod.metadata.discover import DiscoverService
from bodrod.metadata.providers import DeezerProvider, YandexMusicProvider

from .fakes import NAVIDROME, FakeWorld, yandex_track


def service(world: FakeWorld) -> DiscoverService:
    http = world.client()
    navidrome = NavidromeClient(http, NAVIDROME, "admin", "adminpass")
    return DiscoverService([YandexMusicProvider(http, None), DeezerProvider(http)], navidrome)


USER = Credentials.from_password("vasya", "secret")


def test_track_query_understands_artist_by_title():
    world = FakeWorld(
        yandex_search={
            "best": {"type": "track"},
            "tracks": {
                "results": [
                    yandex_track(2, "Город Сочи (кавер)", "Кто-то"),
                    yandex_track(1, "Город Сочи", "Трофим"),
                ]
            },
            "artists": {"results": [{"id": 5, "name": "Сочи"}]},
        }
    )
    result = asyncio.run(service(world).discover("город сочи", USER))
    assert result.intent == "track"
    assert result.source == "yandex"
    first = result.tracks[0]
    assert (first.artist, first.title) == ("Трофим", "Город Сочи")
    assert first.cover_url == "https://avatars.yandex.net/get-music-content/abc/400x400"
    assert first.duration_ms == 200_000


def test_artist_query_returns_top_tracks_and_marks_library():
    world = FakeWorld(
        yandex_search={
            "best": {"type": "artist"},
            "tracks": {"results": [yandex_track(1, "Город Сочи", "Трофим")]},
            "artists": {"results": [{"id": 77, "name": "Трофим", "cover": {"uri": "avatars/%%"}}]},
        },
        yandex_artist_tracks=[yandex_track(1, "Город Сочи", "Трофим"), yandex_track(3, "Снегири", "Трофим")],
        library=[{"id": "nd-1", "title": "Снегири", "artist": "Трофим"}],
    )
    result = asyncio.run(service(world).discover("трофим", USER))
    assert result.intent == "artist"
    assert result.artist is not None and result.artist.name == "Трофим"
    assert [t.title for t in result.top_tracks] == ["Город Сочи", "Снегири"]
    assert [t.library_song_id for t in result.top_tracks] == [None, "nd-1"]


def test_falls_back_to_deezer():
    world = FakeWorld(
        yandex_fails=True,
        deezer_tracks=[
            {
                "id": 9,
                "title": "Город Сочи",
                "duration": 201,
                "artist": {"name": "Трофим"},
                "album": {"title": "A", "cover_medium": "https://cdn/c.jpg"},
            }
        ],
    )
    result = asyncio.run(service(world).discover("город сочи", USER))
    assert result.source == "deezer"
    assert result.tracks[0].duration_ms == 201_000


def test_nothing_found():
    world = FakeWorld(yandex_search={})
    result = asyncio.run(service(world).discover("абракадабра", USER))
    assert result.intent == "none"
