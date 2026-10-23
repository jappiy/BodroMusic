"""Задание 024: «Похожие треки» в плеере — Last.fm с пометкой «есть в библиотеке» и похожие по звучанию."""

import asyncio

import httpx

from bodrod.metadata.lastfm import LastFmClient
from bodrod.metadata.similar import SimilarTracks
from bodrod.navidrome_db import NdSong


class FakeAnalysis:
    async def similar(self, song_id, limit):
        return ["s9", "s7"]


class FakeDb:
    def songs(self, ids):
        return [
            NdSong("s9", "Звезда", "Кино", "Альбом", None, 200.0, 1, "mp3", 320, "/m/1"),
            NdSong("s7", "Город", "Алиса", None, None, None, 1, "mp3", 320, "/m/2"),
        ]


def handler(request: httpx.Request) -> httpx.Response:
    assert request.url.params["method"] == "track.getSimilar"
    tracks = [{"name": "Город", "artist": {"name": "Алиса"}}, {"name": "Новое", "artist": {"name": "Сплин"}}]
    return httpx.Response(200, json={"similartracks": {"track": tracks}})


def test_similar_marks_library_and_adds_sound_neighbours():
    async def in_library(artist, title):
        return "s7" if title == "Город" else None

    lastfm = LastFmClient(httpx.AsyncClient(transport=httpx.MockTransport(handler)), "key", min_interval_s=0)
    service = SimilarTracks(lastfm, in_library, FakeAnalysis(), FakeDb())
    tracks = asyncio.run(service.similar("Кино", "Группа крови", "s1"))
    assert [(t.title, t.library_song_id, t.source) for t in tracks] == [
        ("Город", "s7", "lastfm"),
        ("Новое", None, "lastfm"),
        ("Звезда", "s9", "sound"),  # «Город» по звучанию — уже есть из Last.fm
    ]


def test_no_lastfm_no_analysis_gives_empty_list():
    assert asyncio.run(SimilarTracks(None, None).similar("Кино", "Звезда")) == []
