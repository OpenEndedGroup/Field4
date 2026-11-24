#!/usr/bin/env python3
"""Start a preview server or a demo Field for a worktree, and stop the ones this tool started once they are old.

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
  preview_server.py field <worktree> [--port N] [--owner LABEL] [-- FIELD.SH ARGS]
                                                               run that worktree's ./field.sh, print its URLs

The morning demo had the same leak with a bigger process. Step 6 of the nightly launches a Field
from each branch's worktree (``PORT=<free> AFIELD_FIELD_SH_NO_TERMINAL=1 ./field.sh ...``) so the
human can drive it in the morning, and nothing ever stopped it: on 2026-11-22 nine such Fields and
their 26 Gradle and ``field.sh`` processes were still running a day and a half later, holding six
landed worktrees (about 18 GiB) and much of 24 GiB of swap. A Field started in the background
ignores SIGINT too, so ``kill -INT`` does nothing to it. ``field`` registers the Field like a
preview server, so the same ``stop --older-than-hours 16`` (the next night's Step 0, the 4-hourly
reaper job) ends it: SIGTERM to the Field JVM, which runs its shutdown hooks and saves, then to the
``field.sh`` process group that launched it.

``stop`` only ever signals a process this tool registered, and only after re-checking that the PID
is still an ``http.server`` on the registered port (for a Field: the ``field.sh`` it launched, or the
process listening on the registered port whose cwd is inside the registered worktree), so a reused
PID is left alone. Servers started
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


# What a started server runs. ``python3 -m http.server`` listens with ``request_queue_size = 5``; a
# docs page asks for 50-70 files at once (scripts, fonts, figures, clips), macOS resets every
# connection past the fifth queued one, and the page comes up with its fence scripts missing (raw
# ``:::static`` code, no highlighting) and figures broken. 2026-11-16: all three nightly pages
# looked half-built for that reason alone. The argv keeps ``http.server`` and the port as their own
# words so ``is_our_server`` recognises servers started before and after this change. The directory
# is passed as a path and resolved on every request, not held as the cwd: the docs build renames a
# fresh ``public/field4`` into place, and a server holding the old directory kept serving the
# pre-rebuild site (2026-11-16).
SERVE = (
    "import sys, functools, http.server as h; "
    "h.ThreadingHTTPServer.request_queue_size = 256; "
    "h.ThreadingHTTPServer.daemon_threads = True; "
    "h.ThreadingHTTPServer(('127.0.0.1', int(sys.argv[2])), "
    "functools.partial(h.SimpleHTTPRequestHandler, directory=sys.argv[3])).serve_forever()"
)


def is_our_server(entry: dict) -> bool:
    """The PID is alive and is still the ``http.server`` on the port we registered."""
    if entry.get("kind") == "field":
        return any(pid is not None for pid in field_processes(entry))
    command = command_of(int(entry["pid"]))
    return bool(command) and "http.server" in command and str(entry["port"]) in command.split()


def listener_of(port: int) -> int | None:
    result = subprocess.run(["lsof", "-nP", f"-iTCP:{port}", "-sTCP:LISTEN", "-t"], capture_output=True, text=True)
    pids = [int(line) for line in result.stdout.split() if line.isdigit()]
    return pids[0] if pids else None


def cwd_of(pid: int) -> Path | None:
    result = subprocess.run(["lsof", "-a", "-p", str(pid), "-d", "cwd", "-Fn"], capture_output=True, text=True)
    names = [line[1:] for line in result.stdout.splitlines() if line.startswith("n")]
    return Path(names[0]) if names else None


def field_processes(entry: dict) -> tuple[int | None, int | None]:
    """(the Field listening on the registered port, the ``field.sh`` that launched it), each None when
    it is gone or is no longer ours: a listener counts only while its cwd is inside the worktree."""
    tree = Path(entry["dir"])
    server = listener_of(int(entry["port"]))
    if server is not None:
        cwd = cwd_of(server)
        if cwd is None or not cwd.is_relative_to(tree):
            server = None
    launcher = int(entry["pid"])
    command = command_of(launcher)
    if not command or "field.sh" not in command:
        launcher = None
    return server, launcher


def field_port() -> int:
    """A free port whose HTTP mirror (port + 10000) is free and valid too; an OS-chosen ephemeral
    port is often above 55535, where Field's mirror cannot follow it."""
    for port in range(9700, 9900):
        if not accepting(port) and not accepting(port + 10000):
            with contextlib.suppress(OSError), socket.socket() as sock:
                sock.bind(("127.0.0.1", port))
                return port
    raise SystemExit("no free port in 9700-9899")


