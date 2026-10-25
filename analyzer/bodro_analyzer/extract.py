"""Признаки трека на Essentia: эмбеддинг discogs-effnet и классификаторы поверх него, темп, тональность, громкость.

Аудио декодирует системный ffmpeg (`decode.py`), Essentia получает готовые массивы — её загрузчики не используются.
Модели (некоммерческая лицензия CC BY-NC-SA 4.0 — для домашнего сервера подходит) лежат в `MODELS_DIR`; Dockerfile
скачивает их при сборке образа. Имена входов/выходов графов берутся из json-метаданных моделей.

Необязательные модели (`OPTIONAL_MODELS`: acoustic / electronic, анализатор 0.3.0) загружаются, только если их файлы
есть: в старой базе образа их нет, тогда признаки не пишутся, а bodrod выводит их из жанров discogs.
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass
from typing import Any

import numpy as np

from . import __version__ as ANALYZER_VERSION
from .decode import SAMPLE_RATE, decode

EMBEDDING_MODEL = "discogs-effnet-bs64-1"
GENRE_MODEL = "genre_discogs400-discogs-effnet-1"
"""Классификатор → положительная метка (её вероятность и пишется)."""
BINARY_MODELS = {
    "mood_happy-discogs-effnet-1": ("happy", "happy"),
    "mood_sad-discogs-effnet-1": ("sad", "sad"),
    "mood_aggressive-discogs-effnet-1": ("aggressive", "aggressive"),
    "mood_relaxed-discogs-effnet-1": ("relaxed", "relaxed"),
    "danceability-discogs-effnet-1": ("danceability", "danceable"),
    "voice_instrumental-discogs-effnet-1": ("voice", "voice"),
}
"""Необязательные классификаторы: нет файлов модели — признак не считается."""
OPTIONAL_MODELS = {
    "mood_acoustic-discogs-effnet-1": ("acoustic", "acoustic"),
    "mood_electronic-discogs-effnet-1": ("electronic", "electronic"),
}
TOP_GENRES = 5


@dataclass
class Analysis:
    features: dict[str, Any]
    embedding: list[float]


class EssentiaExtractor:
    """Загружает модели один раз; `analyze(path)` — признаки одного файла."""

    def __init__(self, models_dir: str) -> None:
        import essentia  # noqa: PLC0415 — тяжёлый импорт только в контейнере

        essentia.log.infoActive = False  # без спама предупреждений на каждый файл
        essentia.log.warningActive = False
        import essentia.standard as es  # noqa: PLC0415

        self._es = es
        self._models_dir = models_dir
        self._embedder = es.TensorflowPredictEffnetDiscogs(
            graphFilename=self._path(EMBEDDING_MODEL, ".pb"), output="PartitionedCall:1"
        )
        meta = self._meta(GENRE_MODEL)
        self._genre_labels: list[str] = meta.get("classes") or []
        self._genre = self._head(GENRE_MODEL, meta, "serving_default_model_Placeholder", "PartitionedCall:0")
        self._binary = {}
        for name, (feature, positive) in BINARY_MODELS.items():
            meta = self._meta(name)
            classes = meta.get("classes") or []
            index = classes.index(positive) if positive in classes else 0
            self._binary[feature] = (self._head(name, meta, "model/Placeholder", "model/Softmax"), index)
        for name, (feature, positive) in OPTIONAL_MODELS.items():
            if not (os.path.exists(self._path(name, ".pb")) and os.path.exists(self._path(name, ".json"))):
                continue
            meta = self._meta(name)
            classes = meta.get("classes") or []
            index = classes.index(positive) if positive in classes else 0
            self._binary[feature] = (self._head(name, meta, "model/Placeholder", "model/Softmax"), index)

    def _path(self, name: str, suffix: str) -> str:
        return os.path.join(self._models_dir, name + suffix)

    def _meta(self, name: str) -> dict[str, Any]:
        with open(self._path(name, ".json"), encoding="utf-8") as f:
            return json.load(f)

    def _head(self, name: str, meta: dict[str, Any], default_in: str, default_out: str):  # noqa: ANN202
        schema = meta.get("schema") or {}
        inputs = [i.get("name") for i in schema.get("inputs") or [] if i.get("name")]
        outputs = [o for o in schema.get("outputs") or [] if o.get("name")]
        output = next((o["name"] for o in outputs if o.get("output_purpose") == "predictions"), None)
        return self._es.TensorflowPredict2D(
            graphFilename=self._path(name, ".pb"),
            input=inputs[0] if inputs else default_in,
            output=output or (outputs[0]["name"] if outputs else default_out),
        )

    def analyze(self, path: str) -> Analysis:
        es = self._es
        decoded = decode(path)
        stereo = np.ascontiguousarray(decoded.stereo)
        audio44 = decoded.mono
        audio16 = es.Resample(inputSampleRate=SAMPLE_RATE, outputSampleRate=16000, quality=1)(audio44)
        frames = self._embedder(audio16)
        embedding = frames.mean(axis=0)
        genres = self._genre(frames).mean(axis=0)
        top = sorted(range(len(genres)), key=lambda i: -genres[i])[:TOP_GENRES]
        features: dict[str, Any] = {
            "genres": [[self._genre_labels[i] if i < len(self._genre_labels) else str(i), round(float(genres[i]), 4)]
                       for i in top],
            "moods": {},
        }  # fmt: skip
        for feature, (model, index) in self._binary.items():
            value = round(float(model(frames).mean(axis=0)[index]), 4)
            if feature in ("happy", "sad", "aggressive", "relaxed"):
                features["moods"][feature] = value
            else:
                features[feature] = value
        bpm, *_ = es.RhythmExtractor2013(method="multifeature")(audio44)
        key, scale, strength = es.KeyExtractor()(audio44)
        # Моно-файл ffmpeg уже раскладывает в два одинаковых канала — так его и слышно; раньше громкость моно не
        # считалась, и у таких треков не было признака loudness.
        loudness = es.LoudnessEBUR128(sampleRate=SAMPLE_RATE)(stereo)[2]
        rms = float(np.sqrt(np.mean(np.square(audio44, dtype=np.float64)))) if len(audio44) else 0.0
        features.update(
            {
                "bpm": round(float(bpm), 2),
                "key": key,
                "scale": scale,
                "keyStrength": round(float(strength), 3),
                "loudness": round(float(loudness), 2) if loudness is not None else None,
                "rmsDb": round(20 * float(np.log10(rms)), 2) if rms > 0 else None,
                "analyzerVersion": ANALYZER_VERSION,
                "duration": round(decoded.duration if decoded.duration else len(audio44) / SAMPLE_RATE, 1),
            }
        )
        return Analysis(features=features, embedding=[round(float(x), 6) for x in embedding])
