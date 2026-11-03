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
# In-app update (Android): newest release WITH an .apk wins; a desktop-only newer release is skipped.
rels = [
    {"tag_name": "v9.9.9-prealpha", "draft": True, "assets": [{"name": "app-debug.apk", "browser_download_url": "x"}]},
    {"tag_name": "v0.9.0-prealpha", "assets": [{"name": "omni-node-linux.tar.gz", "browser_download_url": "x"}]},
    {"tag_name": "v0.8.1-prealpha", "html_url": "https://example/r", "assets": [
        {"name": "app-debug.apk", "size": 123, "browser_download_url": "https://example/a.apk"}]},
    {"tag_name": "v0.5.0-prealpha", "assets": [{"name": "app-debug.apk", "browser_download_url": "y"}]},
]
u = e.pick_apk_release(rels, version)
assert u["latest"] == "0.8.1" and u["newer"] and u["apk_url"] == "https://example/a.apk" and u["size"] == 123, u
assert not e.pick_apk_release(rels, "0.8.1")["newer"] and not e.pick_apk_release([], version)["newer"]
rel_file = os.path.join(files, "releases.json")
with open(rel_file, "w") as f:
    json.dump(rels, f)
os.environ["OMNI_NODE_APK_UPDATE_URL"] = "file://" + rel_file
u = json.loads(e.apk_update_json(files, version, env))
assert u["ok"] and u["newer"] and "0.8.1" in u["line"], u
st = json.loads(e.ui_state_json(files, env, False))
assert st["update"]["newer"] and st["update"]["line"] == u["line"] and st["update"]["url"] == "https://example/r", st["update"]
os.environ["OMNI_NODE_APK_UPDATE_URL"] = "http://127.0.0.1:9/nothing"
u = json.loads(e.apk_update_json(files, version, env))
assert not u["ok"] and not u["newer"] and u["line"], u                  # plain message, no exception
del os.environ["OMNI_NODE_APK_UPDATE_URL"]
print("OK: in-app update picks the newest APK release;", u["line"])
words = json.loads(e.words_json(files))
assert words["NOTIF_TURN_OFF"] and os.path.exists(os.path.join(files, ".omni", "node_words.json"))
print(f"OK: shared core through omni_node_entry on Python {sys.version.split()[0]} (Node {version})")
