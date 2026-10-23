"""Хэш аудиоданных файла без тегов — стабильный ключ анализа звучания, когда у трека нет MBID.

Перетегирование (beets, регламент обслуживания) и перенос файла меняют путь и id в Navidrome, но не звук. Хэш берётся
по аудиоданным без контейнеров тегов, так что новые теги и обложка его не меняют:
- FLAC — MD5 несжатого звука из STREAMINFO (его пишет кодировщик); если он нулевой — кадры после метаданных;
- MP3 и прочее с ID3 — без ID3v2 в начале, ID3v1 и APEv2 в конце;
- MP4 / M4A — содержимое атома `mdat`;
- Ogg (Vorbis, Opus, FLAC в Ogg) — полезная нагрузка страниц со звуком (у страниц заголовков позиция 0).
Берётся первый мегабайт аудиоданных и их размер — этого хватает, чтобы различать треки, и чтение остаётся дешёвым.
"""

from __future__ import annotations

import hashlib
import struct
from pathlib import Path

HASH_BYTES = 1024 * 1024


def audio_hash(path: Path) -> str | None:
    """`flac:<md5>` или `<формат>:<sha1>`; `None` — файла нет или он не читается."""
    try:
        with path.open("rb") as f:
            head = f.read(12)
            f.seek(0, 2)
            size = f.tell()
            if head.startswith(b"fLaC"):
                return _flac(f, size)
            if head.startswith(b"OggS"):
                return _ogg(f)
            if head[4:8] == b"ftyp":
                return _mp4(f, size)
            return _plain(f, size)
    except OSError:
        return None


def _digest(kind: str, f, start: int, length: int) -> str:  # noqa: ANN001
    f.seek(start)
    data = f.read(min(HASH_BYTES, max(0, length)))
    digest = hashlib.sha1(data, usedforsecurity=False)
    digest.update(str(length).encode())
    return f"{kind}:{digest.hexdigest()}"


def _flac(f, size: int) -> str:  # noqa: ANN001
    f.seek(4)
    offset = 4
    md5 = None
    while True:
        header = f.read(4)
        if len(header) < 4:
            break
        last, block_type = header[0] & 0x80, header[0] & 0x7F
        length = int.from_bytes(header[1:4], "big")
        if block_type == 0 and length >= 34:
            info = f.read(34)
            md5 = info[18:34]
            f.seek(offset + 4 + length)
        else:
            f.seek(length, 1)
        offset += 4 + length
        if last:
            break
    if md5 and any(md5):
        return f"flac:{md5.hex()}"
    return _digest("flac-frames", f, offset, size - offset)


def _ogg(f) -> str:  # noqa: ANN001
    """Нагрузка страниц со звуком: комментарии (теги) лежат в страницах с позицией 0 и сюда не попадают."""
    f.seek(0)
    digest = hashlib.sha1(usedforsecurity=False)
    taken = 0
    while taken < HASH_BYTES:
        header = f.read(27)
        if len(header) < 27 or not header.startswith(b"OggS"):
            break
        granule = struct.unpack("<q", header[6:14])[0]
        segments = f.read(header[26])
        body = sum(segments)
        if granule == 0:
            f.seek(body, 1)
            continue
        data = f.read(body)
        digest.update(data)
        taken += len(data)
    return f"ogg:{digest.hexdigest()}"


def _mp4(f, size: int) -> str:  # noqa: ANN001
    offset = 0
    while offset + 8 <= size:
        f.seek(offset)
        header = f.read(16)
        length = int.from_bytes(header[0:4], "big")
        kind = header[4:8]
        body = offset + 8
        if length == 1:
            length = int.from_bytes(header[8:16], "big")
            body = offset + 16
        elif length == 0:
            length = size - offset
        if length < 8:
            break
        if kind == b"mdat":
            return _digest("mp4", f, body, offset + length - body)
        offset += length
    return _plain(f, size)


def _plain(f, size: int) -> str:  # noqa: ANN001
    """Файл без своего контейнера тегов (MP3, WAV…): без ID3v2 в начале, ID3v1 и APEv2 в конце."""
    f.seek(0)
    head = f.read(10)
    start = 0
    if head.startswith(b"ID3") and len(head) == 10:
        tag = (head[6] << 21) | (head[7] << 14) | (head[8] << 7) | head[9]
        start = 10 + tag + (10 if head[5] & 0x10 else 0)
    end = size
    if end - start >= 128:
        f.seek(end - 128)
        if f.read(3) == b"TAG":
            end -= 128
    if end - start >= 32:
        f.seek(end - 32)
        footer = f.read(32)
        if footer.startswith(b"APETAGEX"):
            tag_size = int.from_bytes(footer[12:16], "little")
            flags = int.from_bytes(footer[20:24], "little")
            end -= tag_size + (32 if flags & 0x80000000 else 0)
    return _digest("audio", f, start, end - start)
