import asyncio
import shutil
import sqlite3
from pathlib import Path

import mutagen
import pytest
from mutagen.id3 import ID3

from bodrod.encoding import read_values, repair, repair_fields, suspicious, unrecoverable, write_values
from bodrod.placement import fix_encoding

from .test_maintenance import catalog_world, make

FIXTURES = Path(__file__).parent / "fixtures"


@pytest.mark.parametrize(
    ("broken", "fixed"),
    [
        ("Ñåêðåò", "Секрет"),
        ("íè÷åãî íå èñ÷åçàåò", "ничего не исчезает"),
        ("Ìàøèíà Âðåìåíè", "Машина Времени"),
        ("ÑÏËÈÍ", "СПЛИН"),
        ("Ðîê", "Рок"),
        ("Ãðàæäàíñêàÿ Îáîðîíà", "Гражданская Оборона"),
        ("Ñòàðûé ¸æ", "Старый ёж"),
        ("Ñïëèí feat. Ëåïñ", "Сплин feat. Лепс"),
        ("Äèñêîòåêà Àâàðèÿ - Ïðè÷¸ì òóò ÿ?", "Дискотека Авария - Причём тут я?"),
        ("Ôàíòîì-2", "Фантом-2"),
    ],
)
def test_repairs_cp1251_read_as_latin1(broken, fixed):
    assert repair(broken) == fixed
    assert suspicious(broken) and not unrecoverable(broken)


@pytest.mark.parametrize(
    "name",
    [
        "Beyoncé",
        "Mötley Crüe",
        "Sigur Rós",
        "Björk",
        "Røyksopp",
        "Ólafur Arnalds",
        "Émilie Simon",
        "Café del Mar",
        "Zaz Éblouie",
        "À la carte",
        "Секрет",
        "Rock",
        "",
    ],
)
def test_real_names_are_left_alone(name):
    assert repair(name) is None
    assert not unrecoverable(name)


def test_question_marks_go_to_manual_check():
    assert unrecoverable("????? ???")
    assert unrecoverable("Ðîê�")  # символ замены — часть байтов уже потеряна
    fixed, manual = repair_fields({"title": ["???? ??"], "artist": ["Ìàøèíà Âðåìåíè"]})
    assert fixed == {"artist": ["Машина Времени"]} and manual == ["title"]


def _id3v1_only(tmp_path: Path) -> Path:
    """MP3 только с ID3v1 в cp1251 — так пишут старые программы в русской Windows."""
    path = tmp_path / "song.mp3"
    shutil.copy(FIXTURES / "silence.mp3", path)
    audio = mutagen.File(path)
    if audio.tags is not None:
        audio.delete()

    def field(text: str, size: int = 30) -> bytes:
        raw = text.encode("cp1251")
        return raw + b"\0" * (size - len(raw))

    with path.open("ab") as fh:
        fh.write(b"TAG" + field("ничего не исчезает") + field("Секрет") + field("Секретные материалы"))
        fh.write(b"1998" + b"\0" * 30 + bytes([17]))  # жанр 17 = Rock
    return path


def test_mp3_id3v1_becomes_id3v24_utf8(tmp_path):
    path = _id3v1_only(tmp_path)
    values = read_values(path)
    assert values["title"] == ["íè÷åãî íå èñ÷åçàåò"] and values["artist"] == ["Ñåêðåò"]
    fixed, manual = repair_fields(values)
    assert not manual
    write_values(path, fixed)
    tags = ID3(path)
    assert tags.version == (2, 4, 0)
    assert str(tags["TIT2"]) == "ничего не исчезает" and tags["TIT2"].encoding == 3
    assert str(tags["TPE1"]) == "Секрет" and str(tags["TALB"]) == "Секретные материалы"
    with path.open("rb") as fh:
        fh.seek(-128, 2)
        assert fh.read(3) != b"TAG"  # ID3v1 удалён


@pytest.mark.parametrize("name", ["silence.flac", "silence.ogg", "silence.m4a"])
def test_other_formats(tmp_path, name):
    path = tmp_path / name
    shutil.copy(FIXTURES / name, path)
    write_values(path, {"title": ["Ñåêðåò"], "genre": ["Ðîê"]})
    assert fix_encoding(path) == ["кодировка: название", "кодировка: жанр"]
    assert read_values(path)["title"] == ["Секрет"] and read_values(path)["genre"] == ["Рок"]
    assert fix_encoding(path) == []


def _add_song(db: Path, music: Path, song_id: str, rel: str, source: Path) -> Path:
    conn = sqlite3.connect(db)
    conn.execute(
        "INSERT INTO media_file VALUES (?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, 1, ?, NULL, ?, 0)",
        (song_id, "íè÷åãî", "Ñåêðåò", "Секретные материалы", "al-s", 1.0, 1000, "mp3", 128, rel, 1, 1),
    )
    conn.commit()
    conn.close()
    target = music / rel
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(source, target)
    return target


def test_maintenance_fixes_logs_and_reverts(tmp_path):
    world = catalog_world()
    services, music, db, _ = make(world, tmp_path)
    broken = _add_song(db, music, "enc1", "Секрет/Секретные материалы/01.mp3", _id3v1_only(tmp_path))
    mystery = tmp_path / "mystery.mp3"
    shutil.copy(FIXTURES / "silence.mp3", mystery)
    write_values(mystery, {"title": ["????? ??"], "artist": ["Ñåêðåò"]})
    unknown = _add_song(db, music, "enc2", "Секрет/Секретные материалы/02.mp3", mystery)

    run = services.store.get_run(asyncio.run(services.maintenance.run("manual")))
    assert run.status == "completed", run.error
    assert run.stats["encoding_fixed"] == 2
    assert read_values(broken)["artist"] == ["Секрет"]
    assert read_values(unknown) == {"title": ["????? ??"], "artist": ["Секрет"]}
    assert run.stats["open_encoding_manual"] == 1

    summary = services.maintenance.summary()
    assert any(c.key == "encoding_fixed" and c.count == 2 for c in summary.fixed)
    assert any(c.key == "encoding_manual" for c in summary.unresolved)
    assert summary.encoding_manual == ["Секрет/Секретные материалы/02.mp3"]
    fix = next(f for f in summary.encoding if f.path.endswith("01.mp3"))
    title = next(c for c in fix.changes if c.field == "название")
    assert (title.before, title.after) == ("íè÷åãî íå èñ÷åçàåò", "ничего не исчезает")

    # Откат: исходные значения из журнала, после него файл больше не исправляется.
    asyncio.run(services.maintenance.revert_encoding(fix.id))
    assert read_values(broken)["artist"] == ["Ñåêðåò"]
    services.store.clear_checked()
    again = services.store.get_run(asyncio.run(services.maintenance.run("manual")))
    assert again.stats["encoding_fixed"] == 0
    assert read_values(broken)["artist"] == ["Ñåêðåò"]


def test_dry_run_only_reports(tmp_path):
    services, music, db, _ = make(catalog_world(), tmp_path, maintenance_dry_run=True)
    broken = _add_song(db, music, "enc1", "Секрет/Альбом/01.mp3", _id3v1_only(tmp_path))
    run = services.store.get_run(asyncio.run(services.maintenance.run("manual")))
    assert run.stats["encoding_fixed"] == 1
    assert read_values(broken)["artist"] == ["Ñåêðåò"]
    _, actions = services.store.list_actions(run.id, "encoding", 50, 0)
    assert actions and actions[0].planned and "Будет исправлена" in actions[0].details
