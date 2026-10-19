# BodroMusic — резюме проекта для передачи

Состояние на 2026-10-19 (задание 007). Документ для следующего агента или разработчика: что сделано, где что лежит,
как собирать и проверять, что осталось.

## 0. Обязательные правила для агента (выполнять в каждом задании)

Задание считается завершённым, только когда по порядку сделано всё:
**код → CHANGELOG и версия → APK (если менялся клиент) → файл задания в `docs/history/` → обновление этого резюме.**

### 0.1. Каждое задание — отдельный файл в `docs/history/`

- Новое задание = новый файл `docs/history/NNN-kratkoe-nazvanie.md`: `NNN` — следующий свободный номер
  (001, 002, …), название латиницей через дефис. Шаблон — [`docs/history/_template.md`](history/_template.md).
- Если задание продолжается в следующей сессии, в его файл дописывается раздел «Продолжение ГГГГ-ММ-ДД»;
  новый файл не заводится. Файлы завершённых заданий не переписываются.
- В файле задания: формулировка от владельца, решения владельца, что выяснено, что сделано (с путями файлов),
  версии приложения и bodrod, ссылка на релиз с APK, что осталось проверить или сделать.
- Каждое задание — строка в индексе (раздел 10 этого документа).
- `CHANGELOG.md` остаётся как есть: из него строится экран «История версий» в приложении (Keep a Changelog, SemVer).
  В CHANGELOG — краткие пункты для пользователя, подробности — в файле задания.

### 0.2. Новый APK после каждого обновления клиента

