# 010 — Discovery-пул «На пробу» и обратная связь (задача 2 ТЗ рекомендаций)

- **Дата:** 2026-10-20
- **Версии:** приложение 0.15.0 (`versionCode 16`), bodrod 0.9.0
- **Релиз с APK:** https://github.com/jappiy/BodroMusic/releases/tag/v0.15.0
- **Образ bodrod:** `ghcr.io/jappiy/bodromusic-server:<хеш коммита 0.15.0>`
- **Статус:** реализовано; сервер — pytest, приложение — unit-тесты и локальная сборка; на реальном сервере не проверялось

## Задание

[`docs/reco/02-discovery-pool.md`](../reco/02-discovery-pool.md): новые треки сначала в пуле «на пробу» (`_discovery`,
виден Navidrome), в библиотеку их переводят сигналы пользователя; события прослушивания через офлайн-очередь;
треки пула скрыты из исполнителей, альбомов и поиска; метка «Новое»; характер волны 0 / 25 / 50 % пула.

## Что выяснено

- Кнопка дизлайка в плеере уже была (0.4.0, `setRating 1`); дизлайк трека библиотеки исключает его из волны и
  понижает вес артиста — это делал `WaveEngine`. Новое: дизлайк трека пула — reject на сервере.
- Persistent IDs Navidrome (0.55+) строятся по тегам, не по пути, поэтому при переносе файла id обычно сохраняется.
  На случай смены id bodrod записывает старый id в `replacements`, и приложение переносит лайк тем же механизмом,
  что после чистки дублей (`LikedCheck`). Перенести счётчик прослушиваний за другого пользователя Subsonic API не даёт.

## Что сделано

Сервер:
- `bodrod/reco/pool.py` — `PoolService`: события (`feedback_events`), правила promote / reject / expire, перенос файла
  в библиотеку, удаление файла, когда трек никто не держит; раз в 15 минут — id треков пула в Navidrome (по пути),
  лайки/дизлайки из БД Navidrome, истечение срока, пополнение из резерва раз в сутки (крючок `engine_refill` для
  задачи 3).
- `navidrome_db.py` — `annotations()`, `user_annotations()`, `artist_id` / `album_artist_id` у треков.
- `reco/library.py` — альбомы и исполнители, у которых все треки в пуле.
- API: `POST /api/v1/feedback`, `GET /api/v1/pool`, `POST /api/v1/pool/status`, `GET /api/v1/pool/hidden`,
  `POST /api/v1/pool/refill`; `info.pool`.
- `config.py` — `POOL_*`.
- Тесты: `tests/test_pool.py` (8).

Приложение:
- `core/downloads` — `FeedbackEvent`, `PoolItem`, `PoolHidden`, методы клиента.
- `core/domain/wave/PoolMixer.kt` — `WaveCharacter` и подмешивание пула (равномерно, без одного исполнителя подряд),
  тесты `PoolMixerTest`.
- Room 2 (автомиграция): `pending_feedback` — офлайн-очередь событий.
- `data/reco/PoolRepository.kt` — события (сразу или с `SyncWorker`), пул, что скрывать.
- События: `PlaybackCoordinator` (started / completed / skipped), `ReactionRepository` (liked / unliked / disliked),
  `PlaylistRepository` (playlisted), `WaveRepository` (shown).
- `LibraryRepository` — треки, альбомы и исполнители пула скрыты из списков, исполнителя, альбома, жанров, поиска.
- Экран «На пробу» (`ui/screens/pool/PoolScreen.kt`), пункт в «Коллекции»; метка «Новое» в плеере; характер волны в
  «Настройки → Моя волна».

## Что нужно сделать на сервере

Образ bodrod 0.9.0. По умолчанию пул — `/music/_discovery`, резерв — `/music/_reserve` (в контейнере bodrod); папка
музыки уже смонтирована. Navidrome должен видеть `_discovery` (без `.ndignore`).

## Что осталось / проверить

- Пополнение пула новыми треками (не из резерва) — движок кандидатов, задание 011.
- На телефоне: метка «Новое», экран «На пробу», доли пула в волне.
