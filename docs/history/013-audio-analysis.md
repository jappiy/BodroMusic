# 013 — Анализ звучания (задача 5 ТЗ рекомендаций)

- **Дата:** 2026-10-20
- **Версии:** приложение 0.18.0 (`versionCode 19`), bodrod 0.12.0, анализатор 0.1.0
- **Релиз с APK:** https://github.com/jappiy/BodroMusic/releases/tag/v0.18.0
- **Образы:** `ghcr.io/jappiy/bodromusic-server:<хеш коммита 0.18.0>`, `ghcr.io/jappiy/bodromusic-analyzer:latest`
- **Статус:** реализовано; bodrod — pytest (k-means, очередь, похожие, фильтр, API воркера); воркер — тесты цикла без
  Essentia; сама модель (Essentia) локально не запускалась — проверяется сборкой образа в CI и на сервере

## Задание

[`docs/reco/05-audio-analysis.md`](../reco/05-audio-analysis.md): отдельный воркер (Docker, essentia-tensorflow)
считает эмбеддинг discogs-effnet, жанр, настроения, danceability, голос/инструментал, BPM, тональность, громкость;
очередь — пул и резерв, затем библиотека; профиль вкуса 5–10 кластеров; фильтр пула; настроение и плавные переходы
волны; «Похожие по звучанию»; numpy-индекс.

## Что сделано

Воркер `analyzer/` (новый): `bodro_analyzer/extract.py` (Essentia: `TensorflowPredictEffnetDiscogs`, головы
`TensorflowPredict2D` — имена тензоров и метки из json моделей; `RhythmExtractor2013`, `KeyExtractor`,
`LoudnessEBUR128`), `worker.py` (задания → признаки → результат, один файл за раз, `nice 19`), Dockerfile (модели
скачиваются при сборке, только amd64), README с запуском на DSM и на ПК через SMB; workflow `analyzer.yml`
(тесты + образ в GHCR).

bodrod: `reco/analysis.py` — `AnalysisService` (очередь с арендой, `audio_features`, энергия, k-means,
`sound_matches`, `similar`, `features`); `reco/pool.py` — `pool_sound`, фильтр пула в обслуживании; API
`/api/v1/analysis/*`; `info.analysis`; `PoolItem.soundMatch/inWave`; `numpy` в зависимостях. Тесты
`tests/test_analysis.py` (5).

Приложение: `core/domain/wave/SoundArranger.kt` (`WaveMood`, отбор по настроению, плавный порядок, тесты),
`data/reco/SoundRepository.kt`, настроение в «Настройки → Моя волна», волна берёт втрое больше кандидатов при
выбранном настроении, треки пула с `inWave = false` не идут в волну, «Похожие по звучанию» в меню трека, статус
анализа на экране «Рекомендации».

## Что нужно сделать на сервере

1. Случайный токен: `openssl rand -hex 24 > /volume1/docker/secrets/analyzer_token`.
2. bodrod 0.12.0 — читает `/secrets/analyzer_token` сам.
3. Контейнер анализатора — пример YAML в [`analyzer/README.md`](../../analyzer/README.md) (`cpus: 1.5`, музыка только
   на чтение). Если DSM на ARM — запускать на ПК через SMB.
4. Первые результаты — в логах `bodro-analyzer` и на экране «Рекомендации».

## Что осталось / проверить

- Реальная скорость анализа на NAS и пороги `SOUND_MIN_MATCH` / `SOUND_REJECT_MATCH` (косинусы discogs-effnet —
  подобрать по первым данным).
