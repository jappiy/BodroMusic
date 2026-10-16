# BodroMusic — резюме проекта для передачи

Состояние на 2026-10-16. Документ для следующего агента или разработчика: что сделано, где что лежит,
как собирать и проверять, что осталось.

## 1. Что это

Нативное Android-приложение — клиент домашнего сервера **Navidrome** (Subsonic API + OpenSubsonic).
Полное ТЗ: [`docs/SPEC.md`](SPEC.md). План и архитектура: [`docs/PLAN.md`](PLAN.md).
История изменений: [`CHANGELOG.md`](../CHANGELOG.md).

- Репозиторий: `jappiy/BodroMusic` (раньше назывался `invest`, переименован).
- Рабочая ветка: **`claude/new-session-fi1p6v`** — весь код здесь, в основную ветку **не слит**, PR не открыт.
- Текущая версия приложения: **0.9.0** (`versionCode 9`), задаётся в `app/build.gradle.kts`.
  Версия сервиса загрузок bodrod: **0.3.0** (`server/bodrod/__init__.py`, `server/pyproject.toml`).
- Последний предварительный релиз с APK: https://github.com/jappiy/BodroMusic/releases/tag/v0.9.0
  (`BodroMusic-0.9.0.apk` — release с R8; `BodroMusic-0.9.0-debug.apk` — debug, ставится рядом).
- Устройство сервера (контейнеры, папки, потоки скачивания): [`docs/INFRA.md`](INFRA.md).

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

## 9. Раздел «На удаление» и чёрный список (0.9.0 / bodrod 0.3.0, 2026-10-16)

Задача (дословно по смыслу):
1. В Настройках раздел **«На удаление»**: списки «Не нравятся всем», «Не нравятся мне», «Не нравятся <username>»;
   в списке — треки с дизлайком; кнопка «Удалить всё» для текущего списка (с подтверждением) и удаление по одному.
   «Удалить» = удалить файл с сервера окончательно + **блоклист**, чтобы Lidarr/slskd/bodrod не скачали трек заново.
2. Во вкладке «Коллекция» убрать строку «Не нравится».

Что выяснено по коду:
- Дизлайк = `setRating(id, 1)` у текущего пользователя. Subsonic API отдаёт только **свои** рейтинги, поэтому
  дизлайки других пользователей видит только сервер: bodrod должен читать БД Navidrome (`navidrome.db`,
  таблица `annotation`, `item_type='media_file'`, `rating=1`) — нужна папка данных Navidrome в контейнере bodrod (read-only).
- Удаление файла и блоклист — новая часть bodrod (`server/`): путь файла из `media_file.path`, удаление файла,
  в Lidarr — `DELETE /api/v1/trackfile/{id}` + снятие мониторинга альбома, блоклист в SQLite bodrod
  (проверять в `POST /api/v1/requests` и в воркере перед размещением), затем `startScan`.
- Строка «Не нравится» в коллекции — `ui/screens/collection/CollectionScreens.kt` (`onOpenDisliked`, `DislikedScreen`).

Решения владельца: удаляют только админы Navidrome; «всем» = все люди, кроме служебных учёток (`bodrod`);
Lidarr — вариант «Б» (мониторинг альбома снимается, только когда в нём не осталось файлов); блоклист по
«исполнитель + название»; отдельный экран «Чёрный список» с поиском и разблокировкой через диалог; удаление
безвозвратное; подтверждение на каждое удаление; «Вернуть» перенесена в «Не нравятся мне».

Статус: **реализовано, CI зелёный** (сборка, unit-тесты, lint/ktlint/detekt, эмулятор; server — pytest + ruff),
релиз v0.9.0 опубликован. На реальном сервере **ещё не проверено**: нужно обновить проект `bodrod` (ниже).
Реализация — bodrod 0.3.0 (`server/bodrod/cleanup.py`, `navidrome_db.py`, тесты `tests/test_cleanup.py`)
и приложение 0.9.0 (`ui/screens/settings/CleanupScreens.kt`). Устройство серверной части — `docs/INFRA.md`.

