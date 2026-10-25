#!/usr/bin/env python3
"""Build Field's optional SolidCraft bridge from one pinned upstream revision.

The adapter is copied into SolidCraft's workspace so its patched truck crates,
which Cargo does not inherit from a Git dependency, are used by the bridge too.
Nothing from this optional build enters Field's normal compile or check tasks.
"""

from __future__ import annotations

import platform
import shutil
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
UPSTREAM = "https://github.com/tangency-labs/solidcraft.git"
# v0.15.0, pinned by commit because a tag can move.
REVISION = "fa691707c224cae580c42201ed3a22d5e4b8c716"
SOURCE = ROOT / "build" / "solidcraft-src"
ADAPTER = ROOT / "native" / "field-solidcraft"
STAGED = SOURCE / "crates" / "field4-bridge"
OUTPUT = ROOT / "build" / "native" / "solidcraft"


def run(*argv: str, cwd: Path | None = None) -> str:
    print("+", " ".join(argv), flush=True)
    return subprocess.check_output(argv, cwd=cwd, text=True).strip()


def main() -> None:
    if not SOURCE.exists():
        SOURCE.parent.mkdir(parents=True, exist_ok=True)
        run("git", "clone", "--filter=blob:none", "--no-checkout", UPSTREAM, str(SOURCE))
    actual = subprocess.run(["git", "rev-parse", "-q", "--verify", "HEAD"], cwd=SOURCE, text=True, capture_output=True).stdout.strip()
    if actual != REVISION:
        # An older pin (or a fresh --no-checkout clone): move it to this one. The checkout is
        # build output; only local edits to tracked upstream files would stop it. Cargo.lock is
        # not one: building the staged bridge adds it to the workspace lockfile.
        checked_out = run("git", "ls-files", cwd=SOURCE) != ""
        if checked_out:
            run("git", "checkout", "--", "Cargo.lock", cwd=SOURCE)
        dirty =checked_out and run("git", "status", "--porcelain", "--untracked-files=no", cwd=SOURCE)
        if dirty:
            raise SystemExit(f"SolidCraft checkout {SOURCE} has local edits; commit or discard them to move to {REVISION}")
        run("git", "remote", "set-url", "origin", UPSTREAM, cwd=SOURCE)
        run("git", "fetch", "--depth=1", "origin", REVISION, cwd=SOURCE)
        run("git", "checkout", "--detach", REVISION, cwd=SOURCE)
        actual = run("git", "rev-parse", "HEAD", cwd=SOURCE)
        if actual != REVISION:
            raise SystemExit(f"SolidCraft checkout is {actual}, expected {REVISION}")
    if STAGED.exists():
        shutil.rmtree(STAGED)
    shutil.copytree(ADAPTER, STAGED)
    run("cargo", "build", "-p", "field_solidcraft_jvm", "--profile", "ci", cwd=SOURCE)

    system = platform.system()
    name = {"Darwin": "libfield_solidcraft_jvm.dylib", "Linux": "libfield_solidcraft_jvm.so", "Windows": "field_solidcraft_jvm.dll"}.get(system)
    if name is None:
        raise SystemExit(f"unsupported platform: {system}")
    built = SOURCE / "target" / "ci" / name
    if not built.is_file():
        raise SystemExit(f"Cargo succeeded but did not produce {built}")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    shutil.copy2(built, OUTPUT / name)
    print(f"SolidCraft bridge ready: {OUTPUT / name}")


if __name__ == "__main__":
    main()
