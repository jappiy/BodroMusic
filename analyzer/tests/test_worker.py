"""Цикл воркера на подделках: задания, отсутствующий файл, ошибка анализа, результат."""

import json
from pathlib import Path

import httpx

from bodro_analyzer.extract import Analysis
from bodro_analyzer.worker import Worker


class FakeExtractor:
    def __init__(self) -> None:
        self.paths: list[str] = []

    def analyze(self, path: str) -> Analysis:
        self.paths.append(path)
        if path.endswith("broken.flac"):
            raise ValueError("не аудио")
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
    assert "не аудио" in by_path["broken.flac"]["error"]
    assert by_path["missing.flac"]["error"].startswith("Нет файла")
    assert len(extractor.paths) == 2


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
