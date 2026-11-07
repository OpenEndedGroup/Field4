#!/usr/bin/env python3
"""Bound Gradle daemon-log growth without touching a live daemon's log.

Dry-run is the default. Only ``daemon-<pid>.out.log`` files older than the requested horizon are
eligible, and a PID that still exists is always excluded. Gradle caches, registries and JVM state
are outside this tool's scope.
"""

from __future__ import annotations

import argparse
import math
import os
import re
import stat
import time
from pathlib import Path
from typing import Callable


DAEMON_LOG = re.compile(r"daemon-(\d+)\.out\.log$")


def valid_age(days: float) -> bool:
    return math.isfinite(days) and days >= 1


def pid_is_alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


def eligible_logs(
    root: Path,
    *,
    older_than_days: float,
    now: float | None = None,
    alive: Callable[[int], bool] = pid_is_alive,
) -> list[Path]:
    if not valid_age(older_than_days):
        raise ValueError("older_than_days must be finite and at least 1")
    cutoff = (time.time() if now is None else now) - older_than_days * 86400
    found: list[Path] = []
    if not root.is_dir():
        return found
    for path in root.glob("*/daemon-*.out.log"):
        match = DAEMON_LOG.fullmatch(path.name)
        if match is None or path.parent.is_symlink():
            continue
        try:
            info = path.lstat()
        except OSError:
            continue
        if not stat.S_ISREG(info.st_mode) or info.st_mtime >= cutoff:
            continue
        if alive(int(match.group(1))):
            continue
        found.append(path)
    return sorted(found)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--older-than-days", type=float, default=7.0)
    parser.add_argument("--root", type=Path, default=Path.home() / ".gradle" / "daemon")
    args = parser.parse_args()
    if not valid_age(args.older_than_days):
        parser.error("--older-than-days must be finite and at least 1")

    logs = eligible_logs(args.root, older_than_days=args.older_than_days)
    # Keep the identity observed at selection, then check it and the PID again at removal.
    # The files are diagnostics, not a reason to race an active Gradle daemon.
    snapshots = []
    for path in logs:
        try:
            snapshots.append((path, path.lstat()))
        except FileNotFoundError:
            pass
    total = sum(info.st_size for _, info in snapshots)
    print(f"{len(snapshots)} dead-daemon log(s), {total / 1048576:.1f} MiB, older than {args.older_than_days:g} day(s)")
    if not args.apply:
        print("DRY RUN — pass --apply to remove exactly these files.")
        return 0
    removed = skipped = failures = 0
    cutoff = time.time() - args.older_than_days * 86400
    for path, original in snapshots:
        try:
            current = path.lstat()
            match = DAEMON_LOG.fullmatch(path.name)
            if (match is None or path.parent.is_symlink() or not stat.S_ISREG(current.st_mode)
                    or (current.st_dev, current.st_ino, current.st_mtime_ns, current.st_size)
                    != (original.st_dev, original.st_ino, original.st_mtime_ns, original.st_size)
                    or current.st_mtime >= cutoff or pid_is_alive(int(match.group(1)))):
                skipped += 1
                continue
            path.unlink()
            removed += 1
        except FileNotFoundError:
            skipped += 1
        except OSError as error:
            print(f"could not remove {path}: {error}")
            failures += 1
    print(f"removed {removed}; skipped {skipped} changed, live or missing; failed {failures}")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
