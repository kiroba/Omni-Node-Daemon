#!/usr/bin/env python3
"""CI smoke test, no phone needed: runs omni_node_entry the way the app does, on plain CPython.

- start(): the daemon comes up with HOME = files dir and OMNI_LOCAL_TOKEN=1; :9100 refuses callers
  without the token.
- The shared core through the same entry functions the Kotlin shell calls (ui_state_json, do_json,
  duties_json, words_json): status line, the Node version from VERSION, Relay calls policy (not charging
  -> stays off), an added peer handed in, a pairing code, the words cache for the notification.
Run after scripts/fetch_daemon.sh, from the repo root.
"""
import json
import os
import sys
import tempfile
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "app", "src", "main", "python"))
os.environ.setdefault("OMNI_NODE_AUTO_UPDATE", "0")

import omni_node_entry as e  # noqa: E402

files = tempfile.mkdtemp(prefix="files_")
assert e.start(files, "CI phone")
tok_path = os.path.join(files, ".omni", "local_token")
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
url = f"http://127.0.0.1:{os.environ.get('OMNI_TELEMETRY_PORT') or 9100}/api/v1/telemetry/node-health"
env = json.dumps({"platform": "android", "default_network_on": True, "charging": False, "unmetered": True,
                  "battery_saver": False, "radios": {"missing": False, "status": None}})

st = {}
for _ in range(150):
    st = json.loads(e.ui_state_json(files, env, True))
    if st["status"]["line"] != st["words"]["STARTING"]:
        break
    time.sleep(0.2)
print("status:", st["status"]["line"], "|", st["version_line"], "| daemon ref", e.daemon_ref())
assert st["status"]["line"] == st["words"]["LOOKING"], (st["status"], e.error())
version = open(os.path.join(ROOT, "VERSION")).read().strip()
assert st["node_version"] == version, (st["node_version"], version)

try:
    opener.open(url, timeout=2)
    sys.exit("FAIL: node-health answered without the local token")
except urllib.error.HTTPError as err:
    assert err.code == 401, err.code
print("OK: token required on :9100")

r = json.loads(e.do_json(files, "add_peer", json.dumps({"text": "198.51.100.7"}), env))
assert r["ok"] and r["effects"] == ["settings_changed"], r
json.loads(e.do_json(files, "relay", json.dumps({"on": True}), env))
d = json.loads(e.duties_json(files, env, True))
assert d["network_on"] and d["node_id"] and d["relay_active"] is False, d      # not charging: no relaying
st = json.loads(e.ui_state_json(files, env, True))
assert st["peers"]["items"][0]["label"].endswith(st["words"]["PEER_SYNCING"]), st["peers"]
print("relay:", st["relay"]["line"])
r = json.loads(e.do_json(files, "pair_offer", "{}", env))
assert r["ok"], r
offer = json.loads(e.ui_state_json(files, env, False))["pairing"]["offer"]
assert offer and offer["payload"].startswith("omni://pair?p="), offer
words = json.loads(e.words_json(files))
assert words["NOTIF_TURN_OFF"] and os.path.exists(os.path.join(files, ".omni", "node_words.json"))
print(f"OK: shared core through omni_node_entry on Python {sys.version.split()[0]} (Node {version})")
