"""Размещение скачанного файла в библиотеке: поиск в папке slskd, понятное имя, теги и обложка из заявки.

Все функции синхронные (файловые операции); воркер вызывает их через `asyncio.to_thread`.
"""

from __future__ import annotations

import base64
import contextlib
import logging
import os
import re
import shutil
import unicodedata
from dataclasses import dataclass
from pathlib import Path

from .text import normalize

log = logging.getLogger(__name__)

DEFAULT_LAYOUT = "{artist}/{album}/{artist} - {title}"
NO_ALBUM = "Singles"
MAX_PART_BYTES = 120
_FORBIDDEN = re.compile(r'[/\\:*?"<>|]')
# slskd добавляет к имени `_<число>`, если файл с таким именем уже есть в папке.
_SLSKD_SUFFIX = re.compile(r"^(?P<stem>.+?)(?:_\d{6,})?$")


def sanitize_part(value: str | None, fallback: str = "Unknown") -> str:
    """Одна часть пути: без запрещённых и управляющих символов, без точек/пробелов по краям, до 120 байт UTF-8."""
    text = unicodedata.normalize("NFC", value or "")
    text = "".join(" " if unicodedata.category(ch).startswith("C") else ch for ch in text)
    text = _FORBIDDEN.sub(" ", text)
    text = re.sub(r"\s+", " ", text).strip(" .")
    encoded = text.encode("utf-8")
    if len(encoded) > MAX_PART_BYTES:
        text = encoded[:MAX_PART_BYTES].decode("utf-8", errors="ignore").rstrip(" .")
    return text or fallback


def relative_path(layout: str, *, artist: str, title: str, album: str | None, extension: str) -> Path:
    """Путь внутри библиотеки по шаблону `LIBRARY_LAYOUT` + исходное расширение в нижнем регистре."""
    values = {
        "artist": sanitize_part(artist),
        "title": sanitize_part(title),
        "album": sanitize_part(album, NO_ALBUM) if album and album.strip() else NO_ALBUM,
    }
    parts = [sanitize_part(part.format(**values)) for part in layout.strip("/").split("/") if part.strip()]
    ext = extension.lower().lstrip(".")
    parts[-1] = f"{parts[-1]}.{ext}" if ext else parts[-1]
    return Path(*parts)


def remote_basename(remote: str) -> tuple[str | None, str]:
    """`Music\\Трофим\\01 Город Сочи.mp3` → (`Трофим`, `01 Город Сочи.mp3`)."""
    parts = [p for p in remote.replace("\\", "/").split("/") if p]
    return (parts[-2] if len(parts) > 1 else None), (parts[-1] if parts else remote)


def _same_name(candidate: str, wanted: str) -> bool:
    if candidate == wanted:
        return True
    c_stem, c_ext = os.path.splitext(candidate)
    w_stem, w_ext = os.path.splitext(wanted)
    match = _SLSKD_SUFFIX.match(c_stem)
    return c_ext.lower() == w_ext.lower() and match is not None and match.group("stem") == w_stem


def find_local_file(downloads_dir: str, remote: str, size: int | None) -> Path | None:
    """Файл, который slskd сохранил для `remote`: сначала ожидаемый путь, потом поиск по имени во всей папке."""
    root = Path(downloads_dir)
    parent, name = remote_basename(remote)
    if parent:
        expected = root / parent / name
        if expected.is_file() and (not size or expected.stat().st_size == size):
            return expected
    candidates: list[Path] = []
    for dirpath, _dirs, files in os.walk(root):
        for file in files:
            if not file.startswith(".") and _same_name(file, name):
                candidates.append(Path(dirpath) / file)
    if not candidates:
        return None
    if size:
        sized = [c for c in candidates if c.stat().st_size == size]
        if sized:
            candidates = sized
    return max(candidates, key=lambda p: p.stat().st_mtime)


