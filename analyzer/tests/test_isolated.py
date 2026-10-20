"""Анализ в дочернем процессе: краш и зависание трека не роняют воркер, процесс перезапускается."""

import pytest

from bodro_analyzer.isolated import AnalysisCrashed, ExtractorUnavailable, IsolatedExtractor

from tests.fakes import broken_factory, fake_factory


def test_crash_and_hang_kill_only_child() -> None:
    extractor = IsolatedExtractor("/models", factory=fake_factory, timeout_s=3)
    try:
        first = extractor.analyze("/m/a.flac").features["pid"]
        assert extractor.analyze("/m/b.flac").features["pid"] == first  # модели грузятся один раз
        with pytest.raises(RuntimeError, match="не аудио"):
            extractor.analyze("/m/bad.flac")
        assert extractor.analyze("/m/c.flac").features["pid"] == first  # ошибка Python не убивает процесс
        with pytest.raises(AnalysisCrashed, match="упал.*код выхода 3"):
            extractor.analyze("/m/crash.flac")
        second = extractor.analyze("/m/d.flac").features["pid"]
        assert second != first
        with pytest.raises(AnalysisCrashed, match="завис"):
            extractor.analyze("/m/hang.flac")
        assert extractor.analyze("/m/e.flac").features["pid"] not in (first, second)
    finally:
        extractor.close()


def test_recycles_after_max_tasks() -> None:
    extractor = IsolatedExtractor("/models", factory=fake_factory, max_tasks=2)
    try:
        pids = [extractor.analyze(f"/m/{i}.flac").features["pid"] for i in range(3)]
        assert pids[0] == pids[1] != pids[2]
    finally:
        extractor.close()


def test_models_failure_is_not_track_error() -> None:
    extractor = IsolatedExtractor("/nowhere", factory=broken_factory)
    with pytest.raises(ExtractorUnavailable, match="нет моделей"):
        extractor.analyze("/m/a.flac")
