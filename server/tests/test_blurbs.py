"""Подводки к трекам: шаблоны, факты, антиповтор, запрет «Трек из альбома» для синглов, личная история, причины."""

from __future__ import annotations

import asyncio
import datetime as dt
import sqlite3

from bodrod.db import Store
from bodrod.navidrome_db import NavidromeDb, NdAnnotation, NdSong
from bodrod.reco.blurbs import (
    MAX_CHARS,
    TEMPLATES,
    BlurbService,
    Fact,
    Variant,
    choose,
    clean_llm,
    context_facts,
    personal_facts,
    plural,
    render,
    static_facts,
)
from bodrod.reco.store import RecoStore

TODAY = dt.date(2026, 10, 25)


def song(**kw) -> NdSong:
    base = dict(
        id="s1",
        title="Кукушка",
        artist="Кино",
        album="Чёрный альбом",
        album_id="a1",
        duration_s=400,
        size=1,
        suffix="mp3",
        bit_rate=320,
        path="/music/x.mp3",
        track_number=1,
        disc_number=1,
        year=1990,
    )
    return NdSong(**(base | kw))  # type: ignore[arg-type]


def test_templates_are_many_short_and_russian() -> None:
    assert sum(len(v) for v in TEMPLATES.values()) >= 30
    sample = {"bpm": 72, "album": "Чёрный альбом", "years": "35 лет", "year": 1990, "decade": 90, "genres": "рок",
              "Genres": "Рок", "artist": "Кино", "listeners": "1,2 млн", "plays": "12 раз", "since": "весны",
              "seed": "Звезда по имени Солнце", "chart": "Deezer"}  # fmt: skip
    for kind in TEMPLATES:
        for text in render(Fact(kind, sample)):
            assert len(text) <= MAX_CHARS
            assert "{" not in text


def test_plural() -> None:
    assert plural(1, "год", "года", "лет") == "1 год"
    assert plural(22, "год", "года", "лет") == "22 года"
    assert plural(35, "год", "года", "лет") == "35 лет"
    assert plural(12, "раз", "раза", "раз") == "12 раз"


def test_static_facts_album_position_anniversary_and_sound() -> None:
    feature = {"bpm": 72, "moods": {"happy": 0.1, "sad": 0.8, "relaxed": 0.7, "aggressive": 0.1}, "danceability": 0.2}
    facts = static_facts(song(year=1991), feature, ("s1", "s9"), 8, None, TODAY)
    kinds = {f.kind for f in facts}
    assert {"position_first", "album_energy_max", "anniversary", "sound_slow_dark", "sound_sad"} <= kinds
    anniversary = next(f for f in facts if f.kind == "anniversary")
    assert anniversary.data["years"] == "35 лет"


def test_single_never_gets_album_facts() -> None:
    single = song(album="Кукушка", track_number=1)
    facts = static_facts(single, None, ("s1", None), 8, None, TODAY)
    assert not any(f.kind.startswith(("position", "album", "anniversary")) for f in facts)


def test_story_from_lastfm_or_wikipedia_but_not_templates() -> None:
    info = {"variants": ["Трек из альбома «X»", "Последний альбом группы."], "sources": ["template", "wikipedia"],
            "tags": ["post-punk"], "listeners": 1_234_567}  # fmt: skip
    facts = static_facts(song(), None, (None, None), 0, info, TODAY)
    assert Fact("story", text="Последний альбом группы") in facts
    assert all(f.text != "Трек из альбома «X»" for f in facts)
    assert any(f.kind == "listeners" for f in facts)


def test_personal_history_long_ago_first_artist_and_shared_love() -> None:
    notes = [
        NdAnnotation("me", "s1", False, 0, 3, "2026-04-02 10:00:00"),
        NdAnnotation("wife", "s1", True, 0, 50, None),
    ]
    facts = personal_facts(song(), "me", notes, artist_plays=3, today=TODAY)
    long_ago = next(f for f in facts if f.kind == "long_ago")
    assert render(long_ago)[0] == "Вы не слушали его с весны"
    assert any(f.kind == "shared_love" for f in facts)
    assert "wife" not in str(facts)  # о чужих — только факт
    first = personal_facts(song(), "me", [], artist_plays=0, today=TODAY)
    assert render(first[0])[0] == "Первое знакомство с Кино"