@dataclass(frozen=True)
class Tags:
    artist: str
    title: str
    album: str | None
    """Остальное — из каталога (Яндекс): пустое не пишется, существующие теги файла не стираются."""
    album_artist: str | None = None
    track_no: int | None = None
    disc_no: int | None = None
    year: int | None = None
    genre: str | None = None
    lyrics: str | None = None

    @property
    def extra_names(self) -> list[str]:
        names = [("tracknumber", self.track_no), ("discnumber", self.disc_no), ("date", self.year)]
        names += [("genre", self.genre), ("lyrics", self.lyrics)]
        return [n for n, v in names if v]


def read_artist_title(path: Path) -> tuple[str, str] | None:
    """Исполнитель и название из тегов файла (для проверки «этот файл уже размещён»)."""
    try:
        import mutagen

        audio = mutagen.File(path, easy=True)
    except Exception:  # noqa: BLE001 — не аудио или битый файл
        return None
    if audio is None or not audio.tags:
        return None
    artist = (audio.tags.get("artist") or [""])[0]
    title = (audio.tags.get("title") or [""])[0]
    return (artist, title) if artist and title else None


def choose_destination(library: Path, rel: Path, source: Path, tags: Tags) -> tuple[Path, bool]:
    """Свободное имя в библиотеке: `(путь, уже_размещён)`. Тот же файл (размер или наши теги) — повтор не нужен."""
    stem, suffix = rel.stem, rel.suffix
    source_size = source.stat().st_size
    for n in range(1, 100):
        name = f"{stem}{suffix}" if n == 1 else f"{stem} ({n}){suffix}"
        dest = library / rel.parent / name
        if not dest.exists():
            return dest, False
        if dest.stat().st_size == source_size:
            return dest, True
        existing = read_artist_title(dest)
        if (
            existing
            and normalize(existing[0]) == normalize(tags.artist)
            and normalize(existing[1]) == normalize(tags.title)
        ):
            return dest, True
    raise RuntimeError(f"Слишком много файлов с именем {rel}")


