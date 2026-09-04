#!/usr/bin/env python3
"""Bound Gradle daemon-log growth without touching a live daemon's log.

Dry-run is the default. Only ``daemon-<pid>.out.log`` files older than the requested horizon are
eligible, and a PID that still exists is always excluded. Gradle caches, registries and JVM state
are outside this tool's scope.
"""

from __future__ import annotations

import argparse
import os
import re
import time
from pathlib import Path
from typing import Callable


DAEMON_LOG = re.compile(r"daemon-(\d+)\.out\.log$")


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
    cutoff = (time.time() if now is None else now) - older_than_days * 86400
    found: list[Path] = []
    if not root.is_dir():
        return found
    for path in root.glob("*/daemon-*.out.log"):
        match = DAEMON_LOG.fullmatch(path.name)
        if match is None or path.stat().st_mtime >= cutoff:
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
    if args.older_than_days < 1:
        parser.error("--older-than-days must be at least 1")

    logs = eligible_logs(args.root, older_than_days=args.older_than_days)
    total = sum(path.stat().st_size for path in logs)
    print(f"{len(logs)} dead-daemon log(s), {total / 1048576:.1f} MiB, older than {args.older_than_days:g} day(s)")
    if not args.apply:
        print("DRY RUN — pass --apply to remove exactly these files.")
        return 0
    for path in logs:
        path.unlink()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
