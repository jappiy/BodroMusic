# 016 — Анализатор падает на NaN/inf в результате

- **Дата:** 2026-10-20
- **Версии:** bodrod 0.13.1, воркер анализа 0.1.1
- **Релиз с APK:** не требуется — только сервер и анализатор
- **Образы:** `ghcr.io/jappiy/bodromusic-server:33b99e5`, `ghcr.io/jappiy/bodromusic-analyzer:33b99e5`
- **Статус:** реализовано; pytest (анализатор 5, bodrod 158); на сервере не проверено

## Задание

`bodro-analyzer` падает на `ValueError: Out of range float values are not JSON compliant` (`worker.py` `process` →
`httpx.post(json=...)`). Исправить:

1. Перед отправкой результата рекурсивно чистить его: NaN/±inf → `None`, numpy-скаляры — `.item()`, массивы — `.tolist()`.
2. В `run_once` ошибка одного трека не роняет процесс: лог пути с traceback, статус `failed` с текстом ошибки в bodrod,
   следующий трек. Треки `failed` bodrod повторно в очередь не берёт.
3. Заглушить Essentia (`essentia.log.warningActive = False`, `infoActive = False`) и TensorFlow (`TF_CPP_MIN_LOG_LEVEL=2`).
4. В compose анализатора — `logging: json-file, max-size 10m, max-file 3`.
5. Собрать новый образ, обновить тег в compose.

## Что выяснено

- Причина: `LoudnessEBUR128` для тишины (или почти тихого трека) даёт `-inf`; `round(float(-inf))` остаётся `-inf`, а
  сериализация `httpx` падает. Исключение возникало вне `try` вокруг анализа, поэтому валило весь процесс.
- bodrod уже хранил ошибки (`audio_features.error`), но выдавал такие треки снова через 7 дней (`RETRY_ERROR_MS`).

## Что сделано

- Анализатор (`analyzer/bodro_analyzer/worker.py`): `json_safe()` перед каждой отправкой; `process` отправляет
  `status: ok | failed`; `run_once` ловит любое исключение трека (`log.exception`), отправляет `failed` и идёт дальше;
  `httpx.HTTPError` по-прежнему уходит в `run_forever` (bodrod недоступен — пауза минута). `TF_CPP_MIN_LOG_LEVEL=2` —
  в Dockerfile и `main()`; `extract.py` выключает info/warning-логи Essentia до импорта `essentia.standard`.
- bodrod (`models.py`, `main.py`, `reco/analysis.py`): `AnalysisResult.status` (`ok` / `failed`; `failed` без текста
  пишется как «Ошибка анализа»); треки с ошибкой в очередь больше не попадают (убран `RETRY_ERROR_MS`). Чтобы
  проанализировать их заново — удалить строки из `audio_features` (`DELETE FROM audio_features WHERE error IS NOT NULL`).
- Документация: YAML анализатора в `analyzer/README.md` и `docs/INFRA.md` — закреплённый тег и ротация логов.
- Тесты: анализатор — NaN/inf уходят `null`, несериализуемый результат не останавливает пачку, numpy; bodrod —
  упавший трек не выдаётся после истечения аренды, `status: failed` через API.

## Что нужно сделать на сервере

В проекте DSM:
```yaml
  bodrod:
    image: ghcr.io/jappiy/bodromusic-server:33b99e5
  analyzer:
    image: ghcr.io/jappiy/bodromusic-analyzer:33b99e5
    logging:
      driver: json-file
      options:
        max-size: "10m"
        max-file: "3"
```
Затем пересобрать проект (pull + up). Старый раздутый лог контейнера удалится вместе с пересозданием контейнера.

## Что осталось / проверить

- Логи `bodro-analyzer` после обновления: нет падений, нет спама Essentia/TF; `errors` на экране «Рекомендации».