def place_file(source: Path, dest: Path, tags: Tags, cover: tuple[bytes, str] | None) -> list[str]:
    """Копия во временный `.<имя>.part`, теги, затем атомарное переименование. Возвращает записанные теги."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_name(f".{dest.name}.part")
    try:
        shutil.copyfile(source, tmp)
        written = write_tags(tmp, tags, cover)
        os.replace(tmp, dest)
    finally:
        with contextlib.suppress(FileNotFoundError):
            tmp.unlink()
    return written


def remove_source(source: Path, downloads_dir: str) -> None:
    """Удалить исходник и папку пира, если она опустела (в ней могут лежать файлы Soularr — их не трогаем)."""
    source.unlink(missing_ok=True)
    root = Path(downloads_dir).resolve()
    parent = source.parent.resolve()
    if parent != root and root in parent.parents:
        with contextlib.suppress(OSError):
            parent.rmdir()  # только пустую


# region Теги


def write_tags(path: Path, tags: Tags, cover: tuple[bytes, str] | None) -> list[str]:
    """artist / albumartist / title / album + обложка, если её нет. Остальные теги (номер, год, жанр) не трогаем.

    Если существующие теги файла битые и mutagen не может их пересохранить, теги стираются и пишутся заново.
    """
    try:
        return _write_tags(path, tags, cover)
    except Exception:
        log.warning("Не удалось обновить теги %s, пишем их заново с нуля", path.name, exc_info=True)
    import mutagen

    audio = mutagen.File(path)
    if audio is not None and audio.tags is not None:
        audio.delete()
    return _write_tags(path, tags, cover) + ["(старые теги удалены)"]


def _write_tags(path: Path, tags: Tags, cover: tuple[bytes, str] | None) -> list[str]:
    import mutagen
    from mutagen.flac import FLAC
    from mutagen.mp3 import MP3
    from mutagen.mp4 import MP4
    from mutagen.oggopus import OggOpus
    from mutagen.oggvorbis import OggVorbis

    audio = mutagen.File(path)
    if audio is None:
        log.warning("Не удалось определить формат %s — теги не записаны", path.name)
        return []
    written = ["artist", "albumartist", "title"] + (["album"] if tags.album else []) + tags.extra_names
    if isinstance(audio, MP3):
        added_cover = _tag_mp3(audio, tags, cover)
    elif isinstance(audio, MP4):
        added_cover = _tag_mp4(audio, tags, cover)
    elif isinstance(audio, FLAC):
        _tag_vorbis(audio, tags)
        added_cover = _cover_flac(audio, cover)
    elif isinstance(audio, OggVorbis | OggOpus):
        _tag_vorbis(audio, tags)
        added_cover = _cover_ogg(audio, cover)
    else:
        easy = mutagen.File(path, easy=True)
        if easy is None:
            return []
        if easy.tags is None:
            easy.add_tags()
        for key, value in _vorbis_values(tags).items():
            with contextlib.suppress(Exception):
                easy[key] = value
        easy.save()
        return written
    audio.save(**({"v2_version": 4} if isinstance(audio, MP3) else {}))
    return written + (["cover"] if added_cover else [])


def _vorbis_values(tags: Tags) -> dict[str, str]:
    values = {"artist": tags.artist, "albumartist": tags.album_artist or tags.artist, "title": tags.title}
    if tags.album:
        values["album"] = tags.album
    if tags.track_no:
        values["tracknumber"] = str(tags.track_no)
    if tags.disc_no:
        values["discnumber"] = str(tags.disc_no)
    if tags.year:
        values["date"] = str(tags.year)
    if tags.genre:
        values["genre"] = tags.genre
    if tags.lyrics:
        values["lyrics"] = tags.lyrics
    return values


def _tag_vorbis(audio, tags: Tags) -> None:  # noqa: ANN001 — FLAC / OggVorbis / OggOpus
    if audio.tags is None:
        audio.add_tags()
    for key, value in _vorbis_values(tags).items():
        audio[key] = value


def _picture(cover: tuple[bytes, str]):  # noqa: ANN202
    from mutagen.flac import Picture

    data, mime = cover
    picture = Picture()
    picture.type = 3  # обложка (front cover)
    picture.mime = mime
    picture.data = data
    return picture


def _cover_flac(audio, cover: tuple[bytes, str] | None) -> bool:  # noqa: ANN001
    if cover is None or audio.pictures:
        return False
    audio.add_picture(_picture(cover))
    return True


def _cover_ogg(audio, cover: tuple[bytes, str] | None) -> bool:  # noqa: ANN001
    if cover is None or audio.get("metadata_block_picture"):
        return False
    audio["metadata_block_picture"] = [base64.b64encode(_picture(cover).write()).decode("ascii")]
    return True


def embed_cover(path: Path, cover: tuple[bytes, str]) -> bool:
    """Вшить обложку в файл, если её там нет. `False` — уже есть или формат не поддерживается."""
    import mutagen
    from mutagen.flac import FLAC
    from mutagen.mp3 import MP3
    from mutagen.mp4 import MP4, MP4Cover
    from mutagen.oggopus import OggOpus
    from mutagen.oggvorbis import OggVorbis

    audio = mutagen.File(path)
    data, mime = cover
    if isinstance(audio, FLAC):
        added = _cover_flac(audio, cover)
    elif isinstance(audio, OggVorbis | OggOpus):
        added = _cover_ogg(audio, cover)
    elif isinstance(audio, MP3):
        from mutagen.id3 import APIC

        if audio.tags is None:
            audio.add_tags()
        added = not audio.tags.getall("APIC")
        if added:
            audio.tags.add(APIC(encoding=3, mime=mime, type=3, desc="Cover", data=data))
    elif isinstance(audio, MP4):
        if audio.tags is None:
            audio.add_tags()
        added = not audio.tags.get("covr")
        if added:
            kind = MP4Cover.FORMAT_PNG if mime == "image/png" else MP4Cover.FORMAT_JPEG
            audio.tags["covr"] = [MP4Cover(data, imageformat=kind)]
    else:
        return False
    if added:
        audio.save(**({"v2_version": 4} if isinstance(audio, MP3) else {}))
    return added


def read_embedded_cover(path: Path) -> tuple[bytes, str] | None:
    """Встроенная обложка файла (картинка и MIME) — для соседних треков того же альбома."""
    try:
        import mutagen

        audio = mutagen.File(path)
    except Exception:  # noqa: BLE001 — битый файл
        return None
    if audio is None:
        return None
    pictures = getattr(audio, "pictures", None)
    if pictures:
        return pictures[0].data, pictures[0].mime or "image/jpeg"
    tags = audio.tags
    if tags is None:
        return None
    with contextlib.suppress(Exception):
        if hasattr(tags, "getall") and tags.getall("APIC"):
            apic = tags.getall("APIC")[0]
            return apic.data, apic.mime or "image/jpeg"
        covr = tags.get("covr") if hasattr(tags, "get") else None
        if covr:
            return bytes(covr[0]), "image/png" if covr[0].imageformat == covr[0].FORMAT_PNG else "image/jpeg"
        block = tags.get("metadata_block_picture") if hasattr(tags, "get") else None
        if block:
            from mutagen.flac import Picture

            picture = Picture(base64.b64decode(block[0]))
            return picture.data, picture.mime or "image/jpeg"
    return None


def _tag_mp3(audio, tags: Tags, cover: tuple[bytes, str] | None) -> bool:  # noqa: ANN001
    from mutagen.id3 import APIC, TALB, TCON, TDRC, TIT2, TPE1, TPE2, TPOS, TRCK, USLT

    if audio.tags is None:
        audio.add_tags()
    id3 = audio.tags
    id3.setall("TPE1", [TPE1(encoding=3, text=tags.artist)])
    id3.setall("TPE2", [TPE2(encoding=3, text=tags.album_artist or tags.artist)])
    id3.setall("TIT2", [TIT2(encoding=3, text=tags.title)])
    if tags.album:
        id3.setall("TALB", [TALB(encoding=3, text=tags.album)])
    if tags.track_no:
        id3.setall("TRCK", [TRCK(encoding=3, text=str(tags.track_no))])
    if tags.disc_no:
        id3.setall("TPOS", [TPOS(encoding=3, text=str(tags.disc_no))])
    if tags.year:
        id3.setall("TDRC", [TDRC(encoding=3, text=str(tags.year))])
    if tags.genre:
        id3.setall("TCON", [TCON(encoding=3, text=tags.genre)])
    if tags.lyrics:
        id3.setall("USLT", [USLT(encoding=3, lang="rus", desc="", text=tags.lyrics)])
    if cover is None or id3.getall("APIC"):
        return False
    data, mime = cover
    id3.add(APIC(encoding=3, mime=mime, type=3, desc="Cover", data=data))
    return True


def _tag_mp4(audio, tags: Tags, cover: tuple[bytes, str] | None) -> bool:  # noqa: ANN001
    from mutagen.mp4 import MP4Cover

    if audio.tags is None:
        audio.add_tags()
    audio.tags["\xa9ART"] = [tags.artist]
    audio.tags["aART"] = [tags.artist]
    audio.tags["\xa9nam"] = [tags.title]
    if tags.album:
        audio.tags["\xa9alb"] = [tags.album]
    if cover is None or audio.tags.get("covr"):
        return False
    data, mime = cover
    kind = MP4Cover.FORMAT_PNG if mime == "image/png" else MP4Cover.FORMAT_JPEG
    audio.tags["covr"] = [MP4Cover(data, imageformat=kind)]
    return True


# endregion
