Перед работой прочитай docs/HANDOFF.md, раздел 0

Установка на сервер (NAS) — только по docs/DEPLOY.md: образы bodrod и анализатора собираются на NAS из локальной папки
(robocopy → Container Manager «Остановить» → «Создать»), не из GHCR. Тег образа GHCR в YAML проекта не писать,
Container Manager не перезапускать.