def start_field(worktree: Path, port: int | None, owner: str, field_args: list[str], wait_seconds: float) -> int:
    worktree = worktree.resolve()
    if not (worktree / "field.sh").is_file():
        print(f"no field.sh in {worktree}", file=sys.stderr)
        return 1
    port = port or field_port()
    if accepting(port):
        print(f"port {port} is already in use", file=sys.stderr)
        return 1
    logs = registry_path().parent / "preview-logs"
    logs.mkdir(parents=True, exist_ok=True)
    log_path = logs / f"field-{port}.log"
    log = open(log_path, "ab")
    environment = {**os.environ, "PORT": str(port), "AFIELD_FIELD_SH_NO_TERMINAL": "1"}
    process = subprocess.Popen(["./field.sh", *field_args], cwd=worktree, stdin=subprocess.DEVNULL,
                               stdout=log, stderr=log, start_new_session=True, env=environment)
    entry = {"kind": "field", "pid": process.pid, "port": port, "dir": str(worktree), "owner": owner,
             "started": time.time()}
    # Registered before the wait, so a Field that is slow to build is still one `stop` can reach.
    with locked_registry() as entries:
        entries.append(entry)
    deadline = time.time() + wait_seconds
    while not accepting(port):
        if process.poll() is not None or time.time() > deadline:
            print(f"Field did not come up on {port}; see {log_path}", file=sys.stderr)
            stop_field(entry)
            with locked_registry() as entries:
                entries[:] = [e for e in entries if e != entry]
            return 1
        time.sleep(0.5)
    print(json.dumps({"url": f"https://localhost:{port}/index.html", "http": f"http://localhost:{port + 10000}/index.html",
                      "pid": process.pid, "port": port, "dir": str(worktree), "log": str(log_path)}))
    return 0


def stop_field(entry: dict, grace: float = 30.0) -> bool:
    """SIGTERM to the Field first (its shutdown hooks save), then to the launching process group."""
    server, launcher = field_processes(entry)
    if server is not None:
        terminate(server, grace)
    with contextlib.suppress(ProcessLookupError, PermissionError):
        os.killpg(int(entry["pid"]), signal.SIGTERM)  # start_new_session made field.sh its group leader
    if launcher is not None:
        terminate(launcher)
    return not is_our_server(entry)


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
        [sys.executable, "-c", SERVE, "http.server", str(port), str(directory)],
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
    kind = "field  " if entry.get("kind") == "field" else ""
    return (f"{kind}pid {entry['pid']}  port {entry['port']}  {age_hours(entry, now):.1f} h  {state}  "
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
            if stop_field(entry) if entry.get("kind") == "field" else terminate(int(entry["pid"])):
                print(f"stopped {describe(entry, now)}")
            else:
                print(f"FAILED  {describe(entry, now)}")
                kept.append(entry)
                failed += 1
        entries[:] = kept
    return 1 if failed else 0


def main(argv: list[str] | None = None) -> int:
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
    field_cmd = commands.add_parser("field", help="Run a worktree's ./field.sh as a registered demo Field.")
    field_cmd.add_argument("worktree", type=Path)
    field_cmd.add_argument("--port", type=int)
    field_cmd.add_argument("--owner", default="", help="A label for `list`, e.g. the nightly branch.")
    field_cmd.add_argument("--wait-seconds", type=float, default=900,
                           help="How long a first build may take before the port answers (default 900).")
    # Everything after `--` is field.sh's, split off by hand: argparse.REMAINDER after a positional
    # also swallows this tool's own options (`field . --owner x -- ws` handed `--owner` to field.sh).
    argv = sys.argv[1:] if argv is None else list(argv)
    field_args: list[str] = []
    if argv[:1] == ["field"] and "--" in argv:
        split = argv.index("--")
        argv, field_args = argv[:split], argv[split + 1:]
    args = parser.parse_args(argv)

    if args.command == "field":
        return start_field(args.worktree, args.port, args.owner, field_args, args.wait_seconds)

    if args.command == "start":
        return start(args.dir, args.port, args.owner)
    if args.command == "list":
        return list_servers()
    if not (args.all or args.older_than_hours is not None or args.dir is not None):
        parser.error("stop needs --older-than-hours, --dir or --all")
    return stop(args.older_than_hours, args.dir)


if __name__ == "__main__":
    sys.exit(main())
