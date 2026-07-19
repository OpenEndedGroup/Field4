"""Shared process and workspace-safety helpers for verification entrypoints."""

from __future__ import annotations

import json
import os
import signal
import subprocess
import sys
import tempfile
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Callable, Iterator, Sequence


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
) -> tuple[subprocess.CompletedProcess[str], int, bool]:
    """Run a child with heartbeats and a hard outer timeout.

    Every child gets its own process group so a timed-out Gradle/backdoor wrapper
    cannot leave descendants holding ports or stdout pipes open.
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