def test_context_reasons() -> None:
    assert render(context_facts("lastfm,deezer", "Joy Division", False)[0])[0] == "Часто слушают вместе с Joy Division"
    assert render(context_facts("yandex", "Кукушка", True)[0])[0] == "Похоже на «Кукушка», который вы лайкнули"
    assert render(context_facts("chart:deezer", None, False)[0])[0] == "Из чарта Deezer за неделю"
    assert context_facts(None, None, False) == []


def test_choose_avoids_same_type_within_five_tracks() -> None:
    options = {f"t{i}": [Variant("sound", f"звук {i}", 0.55), Variant("year", f"год {i}", 0.3)] for i in range(6)}
    picked = choose(options, [f"t{i}" for i in range(6)], day=1)
    groups = [picked[f"t{i}"].group for i in range(6)]
    assert groups[0] == "sound" and groups[1] == "year"
    for i in range(len(groups)):
        window = groups[max(0, i - 4) : i]
        if groups[i] in window:
            # повтор допустим, только когда других типов у трека нет
            assert all(v.group in window for v in options[f"t{i}"])


def test_rotation_between_variants_by_day() -> None:
    options = {"t": [Variant("story", "A", 0.9), Variant("story", "B", 0.9), Variant("sound", "C", 0.5)]}
    seen = {choose(options, ["t"], day=d)["t"].text for d in range(20)}
    assert seen == {"A", "B"}


def test_clean_llm() -> None:
    assert clean_llm("«Медленный и тёмный трек для вечера.»\nвторая строка") == "Медленный и тёмный трек для вечера"
    assert clean_llm("x" * 200) is None
    assert clean_llm("Only English text here") is None


def make_db(path) -> None:
    conn = sqlite3.connect(path)
    conn.executescript(
        """
        CREATE TABLE user (id TEXT, user_name TEXT, is_admin BOOL);
        CREATE TABLE media_file (id TEXT, title TEXT, artist TEXT, album TEXT, album_id TEXT, duration REAL,
            size INTEGER, suffix TEXT, bit_rate INTEGER, path TEXT, track_number INTEGER, disc_number INTEGER,
            year INTEGER, artist_id TEXT, missing BOOL DEFAULT 0);
        CREATE TABLE annotation (user_id TEXT, item_id TEXT, item_type TEXT, rating INTEGER, starred BOOL,
            play_count INTEGER, play_date TEXT);
        """
    )
    conn.executemany("INSERT INTO user VALUES (?, ?, 0)", [("u1", "me"), ("u2", "wife")])
    rows = [
        ("s1", "Кукушка", "Кино", "Кукушка", "a1", 1, 2009, "ar1"),  # сингл
        ("s2", "Группа крови", "Кино", "Группа крови", "a2", 1, 1988, "ar1"),
        ("s3", "Война", "Кино", "Группа крови", "a2", 2, 1988, "ar1"),
    ]
    for sid, title, artist, album, aid, num, year, arid in rows:
        conn.execute(
            "INSERT INTO media_file VALUES (?, ?, ?, ?, ?, 200, 1, 'mp3', 320, ?, ?, 1, ?, ?, 0)",
            (sid, title, artist, album, aid, f"/music/{sid}.mp3", num, year, arid),
        )
    conn.execute("INSERT INTO annotation VALUES ('u1', 's2', 'media_file', 0, 0, 3, '2026-10-20 10:00:00')")
    conn.commit()
    conn.close()


def test_for_user_never_says_track_from_album_for_single(tmp_path) -> None:
    make_db(tmp_path / "nd.db")
    store = Store(str(tmp_path / "bodrod.db"))
    RecoStore(store)  # таблицы пула
    service = BlurbService(store, NavidromeDb(str(tmp_path / "nd.db")), clock=lambda: 1_792_900_000)
    result = asyncio.run(service.for_user("me", ["s1", "s2", "s3"], today=TODAY))
    texts = {k: v.text for k, v in result.items()}
    assert all("Трек из альбома «Кукушка»" not in t for t in texts.values())
    assert texts["s3"] == "Вы ещё ни разу его не слушали" or texts["s3"] == "Для вас — впервые"
    # Постоянные факты закэшированы, следующий запрос их не пересчитывает.
    assert store.query_one("SELECT COUNT(*) AS n FROM blurbs")["n"] == 3
