"""Shared process and workspace-safety helpers for verification entrypoints."""

from __future__ import annotations

import json
import os
import signal
import ssl
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from contextlib import contextmanager
from pathlib import Path
from typing import Callable, Iterator, Mapping, Sequence


def automation_workspace(repo_root: Path) -> Path:
    """Checkout-local workspace for automated Field servers.

    Automation must not inherit ``~/.afield/last-workspace.txt``: that file is an
    interactive, machine-global preference and can point into a deleted sibling
    worktree. Keeping scratch documents under this checkout's ignored ``build``
    directory also makes ownership explicit and cleanup unsurprising.
    """
    return repo_root.resolve() / "build" / "field-automation-workspace"


def automation_server_environment(
    port: int,
    *,
    base: Mapping[str, str] | None = None,
    extra: Mapping[str, str] | None = None,
) -> dict[str, str]:
    """Environment for an automated Field server without preference side effects."""
    environment = dict(os.environ if base is None else base)
    environment.update({
        "PORT": str(port),
        "AFIELD_SKIP_WORKSPACE_PERSIST": "1",
    })
    if extra is not None:
        environment.update(extra)
    return environment


def field_instance_info(
    port: int,
    ssl_context: ssl.SSLContext,
    *,
    timeout: float = 1.5,
) -> dict[str, object] | None:
    """Return the identity of the Field listening on ``port``, if it can prove one.

    A plain ``/health`` response only proves that *some* Field is alive. Reusing it can execute
    another worktree's bytecode and turn a smoke test into a false green, so supported verification
    entrypoints require the checkout-aware identity endpoint before attaching.
    """
    request = urllib.request.Request(f"https://localhost:{port}/api/instance-info")
    try:
        with urllib.request.urlopen(request, context=ssl_context, timeout=timeout) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except (OSError, ValueError, urllib.error.URLError, urllib.error.HTTPError):
        return None
    return payload if isinstance(payload, dict) else None


def field_instance_problem(
    info: dict[str, object] | None,
    repo_root: Path,
    *,
    allow_stale_source: bool = False,
) -> str | None:
    """Explain why ``info`` is unsafe to reuse from ``repo_root``; ``None`` means safe.

    Identity prevents cross-worktree reuse. The server start timestamp also catches the subtler
    same-checkout case: source edited after Field booted while the old JVM keeps answering health.
    """
    if info is None:
        return "the server does not expose checkout identity (it may be an older or foreign Field)"
    actual_root = info.get("repoRoot")
    try:
        expected_root = str(repo_root.resolve())
        resolved_actual = str(Path(str(actual_root)).resolve())
    except (OSError, TypeError, ValueError):
        return f"the server reported an invalid checkout root: {actual_root!r}"
    if resolved_actual != expected_root:
        return f"the server belongs to {resolved_actual}, not {expected_root}"

    if allow_stale_source:
        return None

    started_raw = info.get("startedAtEpochMs")
    if not isinstance(started_raw, (int, float)):
        return "the server did not report a valid start time"
    started_ns = int(started_raw * 1_000_000)
    watched = [repo_root / "src"]
    watched.extend(repo_root / name for name in ("build.gradle.kts", "settings.gradle.kts", "gradle.properties"))
    newest: Path | None = None
    newest_mtime = started_ns
    for path in watched:
        candidates = path.rglob("*") if path.is_dir() else (path,)
        for candidate in candidates:
            try:
                if candidate.is_file() and candidate.stat().st_mtime_ns > newest_mtime:
                    newest = candidate
                    newest_mtime = candidate.stat().st_mtime_ns
            except OSError:
                continue
    if newest is not None:
        return f"{newest.relative_to(repo_root)} changed after the server started"
    return None


