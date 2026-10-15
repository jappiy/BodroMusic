# BodroMusic

Android-клиент для домашнего сервера **Navidrome** (Subsonic API + OpenSubsonic): несколько учёток, LAN/WAN,
фоновое воспроизведение, скробблинг, лайки/дизлайки и «Моя волна» на клиенте.

Техзадание — [docs/SPEC.md](docs/SPEC.md), план и архитектура — [docs/PLAN.md](docs/PLAN.md),
история изменений — [CHANGELOG.md](CHANGELOG.md).

## Модули

| Модуль | Что внутри |
|---|---|
| `core:model` | Модели (Kotlin/JVM) |
| `core:subsonic` | Клиент Subsonic API, авторизация, маскирование секретов, лог-перехватчик, выбор LAN/WAN |
| `core:domain` | Скробблинг, реакции, `WaveEngine`, разбор CHANGELOG, бейдж качества, псевдо-waveform |
| `app` | Android: Compose UI, Media3, Room, DataStore, Hilt, WorkManager |

## Сборка

```bash
./gradlew assembleDebug                      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease                    # R8, подпись debug-ключом
./gradlew test testDebugUnitTest             # unit-тесты (JVM + Robolectric)
./gradlew connectedDebugAndroidTest          # инструментальные тесты на телефоне/эмуляторе
./gradlew lintDebug ktlintCheck detekt       # статический анализ
./gradlew -Pbodro.jvmOnly=true test          # только JVM-модули, без Android SDK
```

Для smoke-теста против реального сервера скопируйте `local.properties.example` → `local.properties`
и укажите **отдельную тестовую** учётку Navidrome (тесты ставят лайки, рейтинги и скробблят).

CI: `.github/workflows/android.yml` — сборка APK, unit-тесты, lint, инструментальные тесты на эмуляторе.
