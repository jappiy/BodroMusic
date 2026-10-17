# ruff: noqa: E501 — таблица случаев читается построчно
"""Обслуживание библиотеки на случаях из разбора `bodrod.sqlite3` от 17.10.2026 (задание 006).

Пути, форматы, длительности и теги — как в реальной библиотеке: `collections/` (beets, россыпь в `Треки/`)
и `library/` (папки Lidarr, их трогать нельзя).
"""

import asyncio
import shutil
import sqlite3
from dataclasses import dataclass
from pathlib import Path

from bodrod.main import build_services
from bodrod.maintenance import STAT_KEYS
from bodrod.metadata.lookup import LookupMatch, match_score
from bodrod.models import RequestKind, RequestStatus
from bodrod.placement import read_embedded_cover

from .fakes import FakeWorld
from .test_maintenance import flac_bytes
from .test_worker import SETTINGS

FIXTURES = Path(__file__).parent / "fixtures"
LIDARR_ARTISTS = ["SQWOZ BAB", "The Hatters", "Green Day", "Nickelback", "Scorpions"]


@dataclass
class S:
    id: str
    artist: str
    title: str
    album: str | None
    path: str
    duration: float
    suffix: str = "flac"
    bit_rate: int = 900
    track: int | None = None
    album_artist: str | None = None
    cover: int = 1


NICKELBACK = [
    ("Fight for All the Wrong Reasons", "Fight For All The Wrong Reasons", 223),
    ("Photograph", "Photograph", 259),
    ("Animals", "Animals", 186),
    ("Savin' Me", "Savin' Me", 219),
    ("Far Away", "Far Away", 238),
    ("Next Contestant", "Next Contestant", 214),
    ("Side of a Bullet", "Side Of A Bullet", 181),
    ("Someone That You're With", "Someone That You're With", 241),
    ("Rockstar", "Rockstar", 252),
]

