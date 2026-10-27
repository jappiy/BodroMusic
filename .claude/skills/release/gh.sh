#!/usr/bin/env bash
# Помощник скилла /release: GitHub API без gh CLI. Токен — из Git Credential Manager, никуда не выводится.
#   gh.sh wait <workflow.yml> [sha]  — ждать прогон workflow для коммита (по умолчанию HEAD), вывести итог
#   gh.sh logs <run_id>              — хвосты логов упавших задач прогона
#   gh.sh fetch <версия>             — проверить оба APK в релизе v<версия> и скачать их в dist/
set -euo pipefail
repo="jappiy/BodroMusic"
root="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
token="$(printf 'protocol=https\nhost=github.com\n\n' | git credential fill 2>/dev/null | sed -n 's/^password=//p')"
auth=()
[ -n "$token" ] && auth=(-H "Authorization: Bearer $token")

api() { curl -sSL "${auth[@]}" -H "Accept: application/vnd.github+json" "https://api.github.com/repos/$repo/$1"; }
py() { python -c "$1"; }

case "${1:-}" in
  wait)
    wf="${2:?workflow, например android.yml}"
    sha="${3:-$(git -C "$root" rev-parse HEAD)}"
    deadline=$(( $(date +%s) + 75 * 60 ))
    while :; do
      line="$(api "actions/workflows/$wf/runs?head_sha=$sha&per_page=1" | py '
import json, sys
runs = json.load(sys.stdin).get("workflow_runs", [])
if runs:
    r = runs[0]
    print(r["id"], r["status"], r["conclusion"] or "-", r["html_url"])')"
      if [ -n "$line" ]; then
        read -r id status conclusion url <<<"$line"
        echo "$(date +%H:%M:%S) $wf run $id: $status $conclusion"
        if [ "$status" = "completed" ]; then
          echo "$url"
          [ "$conclusion" = "success" ] && exit 0 || exit 1
        fi
      else
        echo "$(date +%H:%M:%S) $wf: прогона для ${sha:0:7} ещё нет"
      fi
      [ "$(date +%s)" -lt "$deadline" ] || { echo "таймаут ожидания $wf" >&2; exit 3; }
      sleep 60
    done
    ;;
  logs)
    run="${2:?run_id}"
    [ -n "$token" ] || { echo "логи без токена недоступны" >&2; exit 1; }
    api "actions/runs/$run/jobs?per_page=50" | py '
import json, sys
for j in json.load(sys.stdin)["jobs"]:
    if j["conclusion"] not in ("success", "skipped", None):
        steps = [s["name"] for s in j["steps"] if s["conclusion"] == "failure"]
        print(j["id"], j["name"], "|", ", ".join(steps))' | while read -r job rest; do
      echo "=== $rest"
      api "actions/jobs/$job/logs" | grep -v '^\s*$' | tail -120
    done
    ;;
  fetch)
    ver="${2:?версия, например 0.32.0}"
    urls="$(api "releases/tags/v$ver" | py "
import json, sys
d = json.load(sys.stdin)
names = {a['name']: a['browser_download_url'] for a in d.get('assets', [])}
need = ['BodroMusic-$ver.apk', 'BodroMusic-$ver-debug.apk']
missing = [n for n in need if n not in names]
if missing:
    sys.exit('в релизе v$ver нет: ' + ', '.join(missing))
print('\n'.join(names[n] for n in need))")"
    mkdir -p "$root/dist"
    for u in $urls; do
      curl -sSfL -o "$root/dist/$(basename "$u")" "$u"
      ls -l "$root/dist/$(basename "$u")"
    done
    echo "https://github.com/$repo/releases/tag/v$ver"
    ;;
  *)
    sed -n '2,5p' "$0"; exit 2 ;;
esac
