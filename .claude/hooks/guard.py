"""PreToolUse: правила проекта, которые нельзя нарушать.

- пуш тегов запрещён (релиз только через workflow_dispatch, docs/HANDOFF.md §0.2) → deny;
- docs/SPEC.md — ТЗ, меняется только по указанию владельца (§0.3) → ask.
"""

import json
import re
import sys

PUSH = re.compile(r"\bgit\b[^;&|\n]*\bpush\b([^;&|\n]*)")
TAG_PUSH = re.compile(
    r"--tags\b|--follow-tags\b|--mirror\b|refs/tags/|(?:^|\s)v\d+\.\d+"
)
SPEC = re.compile(r"(?:^|[\\/\s\"'])SPEC\.md\b", re.IGNORECASE)
SHELL_WRITE = re.compile(
    r"sed\s+-i|(?<![0-9&])>|\btee\b|\bmv\b|\bcp\b|\brm\b|Set-Content|Add-Content|Out-File|Remove-Item|Move-Item|Copy-Item"
)


def decide(decision: str, reason: str) -> int:
    out = {
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": decision,
            "permissionDecisionReason": reason,
        }
    }
    print(json.dumps(out))
    return 0


def main() -> int:
    data = json.loads(sys.stdin.buffer.read().decode("utf-8") or "{}")
    tool = data.get("tool_name", "")
    inp = data.get("tool_input") or {}

    if tool in ("Bash", "PowerShell"):
        cmd = inp.get("command", "")
        for m in PUSH.finditer(cmd):
            if TAG_PUSH.search(m.group(1)):
                return decide(
                    "deny",
                    "Пуш тегов в BodroMusic запрещён: релиз запускается через workflow_dispatch "
                    "(скилл /release, scripts/dispatch-release.sh).",
                )
        if SPEC.search(cmd) and SHELL_WRITE.search(cmd):
            return decide(
                "ask", "docs/SPEC.md — ТЗ, меняется только по указанию владельца."
            )
        return 0

    path = inp.get("file_path") or inp.get("notebook_path") or ""
    if re.search(r"docs[\\/]SPEC\.md$", path, re.IGNORECASE):
        return decide(
            "ask", "docs/SPEC.md — ТЗ, меняется только по указанию владельца."
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
