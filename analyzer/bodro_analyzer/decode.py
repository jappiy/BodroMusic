"""Декодирование системным ffmpeg в подпроцессе: любой формат (opus, m4a, …) → numpy float32.

Essentia `MonoLoader`/`AudioLoader` падали на части файлов (opus, «Could not find stream information» на m4a), а
нативный краш ронял весь воркер. ffmpeg запускается отдельным процессом: битый файл даёт `DecodeError` с текстом
stderr, а не краш.
"""

from __future__ import annotations

import json
import subprocess
from dataclasses import dataclass

import numpy as np

SAMPLE_RATE = 44100
ANALYZE_S = 120.0
"""Сколько секунд трека анализировать."""
SKIP_INTRO_S = 30.0
"""Длинные треки (дольше `ANALYZE_S + SKIP_INTRO_S`) анализируются со смещением: вступление нехарактерно."""


class DecodeError(RuntimeError):
    pass


@dataclass
class Decoded:
    stereo: np.ndarray
    """(n, 2) float32, `SAMPLE_RATE`; моно-файл — оба канала одинаковые."""
    channels: int
    """Каналов в исходном файле (громкость EBU R128 считается только для стерео)."""
    duration: float | None
    """Длительность всего файла, с (из ffprobe)."""

    @property
    def mono(self) -> np.ndarray:
        return self.stereo.mean(axis=1).astype(np.float32)


def _run(args: list[str], timeout: float) -> bytes:
    try:
        done = subprocess.run(args, capture_output=True, timeout=timeout, check=False, stdin=subprocess.DEVNULL)
    except FileNotFoundError as e:
        raise DecodeError(f"Не найден {args[0]}") from e
    except subprocess.TimeoutExpired as e:
        raise DecodeError(f"{args[0]}: нет ответа за {timeout:.0f} с") from e
    if done.returncode != 0:
        message = done.stderr.decode("utf-8", "replace").strip().splitlines()
        raise DecodeError(f"{args[0]}: {message[-1] if message else f'код {done.returncode}'}")
    return done.stdout


def probe(path: str, timeout: float = 60) -> tuple[float | None, int]:
    """Длительность (с) и число каналов первой аудиодорожки."""
    out = _run(
        ["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=channels:format=duration",
         "-of", "json", path],
        timeout,
    )  # fmt: skip
    try:
        info = json.loads(out or b"{}")
    except ValueError as e:
        raise DecodeError("ffprobe: непонятный ответ") from e
    streams = info.get("streams") or []
    if not streams:
        raise DecodeError("ffprobe: в файле нет аудиодорожки")
    try:
        duration = float((info.get("format") or {}).get("duration"))
    except (TypeError, ValueError):
        duration = None
    return duration, int(streams[0].get("channels") or 2)


def decode(path: str, timeout: float = 300) -> Decoded:
    duration, channels = probe(path)
    offset = SKIP_INTRO_S if duration and duration > ANALYZE_S + SKIP_INTRO_S else 0.0
    out = _run(
        ["ffmpeg", "-v", "error", "-nostdin", "-ss", f"{offset:g}", "-i", path, "-map", "0:a:0", "-vn",
         "-t", f"{ANALYZE_S:g}", "-f", "f32le", "-acodec", "pcm_f32le", "-ac", "2", "-ar", str(SAMPLE_RATE), "-"],
        timeout,
    )  # fmt: skip
    samples = np.frombuffer(out, dtype="<f4")
    if len(samples) < 2 * SAMPLE_RATE:  # меньше секунды
        raise DecodeError("ffmpeg: нет аудиоданных")
    stereo = samples[: len(samples) // 2 * 2].reshape(-1, 2).astype(np.float32)
    return Decoded(stereo=stereo, channels=channels, duration=duration)
