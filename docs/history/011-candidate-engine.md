# 011 — Движок кандидатов (задача 3 ТЗ рекомендаций)

- **Дата:** 2026-10-20
- **Версии:** приложение 0.16.0 (`versionCode 17`), bodrod 0.10.0
- **Релиз с APK:** https://github.com/jappiy/BodroMusic/releases/tag/v0.16.0
- **Образ bodrod:** `ghcr.io/jappiy/bodromusic-server:<хеш коммита 0.16.0>`
- **Статус:** реализовано; pytest на поддельных Last.fm / ListenBrainz; с настоящими API не проверялось

## Задание

[`docs/reco/03-candidate-engine.md`](../reco/03-candidate-engine.md): раз в сутки по семенам пользователя собрать
кандидатов из Last.fm, Deezer, ListenBrainz и архива Яндекса, отсеять, оценить и пополнить пул; в приложении — вкл/выкл
и вес каждого источника.

## Что сделано

Сервер:
- `bodrod/reco/engine.py` — `CandidateEngine` (семена из `annotation` Navidrome и promote пула, полураспад 90 дней),
  адаптеры `LastFmAdapter`, `DeezerAdapter`, `ListenBrainzAdapter`, `YandexArchiveAdapter`, скоринг (×1.3 за источник,
  штраф ×0.3 артистам с ≥ 3 reject, ≤ 2 трека артиста, доля новых артистов), выученный вес источника по исходам в
  `pool_owners` (Beta(2, 2)). Настройки источников — в `kv` (`engine.config`).
- `reco/pool.py` — `enqueue` (заявка `pool:<пользователь>` + трек пула `queued`), `_sync_requests` (скачано →
  `in_pool`, не нашли → `failed`); `PoolService.refill` после резерва зовёт движок.
- `worker.py` — заявки `pool:*` кладутся в `DISCOVERY_DIR` (прямые источники и slskd), путь — в `state.destPath`.
- `navidrome_db.user_annotations()`.
- API: `GET/PUT /api/v1/reco/engine`, `GET /api/v1/reco/engine/preview`.
- Настройки: `ENGINE_*`, `LISTENBRAINZ_TOKEN`, `LISTENBRAINZ_USERS`.
- Тесты: `tests/test_engine.py` (10).

Приложение:
- `ui/screens/settings/RecoScreen.kt` — «Настройки → Рекомендации»: источники (переключатель, вес 0–3, счётчики,
  выученный вес), доля новых артистов, «Подобрать сейчас», архив Яндекса по аккаунтам (сборщики, Плюс, резерв).
- `core/downloads` — `EngineStatus`, `HarvestStatus` и методы клиента.

## Что нужно сделать на сервере

- Образ bodrod 0.10.0. Last.fm уже настроен (`LASTFM_API_KEY`).
- ListenBrainz: токен — файлом `/secrets/listenbrainz_token`; `LISTENBRAINZ_USERS: nikolai:<имя в LB>,<жена>:<имя>`.
- Скробблинг в ListenBrainz: в Navidrome `ND_LISTENBRAINZ_ENABLED: "true"`, затем каждый пользователь вводит токен LB в
  своём профиле Navidrome (Плеер → ListenBrainz). Плейлисты Weekly Exploration появляются через 1–2 недели скробблинга.
- Explo (если был) можно отключить: плейлисты LB движок забирает сам.

## Что осталось / проверить

- Реальные ответы Deezer `/artist/{id}/radio` из России (может понадобиться VPN — тогда источник «не отвечает»).
- LB Radio (`/1/explore/lb-radio`) требует токен — без него берутся только Weekly-плейлисты.
