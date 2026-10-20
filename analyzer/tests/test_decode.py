"""Декодирование ffmpeg: стерео 44.1 кГц, смещение для длинных треков, понятная ошибка на битом файле."""

import shutil
import subprocess
from pathlib import Path

import pytest

from bodro_analyzer.decode import SAMPLE_RATE, DecodeError, decode

pytestmark = pytest.mark.skipif(not shutil.which("ffmpeg") or not shutil.which("ffprobe"), reason="нет ffmpeg")


def tone(path: Path, seconds: float, channels: int) -> Path:
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", f"sine=frequency=440:duration={seconds}",
         "-ac", str(channels), "-c:a", "libopus", str(path)],
        check=True,
    )  # fmt: skip
    return path


def test_decodes_opus_mono(tmp_path: Path) -> None:
    decoded = decode(str(tone(tmp_path / "a.opus", 5, 1)))
    assert decoded.channels == 1 and decoded.stereo.shape[1] == 2
    assert abs(len(decoded.mono) / SAMPLE_RATE - 5) < 0.2
    assert decoded.duration is not None and abs(decoded.duration - 5) < 0.2


def test_long_track_is_limited(tmp_path: Path) -> None:
    decoded = decode(str(tone(tmp_path / "long.opus", 200, 2)))
    assert decoded.channels == 2
    assert abs(len(decoded.mono) / SAMPLE_RATE - 120) < 0.5
    assert decoded.duration is not None and decoded.duration > 199


def test_broken_file(tmp_path: Path) -> None:
    broken = tmp_path / "broken.m4a"
    broken.write_bytes(b"not audio at all" * 100)
    with pytest.raises(DecodeError, match="ffprobe"):
        decode(str(broken))
