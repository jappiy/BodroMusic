import os
import shutil
from pathlib import Path

import mutagen
import pytest
from mutagen.flac import FLAC
from mutagen.id3 import ID3, TRCK
from mutagen.mp4 import MP4
from mutagen.oggopus import OggOpus

from bodrod.placement import (
    Tags,
    choose_destination,
    find_local_file,
    place_file,
    relative_path,
    remove_source,
    sanitize_part,
    write_tags,
)

FIXTURES = Path(__file__).parent / "fixtures"
COVER = ((FIXTURES / "cover.jpg").read_bytes(), "image/jpeg")
TAGS = Tags(artist="Трофим", title="Город Сочи", album="Город Сочи (сборник)")


def copy_fixture(tmp_path: Path, name: str, dest: str | None = None) -> Path:
    target = tmp_path / (dest or name)
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(FIXTURES / name, target)
    return target


# region Имена


def test_sanitize_keeps_cyrillic_and_drops_forbidden():
    assert sanitize_part("Трофим") == "Трофим"
    assert sanitize_part('AC/DC: "Back" <in> Black?*|\\') == "AC DC Back in Black"
    assert sanitize_part("  ..Скрытый.  ") == "Скрытый"
    assert sanitize_part("строка\nс\tуправляющими\x00символами") == "строка с управляющими символами"
    assert sanitize_part("...") == "Unknown"
    assert sanitize_part(None, "Singles") == "Singles"


def test_sanitize_limits_bytes_without_breaking_letters():
    long = "Я" * 200  # 2 байта на букву
    result = sanitize_part(long)
    assert len(result.encode("utf-8")) <= 120
    assert result == "Я" * 60


def test_relative_path_layout_and_singles():
    path = relative_path(
        "{artist}/{album}/{artist} - {title}", artist="Трофим", title="Город Сочи", album=None, extension=".MP3"
    )
    assert path == Path("Трофим/Singles/Трофим - Город Сочи.mp3")
    custom = relative_path("{artist}/{title}", artist="A/B", title="T?", album="X", extension="flac")
    assert custom == Path("A B/T.flac")


# endregion

# region Поиск файла


def test_find_expected_path(tmp_path):
    file = copy_fixture(tmp_path, "silence.mp3", "Трофим/01 Город Сочи.mp3")
    found = find_local_file(str(tmp_path), r"Music\Трофим\01 Город Сочи.mp3", file.stat().st_size)
    assert found == file


def test_find_recursive_and_by_size(tmp_path):
    small = copy_fixture(tmp_path, "silence.opus", "a/deep/01 Город Сочи.mp3")
    right = copy_fixture(tmp_path, "silence.mp3", "b/01 Город Сочи_639012345678901234.mp3")
    os.utime(small, (2_000_000_000, 2_000_000_000))  # новее, но не тот размер
    found = find_local_file(str(tmp_path), r"Music\Другая папка\01 Город Сочи.mp3", right.stat().st_size)
    assert found == right


def test_find_newest_when_size_unknown(tmp_path):
    old = copy_fixture(tmp_path, "silence.mp3", "x/song.mp3")
    new = copy_fixture(tmp_path, "silence.mp3", "y/song.mp3")
    os.utime(old, (1_000_000_000, 1_000_000_000))
    assert find_local_file(str(tmp_path), r"peer\song.mp3", None) == new
    assert find_local_file(str(tmp_path), r"peer\missing.mp3", None) is None


# endregion

# region Теги


@pytest.mark.parametrize("name", ["silence.mp3", "silence.flac", "silence.m4a", "silence.opus", "silence.ogg"])
def test_write_tags_and_cover(tmp_path, name):
    file = copy_fixture(tmp_path, name)
    written = write_tags(file, TAGS, COVER)
    assert "cover" in written
    easy = mutagen.File(file, easy=True)
    assert easy["artist"] == ["Трофим"]
    assert easy["title"] == ["Город Сочи"]
    assert easy["album"] == ["Город Сочи (сборник)"]
    assert easy["albumartist"] == ["Трофим"]
    # Повторная запись не добавляет вторую обложку.
    assert "cover" not in write_tags(file, TAGS, COVER)


