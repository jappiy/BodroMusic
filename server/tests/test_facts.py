"""Задание 022: факт о треке или исполнителе из Last.fm для главной «Моя волна»."""

import asyncio

import httpx

from bodrod.metadata.facts import ArtistFacts, first_sentence
from bodrod.metadata.lastfm import LastFmClient

BIO = (
    "Кино — советская рок-группа, основанная в 1981 году. Лидер — Виктор Цой. "
    '<a href="https://www.last.fm/music/Kino">Read more on Last.fm</a>'
)


def test_first_sentence_strips_html_and_link():
    assert first_sentence(BIO) == "Кино — советская рок-группа, основанная в 1981 году."
    assert first_sentence(None) is None
    assert first_sentence('<a href="x">Read more on Last.fm</a>') is None
    long = "слово " * 60
    assert first_sentence(long).endswith("…") and len(first_sentence(long)) <= 181


def make(handler):
    http = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return ArtistFacts(LastFmClient(http, "key", min_interval_s=0))


def test_track_without_wiki_falls_back_to_artist_bio_and_caches():
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        method = request.url.params["method"]
        calls.append(method)
        if method == "track.getInfo":
            return httpx.Response(
                200, json={"track": {"toptags": {"tag": [{"name": "seen live"}, {"name": "russian rock"}]}}}
            )
        return httpx.Response(200, json={"artist": {"bio": {"summary": BIO}, "tags": {"tag": [{"name": "post-punk"}]}}})

    facts = make(handler)
    result = asyncio.run(facts.facts("Кино", "Группа крови"))
    assert result == {
        "fact": "Кино — советская рок-группа, основанная в 1981 году.",
        "tags": ["russian rock"],
        "source": "lastfm",
    }
    asyncio.run(facts.facts("кино", "группа крови"))
    assert calls == ["track.getInfo", "artist.getInfo"]  # второй раз — из кэша


def test_errors_and_missing_key_give_empty_answer():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500)

    empty = {"fact": None, "tags": [], "source": None}
    assert asyncio.run(make(handler).facts("Кино")) == empty
    assert asyncio.run(ArtistFacts(None).facts("Кино")) == empty
