# Omni-Node-Daemon

The Omniverse node daemon as its own Android app: a one-tap **Network** switch that keeps you
connected to people nearby, even when your other Omni apps are closed. Think Tailscale, but for the
Omniverse mesh. (Directive v7, step 1: this ships and passes field tests on real phones *before*
the daemon is ever merged into Omni-Hub.)

## What it does
- **ON**: starts a foreground service (`START_STICKY`, type *connected device*) in its own process,
  running the embedded [Omni-Daemon](https://github.com/kiroba/Omni-Daemon) with zero setup:
  IPC `127.0.0.1:5000`, telemetry `:9100`, P2P sync `:5005`, UDP beacons `:9101`.
- **OFF** (in the app or from the notification): the service stops and its process exits, so every
  socket closes. Nothing half-running.
- One plain status line in the app and the notification: `Network status: Off`,
  `Looking for nearby people...`, `Connected to N nearby`, `Problem: <reason>`.
- Comes back after a reboot or update only if you left it ON. Asks once, in plain words, to skip
  battery optimisation.
- **Locked loopback:** sets `OMNI_LOCAL_TOKEN=1` (daemon v8.19.0+), so other apps on the phone can't
  use `:5000`/`:9100` without the token in the app's private storage.

## One copy of the daemon
The Python daemon is developed and tested only in kiroba/Omni-Daemon. This repo packages it:
`scripts/fetch_daemon.sh <ref>` copies `omni_live_daemon_server.py`, `omni_signing.py` and
`omni_negentropy.py` into `app/src/main/python/` at build time (git-ignored here). Embedded with
[Chaquopy](https://chaquo.com/chaquopy/) (Python 3.11 + `cryptography`).

## Build
CI (`.github/workflows/build.yml`) runs an entry smoke test on CPython and builds a debug APK
(arm64-v8a + x86_64), uploaded as an artifact. Manual run: Actions > build > Run workflow,
optionally with a daemon tag.

## Not yet
- Automatic token handoff to other Omni apps (needs one signing key across the apps). For now, **Connect apps** shows the token behind the screen lock to paste into each app.
- Real-phone field test (the gate before any Omni-Hub merge).
- Release signing / developer registration (limited-distribution account).