SONGS = [
    # п. 2: FLAC из россыпи против MP3 320 из Lidarr — остаётся FLAC.
    S("sq1", "SQWOZ BAB", "GENDER", "GENDER", "collections/Треки/SQWOZ BAB/GENDER.flac", 171),
    S("sq2", "SQWOZ BAB", "ПЕРСОНАЖ (СЕРЫЕ ДОМА)", "ПЕРСОНАЖ", "collections/Треки/SQWOZ BAB/ПЕРСОНАЖ (СЕРЫЕ ДОМА).flac", 160),
    S("sq3", "SQWOZ BAB", "FLOWJOB", "FLOWJOB", "library/SQWOZ BAB/FLOWJOB (2021)/01 - FLOWJOB.mp3", 150, "mp3", 320, 1),
    S("sq4", "SQWOZ BAB", "GENDER", "FLOWJOB", "library/SQWOZ BAB/FLOWJOB (2021)/02 - GENDER.mp3", 172, "mp3", 320, 2),
    S(
        "sq5", "SQWOZ BAB", "ПЕРСОНАЖ (СЕРЫЕ ДОМА)", "FLOWJOB",
        "library/SQWOZ BAB/FLOWJOB (2021)/03 - ПЕРСОНАЖ (СЕРЫЕ ДОМА).mp3", 161, "mp3", 320, 3,
    ),  # fmt: skip
    S("ht1", "The Hatters", "Танцы", "Танцы", "collections/Треки/The Hatters/Танцы.flac", 195),
    S("ht2", "The Hatters", "Танцы", "Forever Young", "library/The Hatters/Forever Young (2016)/01 - Танцы.mp3", 196, "mp3", 320, 1),
    S("ht3", "The Hatters", "Да да", "Forever Young", "library/The Hatters/Forever Young (2016)/02 - Да да.mp3", 200, "mp3", 320, 2),
    S("ht4", "The Hatters", "Бульвар", "Forever Young", "library/The Hatters/Forever Young (2016)/03 - Бульвар.mp3", 210, "mp3", 320, 3),
    # п. 2: трек полного альбома против копии с EP — остаётся альбомная версия.
    S("gd1", "Green Day", "American Idiot", "American Idiot", "collections/Green Day/2004 - American Idiot/01 - American Idiot.flac", 174, track=1),
    S("gd2", "Green Day", "Jesus of Suburbia", "American Idiot", "collections/Green Day/2004 - American Idiot/02 - Jesus of Suburbia.flac", 548, track=2),
    S("gd3", "Green Day", "Holiday", "American Idiot", "collections/Green Day/2004 - American Idiot/03 - Holiday.flac", 232, track=3),
    S("gd4", "Green Day", "Oh Love", "Oh Love E.P.", "library/Green Day/Oh Love E.P. (2012)/01 - Oh Love.flac", 302, track=1),
    S("gd5", "Green Day", "American Idiot", "Oh Love E.P.", "library/Green Day/Oh Love E.P. (2012)/02 - American Idiot.flac", 175, track=2),
    # п. 4 и 6: настоящие короткие треки чужого исполнителя в папке Green Day.
    S("an1", "Agoraphobic Nosebleed", "Hate Crime", "Holiday", "collections/Green Day/2011 - Holiday/01 - Hate Crime.flac", 8, track=1),
    S("an2", "Agoraphobic Nosebleed", "Bliss", "Holiday", "collections/Green Day/2011 - Holiday/02 - Bliss.flac", 6, track=2),
    # п. 2: «.1» — копия, остаётся чистое имя; п. 6: Hed Kandi в «Skillet/2001 - Rise».
    S("sk1", "Skillet", "Rise", "Rise", "collections/Skillet/2013 - Rise/01 - Rise.flac", 245, track=1),
    S("sk2", "Skillet", "Unpopular", "Rise", "collections/Skillet/2013 - Rise/02 - Unpopular.1.flac", 222, track=2),
    S("sk3", "Skillet", "Unpopular", "Rise", "collections/Skillet/2013 - Rise/02 - Unpopular.flac", 222, track=2),
    S("sk4", "Skillet", "Sick of It", "Rise", "collections/Skillet/2013 - Rise/03 - Sick of It.flac", 187, track=3),
    S("hk1", "Kathy Brown", "Turn Me Out", "Rise", "collections/Skillet/2001 - Rise/01 - Turn Me Out.mp3", 400, "mp3", 320, 1, "Hed Kandi"),
    S("hk2", "Hed Kandi", "Rise Up", "Rise", "collections/Skillet/2001 - Rise/02 - Rise Up.mp3", 380, "mp3", 320, 2, "Hed Kandi"),
    S("hk3", "Shena", "Let the Beat", "Rise", "collections/Skillet/2001 - Rise/03 - Let the Beat.mp3", 390, "mp3", 320, 3, "Hed Kandi"),
    # п. 4: радио-версии Scorpions — не обрезки; п. 6: Budai & Vic в папке Scorpions.
    S("sc1", "Scorpions", "Still Loving You", "Still Loving You", "collections/Треки/Scorpions/Still Loving You.flac", 233),
    S("sc2", "Scorpions", "Holiday", "Holiday", "collections/Треки/Scorpions/Holiday.flac", 198),
    S("sc3", "Scorpions", "Still Loving You", "Love at First Sting", "library/Scorpions/Love at First Sting (1984)/09 - Still Loving You.flac", 406, track=9),
    S("sc4", "Scorpions", "Holiday", "Lovedrive", "library/Scorpions/Lovedrive (1979)/06 - Holiday.flac", 393, track=6),
    S("bv1", "Budai & Vic", "All Night Long", "All Night Long", "collections/Scorpions/0000 - All Night Long/01 - All Night Long.mp3", 210, "mp3", 320, 1),
    S("bv2", "Budai & Vic", "All Night Long (Extended)", "All Night Long", "collections/Scorpions/0000 - All Night Long/02 - All Night Long (Extended).mp3", 330, "mp3", 320, 2),
    # п. 4: интервью — отдельный трек, а не обрезок песни (заявка bodrod скачала его под песню).
    S("nz1", "Noize MC", "Трансгуманизм 2.0 (Interview)", "Трансгуманизм 2.0", "collections/Треки/Noize MC/Трансгуманизм 2.0 (Interview).flac", 45),
    # п. 5: альбом в двух папках.
    S("nv1", "Nirvana", "Serve the Servants", "In Utero", "collections/Nirvana/1993 - In Utero/01 - Serve the Servants.flac", 216, track=1),
    S("nv2", "Nirvana", "Scentless Apprentice", "In Utero", "collections/Nirvana/1993 - In Utero/02 - Scentless Apprentice.flac", 228, track=2),
    S("nv3", "Nirvana", "Heart-Shaped Box", "In Utero", "collections/Nirvana/1993 - In Utero/03 - Heart-Shaped Box.flac", 281, track=3),
    S("nv4", "Nirvana", "Rape Me", "In Utero", "collections/Треки/Nirvana/Rape Me.flac", 170),
    S("nv5", "Nirvana", "Smells Like Teen Spirit", "Nevermind", "collections/Треки/Nirvana/Smells Like Teen Spirit.flac", 301),
    S("nv6", "Nirvana", "Come as You Are", "Nevermind", "collections/Треки/Nirvana/Come as You Are.flac", 219),
    S("nv7", "Nirvana", "Lithium", "Nevermind", "collections/Треки/Nirvana/Lithium.flac", 257),
    S("nv8", "Nirvana", "All Apologies", "MTV Unplugged in New York", "collections/Треки/Nirvana/All Apologies.flac", 263),
    S("nb1", "Nickelback", "Never Again", "Silver Side Up", "collections/Nickelback/2001 - Silver side up/01 - Never Again.flac", 260, track=1),
    S("nb2", "Nickelback", "How You Remind Me", "Silver side up", "collections/Треки/Nickelback/How You Remind Me.flac", 223),
    S("ka1", "Каста", "Вокруг шум", "Лучшие песни", "collections/Каста/Лучшие песни/01 - Вокруг шум.mp3", 230, "mp3", 320, 1),
    S("ka2", "Каста", "Ревность", "Лучшие песни", "collections/Каста/Лучшие песни/02 - Ревность.mp3", 240, "mp3", 320, 2),
    S("ka3", "Каста", "Сказка", "Лучшие песни", "collections/Каста/Лучшие песни/03 - Сказка.mp3", 250, "mp3", 320, 3),
    S("ka4", "Каста", "Скажи, что ты в теме", "Лучшие песни", "collections/Треки/Каста/Скажи, что ты в теме.mp3", 220, "mp3", 320),
    S("vc1", "Виталий Чирва", "Красная", "Авантюрист", "collections/Виталий Чирва/2021 - Авантюрист/01 - Красная.mp3", 200, "mp3", 320, 1),
    S("vc2", "Виталий Чирва", "Луна", "Авантюрист", "collections/Виталий Чирва/2021 - Авантюрист/02 - Луна.mp3", 210, "mp3", 320, 2),
    S("vc3", "Виталий Чирва", "Авантюрист", "Авантюрист", "collections/Треки/Виталий Чирва/Авантюрист.mp3", 190, "mp3", 320),
    S("zt1", "Заточка", "Раз", "Дом", "collections/Заточка/2020 - Дом/01 - Раз.mp3", 200, "mp3", 320, 1),
    S("zt2", "Заточка", "Два", "Дом", "collections/Заточка/2020 - Дом/02 - Два.mp3", 210, "mp3", 320, 2),
    S("zt3", "Заточка feat. Ёлка", "Три", "Дом", "collections/Треки/Заточка feat. Ёлка/03 - Три.mp3", 220, "mp3", 320, 3),
    S("now1", "Tiësto", "The Business", "Now That's What I Call Music! 108", "collections/Треки/Tiësto/The Business.mp3", 164, "mp3", 320, album_artist="Various Artists"),
    S("now2", "Glass Animals", "Heat Waves", "Now That's What I Call Music! 108", "collections/Треки/Glass Animals/Heat Waves.mp3", 238, "mp3", 320, album_artist="Various Artists"),
    S("st1", "Sting", "Fields of Gold", "The Very Best Of Sting & The Police", "collections/Треки/Sting/Fields of Gold.mp3", 220, "mp3", 320, album_artist="Sting"),
    S("st2", "The Police", "Roxanne", "The Very Best Of Sting & The Police", "collections/Треки/The Police/Roxanne.mp3", 192, "mp3", 320, album_artist="Sting"),
    # п. 7: россыпь синглов с разных альбомов без обложек.
    S("mp1", "MULTIPASS", "Огни", "Огни", "collections/Треки/MULTIPASS/Огни.mp3", 180, "mp3", 320, cover=0),
    S("mp2", "MULTIPASS", "Ночь", "Город", "collections/Треки/MULTIPASS/Ночь.mp3", 190, "mp3", 320, cover=0),
    S("mp3", "MULTIPASS", "Сон", "Море", "collections/Треки/MULTIPASS/Сон.mp3", 200, "mp3", 320, cover=0),
] + [  # п. 3: WMA 128 без тегов в россыпи, те же треки во FLAC в Lidarr.
    S(f"nw{i}", "[Unknown Artist]", wma, "[Unknown Album]", f"collections/Треки/Nickelback/{wma}.wma", d + 1, "wma", 128)
    for i, (_, wma, d) in enumerate(NICKELBACK)
] + [
    S(
        f"nf{i}", "Nickelback", flac, "All the Right Reasons",
        f"library/Nickelback/All the Right Reasons (2005)/{i + 1:02d} - {flac}.flac", d, track=i + 1,
    )  # fmt: skip
    for i, (flac, _, d) in enumerate(NICKELBACK)
]  # fmt: skip