Что нужно сделать на сервере (DSM → проект `bodrod` → YAML), затем пересобрать проект:
```yaml
image: ghcr.io/jappiy/bodromusic-server:<хеш коммита>
volumes:
  - /volume1/docker/bodromusic/navidrome:/navidrome          # БД Navidrome (без :ro — нужен файл -shm)
  - /volume1/docker/beets/config/slsk:/slsk-worker           # очередь slsk-worker
environment:
  NAVIDROME_DB: /navidrome/navidrome.db
  LIDARR_MUSIC_DIR: /volume2/music
  SLSK_WORKER_QUEUE: /slsk-worker/queue.txt
```
Как устроено:
- Сервер (`server/bodrod/`): `navidrome_db.py` — чтение `navidrome.db` (`mode=ro`): пользователи, дизлайки
  (`annotation.rating = 1`), пути (`media_file.path` + `library.path`, пропуск `missing`). `cleanup.py` —
  `CleanupService`: списки, удаление (через Lidarr `DELETE /trackfile` при `LIDARR_MUSIC_DIR`, иначе с диска;
  удаляет папку альбома без аудио и пустую папку исполнителя; файлы вне `MUSIC_DIR` не трогает), чистка
  очереди slsk-worker, `startScan`, фоновая зачистка `sweep()` раз в `BLOCKLIST_SWEEP_S`. Чёрный список —
  таблица `blocklist` в SQLite bodrod (`db.py`, ключ `normalize(artist)\x1fnormalize(title)`, уникальный индекс).
  `POST /api/v1/requests` отвечает 409 на заблокированный трек. Эндпоинты `/api/v1/cleanup/*`, `/api/v1/blocklist`
  — только для админов Navidrome (по `user.is_admin`), иначе 403. `/api/v1/info` отдаёт `cleanup` и `admin`.
- Приложение: `core:downloads` — модели и методы клиента (`DownloadsError.Forbidden` для 403);
  `DownloadsRepository` — прокидывает их; `ui/screens/settings/CleanupScreens.kt` — `CleanupScreen`
  (чипы групп, «Удалить все (N)», удаление по одному, «Вернуть» во «Не нравятся мне», диалоги подтверждения)
  и `BlocklistScreen` (поиск с задержкой 300 мс, страницы по 200, разблокировка через диалог). Без прав админа
  или без настроенного сервиса — только свои дизлайки из кэша Room. Старые `DislikedScreen`/`DislikedRoute`
  и строка «Не нравится» в «Коллекции» удалены.
- Тесты: `server/tests/test_cleanup.py` (5 тестов: списки, 403, удаление + блоклист + 409, зачистка, Lidarr),
  `DownloadsClientTest` — `cleanup endpoints`.

Проверить в Lidarr: Settings → Media Management → Recycling Bin пуст (иначе удалённое уйдёт в корзину).

Что проверить после обновления сервера: в логе `docker logs bodrod` нет ошибок чтения `navidrome.db`; в приложении
у админа видны вкладки «Не нравятся всем / мне / kalainidi»; удаление тестового трека убирает файл, запись
появляется в «Чёрном списке», повторная заявка на него в поиске отклоняется.

Не сделано: объединение DSM-проектов `bodromusic`, `bodrod`, `slskd`, `soularr`, `beets` в один (владелец разрешил).
Нужны текущие YAML этих проектов из DSM; при объединении сохранить имена контейнеров и сети или перейти на одну сеть.

### Продолжение на компьютере владельца (Windows, папка `C:\Проекты\BodroMusic`)

```powershell
# первый раз
git clone -b claude/new-session-fi1p6v https://github.com/jappiy/BodroMusic.git "C:\Проекты\BodroMusic"
# если папка уже есть
cd "C:\Проекты\BodroMusic"; git fetch origin; git switch claude/new-session-fi1p6v; git pull
# дальше: запустить `claude` в этой папке и попросить «продолжи по docs/HANDOFF.md, раздел 9»
```
