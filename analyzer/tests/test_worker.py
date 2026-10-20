"""Цикл воркера на подделках: задания, отсутствующий файл, ошибка анализа, результат."""

import json
import math
from pathlib import Path

import httpx
import pytest

from bodro_analyzer.extract import Analysis
from bodro_analyzer.worker import Worker, json_safe


class FakeExtractor:
    def __init__(self) -> None:
        self.paths: list[str] = []

    def analyze(self, path: str) -> Analysis:
        self.paths.append(path)
        if path.endswith("broken.flac"):
            raise ValueError("не аудио")
        if path.endswith("silent.flac"):  # тишина: громкость -inf
            return Analysis(features={"loudness": -math.inf, "bpm": math.nan}, embedding=[0.0, math.inf])
        return Analysis(features={"bpm": 120.0}, embedding=[0.1, 0.2])


def test_processes_jobs_and_reports_errors(tmp_path: Path) -> None:
    (tmp_path / "Artist").mkdir()
    (tmp_path / "Artist" / "song.flac").write_bytes(b"x")
    (tmp_path / "broken.flac").write_bytes(b"x")
    results: list[dict] = []

    def handle(request: httpx.Request) -> httpx.Response:
        assert request.headers["X-Analyzer-Token"] == "t"
        if request.url.path.endswith("/next"):
            return httpx.Response(200, json={"jobs": ["Artist/song.flac", "broken.flac", "missing.flac"]})
        results.append(json.loads(request.content))
        return httpx.Response(204)

    extractor = FakeExtractor()
    worker = Worker(httpx.Client(transport=httpx.MockTransport(handle)), "http://bodrod", "t", str(tmp_path), extractor)
    assert worker.run_once() == 3
    by_path = {r["path"]: r for r in results}
    assert by_path["Artist/song.flac"]["features"] == {"bpm": 120.0}
    assert by_path["Artist/song.flac"]["embedding"] == [0.1, 0.2]
    assert by_path["Artist/song.flac"]["status"] == "ok"
    assert "не аудио" in by_path["broken.flac"]["error"]
    assert by_path["broken.flac"]["status"] == "failed"
    assert by_path["missing.flac"]["error"].startswith("Нет файла")
    assert by_path["missing.flac"]["status"] == "failed"
    assert len(extractor.paths) == 2


def test_non_finite_values_sent_as_null(tmp_path: Path) -> None:
    (tmp_path / "silent.flac").write_bytes(b"x")
    results: list[dict] = []

    def handle(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/next"):
            return httpx.Response(200, json={"jobs": ["silent.flac"]})
        results.append(json.loads(request.content))
        return httpx.Response(204)

    worker = Worker(
        httpx.Client(transport=httpx.MockTransport(handle)), "http://b", "t", str(tmp_path), FakeExtractor()
    )
    assert worker.run_once() == 1
    assert results == [
        {"path": "silent.flac", "status": "ok", "features": {"loudness": None, "bpm": None}, "embedding": [0.0, None]}
    ]


def test_unexpected_error_does_not_stop_batch(tmp_path: Path) -> None:
    for name in ("a.flac", "b.flac"):
        (tmp_path / name).write_bytes(b"x")
    results: list[dict] = []

    def handle(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/next"):
            return httpx.Response(200, json={"jobs": ["a.flac", "b.flac"]})
        results.append(json.loads(request.content))
        return httpx.Response(204)

    class Exploding(FakeExtractor):
        def analyze(self, path: str) -> Analysis:
            if path.endswith("a.flac"):
                return Analysis(features={"x": object()}, embedding=[])  # не сериализуется в JSON
            return super().analyze(path)

    worker = Worker(httpx.Client(transport=httpx.MockTransport(handle)), "http://b", "t", str(tmp_path), Exploding())
    assert worker.run_once() == 2
    assert [r["path"] for r in results] == ["a.flac", "b.flac"]
    assert results[0]["status"] == "failed" and "TypeError" in results[0]["error"]


def test_json_safe_numpy() -> None:
    np = pytest.importorskip("numpy")
    value = {"a": np.float32(1.5), "b": np.array([1.0, np.nan]), "c": [np.float64(-np.inf)], "d": np.int64(3)}
    assert json_safe(value) == {"a": 1.5, "b": [1.0, None], "c": [None], "d": 3}


def test_idle_sleep_when_no_jobs() -> None:
    slept: list[float] = []
    calls = {"n": 0}

    def handle(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        if calls["n"] > 1:
            raise KeyboardInterrupt
        return httpx.Response(200, json={"jobs": []})

    worker = Worker(
        httpx.Client(transport=httpx.MockTransport(handle)), "http://b", "t", "/m", FakeExtractor(), idle_s=5,
        sleep=slept.append,
    )  # fmt: skip
    try:
        worker.run_forever()
    except KeyboardInterrupt:
        pass
    assert slept == [5]
