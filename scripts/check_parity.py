#!/usr/bin/env python3
"""Parity check: the Kotlin shell must not spell out Omni Node words or status logic itself.

Every word on the Node screen comes from the shared core (kiroba/Omni-Daemon Admin/omni_node_words.py
through omni_node_core.ui_state()), so Android, desktop and later iOS say the same thing in the same
state. This fails when a .kt string literal contains:
  - any omni_node_words string of 10+ characters (as packaged by scripts/fetch_daemon.sh), or
  - a status phrase ("Connected to", "Quiet mode", "Problem: ", "Starting...", ...).

Run after scripts/fetch_daemon.sh:   python3 scripts/check_parity.py
"""
import importlib.util
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KOTLIN = ROOT / "app" / "src" / "main" / "kotlin"
WORDS = ROOT / "app" / "src" / "main" / "python" / "omni_node_words.py"
STATUS_PATTERNS = re.compile(r"Connected to|Looking for nearby|Network status|Quiet mode|Relaying ·|Reachable at|"
                             r"Problem: |Starting\.\.\.|Turn on Network|Help calls connect|Pair your phone|"
                             r"node token|Keeps you connected|Waiting: |Bluetooth: |Wi-Fi Aware: ")
LITERAL = re.compile(r'"((?:[^"\\\n]|\\.)*)"')


def load_words() -> list:
    if not WORDS.exists():
        sys.exit(f"{WORDS} missing: run scripts/fetch_daemon.sh first")
    spec = importlib.util.spec_from_file_location("omni_node_words", WORDS)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return [v for v in mod.all_words().values() if len(v) >= 10]


def literals(text: str):
    """String literals in Kotlin source, skipping // and /* */ comments (good enough for this repo)."""
    text = re.sub(r"/\*.*?\*/", lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)
    for n, line in enumerate(text.splitlines(), 1):
        code = re.sub(r'//.*$', "", line) if '"' not in line.split("//")[0] or line.count('"') % 2 == 0 else line
        for m in LITERAL.finditer(code):
            yield n, m.group(1)


def main() -> int:
    words = load_words()
    bad = []
    files = sorted(KOTLIN.rglob("*.kt"))
    for f in files:
        for n, s in literals(f.read_text(encoding="utf-8")):
            hit = STATUS_PATTERNS.search(s) or next((w for w in words if w in s), None)
            if hit:
                bad.append(f"{f.relative_to(ROOT)}:{n}: \"{s}\"")
    if bad:
        print("User-facing Node words belong in Omni-Daemon Admin/omni_node_words.py (the shared core), "
              "not in Kotlin:")
        print("\n".join("  " + b for b in bad))
        return 1
    print(f"parity OK: {len(files)} Kotlin files, {len(words)} shared words, no copies in the shell")
    return 0


if __name__ == "__main__":
    sys.exit(main())
