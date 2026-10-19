"""Признаки трека на Essentia: эмбеддинг discogs-effnet и классификаторы поверх него, темп, тональность, громкость.

Модели (некоммерческая лицензия CC BY-NC-SA 4.0 — для домашнего сервера подходит) лежат в `MODELS_DIR`; Dockerfile
скачивает их при сборке образа. Имена входов/выходов графов берутся из json-метаданных моделей.
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass
from typing import Any

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
TOP_GENRES = 5


@dataclass
class Analysis:
    features: dict[str, Any]
    embedding: list[float]


class EssentiaExtractor:
    """Загружает модели один раз; `analyze(path)` — признаки одного файла."""

    def __init__(self, models_dir: str) -> None:
        import essentia.standard as es  # noqa: PLC0415 — тяжёлый импорт только в контейнере

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
        audio16 = es.MonoLoader(filename=path, sampleRate=16000, resampleQuality=4)()
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
        stereo, rate, *_ = es.AudioLoader(filename=path)()
        mono = es.MonoMixer()(stereo, stereo.shape[1] if stereo.ndim > 1 else 1)
        audio44 = es.Resample(inputSampleRate=rate, outputSampleRate=44100)(mono) if rate != 44100 else mono
        bpm, *_ = es.RhythmExtractor2013(method="multifeature")(audio44)
        key, scale, strength = es.KeyExtractor()(audio44)
        loudness = es.LoudnessEBUR128(sampleRate=rate)(stereo)[2] if stereo.ndim > 1 else None
        features.update(
            {
                "bpm": round(float(bpm), 2),
                "key": key,
                "scale": scale,
                "keyStrength": round(float(strength), 3),
                "loudness": round(float(loudness), 2) if loudness is not None else None,
                "duration": round(len(audio16) / 16000.0, 1),
            }
        )
        return Analysis(features=features, embedding=[round(float(x), 6) for x in embedding])
