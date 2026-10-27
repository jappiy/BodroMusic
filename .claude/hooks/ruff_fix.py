"""PostToolUse: после правки .py в server/ или analyzer/ — ruff format и ruff check --fix (как в CI).

Оставшиеся ошибки линтера возвращаются Claude (exit 2), чтобы он их исправил.
"""

import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROJECTS = ("server", "analyzer")


def main() -> int:
    data = json.loads(sys.stdin.buffer.read().decode("utf-8") or "{}")
    raw = (data.get("tool_input") or {}).get("file_path") or ""
    if not raw.endswith(".py"):
        return 0
    path = Path(raw).resolve()
    try:
        rel = path.relative_to(ROOT)
    except ValueError:
        return 0
    if rel.parts[0] not in PROJECTS or not path.exists():
        return 0
    cwd = ROOT / rel.parts[0]  # там pyproject.toml с настройками ruff
    run = lambda *args: subprocess.run(  # noqa: E731
        [sys.executable, "-m", "ruff", *args, str(path)],
        cwd=cwd,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    check = run("check", "--fix", "--quiet", "--output-format", "concise")
    if "No module named ruff" in check.stderr:
        print(
            "ruff не установлен: python -m pip install --user ruff==0.8.4",
            file=sys.stderr,
        )
        return 1
    run("format", "--quiet")
    if check.returncode != 0:
        print(
            f"ruff check {rel.as_posix()}:\n{check.stdout}{check.stderr}",
            file=sys.stderr,
        )
        return 2
    return 0


if __name__ == "__main__":
    sys.stderr.reconfigure(encoding="utf-8")
    sys.exit(main())
