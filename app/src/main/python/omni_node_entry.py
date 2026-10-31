"""Starts the Omni-Daemon inside the Android app (called from OmniDaemonService, own process).

The daemon reads its settings from the environment and its paths from HOME / cwd when it is
imported, so everything is set before the import. Stopping = the service kills this process; the
OS closes every socket, so there is no half-stopped state.
"""
import os
import sys
import threading

_started = False
_error = None


def _run():
    global _error
    try:
        import omni_live_daemon_server as daemon
        daemon.main()
    except BaseException as e:  # noqa: BLE001 - reported to the UI as "Problem: ..."
        _error = f"{type(e).__name__}: {e}"
        print(f"[omni-node] daemon stopped: {_error}", file=sys.stderr, flush=True)


def start(files_dir: str) -> bool:
    """files_dir = Context.getFilesDir(): app-private, survives updates, removed on uninstall."""
    global _started
    if _started:
        return False
    os.makedirs(files_dir, exist_ok=True)
    os.environ["HOME"] = files_dir                  # ~/.omniverse (node key), ~/.omni (tokens)
    os.environ["OMNI_LOCAL_TOKEN"] = "1"            # other apps on the phone must not reach :5000
    os.environ["PYTHONUTF8"] = "1"
    os.environ.setdefault("NO_PROXY", "127.0.0.1,localhost,::1")
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
