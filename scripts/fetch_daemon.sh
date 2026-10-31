#!/usr/bin/env bash
# Copies the daemon from kiroba/Omni-Daemon into the app. One source of truth: the daemon is
# developed and tested there (27 suites); this repo only packages it.
#   scripts/fetch_daemon.sh [ref]     ref = tag, branch or commit (default: main)
set -euo pipefail
REF="${1:-main}"
DEST="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/python"
TMP="$(mktemp -d)"
git clone --quiet https://github.com/kiroba/Omni-Daemon.git "$TMP/daemon"
git -C "$TMP/daemon" checkout --quiet "$REF"
for f in omni_live_daemon_server.py omni_signing.py omni_negentropy.py; do
  cp "$TMP/daemon/Admin/$f" "$DEST/$f"
done
echo "$REF $(git -C "$TMP/daemon" rev-parse --short HEAD)" > "$DEST/DAEMON_REF"
grep -m1 '^VERSION = ' "$DEST/omni_live_daemon_server.py"
rm -rf "$TMP"
