# 001 — Глобальный поиск и загрузки

- **Дата:** см. раздел 0.8.0 в `CHANGELOG.md`
- **Версии:** приложение 0.8.0; первая версия сервиса bodrod (`server/`)
- **Статус:** реализовано, покрыто тестами на поддельных серверах; с настоящими slskd, Lidarr, Яндексом и Deezer
  на момент выпуска не проверялось

Файл заведён задним числом при введении правила «одно задание — один файл»; подробности задания не сохранились.

## Что сделано

- Сервис `server/` (bodrod, Python/FastAPI) рядом с Navidrome; инструкция — `server/README.md`,
  CI — `.github/workflows/server.yml` (pytest + ruff, образ `ghcr.io/jappiy/bodromusic-server`).
- Поиск: сначала `search3` в библиотеке; если пусто — `GET /api/v1/discover` (Яндекс Музыка → Deezer).
  По нажатию — заявка: трек через slskd, исполнитель через Lidarr; затем `startScan` и ожидание трека в Navidrome.
- Клиент: модуль `core:downloads` (`DownloadsClient`, `DownloadWatch`), `data/downloads/` (репозиторий,
  `DownloadWatchWorker`, уведомления), `ui/screens/search/Discover.kt`, `ui/screens/downloads/DownloadsScreen.kt`.
  Адрес сервиса — отдельно для каждой учётки (DataStore `downloads`).

Текущее устройство — `docs/HANDOFF.md`, раздел 7а, и `docs/INFRA.md`, раздел 5.3.
