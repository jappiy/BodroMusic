---
name: release
description: Выпуск новой версии приложения BodroMusic по docs/HANDOFF.md §0.2 — поднять versionCode/versionName, раздел в CHANGELOG, пуш, зелёный CI android.yml, запуск workflow Release, проверка обоих APK и скачивание в dist/. Использовать в конце любого задания, которое меняло app/ или core/, или когда владелец просит «выпусти релиз / собери APK».
argument-hint: "[patch|minor|X.Y.Z]"
---

# /release — выпуск APK

Аргумент: `$ARGUMENTS` — `patch`, `minor` или точная версия `X.Y.Z`. Пусто — выбрать самому:
в CHANGELOG есть «### Добавлено» → minor, только исправления → patch.

Помощник для GitHub API (gh CLI на этом компьютере нет): `bash .claude/skills/release/gh.sh`.

## 0. Проверки до начала

- Изменения задания касаются `app/` или `core/` (иначе APK не нужен: для `server/` — только версия bodrod,
  см. HANDOFF §0.2, последний пункт).
- Код задания закоммичен или готов к коммиту; посторонних изменений в `git status` нет.
- Релиза с такой версией ещё нет: `curl -s https://api.github.com/repos/jappiy/BodroMusic/releases/tags/v<версия>`
  должен вернуть `Not Found`.

## 1. Версия

В `app/build.gradle.kts` (строки `val appVersionCode = …` и `val appVersionName = "…"`):
`appVersionCode` +1, `appVersionName` — новая версия.

## 2. CHANGELOG.md

- Новый раздел `## [X.Y.Z] - ГГГГ-ММ-ДД` (сегодняшняя дата) сразу под пустым `## [Unreleased]`;
  `[Unreleased]` остаётся пустым.
- Содержимое из `[Unreleased]` (если было) переносится в новый раздел, остальное дописывается по заданию.
- Подразделы Keep a Changelog: `### Добавлено`, `### Изменено`, `### Исправлено`. Пункты — для пользователя,
  простыми словами, как в соседних разделах; технические подробности — в файл задания, не сюда.
- Если в задании менялся сервер — пункт «Сервис загрузок X.Y.Z: …» в «Изменено».
- Из этого файла строится экран «История версий» в приложении и описание релиза — формат не ломать.

## 3. Коммит и пуш

- Сообщение в стиле истории: `app X.Y.Z / bodrod A.B.C: кратко через запятую` (без bodrod, если сервер не менялся).
- `git push` текущей ветки. **Теги не создавать и не пушить** — релиз только через workflow_dispatch.

## 4. Дождаться зелёного CI

- `bash .claude/skills/release/gh.sh wait android.yml` — запустить в фоне (`run_in_background`), прогон идёт
  до ~40 минут; следить через Monitor, не ждать тычка.
- Упал → `bash .claude/skills/release/gh.sh logs <run_id>` (id — в выводе wait), исправить, закоммитить,
  запушить, ждать заново. Release на красном CI не запускать.

## 5. Запустить Release

- `bash scripts/dispatch-release.sh X.Y.Z` — должно вывести `HTTP 204`.
- `bash .claude/skills/release/gh.sh wait release.yml` — в фоне, до ~45 минут. Упал — `logs`, исправить,
  повторить с шага 3 (та же версия, если релиз не опубликован).

## 6. Проверить и скачать APK

- `bash .claude/skills/release/gh.sh fetch X.Y.Z` — проверяет, что в релизе есть `BodroMusic-X.Y.Z.apk` и
  `BodroMusic-X.Y.Z-debug.apk`, и кладёт оба в `dist/` (папка в .gitignore). Выводит ссылку на релиз.
- Подпись проверяет сам workflow (отпечаток `c5146426…94de62`); если он упал на подписи — ключ не трогать,
  сообщить владельцу.

## 7. После релиза

- Ссылку на релиз — в файл задания `docs/history/NNN-…md` и в раздел 1 `docs/HANDOFF.md`, плюс версия
  приложения в шапке HANDOFF (HANDOFF §0.1, §0.3). Коммит `Документация: релиз vX.Y.Z`, пуш.
- Владельцу — версия, ссылка на релиз и путь к APK в `C:\Projects\BodroMusic\dist\`.
