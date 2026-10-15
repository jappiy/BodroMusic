# BodroMusic — план реализации

## Модули

| Модуль | Тип | Что внутри |
|---|---|---|
| `:core:model` | Kotlin/JVM | Модели: `Song`, `Album`, `Artist`, `Playlist`, `Lyrics`, `QueueSnapshot`, `WaveSettings`… |
| `:core:subsonic` | Kotlin/JVM | `SubsonicClient` (OkHttp + kotlinx.serialization), `SubsonicAuth`, `SecretMasker`, `NavidromeLogInterceptor`, `ServerSelector` (LAN/WAN), `LrcParser` |
| `:core:domain` | Kotlin/JVM | `ScrobbleTracker`, `ScrobbleQueue`, `ReactionRules`, `WaveEngine` + `WaveSource`/`LocalWaveSource`, `ChangelogParser`, `QualityBadges`, `PseudoWaveform`, `SleepTimer` |
| `:app` | Android | Compose UI, Hilt, Room, DataStore, Media3 (`MediaLibraryService`), WorkManager, Tink |

Чистая логика вынесена в JVM-модули: она тестируется без эмулятора и без Android SDK
(`./gradlew -Pbodro.jvmOnly=true test`).

Инструментальные тесты работают с настоящим приложением (без Hilt-testing): доступ к синглтонам —
через `AppEntryPoint`, сервер — поддельный Navidrome на MockWebServer (`FakeNavidrome`, отдаёт
сгенерированный WAV как поток трека).

## Ключевые классы `:app`

- `data/account`: `AccountRepository`, `CredentialStore` (Tink AEAD + Android Keystore), `ConnectionManager` (активная учётка, LAN/WAN, смена сети).
- `data/local`: `BodroDatabase` (Room), DAO.
- `data/repository`: `LibraryRepository`, `PlaylistRepository`, `ReactionRepository`, `ScrobbleRepository`, `HistoryRepository`, `WaveRepository`, `LogRepository`, `SearchHistoryRepository`.
- `data/settings`: `SettingsRepository` (DataStore).
- `sync`: `SyncWorker` (скробблы + реакции), `SyncScheduler`.
- `playback`: `PlaybackService` (`MediaLibraryService`), `PlayerConnection` (MediaController → `StateFlow`), `QueuePersister`, `WaveQueueManager`, `ScrobbleController`, `StreamDataSourceFactory` (ResolvingDataSource + SimpleCache), `SleepTimerController`.
- `ui`: тема, компоненты, экраны (Главная, Коллекция, Поиск, Настройки, Плеер, Волна, Альбом, Исполнитель, Плейлист, Логи, История версий…).

## Схема Room (все таблицы с `accountId`)

| Таблица | Поля |
|---|---|
| `accounts` | id, username, localUrl, externalUrl, serverVersion, openSubsonic, extensions, createdAt |
| `disliked_tracks` | accountId, songId, artistId, songJson, createdAt, syncState |
| `liked_tracks` | accountId, songId, songJson, updatedAt |
| `pending_reactions` | accountId, target, targetId, serverState, desiredState, updatedAt |
| `pending_scrobbles` | id, accountId, songId, timestamp, attempts — UNIQUE(accountId, songId, timestamp) |
| `play_history` | id, accountId, songId, artistId, albumId, genre, playedAt, listenedMs, skippedEarly |
| `queue_state` | accountId (PK), snapshotJson, currentIndex, positionMs, updatedAt |
| `search_history` | accountId, query, updatedAt |
| `log_entries` | id, accountId, timestamp, type (network/player), category, endpoint, serverKind, url, httpCode, subsonicStatus, errorCode, message, durationMs, size, headers, body |

## Этапы

1. Каркас, тема, навигация, вход, сеть, логи, история версий.
2. Библиотека: главная, коллекция, альбом, исполнитель, поиск, плейлисты.
3. Плеер: Media3-сервис, мини/полный плеер, очередь, фон, уведомление, восстановление, кэш, качество.
4. Скробблинг, лайки/дизлайки, офлайн-очереди.
5. «Моя волна», миксы, hero-блок.
6. Мультиаккаунт, полировка UI, пустые состояния, планшеты.
7. Инструментальные тесты, проверка на телефоне, lint, release.

## Решения

- Дизлайк = `setRating(id, 1)`; при синхронизации все треки с `userRating == 1` считаются дизлайкнутыми.
- Офлайн-реакции схлопываются в одно изменение «состояние на сервере → желаемое».
- Стрим-URL подписывается в момент открытия (`ResolvingDataSource`), поэтому смена LAN/WAN и качества
  применяется без пересборки очереди; ключ кэша — `songId + качество`.
- Retrofit не используется: Subsonic — это один шаблон запроса, `SubsonicClient` на OkHttp проще и прозрачнее для логов.
