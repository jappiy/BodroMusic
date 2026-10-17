# 006 — Исправления обслуживания библиотеки и заявок на скачивание

- **Дата:** 2026-10-17
- **Версии:** приложение 0.12.0 (`versionCode 12`), bodrod 0.5.0
- **Релиз с APK:** https://github.com/jappiy/BodroMusic/releases/tag/v0.12.0 (`BodroMusic-0.12.0.apk`, `BodroMusic-0.12.0-debug.apk`)
- **Образ bodrod:** `ghcr.io/jappiy/bodromusic-server:0c5b78e` (bodrod 0.5.0, `server.yml` зелёный)
- **Статус:** реализовано; CI зелёный на `0c5b78e` (Android: сборка, unit-тесты, lint, эмулятор; Server: pytest, ruff, образ).
  Локально — pytest (78), ruff, ktlint, detekt, unit-тесты, Android Lint, сборка debug и release.
  На реальном сервере и на телефоне не проверено. `MAINTENANCE_DRY_RUN` остаётся `true`.

## Задание

Файл владельца `task-maintenance-fixes.md`: разбор `bodrod.sqlite3` от 17.10.2026 — запуски обслуживания №1 (manual,
01:05) и №2 (schedule, 04:00), оба пробные, 372 действия, 40 заявок. Цель — чтобы обслуживание можно было безопасно
перевести в боевой режим. Пункты:

1. Пробный режим не должен писать кэш проверок; версия набора источников в отметках «не найдено»; миграция,
   очищающая `maintenance_checked`.
2. Дубли: порядок выбора оставляемой копии (качество → тип релиза → полный альбом → Lidarr → чистое имя).
3. Дубли: файлы без тегов (исполнитель из папки, название из имени файла, длительность ±3 с).
4. Короткие файлы: убрать ложные срабатывания, «подозрительно короткий» — только в отчёт, исправить счётчики.
5. Сборка альбомов: правило целевой папки, не собирать сборники и Various, «Объединение исполнителя» отдельным типом.
6. Новая проверка «чужой релиз» в папке исполнителя.
7. Обложки в папках россыпи — в каждый файл, по альбому трека.
8. Источники обложек и тегов: единый интерфейс, iTunes, MusicBrainz + CAA, Discogs (опционально), порядок через env,
   порог 0,85, ±3 с, транслит, локальный фолбэк, источник в отчёте.
9. Единый набор ключей статистики, формулировки «будет …» в пробном режиме, блок «требует внимания».
10. Заявки: ошибка кодировки ASCII, удалённый артист в Lidarr (404), очередь и повторы поиска в Soulseek.
11. Пароль админа Navidrome — из файла в `/secrets`, убрать из `docker-compose.yml`.

