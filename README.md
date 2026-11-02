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
- **Nearby radios (0.2.0):** BLE and Wi-Fi Aware discovery live in Omni Node, with an Add peer
  list and an Internet status line (reachable directly, through the router, or not yet).
- **Relay calls (0.3.0, opt-in, off by default):** lets this phone relay OmniCast calls for people
  who can't connect directly. It only relays while the phone is **charging and on unmetered
  Wi-Fi/Ethernet**, never on mobile data, with a daily cap (250 MB, 1 GB default, 5 GB). The card
  shows why it's waiting, active calls, MB used today, and whether the router opened the ports.
  Desktops running Omni-Daemon relay by default, so phones are only a backup.

- **Pair a device (0.4.0, needs daemon v8.24.0):** Show a code (6 digits, 5 minutes, 5 tries, plus
  the pairing text to paste), Enter a code (or `123456 192.168.1.20` if the other device isn't found),
  the paired devices (tap one to remove it) and a Quiet mode line. Once paired, the node's beacons
  carry only a rotating token and `:5005` stays silent for anyone who isn't paired. Unpaired phones
  behave as before. The daemon side is documented in Omni-Daemon `Admin/docs/QUIET_MESH.md`. No QR
  drawing on the phone yet (no QR library in the app); the desktop draws one when `qrcode` is installed.

## One app, every platform (0.5.0)
Omni Node on Android and on the desktop (Windows, macOS, Linux) is **one app**: the screen state and
every action come from one shared core in Omni-Daemon (`Admin/omni_node_core.py`, words in
`omni_node_words.py`). The Kotlin code here only renders the core's `ui_state()` and sends taps to
`do()` through Chaquopy (`NodeCore.kt`, `omni_node_entry.py`). It keeps just the Android environment:
the foreground service and boot start, permissions, radios, charging/Wi-Fi/battery-saver facts
(`NodeEnv.kt`), the screen lock before the token, the clipboard and the notification. CI's `parity` job
(`scripts/check_parity.py`) fails if a `.kt` file spells out Node words. Architecture, env adapter and
iOS notes: Omni-Daemon `Admin/docs/NODE_SHARED_CORE.md`.

New with the shared core, on both platforms at once: an **Internet** switch ("Open a port on my
router"), **Check for updates** (newest release here, daily and on demand) and the version line
"Omni Node 0.5.0 · daemon v8.27.0".

**One version, one release.** `VERSION` is the only Omni Node version. Gradle reads it for the APK,
and `fetch_daemon.sh` / `scripts/package_desktop.py` write it into the APK and every desktop archive.
One `v*` tag (it must start with `v<VERSION>`) builds the APK plus `omni-node-windows-x64.zip`,
`omni-node-linux.tar.gz` and `omni-node-macos.tar.gz` from the same Omni-Daemon commit. Each desktop
archive is smoke-tested on its own OS. The release is published only after the whole set is checked.

## One copy of the daemon
The Python daemon is developed and tested only in kiroba/Omni-Daemon. This repo packages it:
`scripts/fetch_daemon.sh <ref>` copies `omni_live_daemon_server.py` plus every `omni_*` module it
imports (signing, negentropy, wan, relay...) into `app/src/main/python/` at build time (git-ignored
here; the list lands in `DAEMON_FILES`). Embedded with [Chaquopy](https://chaquo.com/chaquopy/)
(Python 3.11 + `cryptography`).

## Build
CI (`.github/workflows/build.yml`) pins one Omni-Daemon commit, runs the parity check and an entry
smoke test on CPython (the daemon plus the shared core through the same calls the Kotlin screens make),
builds a debug APK (arm64-v8a + x86_64) and checks that the core is inside it, and builds and
smoke-tests the three desktop archives. Tags publish all four in one pre-release. Manual run:
Actions > build > Run workflow, optionally with a daemon tag.

## Not yet
- Field tests of Relay calls: charging/Wi-Fi gating, router port mapping, and calls between
  different networks.
- Automatic token handoff to other Omni apps (needs one signing key across the apps). For now, **Connect apps** shows the token behind the screen lock to paste into each app.
- Real-phone field test (the gate before any Omni-Hub merge), including pairing two phones on one Wi-Fi.
- Scanning a pairing QR code with the camera (paste the pairing text for now).
- Release signing / developer registration (limited-distribution account).