- Любое изменение того, что попадает в APK (`app/`, `core/*`), в рамках задания:
  1. `versionCode` +1 и новый `versionName` в `app/build.gradle.kts` (исправления — patch, новые функции — minor);
  2. раздел новой версии в `CHANGELOG.md`;
  3. пуш, дождаться зелёного CI (`android.yml`);
  4. запустить workflow **Release** вручную (`workflow_dispatch`, версия = новый `versionName`; пуш тегов запрещён — раздел 6);
  5. дождаться завершения и убедиться, что в релизе есть `BodroMusic-<версия>.apk` и `BodroMusic-<версия>-debug.apk`.
  6. на компьютере владельца — скачать оба APK из релиза в `C:\Projects\BodroMusic\dist\` (папка в `.gitignore`,
     там лежат APK всех версий; владелец ставит их оттуда).
- Если сборка или релиз упали — исправить и повторить. Задание по клиенту без опубликованного APK не закрывается.
- Один релиз на задание: промежуточные пуши внутри задания отдельного релиза не требуют.
- Ссылка на релиз — в файл задания и в раздел 1 этого документа.
- Если работа идёт на компьютере владельца и Android SDK доступен, можно дополнительно собрать локально
  (`.\gradlew.bat assembleDebug assembleRelease`), но релиз на GitHub обязателен в любом случае.
- Изменения только в `server/`: APK не нужен; поднять версию bodrod (`server/bodrod/__init__.py`,
  `server/pyproject.toml`), записать тег образа GHCR (хеш коммита) в файл задания и в `docs/INFRA.md`.

### 0.3. Обновление документации после каждой работы с кодом

В том же задании, отдельным коммитом или вместе с кодом:
- **`docs/HANDOFF.md` (этот файл)**: дата в шапке, текущие версии приложения и bodrod, ссылка на последний релиз,
  таблица статуса, число тестов, архитектура (новые модули, классы, экраны, эндпоинты, решения), известные ограничения,
  следующие шаги, индекс истории (раздел 10).
- **`docs/PLAN.md`** — если изменились модули, ключевые классы или схема Room.
- **`docs/INFRA.md`** — если изменились контейнеры, YAML проектов DSM, папки, переменные окружения, образ bodrod.
- **`docs/SPEC.md`** — не менять без указания владельца (это ТЗ).
- Устаревшее заменять, а не дописывать рядом. Решённые пункты из «Известных ограничений» и «Следующих шагов» удалять.
  Подробный разбор задания — в файл задания, а не новым разделом в этот документ.
- Резюме должно оставаться достаточным, чтобы новый сеанс продолжил работу без пересказа контекста.

## 1. Что это

Нативное Android-приложение — клиент домашнего сервера **Navidrome** (Subsonic API + OpenSubsonic).
Полное ТЗ: [`docs/SPEC.md`](SPEC.md). План и архитектура: [`docs/PLAN.md`](PLAN.md).
История изменений: [`CHANGELOG.md`](../CHANGELOG.md). История заданий: [`docs/history/`](history/) (индекс — раздел 10).

- Репозиторий: `jappiy/BodroMusic` (раньше назывался `invest`, переименован).
- Рабочая ветка: **`claude/new-session-fi1p6v`** — весь код здесь, в основную ветку **не слит**, PR не открыт.
- Текущая версия приложения: **0.13.0** (`versionCode 13`), задаётся в `app/build.gradle.kts`.
  Версия сервиса загрузок bodrod: **0.6.0** (`server/bodrod/__init__.py`, `server/pyproject.toml`).
- Последний предварительный релиз с APK: https://github.com/jappiy/BodroMusic/releases/tag/v0.13.0
  (`BodroMusic-0.13.0.apk` — release с R8; `BodroMusic-0.13.0-debug.apk` — debug, ставится рядом).
- Устройство сервера (контейнеры, папки, потоки скачивания): [`docs/INFRA.md`](INFRA.md).

## 2. Статус

**CI полностью зелёный** на `6425a2c` (0.13.0 / bodrod 0.6.0, задание 007, Last.fm): Android и `server.yml`, образ `6425a2c`. Релиз — https://github.com/jappiy/BodroMusic/releases/tag/v0.13.0, подробности — [`docs/history/007-lastfm-channel.md`](history/007-lastfm-channel.md).
Предыдущее состояние: CI зелёный на `0c5b78e` (0.12.0 / bodrod 0.5.0, задание 006), релиз v0.12.0.

| Пункт ТЗ (раздел 1) | Статус |
|---|---|
| 1. Вход, LAN/WAN | ✅ вход проверен на эмуляторе; автовыбор адреса покрыт unit-тестами; с реальным сервером не проверялось |
| 2. UI в стиле референсов | ⚠️ реализовано, но скриншоты-референсы в репозиторий не попали (`docs/references/` пуст), визуальная сверка не делалась |
| 3. Фон, уведомление, экран блокировки, наушники | ✅ фон и foreground-уведомление проверены на эмуляторе; экран блокировки и гарнитура — через Media3, тестами не покрыты |
| 4. Восстановление очереди и позиции | ✅ проверено на эмуляторе (тот же трек, позиция ±2 с) |
| 5. Скробблинг и офлайн-очередь | ✅ unit-тесты; «сейчас играет» проверено на эмуляторе |
| 6. Лайки и дизлайки, исключение из волны | ✅ эмулятор + property-тест на 1000 генераций |
| 7. «Моя волна» и миксы | ✅ алгоритм покрыт тестами; в интерфейсе тестами не покрыта |
| 8. История версий | ✅ эмулятор |
| 9. Логи Navidrome | ✅ эмулятор, проверено маскирование секретов |
| 10. Автотесты | ⚠️ на эмуляторе в CI; на физическом телефоне не запускались |

**Владелец пользуется приложением на телефоне** (с 0.9.0) и присылает замечания — первая порция разобрана
в заданиях 004 (0.10.0) и 005 (0.11.0); задание 006 — исправления обслуживания по разбору реальной базы bodrod. Release-APK (R8) на устройстве агентом не проверялся; CI запускает только debug.
Если release падает, а debug нет, — смотреть правила в `app/proguard-rules.pro`.

### Тесты
- Unit: **141** — `core:subsonic` 53, `core:domain` 61, `core:downloads` 8, `app` 19 (Robolectric + Room in-memory + MockWebServer + Turbine/MockK).
- Сервер (`server/tests`, pytest): **90** (в т. ч. `test_lastfm.py`), в том числе `test_maintenance.py` (регламент на мини-библиотеке с FLAC-заголовками)
  и `test_maintenance_cases.py` (случаи из реальной библиотеки: дубли, короткие, альбомы, чужие релизы, обложки).
- Инструментальные: **9** — 8 проходят, 1 пропускается (`RealServerSmokeTest`, нет данных тестовой учётки).
  Тесты гоняют настоящее приложение против поддельного сервера `FakeNavidrome` (MockWebServer,
  отдаёт сгенерированный WAV как поток трека). Hilt-testing не используется: доступ к синглтонам — через `di/AppEntryPoint`.

## 3. Архитектура

| Модуль | Тип | Содержимое |
|---|---|---|
| `core/model` | Kotlin/JVM | Модели: `Song`, `Album`, `QueueSnapshot`, `QueueSource`, `WaveSettings`… |
| `core/subsonic` | Kotlin/JVM | `SubsonicClient` (OkHttp + kotlinx.serialization, без Retrofit), token-авторизация, `SecretMasker`, `NavidromeLogInterceptor`, `ServerSelector`/`PingServerProbe` (LAN/WAN), `LrcParser` |
| `core/domain` | Kotlin/JVM | `ScrobbleTracker`, `ScrobbleQueue`, `ReactionRules`/`PendingReactionMerger`, `WaveEngine` + `LocalWaveSource`, `ChangelogParser`, `QualityBadges`, `PseudoWaveform`, `SleepTimer` |
| `app` | Android | Compose + Material 3, Hilt, Room, DataStore, Media3, WorkManager, Coil 3, Tink |

Ключевое в `app/src/main/kotlin/ru/bodro/music/`:
- `data/account` — `ConnectionManager` (активная учётка, выбор LAN/WAN, реакция на смену сети), `AccountRepository`, `TinkCredentialStore` (пароли AES-GCM, ключ в Android Keystore).
- `data/repository` — `LibraryRepository`, `ReactionRepository` (оптимистичный UI + очередь `pending_reactions`), `ScrobbleRepository`, `WaveRepository`, `HistoryRepository`, плейлисты, поиск, CHANGELOG.
- `data/log/LogRepository` — журнал запросов и событий плеера в Room (кольцевой буфер 5000 записей / 7 дней).
- `data/network/CleartextPolicy` — HTTP только для приватных адресов (Network Security Config не умеет диапазоны IP, поэтому правило в интерцепторе OkHttp).
- `playback/` — `PlaybackService` (`MediaLibraryService`, старт после 0,5 с буфера), `StreamPrefetcher` (начало
  двух следующих треков очереди — в кэш стрима), `PlaybackCoordinator` (скробблинг, история, сохранение очереди, догрузка волны, таймер сна, ошибки, смена учётки), `PlayerConnection` (MediaController → `StateFlow` для UI), `StreamDataSourceFactory` (URL потока подписывается в момент открытия; ключ кэша без токенов).
- `sync/` — `SyncWorker` + `SyncScheduler` (WorkManager: скробблы и реакции).
- `data/offline/OfflineLiked.kt` — скачивание лайкнутых треков для прослушивания без сети (настройка «Скачивать
  понравившиеся треки»): `OfflineStore` (файлы `files/offline/<accountId>/<songId>`), `OfflineSyncWorker`
  (только Wi-Fi), `OfflineScheduler` (следит за настройкой и кэшем лайков). `StreamDataSourceFactory` отдаёт
  скачанный файл вместо потока.
- `data/downloads/LikedCheck.kt` — «Любимое» без удалённых треков (`POST /api/v1/library/check` в bodrod),
  перенос лайка на копию, оставшуюся после чистки дублей.
- `ui/` — `app/` (корень, навигация, `MainViewModel` с общими действиями), `screens/*`, `components/`, `theme/`, `image/` (Coil со стабильными ключами кэша, палитра обложек).

Ключевые решения:
- Дизлайк = `setRating(id, 1)`; трек с `userRating == 1` с сервера считается дизлайкнутым (`ReactionRepository.ingest`).
- Офлайн-реакции схлопываются в одно изменение «как на сервере → как сейчас».
- Все данные в Room разделены по `accountId`; при смене учётки граф навигации пересоздаётся (`key(account.id)`), плеер очищается и загружает очередь новой учётки.
- Один `OkHttpClient` на всё (API, стрим, обложки, офлайн-скачивание) — общие логи и правило cleartext.
- Качество воспроизведения одно на все сети: «Оригинал» / «Медленный интернет» (128 kbps) — `AppSettings.quality`.
- Статистика «Коллекции» (всего прослушиваний, любимые исполнители и треки) — по `playCount` Navidrome
  (`LibraryRepository.listeningStats()`: альбомы `frequent` + треки топ-20 альбомов), а не по локальной истории.
- Повторное нажатие на выбранную вкладку — возврат к корню раздела (`BodroRoot.onTabClick`).
- «Популярные треки» на экране исполнителя — из Яндекс Музыки через bodrod (`/api/v1/artists/popular`): скачанные
  и нескачанные (с загрузкой). Без bodrod — своя история + `getTopSongs`.
- Last.fm (bodrod 0.6.0, `metadata/lastfm.py`) — дополнительный канал, последний в цепочках тегов, обложек и поиска
  (`TAG_PROVIDERS`, `COVER_PROVIDERS`, `DISCOVER_PROVIDERS`); нужен `LASTFM_API_KEY`. После 3 сбоев подряд 5 минут
  не опрашивается. Скробблинг в Last.fm — средствами Navidrome (`ND_LASTFM_*`), см. задание 007.

## 4. Сборка и проверка

```bash
./gradlew assembleDebug assembleRelease
./gradlew test testDebugUnitTest
./gradlew connectedDebugAndroidTest            # нужен телефон или эмулятор
./gradlew lintDebug ktlintCheck detekt
./gradlew -Pbodro.jvmOnly=true test ktlintCheck detekt   # только JVM-модули, без Android SDK
```

Smoke-тест против реального Navidrome: скопировать `local.properties.example` → `local.properties`
и указать **отдельную тестовую** учётку (`navidrome.test.url/user/password`) — тесты ставят лайки,
рейтинги и скробблят.

## 5. CI и релизы

- `.github/workflows/android.yml` — на каждый пуш: job «Build, unit tests, lint» и затем job с эмулятором.
- `.github/workflows/release.yml` — релиз с APK. Запуск: пуш тега `v*` **или** вручную
  (Actions → Release → Run workflow, ввести версию, например `0.8.0` — тег создаст GitHub).
  Версии `0.x` публикуются как pre-release; текст релиза берётся из соответствующего раздела `CHANGELOG.md`.
- Скачивать APK — со страницы Releases; артефакты запусков (zip) тоже доступны в Actions.

Что было важно для эмулятора (не ломать):
- Бесплатный раннер — **8 ГБ RAM, 2 ядра для KVM**. Поэтому APK собираются **до** запуска эмулятора,
  эмулятор — облегчённый образ **ATD** (`api-level: 33`, `target: aosp_atd`, `pixel_4`, 2 ядра, 3 ГБ),
  Gradle в шаге тестов — `--no-daemon -Xmx2g`.
- Шаг «Free disk space» удаляет .NET/GHC/Boost. **Не удалять `$AGENT_TOOLSDIRECTORY`** — там JDK.
- В workflow `concurrency: cancel-in-progress` — новый пуш отменяет текущий прогон на той же ветке.

## 6. Особенности среды Claude Code (облако), в которой велась работа

- `dl.google.com` / `maven.google.com` **заблокированы** сетевой политикой: Android SDK и AndroidX
  локально недоступны. Локально собираются и тестируются только JVM-модули (`-Pbodro.jvmOnly=true`);
  Android-часть проверяется через CI. Код Android можно гонять через ktlint/detekt CLI (jar с Maven Central).
  Владелец может разрешить домен в настройках сети окружения — тогда можно собирать локально.
- Пуш **тегов** через git-прокси запрещён (разрешена только рабочая ветка) — поэтому релиз запускается через `workflow_dispatch`.
- Скачивание артефактов GitHub (хост `*.blob.core.windows.net`) заблокировано; логи CI читаются через GitHub MCP
  (`get_job_logs`). Для понятных ошибок тестов в логе включён `exceptionFormat = FULL`.
- Публичный API GitHub (`api.github.com`) доступен без токена — им удобно опрашивать статус прогонов.

## 7. Известные ограничения

- **Gapless**: переключателя нет — ExoPlayer делает воспроизведение без пауз сам, штатно не отключается (в настройках пояснение).
- **Размер кэша стрима** применяется после перезапуска (кэш создаётся один раз на процесс).
- **Офлайн-лайки** качаются только по Wi-Fi и в оригинальном формате (FLAC может занимать много места);
  в списках треков значка «скачано» пока нет.
- **Android Auto**: `MediaLibraryService` есть, но корень каталога пустой.
- **Версии AndroidX** взяты как заведомо существующие стабильные (AGP 8.11.1, Compose BOM 2025.08.00,
  Media3 1.8.0, Room 2.7.2…) — свежесть не проверялась (Google Maven был недоступен). Стоит обновить.
- Скриншоты экранов (раздел 9.4 ТЗ) не сделаны.
- Предупреждение компилятора про `@ApplicationContext` на параметре (`LogsScreens.kt`, KT-73255) — безвредно.

## 7а. Глобальный поиск и загрузки (0.8.0)

- Сервис `server/` (bodrod, Python/FastAPI) работает на сервере рядом с Navidrome. Инструкция — `server/README.md`,
  CI — `.github/workflows/server.yml` (pytest + ruff, образ `ghcr.io/jappiy/bodromusic-server`).
- Сценарий: поиск в библиотеке (`search3`). Если треков нет, вызывается `GET /api/v1/discover` (Яндекс Музыка → Deezer).
  По нажатию создаётся заявка: трек скачивается через slskd, исполнитель — через Lidarr. Сервис запускает `startScan`
  в Navidrome и ждёт появления трека. `DownloadWatchWorker` в приложении опрашивает заявки и показывает уведомление.
- Клиент: модуль `core:downloads` (`DownloadsClient`, `DownloadWatch`), `data/downloads/` (репозиторий, воркер,
  уведомления), `ui/screens/search/Discover.kt`, `ui/screens/downloads/DownloadsScreen.kt`.
  Адрес сервиса хранится отдельно для каждой учётки в DataStore `downloads`.
- С настоящими slskd, Lidarr, Яндексом и Deezer интеграция **не проверялась**: из облачной среды эти API недоступны.
  Клиенты написаны по документации API и покрыты тестами на поддельных серверах. При первой проверке на сервере
  смотрите логи контейнера (`docker logs bodrod`).

## 8. Следующие шаги

0. Обновить проект `bodrod` на сервере до 0.5.0: пароль Navidrome — в `/secrets/navidrome_password`, убрать его из YAML,
   два пробных прогона регламента подряд (счётчики должны совпасть) — раздел «Что нужно сделать на сервере» в
   [`docs/history/006-maintenance-fixes.md`](history/006-maintenance-fixes.md). `MAINTENANCE_DRY_RUN=false` владелец включает сам.
1. На сервере: образ bodrod 0.6.0 (`6425a2c`) и ключ
   `/secrets/lastfm_api_key` (раздел «Что нужно сделать на сервере» в [`docs/history/007-lastfm-channel.md`](history/007-lastfm-channel.md)).
   Следующая порция замечаний владельца — новое задание `docs/history/008-…`.
   Проверить на телефоне: из 004 — офлайн-лайки, счётчики «Коллекции»; из 005 — старт и переключение треков,
   экран исполнителя, теги «Моей волны».
2. Сверка UI с референсами — нужны скриншоты в `docs/references/` (см. раздел 6.1 ТЗ).
3. Проверить release-сборку (R8) на устройстве.
4. При желании — smoke-тест против реального сервера: данные тестовой учётки в CI secrets (если сервер доступен из интернета по DDNS).
5. Скриншоты ключевых экранов в CI (Compose `captureToImage` в инструментальном тесте → артефакт).
6. Слить ветку в основную (PR), затем 1.0.0: запись в CHANGELOG, `versionCode/versionName`, релиз.
7. Roadmap после 1.0 — раздел 12 ТЗ (офлайн-скачивание, Android Auto, настоящая waveform и т.д.).

Правила завершения каждого задания (CHANGELOG и версия, APK, файл в `docs/history/`, обновление документации) —
раздел 0. Язык UI — русский, строки только в `strings.xml`.

## 9. Раздел «На удаление» и чёрный список (0.9.0 / bodrod 0.3.0)

Реализовано, CI зелёный, релиз v0.9.0 опубликован; на реальном сервере не проверено. Серверная часть —
`server/bodrod/cleanup.py`, `navidrome_db.py`; приложение — `ui/screens/settings/CleanupScreens.kt`.
Задача, решения владельца, устройство и YAML для сервера — [`docs/history/003-cleanup-blocklist.md`](history/003-cleanup-blocklist.md).

## 9а. Обслуживание библиотеки (bodrod 0.5.0 / app 0.12.0)

Ночной регламент bodrod (`server/bodrod/maintenance.py`): обрезанные файлы, дубли треков (и файлов без тегов),
дубли альбомов, «объединение исполнителя», чужие релизы в папках исполнителей, пустые теги и обложки.
Источники обложек и тегов — цепочки `COVER_PROVIDERS` / `TAG_PROVIDERS` (`server/bodrod/metadata/lookup.py`:
Яндекс, Deezer, iTunes, MusicBrainz + CAA, Discogs, Last.fm). Убранное — в карантин на 30 дней. Отчёт с фильтрами
(в т. ч. «Требует внимания»), запуск и «Вернуть» — в приложении (`ui/screens/settings/MaintenanceScreen.kt`,
только админы). Пробный режим ничего не пишет и не запоминает (два прогона подряд дают один отчёт).
Правила и переменные — `server/README.md`; первый вариант — задание 005, исправления по реальной базе — задание 006.
Первый пробный прогон 0.4.0 на сервере был (база разобрана в 006); 0.5.0 на сервере ещё не запускался.

## 10. История заданий

Каждое задание — отдельный файл в `docs/history/` (правила — раздел 0.1). Задания до 0.8.0 описаны только в `CHANGELOG.md`.

| № | Дата | Задание | Версии | Файл |
|---|---|---|---|---|
| 001 | см. CHANGELOG | Глобальный поиск и загрузки (bodrod) | app 0.8.0 | [001-global-search-downloads.md](history/001-global-search-downloads.md) |
| 002 | 2026-10-16 | Развёртывание bodrod на сервере, шаг `placing` | bodrod 0.2.0–0.2.1 | [002-bodrod-deploy.md](history/002-bodrod-deploy.md) |
| 003 | 2026-10-16 | Раздел «На удаление» и чёрный список | app 0.9.0, bodrod 0.3.0 | [003-cleanup-blocklist.md](history/003-cleanup-blocklist.md) |
| 004 | 2026-10-16 | Замечания по интерфейсу, часть 1 (плеер, вкладки, главная, настройки, коллекция, офлайн-лайки) | app 0.10.0 | [004-ui-feedback-1.md](history/004-ui-feedback-1.md) |
| 005 | 2026-10-16 | Регламент обслуживания библиотеки, популярное исполнителя из Яндекса, быстрый старт воспроизведения, мелкие правки | app 0.11.0, bodrod 0.4.0 | [005-maintenance-artist-playback.md](history/005-maintenance-artist-playback.md) |
| 006 | 2026-10-17 | Исправления обслуживания библиотеки и заявок по разбору `bodrod.sqlite3`: пробный режим, дубли, короткие, альбомы, чужие релизы, источники обложек, очередь Soulseek, секреты | app 0.12.0, bodrod 0.5.0 | [006-maintenance-fixes.md](history/006-maintenance-fixes.md) |
| 007 | 2026-10-19 | Last.fm — дополнительный канал тегов, обложек, глобального поиска и популярного исполнителя; ответ про скробблинг | app 0.13.0, bodrod 0.6.0 | [007-lastfm-channel.md](history/007-lastfm-channel.md) |

## 11. Продолжение на компьютере владельца (Windows, папка `C:\Projects\BodroMusic`)

```powershell
# первый раз
git clone -b claude/new-session-fi1p6v https://github.com/jappiy/BodroMusic.git "C:\Projects\BodroMusic"
# если папка уже есть
cd "C:\Projects\BodroMusic"; git fetch origin; git switch claude/new-session-fi1p6v; git pull
# дальше: запустить `claude` в этой папке и попросить «продолжи по docs/HANDOFF.md»
```

Локальная сборка на этом компьютере (проверено 2026-10-16):
- Android SDK: `%LOCALAPPDATA%\Android\Sdk` (переменная `ANDROID_HOME`), `adb` — в `platform-tools`; телефон владельца
  подключается по USB (`adb devices`).
- JDK из Android Studio (`C:\Program Files\Android\Android Studio\jbr`) — **25**, а Gradle 8.14 на нём не запускается.
  Портативный Temurin 17 лежит в `C:\tools\jdk17`. В Git Bash: `export JAVA_HOME=C:/tools/jdk17 ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"`.
- Python 3.12 есть (`py -3`); для тестов сервера — venv с `pip install -r server/requirements-dev.txt`.
- `gh` не установлен: статус CI — через публичный API GitHub (репозиторий публичный). Release запускается через API
  (`POST …/actions/workflows/release.yml/dispatches`) с токеном из Git Credential Manager (`git credential fill`),
  если владелец залогинен в git на этом компьютере; иначе — вручную в Actions.
