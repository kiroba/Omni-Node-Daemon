"""Omni Node inside the Android app: starts the daemon (OmniDaemonService, own ":daemon" process) and
hands the Kotlin screens the shared Node core (kiroba/Omni-Daemon Admin/omni_node_core.py).

Kotlin keeps only Android environment code (service, boot, permissions, radios, charging/Wi-Fi facts,
screen lock, clipboard). Everything the screen says and decides comes from the core, through these
functions, as JSON strings (simple to pass through Chaquopy):

    ui_state_json(files_dir, env_json, refresh)        -> NodeCore.ui_state()   (screen process)
    do_json(files_dir, action, args_json, env_json)    -> NodeCore.do()         (screen process)
    duties_json(files_dir, env_json, force_peers)      -> NodeCore.sync()       (:daemon process)
    words_json(files_dir)                              -> every Node word       (notification labels)

The daemon reads its settings from the environment and its paths from HOME / cwd when it is
imported, so everything is set before the import. Stopping = the service kills this process; the
OS closes every socket, so there is no half-stopped state.
"""
import json
import os
import sys
import threading

_started = False
_error = None
_core = None
_core_lock = threading.Lock()


def _run():
    global _error
    try:
        import omni_live_daemon_server as daemon
        daemon.main()
    except BaseException as e:  # noqa: BLE001 - reported to the UI as "Problem: ..."
        _error = f"{type(e).__name__}: {e}"
        print(f"[omni-node] daemon stopped: {_error}", file=sys.stderr, flush=True)


def _home(files_dir: str):
    """~ is the app's files dir in both processes: ~/.omniverse (node key), ~/.omni (tokens, settings)."""
    os.makedirs(files_dir, exist_ok=True)
    os.environ["HOME"] = files_dir
    os.environ.setdefault("NO_PROXY", "127.0.0.1,localhost,::1")


def start(files_dir: str, node_name: str = None) -> bool:
    """files_dir = Context.getFilesDir(): app-private, survives updates, removed on uninstall.
    node_name (e.g. Build.MODEL) is what a paired device shows for this phone (daemon v8.24.0+)."""
    global _started
    if _started:
        return False
    _home(files_dir)
    os.environ["OMNI_LOCAL_TOKEN"] = "1"            # other apps on the phone must not reach :5000
    os.environ["OMNI_RELAY"] = "off"                # phone relay only via Relay calls (core policy)
    os.environ["PYTHONUTF8"] = "1"
    try:                                             # the Internet switch, before the daemon's first check
        import omni_node_core as C
        upnp = C.Settings(defaults=C.profile({"platform": "android"})["defaults"])["internet_on"]
        os.environ.setdefault("OMNI_UPNP", "1" if upnp else "0")
    except Exception:  # noqa: BLE001 - an older daemon without the core: keep its default
        pass
    if node_name:
        os.environ.setdefault("OMNI_NODE_NAME", str(node_name)[:40])   # Pair a device shows this name
    os.chdir(files_dir)                              # continuity_events.db lives here
    threading.Thread(target=_run, name="omni-daemon", daemon=True).start()
    _started = True
    return True


def error() -> str:
    return _error or ""


def daemon_ref() -> str:
    try:
        with open(os.path.join(os.path.dirname(__file__), "DAEMON_REF"), encoding="utf-8") as f:
            return f.read().strip()
    except OSError:
        return "unknown"


# ---- the shared core ----------------------------------------------------------------------------

def _env(env_json: str) -> dict:
    try:
        env = json.loads(env_json or "{}")
    except ValueError:
        env = {}
    env = env if isinstance(env, dict) else {}
    env.setdefault("platform", "android")
    if _error:
        env["node_error"] = _error[:120]
    return env


def core(files_dir: str, env_json: str = "{}"):
    """One NodeCore per process (the screen and the :daemon service each have their own)."""
    global _core
    with _core_lock:
        _home(files_dir)
        import omni_node_core as C
        if _core is None:
            _core = C.NodeCore(env=_env(env_json))
            _cache_words()
        else:
            _core.set_env(_env(env_json))
        return _core


def _cache_words():
    """~/.omni/node_words.json: lets the service label its notification before Python is up."""
    try:
        import omni_node_words as W
        path = os.path.join(os.environ["HOME"], ".omni", "node_words.json")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        data = json.dumps(W.all_words())
        try:
            with open(path, encoding="utf-8") as f:
                if f.read() == data:
                    return
        except OSError:
            pass
        with open(path + ".tmp", "w", encoding="utf-8") as f:
            f.write(data)
        os.replace(path + ".tmp", path)
    except Exception:  # noqa: BLE001 - only a cache
        pass


def ui_state_json(files_dir: str, env_json: str = "{}", refresh: bool = True) -> str:
    c = core(files_dir, env_json)
    if refresh:
        c.refresh()
    return json.dumps(c.ui_state())


def do_json(files_dir: str, action: str, args_json: str = "{}", env_json: str = "{}") -> str:
    c = core(files_dir, env_json)
    try:
        args = json.loads(args_json or "{}")
    except ValueError:
        args = {}
    return json.dumps(c.do(str(action), args if isinstance(args, dict) else {}))


def duties_json(files_dir: str, env_json: str = "{}", force_peers: bool = False) -> str:
    """The :daemon service's tick: read the node, apply Relay calls policy + router port, re-hand peers."""
    c = core(files_dir, env_json)
    return json.dumps(c.sync(bool(force_peers)))


def words_json(files_dir: str) -> str:
    _home(files_dir)
    import omni_node_words as W
    return json.dumps(W.all_words())
