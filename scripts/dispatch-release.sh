#!/usr/bin/env bash
# Запуск workflow «Release» на GitHub (workflow_dispatch) для версии из аргумента: scripts/dispatch-release.sh 0.28.0
# Токен — из Git Credential Manager этого компьютера; он никуда не выводится и не сохраняется.
set -euo pipefail
version="${1:?версия, например 0.28.0}"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "неверная версия: $version" >&2; exit 2; }
branch="$(git -C "$(dirname "$0")/.." rev-parse --abbrev-ref HEAD)"
token="$(printf 'protocol=https\nhost=github.com\n\n' | git credential fill 2>/dev/null | sed -n 's/^password=//p')"
[ -n "$token" ] || { echo "нет токена GitHub в Git Credential Manager" >&2; exit 1; }
code="$(curl -s -o /dev/null -w '%{http_code}' -X POST \
  -H "Authorization: Bearer $token" -H "Accept: application/vnd.github+json" \
  https://api.github.com/repos/jappiy/BodroMusic/actions/workflows/release.yml/dispatches \
  -d "{\"ref\":\"$branch\",\"inputs\":{\"version\":\"$version\"}}")"
echo "Release $version на $branch: HTTP $code"
[ "$code" = "204" ]
