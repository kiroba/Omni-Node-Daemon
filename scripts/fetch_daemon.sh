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
# Copy the server plus every omni_* module it imports, so a new daemon module (omni_wan in
# v8.21.0, omni_relay in v8.22.0) can never be left out of the APK again.
SRC="$TMP/daemon/Admin"
MODS="$(grep -ohE '^(import|from) omni_[a-z0-9_]+' "$SRC/omni_live_daemon_server.py" | awk '{print $2}' | sort -u)"
cp "$SRC/omni_live_daemon_server.py" "$DEST/omni_live_daemon_server.py"
echo omni_live_daemon_server.py > "$DEST/DAEMON_FILES"
for m in $MODS; do
  cp "$SRC/$m.py" "$DEST/$m.py"
  echo "$m.py" >> "$DEST/DAEMON_FILES"
done
echo "packaged: $(tr '\n' ' ' < "$DEST/DAEMON_FILES")"
echo "$REF $(git -C "$TMP/daemon" rev-parse --short HEAD)" > "$DEST/DAEMON_REF"
grep -m1 '^VERSION = ' "$DEST/omni_live_daemon_server.py"
rm -rf "$TMP"