def test_cover_is_embedded_per_format(tmp_path):
    mp3 = copy_fixture(tmp_path, "silence.mp3")
    write_tags(mp3, TAGS, COVER)
    assert ID3(mp3).getall("APIC")[0].data == COVER[0]
    flac = copy_fixture(tmp_path, "silence.flac")
    write_tags(flac, TAGS, COVER)
    assert FLAC(flac).pictures[0].data == COVER[0]
    m4a = copy_fixture(tmp_path, "silence.m4a")
    write_tags(m4a, TAGS, COVER)
    assert bytes(MP4(m4a).tags["covr"][0]) == COVER[0]
    opus = copy_fixture(tmp_path, "silence.opus")
    write_tags(opus, TAGS, COVER)
    assert OggOpus(opus).get("metadata_block_picture")


def test_existing_tags_are_kept(tmp_path):
    mp3 = copy_fixture(tmp_path, "silence.mp3")
    id3 = ID3()
    id3.add(TRCK(encoding=3, text="7"))
    id3.save(mp3)
    write_tags(mp3, Tags("Трофим", "Город Сочи", None), None)
    tags = ID3(mp3)
    assert tags["TRCK"].text == ["7"]
    assert "TALB" not in tags
    assert tags.version[1] == 4


# endregion

# region Размещение


def test_place_file_is_atomic_and_tagged(tmp_path):
    source = copy_fixture(tmp_path, "silence.flac", "slsk/peer/song.flac")
    dest = tmp_path / "library" / "Трофим" / "Singles" / "Трофим - Город Сочи.flac"
    place_file(source, dest, TAGS, None)
    assert dest.is_file()
    assert not list(dest.parent.glob(".*.part"))
    assert mutagen.File(dest, easy=True)["title"] == ["Город Сочи"]
    assert source.is_file()  # исходник удаляет отдельный шаг


def test_name_conflict(tmp_path):
    library = tmp_path / "library"
    rel = Path("Трофим/Singles/Трофим - Город Сочи.mp3")
    source = copy_fixture(tmp_path, "silence.mp3", "slsk/p/song.mp3")
    other = copy_fixture(tmp_path, "silence.flac", "library/Трофим/Singles/Трофим - Город Сочи.mp3")
    dest, already = choose_destination(library, rel, source, TAGS)
    assert (dest.name, already) == ("Трофим - Город Сочи (2).mp3", False)
    # Тот же размер, что у исходника, — файл уже размещён.
    shutil.copyfile(source, other)
    assert choose_destination(library, rel, source, TAGS) == (other, True)


def test_already_placed_by_our_tags(tmp_path):
    library = tmp_path / "library"
    rel = Path("Трофим/Singles/Трофим - Город Сочи.mp3")
    source = copy_fixture(tmp_path, "silence.mp3", "slsk/p/song.mp3")
    place_file(source, library / rel, TAGS, COVER)  # после тегов размер другой
    assert choose_destination(library, rel, source, TAGS) == (library / rel, True)


def test_remove_source_cleans_only_empty_peer_dir(tmp_path):
    downloads = tmp_path / "slsk"
    lonely = copy_fixture(tmp_path, "silence.mp3", "slsk/peer1/a.mp3")
    shared = copy_fixture(tmp_path, "silence.mp3", "slsk/peer2/b.mp3")
    copy_fixture(tmp_path, "silence.mp3", "slsk/peer2/soularr.mp3")
    root_file = copy_fixture(tmp_path, "silence.mp3", "slsk/c.mp3")
    remove_source(lonely, str(downloads))
    remove_source(shared, str(downloads))
    remove_source(root_file, str(downloads))
    assert not (downloads / "peer1").exists()
    assert (downloads / "peer2" / "soularr.mp3").exists()
    assert downloads.is_dir()


# endregion