def make_library(tmp_path: Path) -> tuple[Path, Path]:
    music = tmp_path / "music"
    db = tmp_path / "navidrome.db"
    conn = sqlite3.connect(db)
    conn.executescript(
        """
        CREATE TABLE user (id TEXT, user_name TEXT, is_admin BOOL);
        CREATE TABLE library (id INTEGER, path TEXT);
        CREATE TABLE media_file (id TEXT, title TEXT, artist TEXT, album TEXT, album_id TEXT, album_artist TEXT,
            duration REAL, size INTEGER, suffix TEXT, bit_rate INTEGER, path TEXT, library_id INTEGER,
            track_number INTEGER, disc_number INTEGER, has_cover_art BOOL, missing BOOL DEFAULT 0);
        CREATE TABLE annotation (user_id TEXT, item_id TEXT, item_type TEXT, rating INTEGER, starred BOOL,
            play_count INTEGER);
        """
    )
    conn.execute("INSERT INTO user VALUES ('u-admin', 'admin', 1)")
    conn.execute("INSERT INTO library VALUES (1, '/music')")
    for s in SONGS:
        size = int(s.bit_rate * 125 * s.duration)
        conn.execute(
            "INSERT INTO media_file VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, NULL, ?, 0)",
            (s.id, s.title, s.artist, s.album, f"al-{s.album}", s.album_artist, s.duration, size, s.suffix,
             s.bit_rate, s.path, s.track, s.cover),
        )  # fmt: skip
        file = music / s.path
        file.parent.mkdir(parents=True, exist_ok=True)
        if s.suffix == "flac":
            file.write_bytes(flac_bytes(s.duration))
        elif s.suffix == "mp3":
            shutil.copyfile(FIXTURES / "silence.mp3", file)
        else:
            file.write_bytes(b"0&\xb2u\x8ef\xcf\x11" + bytes(64))  # заголовок ASF/WMA
    conn.commit()
    conn.close()
    return music, db


