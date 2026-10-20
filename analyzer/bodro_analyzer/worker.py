"""Цикл воркера: взять задания у bodrod, посчитать признаки, отдать результат. Один файл за раз.

Анализ идёт в дочернем процессе (`isolated.py`): нативный краш помечает трек `failed`, воркер продолжает работу.

Переменные окружения:
- `BODROD_URL` — адрес bodrod (`http://bodrod:8790`);
- `ANALYZER_TOKEN` (или `ANALYZER_TOKEN_FILE`) — тот же токен, что у bodrod;
- `MUSIC_DIR` — папка музыки внутри контейнера (пути заданий — относительно неё), по умолчанию `/music`;
- `MODELS_DIR` — модели Essentia (в образе — `/models`);
- `BATCH` — сколько заданий брать за раз (10), `IDLE_S` — пауза, когда заданий нет (300);
- `TRACK_TIMEOUT_S` — предел анализа одного трека (600), после него дочерний процесс убивается.
"""

from __future__ import annotations

import logging
import math
import os
import time
from collections.abc import Callable
from typing import Any, Protocol

import httpx

from .isolated import ExtractorUnavailable

log = logging.getLogger("bodro_analyzer")


class Extractor(Protocol):
    def analyze(self, path: str) -> Any: ...


def _env(name: str, default: str | None = None) -> str | None:
    value = os.environ.get(name, "").strip()
    path = os.environ.get(f"{name}_FILE", "").strip()
    if not value and path:
        with open(path, encoding="utf-8") as f:
            value = f.read().strip()
    return value or default


def json_safe(value: Any) -> Any:
    """Рекурсивно привести результат к JSON: numpy-скаляры — `.item()`, массивы — `.tolist()`, NaN и ±inf — `None`.

    Тихий трек даёт громкость `-inf`, а `httpx` с `json=` такие числа не сериализует (`ValueError`).
    """
    if type(value).__module__ == "numpy":
        value = value.tolist() if getattr(value, "ndim", 0) else value.item()
    if isinstance(value, float):
        return value if math.isfinite(value) else None
    if isinstance(value, dict):
        return {str(k): json_safe(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [json_safe(v) for v in value]
    return value


class Worker:
    def __init__(
        self,
        http: httpx.Client,
        base_url: str,
        token: str,
        music_dir: str,
        extractor: Extractor,
        batch: int = 10,
        idle_s: float = 300,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        self._http = http
        self._base = base_url.rstrip("/")
        self._headers = {"X-Analyzer-Token": token}
        self._music_dir = music_dir
        self._extractor = extractor
        self._batch = batch
        self._idle_s = idle_s
        self._sleep = sleep

    def jobs(self) -> list[str]:
        response = self._http.get(
            f"{self._base}/api/v1/analysis/next", params={"limit": self._batch}, headers=self._headers, timeout=60
        )
        response.raise_for_status()
        return response.json().get("jobs") or []

    def _send(self, body: dict[str, Any]) -> None:
        response = self._http.post(
            f"{self._base}/api/v1/analysis/result", json=json_safe(body), headers=self._headers, timeout=60
        )
        response.raise_for_status()

    def _fail(self, relative: str, error: str) -> None:
        self._send({"path": relative, "status": "failed", "error": error[:500]})

    def process(self, relative: str) -> bool:
        """Посчитать и отправить один файл. `True` — признаки посчитаны."""
        local = os.path.join(self._music_dir, *relative.split("/"))
        if not os.path.isfile(local):
            self._fail(relative, f"Нет файла: {local}")
            return False
        started = time.monotonic()
        result = self._extractor.analyze(local)
        self._send({"path": relative, "status": "ok", "features": result.features, "embedding": result.embedding})
        log.info("%s — %.1f с", relative, time.monotonic() - started)
        return True

    def run_once(self) -> int:
        jobs = self.jobs()
        for relative in jobs:
            try:
                self.process(relative)
            except (httpx.HTTPError, ExtractorUnavailable):
                raise  # bodrod недоступен — повтор всей пачки в run_forever; модели не грузятся — остановка
            except Exception as e:  # noqa: BLE001 — один трек не роняет воркер
                log.exception("%s — ошибка", relative)  # путь и traceback
                try:
                    self._fail(relative, f"{type(e).__name__}: {e}")
                except httpx.HTTPError:
                    raise
                except Exception:  # noqa: BLE001
                    log.exception("%s — не удалось отправить статус failed", relative)
        return len(jobs)

    def run_forever(self) -> None:
        while True:
            try:
                if self.run_once() == 0:
                    self._sleep(self._idle_s)
            except httpx.HTTPError as e:
                log.warning("bodrod недоступен: %s — повтор через минуту", e)
                self._sleep(60)


def main() -> None:
    os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")  # до импорта TensorFlow (через essentia)
    logging.basicConfig(level=os.environ.get("LOG_LEVEL", "INFO"), format="%(asctime)s %(levelname)s %(message)s")
    token = _env("ANALYZER_TOKEN")
    base = _env("BODROD_URL")
    if not token or not base:
        raise SystemExit("Нужно задать BODROD_URL и ANALYZER_TOKEN")
    from .isolated import IsolatedExtractor

    extractor = IsolatedExtractor(
        _env("MODELS_DIR", "/models") or "/models", timeout_s=float(_env("TRACK_TIMEOUT_S", "600") or 600)
    )
    worker = Worker(
        httpx.Client(),
        base,
        token,
        _env("MUSIC_DIR", "/music") or "/music",
        extractor,
        batch=int(_env("BATCH", "10") or 10),
        idle_s=float(_env("IDLE_S", "300") or 300),
    )
    log.info("Анализатор запущен: %s", base)
    worker.run_forever()