def elapsed_text(seconds: float) -> str:
    if seconds < 60.0:
        return f"{seconds:.0f}s"
    minutes = int(seconds // 60)
    return f"{minutes}m{int(seconds % 60):02d}s"


def terminate_process_tree(process: subprocess.Popen[str], *, grace: float = 5.0) -> None:
    """Terminate a process and the new process group created for it."""
    if process.poll() is not None:
        return
    try:
        if os.name == "nt":
            process.terminate()
        else:
            os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    try:
        process.wait(timeout=grace)
        return
    except subprocess.TimeoutExpired:
        pass
    try:
        if os.name == "nt":
            process.kill()
        else:
            os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        return
    process.wait(timeout=grace)


def run_with_watchdog(
    command: Sequence[str],
    *,
    cwd: Path,
    timeout: float,
    heartbeat_interval: float = 15.0,
    heartbeat: Callable[[str], None] | None = None,
    label: str = "process",
    completion_probe: Callable[[str], bool] | None = None,
    exit_grace: float = 10.0,
    input_text: str | None = None,
    on_start: Callable[[int], None] | None = None,
) -> tuple[subprocess.CompletedProcess[str], int, bool]:
    """Run a child with heartbeats and a hard outer timeout.

    Every child gets its own process group so a timed-out Gradle/backdoor wrapper
    cannot leave descendants holding ports or stdout pipes open.

    `on_start` is called once with the child's PID as soon as it exists. The docs
    runner uses it to know *which* backdoor invocation a failure belongs to: the
    backdoor client names its session log `…-pid<PID>-….jsonl`, so the PID is an exact
    key where "newest log that happens to contain an error" is only a guess.
    """
    with tempfile.TemporaryFile(mode="w+", encoding="utf-8") as stdout_file, tempfile.TemporaryFile(
        mode="w+", encoding="utf-8"
    ) as stderr_file:
        popen_kwargs: dict[str, object] = {
            "cwd": cwd,
            "text": True,
            "stdout": stdout_file,
            "stderr": stderr_file,
            "stdin": subprocess.PIPE if input_text is not None else subprocess.DEVNULL,
        }
        if os.name == "nt":
            popen_kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP  # type: ignore[attr-defined]
        else:
            popen_kwargs["start_new_session"] = True
        started = time.monotonic()
        process = subprocess.Popen(list(command), **popen_kwargs)
        if on_start is not None:
            on_start(process.pid)
        if input_text is not None:
            assert process.stdin is not None
            process.stdin.write(input_text)
            process.stdin.close()
        next_heartbeat = started + max(1.0, heartbeat_interval)
        completion_seen_at: float | None = None
        last_stdout_size = -1
        partial_stdout = ""
        timed_out = False
        try:
            while process.poll() is None:
                now = time.monotonic()
                elapsed = now - started
                if completion_probe is not None and completion_seen_at is None:
                    stdout_size = os.fstat(stdout_file.fileno()).st_size
                    if stdout_size != last_stdout_size:
                        stdout_file.seek(0)
                        partial_stdout = stdout_file.read()
                        stdout_file.seek(0, os.SEEK_END)
                        last_stdout_size = stdout_size
                        if completion_probe(partial_stdout):
                            completion_seen_at = now
                hit_outer_limit = elapsed >= timeout
                hit_exit_grace = completion_seen_at is not None and now - completion_seen_at >= exit_grace
                if hit_outer_limit or hit_exit_grace:
                    timed_out = True
                    terminate_process_tree(process)
                    break
                if heartbeat is not None and now >= next_heartbeat:
                    suffix = ""
                    if completion_seen_at is not None:
                        suffix = f"; commands complete, waiting up to {exit_grace:.0f}s for process exit"
                    heartbeat(
                        f"  {label}: still running after {elapsed_text(elapsed)} "
                        f"(outer limit {elapsed_text(timeout)}{suffix})"
                    )
                    next_heartbeat = now + max(1.0, heartbeat_interval)
                time.sleep(0.25)
        except BaseException:
            terminate_process_tree(process)
            raise
        process.wait()
        stdout_file.flush()
        stderr_file.flush()
        stdout_file.seek(0)
        stderr_file.seek(0)
        stdout = stdout_file.read()
        stderr = stderr_file.read()
    duration_ms = round((time.monotonic() - started) * 1000.0)
    completed = subprocess.CompletedProcess(list(command), process.returncode, stdout, stderr)
    return completed, duration_ms, timed_out


@contextmanager
def verification_lock(
    repo_root: Path,
    *,
    label: str,
    progress: Callable[[str], None] = print,
    heartbeat_interval: float = 15.0,
) -> Iterator[None]:
    """Serialize supported verification commands within one checkout.

    Smoke, UI, and docs capture commands all compile into and execute from the
    same Gradle outputs. Concurrent invocations can corrupt Kotlin incremental
    backups and class directories, so waiting is safer than pretending they are
    independent. The lock file records the current owner for useful telemetry.
    """
    lock_path = repo_root / ".field-backdoor" / "verification.lock"
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    handle = lock_path.open("a+", encoding="utf-8")
    handle.seek(0)
    started = time.monotonic()
    next_heartbeat = started

    if os.name == "nt":
        import msvcrt

        lock_flag = msvcrt.LK_NBLCK
        unlock = lambda: msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
        try_lock = lambda: msvcrt.locking(handle.fileno(), lock_flag, 1)
    else:
        import fcntl

        unlock = lambda: fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
        try_lock = lambda: fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)

    while True:
        try:
            try_lock()
            break
        except (BlockingIOError, OSError):
            now = time.monotonic()
            if now >= next_heartbeat:
                handle.seek(0)
                owner = handle.read().strip() or "another verification command"
                progress(
                    f"WAIT {label}: shared checkout is busy ({owner}); "
                    f"waiting {elapsed_text(now - started)}"
                )
                next_heartbeat = now + max(1.0, heartbeat_interval)
            time.sleep(0.25)

    owner = {
        "pid": os.getpid(),
        "label": label,
        "started": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "command": " ".join(sys.argv),
    }
    handle.seek(0)
    handle.truncate()
    handle.write(json.dumps(owner, separators=(",", ":")))
    handle.flush()
    try:
        yield
    finally:
        handle.seek(0)
        handle.truncate()
        handle.flush()
        unlock()
        handle.close()
