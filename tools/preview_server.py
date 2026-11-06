#!/usr/bin/env python3
"""Start a static preview server for a worktree, and stop the ones this tool started once they are old.

The nightly ends each branch with a URL the human can open in the morning: a page built into
``<worktree>/public/field4`` and served with ``python3 -m http.server``. Those servers were started
by hand and never stopped. Each holds a live cwd inside its worktree, so the reaper keeps the whole
tree -- 10 to 16 GiB -- and on 2026-11-03..06 three nights running left 20-30 GiB that only a hand
``kill`` could free. SIGINT does not stop ``http.server`` either.

So the server has an owner now:

  preview_server.py start <dir> [--port N] [--owner LABEL]   serve <dir> on 127.0.0.1, print the URL
  preview_server.py list                                       what is registered, and whether it is alive
  preview_server.py stop --older-than-hours H                  stop registered servers at least H hours old
  preview_server.py stop --dir <worktree>                      stop registered servers serving inside it
  preview_server.py stop --all

``stop`` only ever signals a process this tool registered, and only after re-checking that the PID
is still an ``http.server`` on the registered port, so a reused PID is left alone. Servers started
any other way are not touched. The registry is ``~/.field4/preview-servers.json``
(``FIELD4_PREVIEW_REGISTRY`` overrides it). Logs go to ``~/.field4/preview-logs/``, never into the
worktree, so a server cannot make the tree it serves look dirty.
"""

from __future__ import annotations

import argparse
import contextlib
import fcntl
import json
import os
import signal
import socket
import subprocess
import sys
import time
from pathlib import Path


def registry_path() -> Path:
    override = os.environ.get("FIELD4_PREVIEW_REGISTRY")
    return Path(override) if override else Path.home() / ".field4" / "preview-servers.json"


@contextlib.contextmanager
def locked_registry():
    """Yield the registry's entries for editing; whatever the list holds afterwards is written back."""
    path = registry_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path.with_suffix(".lock"), "w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        try:
            entries = json.loads(path.read_text()) if path.exists() else []
        except json.JSONDecodeError:
            entries = []
        yield entries
        tmp = path.with_suffix(".tmp")
        tmp.write_text(json.dumps(entries, indent=2) + "\n")
        tmp.replace(path)


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def accepting(port: int) -> bool:
    with socket.socket() as sock:
        sock.settimeout(0.2)
        return sock.connect_ex(("127.0.0.1", port)) == 0


def command_of(pid: int) -> str | None:
    result = subprocess.run(["ps", "-o", "command=", "-p", str(pid)], capture_output=True, text=True)
    return result.stdout.strip() or None


def is_our_server(entry: dict) -> bool:
    """The PID is alive and is still the ``http.server`` on the port we registered."""
    command = command_of(int(entry["pid"]))
    return bool(command) and "http.server" in command and str(entry["port"]) in command.split()


def start(directory: Path, port: int | None, owner: str) -> int:
    directory = directory.resolve()
    if not directory.is_dir():
        print(f"not a directory: {directory}", file=sys.stderr)
        return 1
    port = port or free_port()
    if accepting(port):
        print(f"port {port} is already in use", file=sys.stderr)
        return 1
    logs = registry_path().parent / "preview-logs"
    logs.mkdir(parents=True, exist_ok=True)
    log = open(logs / f"{port}.log", "ab")
    process = subprocess.Popen(
        [sys.executable, "-m", "http.server", str(port), "--bind", "127.0.0.1"],
        cwd=directory, stdin=subprocess.DEVNULL, stdout=log, stderr=log, start_new_session=True,
    )
    deadline = time.time() + 10
    while not accepting(port):
        if process.poll() is not None or time.time() > deadline:
            print(f"server did not come up on {port}; see {logs / f'{port}.log'}", file=sys.stderr)
            with contextlib.suppress(ProcessLookupError):
                process.kill()
            return 1
        time.sleep(0.1)
    with locked_registry() as entries:
        entries.append({
            "pid": process.pid, "port": port, "dir": str(directory), "owner": owner,
            "started": time.time(),
        })
    print(json.dumps({"url": f"http://127.0.0.1:{port}/", "pid": process.pid, "port": port,
                      "dir": str(directory)}))
    return 0


def age_hours(entry: dict, now: float) -> float:
    return (now - float(entry["started"])) / 3600


def describe(entry: dict, now: float) -> str:
    state = "alive" if is_our_server(entry) else "gone"
    return (f"pid {entry['pid']}  port {entry['port']}  {age_hours(entry, now):.1f} h  {state}  "
            f"{entry.get('owner') or '-'}  {entry['dir']}")


def list_servers() -> int:
    now = time.time()
    with locked_registry() as entries:
        for entry in entries:
            print(describe(entry, now))
        if not entries:
            print("no registered preview servers")
    return 0


def terminate(pid: int, grace: float = 3.0) -> bool:
    with contextlib.suppress(ProcessLookupError):
        os.kill(pid, signal.SIGTERM)
    deadline = time.time() + grace
    while time.time() < deadline:
        if command_of(pid) is None:
            return True
        time.sleep(0.1)
    with contextlib.suppress(ProcessLookupError):
        os.kill(pid, signal.SIGKILL)
    time.sleep(0.2)
    return command_of(pid) is None


def selected(entry: dict, *, older_than_hours: float | None, inside: Path | None, now: float) -> bool:
    if older_than_hours is not None and age_hours(entry, now) < older_than_hours:
        return False
    if inside is not None and not Path(entry["dir"]).is_relative_to(inside):
        return False
    return True


def stop(older_than_hours: float | None, inside: Path | None) -> int:
    now = time.time()
    inside = inside.resolve() if inside is not None else None
    failed = 0
    with locked_registry() as entries:
        kept = []
        for entry in entries:
            if not is_our_server(entry):
                # Dead, or the PID now belongs to something else: forget it, never signal it.
                print(f"forgot  {describe(entry, now)}")
                continue
            if not selected(entry, older_than_hours=older_than_hours, inside=inside, now=now):
                kept.append(entry)
                continue
            if terminate(int(entry["pid"])):
                print(f"stopped {describe(entry, now)}")
            else:
                print(f"FAILED  {describe(entry, now)}")
                kept.append(entry)
                failed += 1
        entries[:] = kept
    return 1 if failed else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    start_cmd = commands.add_parser("start", help="Serve a directory on 127.0.0.1 and register the server.")
    start_cmd.add_argument("dir", type=Path)
    start_cmd.add_argument("--port", type=int)
    start_cmd.add_argument("--owner", default="", help="A label for `list`, e.g. the nightly branch.")
    commands.add_parser("list", help="Show registered servers.")
    stop_cmd = commands.add_parser("stop", help="Stop registered servers.")
    stop_cmd.add_argument("--older-than-hours", type=float, metavar="H")
    stop_cmd.add_argument("--dir", type=Path, metavar="PATH", help="Only servers serving inside PATH.")
    stop_cmd.add_argument("--all", action="store_true", help="Every registered server.")
    args = parser.parse_args()

    if args.command == "start":
        return start(args.dir, args.port, args.owner)
    if args.command == "list":
        return list_servers()
    if not (args.all or args.older_than_hours is not None or args.dir is not None):
        parser.error("stop needs --older-than-hours, --dir or --all")
    return stop(args.older_than_hours, args.dir)


if __name__ == "__main__":
    sys.exit(main())