class FakeLookup:
    """Источник обложек и тегов: отвечает только на известные альбомы."""

    tags = True

    def __init__(self, name: str, albums: dict[str, str]) -> None:
        self.name = name
        self.albums = albums
        self.calls: list[tuple[str, str, str | None]] = []

    async def lookup(self, artist, title, album, duration_s):
        self.calls.append((artist, title, album))
        if album not in self.albums:
            return []
        return [
            LookupMatch(
                artist=artist,
                title=title,
                album=album,
                duration_s=duration_s,
                cover_url=self.albums[album],
                source=self.name,
                score=match_score(artist, title, duration_s, artist, title, duration_s),
            )
        ]


def make(tmp_path: Path, **overrides):
    music, db = make_library(tmp_path)
    world = FakeWorld(
        lidarr_artists=[{"id": i, "path": f"/volume2/music/library/{a}"} for i, a in enumerate(LIDARR_ARTISTS)]
    )
    settings = SETTINGS.__class__(
        **{
            **SETTINGS.__dict__,
            "data_dir": str(tmp_path),
            "navidrome_db": str(db),
            "music_dir": str(music),
            "lidarr_music_dir": "/volume2/music",
            "tag_providers": (),
            "cover_providers": (),
            **overrides,
        }
    )
    services = build_services(settings, world.client())
    # Заявка bodrod скачала интервью под видом песни: длительность из каталога — у песни.
    request, _ = services.store.create(
        username="vasya", kind=RequestKind.TRACK, artist="Noize MC", title="Трансгуманизм 2.0", album=None,
        duration_ms=212_000, cover_url=None,
    )  # fmt: skip
    request.status, request.song_id = RequestStatus.COMPLETED, "nz1"
    services.store.save(request)
    fetched: list[str] = []

    async def fetch(url: str):
        fetched.append(url)
        return (FIXTURES / "cover.jpg").read_bytes(), "image/jpeg"

    services.maintenance._fetch_cover = fetch
    return services, music, fetched


