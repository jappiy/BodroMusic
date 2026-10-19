# 012 — Чарты (задача 4 ТЗ рекомендаций)

- **Дата:** 2026-10-20
- **Версии:** приложение 0.17.0 (`versionCode 18`), bodrod 0.11.0
- **Релиз с APK:** https://github.com/jappiy/BodroMusic/releases/tag/v0.17.0
- **Образ bodrod:** `ghcr.io/jappiy/bodromusic-server:<хеш коммита 0.17.0>`
- **Статус:** реализовано; pytest на подделках (Deezer, Apple, ListenBrainz); с настоящими API не проверялось

## Задание

[`docs/reco/04-charts.md`](../reco/04-charts.md): чарты раз в сутки, на главной под «Моей волной»; снимок с движением;
недостающее из топ-30 — в общий пул «charts»; экран чарта со стрелками и состояниями; выбор и порядок чартов.

## Что сделано

Сервер:
- `bodrod/reco/charts.py` — `build_sources` (Яндекс, Deezer мир / Top Russia, Last.fm мир / Россия, Apple Music RSS
  `ru`, ListenBrainz sitewide), `ChartsService`: таблицы `charts`, `chart_entries` (позиция и прошлая позиция),
  обновление раз в сутки, скрытие неответившего чарта, загрузка топа в пул `charts`, удаление выпавших через 30 дней
  без прослушиваний.
- `reco/pool.py` — `expire_owner`; обычный срок пула (21 день) на владельца `charts` не действует.
- `sources/yandex.py` — `YandexMusicApi.chart()`.
- API: `GET /api/v1/charts`, `GET /api/v1/charts/{id}`, `POST /api/v1/charts/refresh`; настройки `CHARTS`,
  `CHARTS_DOWNLOAD_TOP`.
- Тесты: `tests/test_charts.py` (4).

Приложение:
- `data/reco/ChartsRepository.kt` — список (порядок и скрытые из настроек), позиции, треки для воспроизведения,
  «Скачать».
- `ui/screens/charts/ChartScreens.kt` — `ChartCard` (коллаж 2×2), `ChartScreen`, `ChartsSettingsScreen`.
- Главная: карусель «Чарты» под миксами; «Настройки → Чарты на главной».
- Тесты: `ChartsOrderTest`.

## Что нужно сделать на сервере

Образ bodrod 0.11.0. Первое обновление чартов — сразу после старта. Deezer из России может не отвечать — тогда его
чарты скрыты (или задать VPN для контейнера).

## Что осталось / проверить

- Реальные ответы Apple Music RSS для `ru` и ListenBrainz sitewide.
