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
UPSTREAM = "https://github.com/bherbruck/solidcraft.git"
REVISION = "e519fbd14dce22e7d03b44d1125da0bdaec2935c"
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
        run("git", "fetch", "--depth=1", "origin", REVISION, cwd=SOURCE)
        run("git", "checkout", REVISION, cwd=SOURCE)
    actual = run("git", "rev-parse", "HEAD", cwd=SOURCE)
    if actual != REVISION:
        raise SystemExit(f"SolidCraft checkout is {actual}, expected {REVISION}; move it aside to rebuild")
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
