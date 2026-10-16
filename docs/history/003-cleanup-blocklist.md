# 003 — Раздел «На удаление» и чёрный список

- **Дата:** 2026-10-16
- **Версии:** приложение 0.9.0 (`versionCode 9`), bodrod 0.3.0
- **Релиз с APK:** https://github.com/jappiy/BodroMusic/releases/tag/v0.9.0
- **Статус:** реализовано, CI зелёный; на реальном сервере не проверено


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
