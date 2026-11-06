"""Owned keep-awake processes and cooperative, per-volume verification disk claims.

Claims protect declared budgets between cooperating runners, not filesystem blocks.
They never remove build output, branches, worktrees or user data.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import sys
import uuid
from contextlib import contextmanager


def lock_file(handle, *, blocking=True):
    if os.name == "nt":
        import msvcrt
        handle.seek(0)
        msvcrt.locking(handle.fileno(), msvcrt.LK_LOCK if blocking else msvcrt.LK_NBLCK, 1)
    else:
        import fcntl
        fcntl.flock(handle.fileno(), fcntl.LOCK_EX | (0 if blocking else fcntl.LOCK_NB))


class SharedDiskClaim:
    def __init__(self, path: Path, *, root: Path | None = None):
        root = root or Path(os.environ.get("FIELD_VERIFY_BUDGET_DIR", Path.home() / ".afield/verification-budgets"))
        self.folder = root / str(path.stat().st_dev)
        self.handle = None
        self.path = None
        self.others = 0

    @contextmanager
    def admission(self):
        self.folder.mkdir(parents=True, exist_ok=True)
        with (self.folder / "admission.lock").open("a+b") as guard:
            if guard.tell() == 0:
                guard.write(b" ")
                guard.flush()
            lock_file(guard)
            yield

    def acquire(self, *, free=None, probe=None, floor, reserve, planned):
        with self.admission():
            if probe is not None:
                free = probe()
            if free is not None and (type(free) is not int or free < 0):
                raise ValueError("invalid free-space reading during shared disk admission")
            claims = []
            for path in self.folder.glob("*.json"):
                try:
                    handle = path.open("r+b")
                except FileNotFoundError:
                    continue
                stale = False
                with handle:
                    try:
                        lock_file(handle, blocking=False)
                    except (BlockingIOError, OSError):
                        record = json.load(handle)
                        if not isinstance(record, dict) or not all(
                                type(record.get(k)) is int and record[k] >= 0
                                for k in ("floor", "reserve", "planned")):
                            raise ValueError(f"invalid active disk claim: {path}")
                        claims.append(record)
                    else:
                        # Only expired reservation metadata is removed. A process crash releases
                        # the OS lock; PID reuse and wall-clock ages do not decide ownership.
                        stale = True
                if stale:
                    path.unlink(missing_ok=True)
            self.others = sum(c["reserve"] + c["planned"] for c in claims)
            required = max([floor] + [c["floor"] for c in claims]) + reserve + planned + self.others
            if free is None or free < required:
                available = "unknown" if free is None else f"{free / 1024**3:.2f} GiB"
                raise ValueError(f"shared disk budget: {available} available; need {required / 1024**3:.2f} GiB, including "
                                 f"{self.others / 1024**3:.2f} GiB claimed by other runs")
            self.path = self.folder / f"{uuid.uuid4().hex}.json"
            self.handle = self.path.open("x+b")
            try:
                self.handle.write(json.dumps(dict(pid=os.getpid(), floor=floor, reserve=reserve,
                                                  planned=planned)).encode())
                self.handle.flush()
                lock_file(self.handle)
            except BaseException:
                self.handle.close()
                self.handle = None
                self.path.unlink(missing_ok=True)
                raise

    def close(self):
        if self.handle:
            # Unlink while locked. Windows requires closing first; admission serializes both.
            with self.admission():
                self.handle.close()
                self.handle = None
                self.path.unlink(missing_ok=True)


class KeepAwake:
    def __init__(self):
        self.process = None
        self.error = None
        self.pid = None

    def start(self):
        if sys.platform == "darwin" and self.process is None:
            try:
                self.process = subprocess.Popen(["caffeinate", "-dims", "-w", str(os.getpid())],
                                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                self.pid = self.process.pid
            except OSError as exc:
                self.error = str(exc)
        return self

    def report(self):
        return {"supported": sys.platform == "darwin", "pid": self.pid,
                "active": self.process is not None and self.process.poll() is None, "error": self.error}

    def close(self):
        if self.process:
            if self.process.poll() is None:
                self.process.terminate()
                try:
                    self.process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    self.process.kill()
                    self.process.wait(timeout=5)
            self.process = None

    def __enter__(self):
        return self.start()

    def __exit__(self, *args):
        self.close()