Тест-кейсы из задания (SQWOZ BAB, The Hatters, Green Day, Skillet, Nickelback, Scorpions, Noize MC, Nirvana,
Каста, Чирва, Now That's What I Call Music, Sting / The Police, MULTIPASS, Киркоров, Крутой, О! Марго) — в тестах.

## Решения владельца

- Порядок работ: сначала пп. 1, 2, 4, 5, затем 3, 6, 7, 9, потом 8, 10, 11.
- `MAINTENANCE_DRY_RUN` остаётся `true`; на `false` владелец переключает сам после проверки отчёта.
- Пароль Navidrome владелец сменит сам после переноса в `/secrets`.

## Что выяснено

- **Ошибка `'ascii' codec can't encode characters in position 60-72`** у заявки «ЙОП ШОУ — По Стингу» — та же, что
  в задании 002: `bodrod-secrets` брал ключ slskd вместе с русским комментарием, httpx не может отправить заголовок
  `X-API-Key` с кириллицей. Заявка упала до исправления `sed` и так и осталась `failed`. В коде путей, имён файлов и
  subprocess с ASCII нет (`PYTHONUTF8=1`); теперь ключи для заголовков очищаются от `# …` и проверяются при старте.
- **Почему пробные запуски «съедали» отчёт:** `_report_once`, `_fix_tags` и `_fix_cover` писали `maintenance_checked`
  независимо от режима, поэтому запуск №2 пропустил почти всё.
- **Green Day «American Idiot»** убирался, потому что заглавный трек альбома считался синглом (альбом = название).
- **Короткие файлы:** порог 60% от полной копии попадал на радио-версии Scorpions (57% и 50%); интервью Noize MC
  попадало через длительность из каталога заявки bodrod (заявка на песню скачала интервью).
- **Сборка альбомов** выбирала папку по общему числу файлов в ней, поэтому `Треки/Nirvana` побеждала альбомную папку;
  группировка по альбомному исполнителю склеивала Various Artists и Sting / The Police.
- Реальной `bodrod.sqlite3` в репозитории и на компьютере нет — случаи воспроизведены по описанию задания.

## Что сделано

Сервер (bodrod 0.5.0):
- `bodrod/maintenance.py` — переписан:
  - пробный режим не пишет `maintenance_checked` (`_mark_checked`), отметки «не найдено» для тегов и обложек хранят
    `chain_version` списка источников;
  - выбор оставляемой копии `keep_rank`: качество (lossless, битрейт с шагом 32) → тип релиза (`_release_type`) →
    полный альбом (`_in_full_album`) → Lidarr → чистое имя (`.1`, `(1)`, ` copy`, если есть копия без суффикса);
    убираются россыпь и копии того же альбома, трек другого полного альбома остаётся;
  - файлы без тегов (`_derive_names`): исполнитель — из папки исполнителя, название — из имени файла;
  - короткие (`_short_reason`, `_shorter_than`): признаки другой версии и радио-версии → «требует внимания»,
    «подозрительно короткий» — только отчёт; счётчики `short_found` = `short_removed` + `short_skipped`;
  - альбомы: цель по ключу (не россыпь, треков альбома, `ГГГГ - Альбом`, lossless), Various/сборники и разные
    исполнители (`_one_artist`) пропускаются, `artist_merge` — перенос из папки варианта исполнителя;
  - новая проверка `_mismatches` (тип `mismatch`), сравнение `text.same_artist` (регистр, ё/е, feat./&, транслит);
  - обложки: папки россыпи (`Треки/…` и папки с разными альбомами) — `_fix_file_cover` вшивает обложку в файл,
    сначала обложка соседнего трека того же альбома (`placement.read_embedded_cover`), затем цепочка источников;
    не скачалась у источника — следующий;
  - статистика — всегда все ключи `STAT_KEYS`, `attention` = `short_skipped + tags_problems + covers_missing +
    mismatches`; формулировки пробного режима «будет …» (`_say`), признак `planned` у запланированных действий.
- `bodrod/metadata/lookup.py` — новый: `LookupProvider` (`lookup(artist, title, album, duration) → LookupMatch`
  с `score` 0..1), `CatalogLookup` (Яндекс, Deezer), `ITunesLookup`, `MusicBrainzLookup` (User-Agent, 1 запрос/с,
  CAA `front-1200`), `DiscogsLookup` (только обложки, по токену), `match_score`, `chain_version`, `build_chain`.
- `bodrod/text.py` — `translit`, `to_latin`, `similarity_any`, `main_artist`, `same_artist`, `search_queries`.
- `bodrod/db.py` — `PRAGMA user_version = 1`: очистка `maintenance_checked`, колонка `planned` у действий
  (старые с «(пробный запуск — ничего не изменено)» помечаются), фильтр `kind=attention`.
- `bodrod/worker.py` — очередь поисков (`SLSKD_PARALLEL_SEARCHES`, `SLSKD_SEARCH_GAP_S`), до трёх запросов,
  повтор через `SLSKD_RETRY_INTERVAL_S` до `SLSKD_RETRY_ROUNDS` раз, в `message` — запросы, ответы, файлы, подходящие;
  404 Lidarr → сразу `failed` «Артист удалён из Lidarr».
- `bodrod/matching.py` — сравнение имени файла в латинице (файл `Kirkorov - Luiza` к заявке «Киркоров — Луиза»).
- `bodrod/config.py` — `_secret` (ключи без `# …`, только ASCII), пароль из `/secrets/navidrome_password` по
  умолчанию, `COVER_PROVIDERS`, `TAG_PROVIDERS`, `DISCOGS_TOKEN`, переменные очереди Soulseek.
- `bodrod/placement.py` — `embed_cover`, `read_embedded_cover`.
- `server/README.md`, `server/docker-compose.example.yml` — новые переменные, пароль файлом.

Приложение 0.12.0:
- `ui/screens/settings/MaintenanceScreen.kt` — фильтры «Требует внимания», «Исполнители», «Чужие релизы»,
  счётчики «будет …» в пробном режиме, «требует внимания» по ключу `attention`, цвет запланированных действий.
- `core/downloads/Models.kt` — `MaintenanceAction.planned`; строки — `res/values/strings.xml`.

Тесты:
- `server/tests/test_maintenance_cases.py` — новая мини-библиотека со всеми случаями задания: два пробных запуска
  подряд с одинаковыми счётчиками и пустым кэшем, выбор копии, WMA без тегов, короткие, альбомы и
  «Объединение исполнителя», чужие релизы, обложки в россыпи, повторный поиск после смены источников, миграция.
- `server/tests/test_worker.py` — запросы для Киркорова, Крутого, «О! Марго», второй запрос находит файл,
  очередь поисков, повторы и окончательный `failed` с отчётом, 404 Lidarr.
- `server/tests/test_secrets_lookup.py` — пароль из `/secrets`, ключ с комментарием, заявка «ЙОП ШОУ — По Стингу»
  от поиска до размещения, `match_score` с транслитом, разбор iTunes и MusicBrainz.
- `server/tests/test_maintenance.py` — обновлён под новые правила (5-секундный трек — в отчёт, а не в карантин).

## Что нужно сделать на сервере

1. Пароль администратора Navidrome — в файл (DSM, File Station или SSH):
   ```sh
   printf '%s' 'ПАРОЛЬ' > /volume1/docker/bodrod/secrets/navidrome_password
   chown 1027:100 /volume1/docker/bodrod/secrets/navidrome_password
   chmod 600 /volume1/docker/bodrod/secrets/navidrome_password
   ```
   `bodrod-secrets` делает `chown` только для `env` и `data` — владельца файла пароля поставить вручную (или
   добавить в его команду `chown 1027:100 /secrets/navidrome_password`).
2. В YAML проекта `bodrod` убрать строку `NAVIDROME_ADMIN_PASSWORD: "…"` и обновить образ (тег — хеш коммита
   `0c5b78e`). Том `/volume1/docker/bodrod/secrets:/secrets:ro` уже подключён;
   bodrod сам читает `/secrets/navidrome_password`.
   ```yaml
   image: ghcr.io/jappiy/bodromusic-server:0c5b78e
   environment:
     MAINTENANCE_DRY_RUN: "true"     # остаётся до проверки отчёта
     # COVER_PROVIDERS: yandex,deezer,itunes,musicbrainz   — по умолчанию
     # TAG_PROVIDERS: musicbrainz,yandex,deezer            — по умолчанию
     # DISCOGS_TOKEN — только файлом /secrets/discogs_token, если нужен Discogs
   ```
3. Пересобрать проект, убедиться в логе: `Источники обложек: yandex, deezer, itunes, musicbrainz; тегов: …`.
   Кэш проверок очистится сам (миграция при первом старте 0.5.0).
4. Сменить пароль `bodrod` в Navidrome и записать новый в файл (владелец).
5. **Настройки → Обслуживание библиотеки → Запустить сейчас** два раза подряд: счётчики должны совпасть;
   пройтись по фильтрам «Требует внимания», «Чужие релизы», «Дубли» и сверить с тест-кейсами задания.
6. Заявки, упавшие раньше («Не нашли подходящий файл», «ЙОП ШОУ», «Свят»), можно поставить заново кнопкой
   «Скачать»: новая заявка пойдёт с очередью и тремя запросами.

## Что осталось / проверить

- На реальной библиотеке: два пробных запуска, тест-кейсы задания, качество совпадений iTunes и MusicBrainz для
  русской музыки, нагрузка MusicBrainz (1 запрос/с при `MAINTENANCE_CATALOG_LIMIT=200` — до ~3 минут на теги).
- Нельзя проверить из тестов: есть ли у Cover Art Archive картинка для выбранного релиза (если нет — берётся
  следующий источник), реальные ответы iTunes для `country=RU`.
- В папках россыпи старый `cover.jpg` (если кто-то положил) не удаляется — только вшивается обложка в файлы.
- На телефоне: экран «Обслуживание библиотеки» с новыми фильтрами.