def actions(services, run_id):
    return services.store.list_actions(run_id, None, 1000, 0)[1]


def by_path(services, run_id, kind):
    return {a.path: a for a in actions(services, run_id) if a.kind == kind}


def run(services):
    run_id = asyncio.run(services.maintenance.run("manual"))
    result = services.store.get_run(run_id)
    assert result.status == "completed", result.error
    return result


def test_dry_run_twice_same_counters_and_nothing_remembered(tmp_path):
    services, music, fetched = make(tmp_path, maintenance_dry_run=True)
    first, second = run(services), run(services)
    assert set(first.stats) == set(STAT_KEYS)
    assert first.stats == second.stats
    assert first.stats["duplicates_removed"] > 0 and first.stats["mismatches"] == 3
    assert services.store.clear_checked() == 0
    assert not (music / ".bodrod-quarantine").exists() and not fetched
    planned = [a for a in actions(services, first.id) if a.planned]
    assert planned and all(
        not a.applied and ("будет" in a.details.lower() or "будут" in a.details.lower()) for a in planned
    )
    assert all(not a.applied for a in actions(services, first.id))
    # «Требует внимания» — только находки, без запланированных исправлений.
    total, attention = services.store.list_actions(first.id, "attention", 1000, 0)
    assert total == first.stats["attention"]
    assert all(not a.planned and a.kind in ("tags", "cover", "short", "mismatch") for a in attention)


def test_duplicate_keeps_best_copy(tmp_path):
    services, music, _ = make(tmp_path)
    result = run(services)
    dups = by_path(services, result.id, "duplicate")
    for kept in (
        "collections/Треки/SQWOZ BAB/GENDER.flac",
        "collections/Треки/SQWOZ BAB/ПЕРСОНАЖ (СЕРЫЕ ДОМА).flac",
        "collections/Треки/The Hatters/Танцы.flac",
        "collections/Green Day/2004 - American Idiot/01 - American Idiot.flac",
        "collections/Skillet/2013 - Rise/02 - Unpopular.flac",
    ):
        assert (music / kept).exists(), kept
        assert kept not in dups, dups[kept].details
    # MP3 и EP-копия — в папках Lidarr, их не трогаем; копия «.1» убрана.
    assert (music / "library/SQWOZ BAB/FLOWJOB (2021)/02 - GENDER.mp3").exists()
    assert "collections/Skillet/2013 - Rise/02 - Unpopular.1.flac" in dups
    assert not (music / "collections/Skillet/2013 - Rise/02 - Unpopular.1.flac").exists()


def test_untagged_wma_found_as_duplicates(tmp_path):
    services, music, _ = make(tmp_path)
    result = run(services)
    dups = by_path(services, result.id, "duplicate")
    for _, wma, _ in NICKELBACK:
        path = f"collections/Треки/Nickelback/{wma}.wma"
        assert path in dups, path
        assert "All the Right Reasons" in dups[path].details and "без тегов" in dups[path].details
        assert not (music / path).exists()
    assert all((music / f"library/Nickelback/All the Right Reasons (2005)/{i + 1:02d} - {f}.flac").exists()
               for i, (f, _, _) in enumerate(NICKELBACK))  # fmt: skip


def test_short_files_not_removed_by_mistake(tmp_path):
    services, music, _ = make(tmp_path)
    result = run(services)
    short = by_path(services, result.id, "short")
    for path in (
        "collections/Треки/Scorpions/Still Loving You.flac",
        "collections/Треки/Scorpions/Holiday.flac",
        "collections/Треки/Noize MC/Трансгуманизм 2.0 (Interview).flac",
        "collections/Green Day/2011 - Holiday/01 - Hate Crime.flac",
        "collections/Green Day/2011 - Holiday/02 - Bliss.flac",
    ):
        assert (music / path).exists(), path
        assert path in short and short[path].applied is False and short[path].planned is False, path
    assert "радио-версию" in short["collections/Треки/Scorpions/Still Loving You.flac"].details
    assert "interview" in short["collections/Треки/Noize MC/Трансгуманизм 2.0 (Interview).flac"].details
    stats = result.stats
    assert stats["short_removed"] == 0 and stats["short_skipped"] == 5
    assert stats["short_found"] >= stats["short_removed"] + stats["short_skipped"]


