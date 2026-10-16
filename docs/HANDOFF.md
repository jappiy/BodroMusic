# BodroMusic — резюме проекта для передачи

Состояние на 2026-10-16. Документ для следующего агента или разработчика: что сделано, где что лежит,
как собирать и проверять, что осталось.

## 1. Что это

Нативное Android-приложение — клиент домашнего сервера **Navidrome** (Subsonic API + OpenSubsonic).
Полное ТЗ: [`docs/SPEC.md`](SPEC.md). План и архитектура: [`docs/PLAN.md`](PLAN.md).
История изменений: [`CHANGELOG.md`](../CHANGELOG.md).

- Репозиторий: `jappiy/BodroMusic` (раньше назывался `invest`, переименован).
- Рабочая ветка: **`claude/new-session-fi1p6v`** — весь код здесь, в основную ветку **не слит**, PR не открыт.
- Текущая версия: **0.7.0** (`versionCode 7`), задаётся в `app/build.gradle.kts`.
- Опубликован предварительный релиз с APK: https://github.com/jappiy/BodroMusic/releases/tag/v0.7.0
  (`BodroMusic-0.7.0.apk` — release с R8; `BodroMusic-0.7.0-debug.apk` — debug, ставится рядом).

## 2. Статус

**CI полностью зелёный** (последний прогон на коммите `030564b`): сборка debug и release, unit-тесты,
Android Lint, ktlint, detekt, инструментальные тесты на эмуляторе.

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

**Владелец ещё не проверял приложение на телефоне.** Release-APK (R8) на устройстве никто не запускал;
CI запускает только debug. Если release падает, а debug нет, — смотреть правила в `app/proguard-rules.pro`.

### Тесты
- Unit: **130** — `core:subsonic` 53, `core:domain` 61, `app` 16 (Robolectric + Room in-memory + MockWebServer + Turbine/MockK).
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
- `playback/` — `PlaybackService` (`MediaLibraryService`), `PlaybackCoordinator` (скробблинг, история, сохранение очереди, догрузка волны, таймер сна, ошибки, смена учётки), `PlayerConnection` (MediaController → `StateFlow` для UI), `StreamDataSourceFactory` (URL потока подписывается в момент открытия; ключ кэша без токенов).
- `sync/` — `SyncWorker` + `SyncScheduler` (WorkManager: скробблы и реакции).
- `ui/` — `app/` (корень, навигация, `MainViewModel` с общими действиями), `screens/*`, `components/`, `theme/`, `image/` (Coil со стабильными ключами кэша, палитра обложек).

Ключевые решения:
- Дизлайк = `setRating(id, 1)`; трек с `userRating == 1` с сервера считается дизлайкнутым (`ReactionRepository.ingest`).
- Офлайн-реакции схлопываются в одно изменение «как на сервере → как сейчас».
- Все данные в Room разделены по `accountId`; при смене учётки граф навигации пересоздаётся (`key(account.id)`), плеер очищается и загружает очередь новой учётки.
- Один `OkHttpClient` на всё (API, стрим, обложки) — общие логи и правило cleartext.

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
- **Android Auto**: `MediaLibraryService` есть, но корень каталога пустой.
- **Версии AndroidX** взяты как заведомо существующие стабильные (AGP 8.11.1, Compose BOM 2025.08.00,
  Media3 1.8.0, Room 2.7.2…) — свежесть не проверялась (Google Maven был недоступен). Стоит обновить.
- **Схема Room** (`app/schemas/`) генерируется при сборке, но в git не закоммичена — закоммитить перед первой миграцией БД.
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

1. Владелец ставит APK, проверяет на телефоне с реальным сервером; разобрать его замечания.
2. Сверка UI с референсами — нужны скриншоты в `docs/references/` (см. раздел 6.1 ТЗ).
3. Проверить release-сборку (R8) на устройстве.
4. При желании — smoke-тест против реального сервера: данные тестовой учётки в CI secrets (если сервер доступен из интернета по DDNS).
5. Скриншоты ключевых экранов в CI (Compose `captureToImage` в инструментальном тесте → артефакт).
6. Слить ветку в основную (PR), затем 1.0.0: запись в CHANGELOG, `versionCode/versionName`, релиз.
7. Roadmap после 1.0 — раздел 12 ТЗ (офлайн-скачивание, Android Auto, настоящая waveform и т.д.).

Правило проекта (из ТЗ): каждое изменение функциональности — запись в `CHANGELOG.md` и повышение
`versionCode`/`versionName` в `app/build.gradle.kts`. Язык UI — русский, строки только в `strings.xml`.
