"""Задания 022 и 025: короткий текст о треке или исполнителе на главной — только русский, законченный, без обрезки."""

import asyncio

import httpx

from bodrod.db import Store
from bodrod.metadata.facts import ArtistFacts, clean_text, first_sentence, pick_sentences, template_variants
from bodrod.metadata.lastfm import LastFmClient

BIO = (
    "Кино — советская рок-группа, основанная в 1981 году. Лидер — Виктор Цой. "
    '<a href="https://www.last.fm/music/Kino">Read more on Last.fm</a>'
)
BROWN = (
    "Джеймс Браун (англ. James Brown; 3 мая 1933 — 25 декабря 2006) — американский певец, один из самых "
    "влиятельных музыкантов XX века, «крёстный отец соула». Родился в Южной Каролине, однако большую часть "
    "детства провёл в Огасте."
)


def test_first_sentence_strips_html_and_link():
    assert first_sentence(BIO) == "Кино — советская рок-группа, основанная в 1981 году."
    assert first_sentence(None) is None
    assert first_sentence('<a href="x">Read more on Last.fm</a>') is None


def test_english_text_is_never_used():
    assert pick_sentences('This is Ghost\'s leading single for the album "Meliora", release 05/31/2015.', 110) == []


def test_long_sentence_is_shortened_to_a_complete_phrase_not_cut():
    assert clean_text("Джеймс Браун (3 мая 1933 — 25 декабря 2006) родился") == "Джеймс Браун родился"
    found = pick_sentences(BROWN, 110)
    assert found[0].startswith("Джеймс Браун — американский певец, один из самых влиятельных")
    assert pick_sentences(BROWN, 60)[0] == "Джеймс Браун — американский певец."
    assert all(len(s) <= 110 and s.endswith(".") and "…" not in s for s in found)
    # Абзац из одного длинного предложения без запятых — ничего, а не обрезок.
    assert pick_sentences("Очень " * 40 + "длинное предложение без запятых.", 110) == []


def test_abbreviations_do_not_split_sentences():
    text = "Группа «Сплин» основана в 1994 г. в Санкт-Петербурге. Лидер — А. Васильев."
    assert pick_sentences(text, 110)[0] == "Группа «Сплин» основана в 1994 г. в Санкт-Петербурге."


def test_templates_are_russian_and_fit():
    found = template_variants("Ghost", "Meliora", 2015, ["heavy metal", "seen live"], 1_234_567, 110, "Cirice")
    assert found == ["Жанр — хеви-метал", "У Ghost больше 1,2 млн слушателей на Last.fm"]
    # «Трек из альбома» — только без других фактов и не для сингла (альбом = название трека).
    assert template_variants("Ghost", "Meliora", 2015, [], None, 110, "Cirice") == ["Трек из альбома «Meliora» (2015)"]
    assert template_variants("Ghost", "Cirice", 2015, [], None, 110, "Cirice") == []
    assert template_variants("X", "Singles", None, [], None, 110) == []


def make(handler, store=None):
    http = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return ArtistFacts(LastFmClient(http, "key", min_interval_s=0), http=http, store=store)


def wiki_and_lastfm(calls):
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.host == "ru.wikipedia.org":
            params = request.url.params
            calls.append("wiki:" + params.get("action", "") + ":" + params.get("list", params.get("prop", "")))
            if params.get("list") == "search":
                query = params["srsearch"]
                return httpx.Response(
                    200, json={"query": {"search": [] if "песня" in query else [{"title": "Кино (группа)"}]}}
                )
            return httpx.Response(
                200,
                json={"query": {"pages": {"1": {"title": "Кино (группа)", "extract": "«Кино» (Kino) — " + BIO[7:]}}}},
            )
        method = request.url.params["method"]
        calls.append(method)
        if method == "track.getInfo":
            return httpx.Response(200, json={"track": {"toptags": {"tag": [{"name": "russian rock"}]}}})
        return httpx.Response(
            200,
            json={"artist": {"bio": {"summary": "Kino is a Soviet band."}, "stats": {"listeners": "900000"}}},
        )

    return handler


def test_wikipedia_first_then_templates_and_cache(tmp_path):
    calls: list[str] = []
    store = Store(str(tmp_path / "db.sqlite3"))
    facts = make(wiki_and_lastfm(calls), store)
    result = asyncio.run(facts.facts("Кино", "Группа крови", "Группа крови", 1988))
    assert result["fact"] == "«Кино» — советская рок-группа, основанная в 1981 году."
    assert result["source"] == "wikipedia"
    # Есть настоящий текст — шаблонов нет; альбом совпадает с названием — «Трек из альбома» не бывает вовсе.
    assert not any(v.startswith("Трек из альбома") or v.startswith("Жанр") for v in result["variants"])
    assert all("Soviet" not in v for v in result["variants"])
    count = len(calls)
    asyncio.run(facts.facts("кино", "группа крови", "Группа крови", 1988))
    assert len(calls) == count  # второй раз — из кэша
    # После перезапуска — из базы bodrod.
    again = make(wiki_and_lastfm(calls), store)
    assert asyncio.run(again.facts("Кино", "Группа крови", "Группа крови", 1988)) == result
    assert len(calls) == count


def test_errors_and_missing_sources_give_empty_answer():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500)

    empty = {"fact": None, "variants": [], "tags": [], "source": None}
    assert asyncio.run(make(handler).facts("Кино")) == empty
    assert asyncio.run(ArtistFacts(None).facts("Кино")) == empty


class FakeYandex:
    async def search_artists(self, text):
        return [("1", "Ghost")]

    async def artist_description(self, artist_id):
        return (
            "Шведская рок-группа, основанная в 2006 году в Линчёпинге. "
            "На данный момент коллектив состоит из девяти человек"
        )


def test_yandex_description_when_wikipedia_is_down():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectTimeout("нет сети")

    http = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    facts = ArtistFacts(None, http=http, yandex=lambda: FakeYandex())
    result = asyncio.run(facts.facts("Ghost", "Cirice"))
    assert result["source"] == "yandex"
    assert result["variants"][0] == "Шведская рок-группа, основанная в 2006 году в Линчёпинге."
    assert result["variants"][1] == "На данный момент коллектив состоит из девяти человек."