def test_albums_merge_into_album_folder(tmp_path):
    services, music, _ = make(tmp_path)
    result = run(services)
    albums = {a.album: a for a in actions(services, result.id) if a.kind == "album"}
    assert albums["In Utero"].path == "collections/Nirvana/1993 - In Utero"
    assert (music / "collections/Nirvana/1993 - In Utero/Rape Me.flac").exists()
    assert (music / "collections/Треки/Nirvana/Lithium.flac").exists()  # другие альбомы остаются в россыпи
    assert albums["Silver Side Up"].path == "collections/Nickelback/2001 - Silver side up"
    assert albums["Лучшие песни"].path == "collections/Каста/Лучшие песни"
    assert albums["Авантюрист"].path == "collections/Виталий Чирва/2021 - Авантюрист"
    assert not any("Now That" in (a or "") or "Sting" in (a or "") for a in albums)
    merges = [a for a in actions(services, result.id) if a.kind == "artist_merge"]
    assert [(m.album, m.path) for m in merges] == [("Дом", "collections/Заточка/2020 - Дом")]
    assert "Объединение исполнителя" in merges[0].details and "Заточка feat. Ёлка" in merges[0].details
    assert result.stats["albums_merged"] == 4 and result.stats["artists_merged"] == 1
    assert (music / "collections/Треки/Sting/Fields of Gold.mp3").exists()
    assert (music / "collections/Треки/The Police/Roxanne.mp3").exists()


def test_foreign_release_in_artist_folder(tmp_path):
    services, _, _ = make(tmp_path)
    result = run(services)
    mismatches = by_path(services, result.id, "mismatch")
    assert set(mismatches) == {
        "collections/Scorpions/0000 - All Night Long",
        "collections/Skillet/2001 - Rise",
        "collections/Green Day/2011 - Holiday",
    }
    assert "Budai & Vic" in mismatches["collections/Scorpions/0000 - All Night Long"].details
    assert all(not a.applied for a in mismatches.values())
    assert result.stats["mismatches"] == 3


def test_loose_folder_covers_embedded_per_file(tmp_path):
    services, music, fetched = make(tmp_path)
    provider = FakeLookup("itunes", {"Огни": "https://cover/ogni", "Город": "https://cover/gorod"})
    services.maintenance.cover_chain = [provider]
    result = run(services)
    folder = music / "collections/Треки/MULTIPASS"
    assert not any(p.suffix == ".jpg" for p in folder.iterdir())  # не cover.jpg на всю папку
    assert read_embedded_cover(folder / "Огни.mp3") is not None
    assert read_embedded_cover(folder / "Ночь.mp3") is not None
    assert read_embedded_cover(folder / "Сон.mp3") is None
    assert sorted(fetched) == ["https://cover/gorod", "https://cover/ogni"]
    covers = by_path(services, result.id, "cover")
    assert "iTunes" in covers["collections/Треки/MULTIPASS/Огни.mp3"].details
    assert covers["collections/Треки/MULTIPASS/Сон.mp3"].applied is False


def test_new_provider_rechecks_not_found(tmp_path):
    services, music, _ = make(tmp_path)
    maintenance = services.maintenance
    empty = FakeLookup("yandex", {})
    maintenance.cover_chain = [empty]
    first = run(services)
    assert "collections/Треки/MULTIPASS/Огни.mp3" in by_path(services, first.id, "cover")
    calls = len(empty.calls)
    second = run(services)  # «не найдено» запомнено: тот же набор источников не спрашиваем
    assert len(empty.calls) == calls and "collections/Треки/MULTIPASS/Огни.mp3" not in by_path(
        services, second.id, "cover"
    )
    maintenance.cover_chain = [empty, FakeLookup("itunes", {"Огни": "https://cover/ogni"})]
    third = run(services)
    found = by_path(services, third.id, "cover")["collections/Треки/MULTIPASS/Огни.mp3"]
    assert found.applied and "iTunes" in found.details
    assert read_embedded_cover(music / "collections/Треки/MULTIPASS/Огни.mp3") is not None


def test_migration_clears_dry_run_checks(tmp_path):
    from bodrod.db import Store

    path = tmp_path / "old.sqlite3"
    store = Store(str(path))
    store.set_checked("cover:/music/x", "1")
    store._conn.execute("PRAGMA user_version = 0")
    store.close()
    store = Store(str(path))
    assert store.checked("cover:/music/x") is None
