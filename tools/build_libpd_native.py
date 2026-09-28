#!/usr/bin/env python3
"""Build libpd — Pure Data as a C library — for `afield.pd`, on macOS, Linux or Windows.

libpd is Pd's DSP engine and message system without its GUI: open a `.pd` patch, send it
messages, pull blocks of audio out of it. `afield.pd` binds its C ABI (`z_libpd.h`) directly
through Java FFM, so there is no wrapper library of Field's own — only libpd itself.

It is built with ``PD_MULTI=ON``: every `PdInstance` in Field is its own Pd, with its own patches,
its own `dac~` and its own receive hooks. That is what lets a test render a patch offline while
another box's patch is playing, and what lets two boxes each own a Pd without hearing each other's
`[s]`/`[r]` names.

Nothing is vendored. The source is a shallow clone of one pinned libpd tag (with its `pure-data`
submodule), cached in the platform's per-user Field cache — shared by clones and worktrees and
preserved by Gradle ``clean`` — and checked against pinned commit hashes before every build. Override
the cache root with ``AFIELD_NATIVE_DOWNLOAD_CACHE``.

    python3 tools/build_libpd_native.py
    ./gradlew buildLibpdNative

The result is ``build/field-libpd/build/libs/libpd-multi.<dylib|so|dll>``; point Field at another
one with ``-Dafield.pd.library=<path>`` or ``AFIELD_PD_LIBRARY``.
"""

from __future__ import annotations

import argparse
import os
import platform
import shutil
import subprocess
import sys
from pathlib import Path

from native_download_cache import native_download_cache

REPO_ROOT = Path(__file__).resolve().parent.parent
WORK = REPO_ROOT / "build" / "field-libpd"

# libpd 0.16.1 (Pd 0.56-5, as its m_pd.h says). BSD-3 licensed, like Pd itself.
LIBPD_URL = "https://github.com/libpd/libpd.git"
LIBPD_TAG = "0.16.1"
LIBPD_COMMIT = "ba0dc63262901d658af8bbda5e619a60fa975e78"
PURE_DATA_COMMIT = "f009fd8d7b537e209e09898d487fdf1bf547da2b"


def library_name() -> str:
    if os.name == "nt":
        return "libpd-multi.dll"
    if platform.system() == "Darwin":
        return "libpd-multi.dylib"
    return "libpd-multi.so"


def run(command: list[str], what: str, cwd: Path | None = None) -> None:
    print(f"[field-libpd] {what}: {' '.join(command)}", flush=True)
    result = subprocess.run(command, cwd=cwd)
    if result.returncode != 0:
        raise SystemExit(f"[field-libpd] {what} failed with exit code {result.returncode}")


def output(command: list[str], cwd: Path) -> str:
    return subprocess.run(command, cwd=cwd, capture_output=True, text=True, check=True).stdout.strip()


def pinned(source: Path) -> bool:
    """True when `source` is a complete checkout of exactly the pinned commits."""
    if not (source / "libpd_wrapper" / "z_libpd.h").exists():
        return False
    if not (source / "pure-data" / "src" / "m_pd.h").exists():
        return False
    try:
        head = output(["git", "rev-parse", "HEAD"], source)
        pd_head = output(["git", "rev-parse", "HEAD"], source / "pure-data")
    except (subprocess.CalledProcessError, FileNotFoundError):
        return False
    if head != LIBPD_COMMIT or pd_head != PURE_DATA_COMMIT:
        print(f"[field-libpd] cached checkout is at {head[:10]}/{pd_head[:10]}, "
              f"pinned {LIBPD_COMMIT[:10]}/{PURE_DATA_COMMIT[:10]}", flush=True)
        return False
    return True


def fetch(source: Path) -> None:
    if pinned(source):
        print(f"[field-libpd] cached {source}", flush=True)
        return
    if source.exists():
        shutil.rmtree(source)
    source.parent.mkdir(parents=True, exist_ok=True)
    temporary = source.with_name(f".{source.name}.{os.getpid()}.tmp")
    if temporary.exists():
        shutil.rmtree(temporary)
    try:
        run(["git", "-c", "advice.detachedHead=false", "clone", "--quiet", "--depth", "1",
             "--branch", LIBPD_TAG, "--recurse-submodules", "--shallow-submodules",
             LIBPD_URL, str(temporary)], "clone")
        if not pinned(temporary):
            raise SystemExit(f"[field-libpd] tag {LIBPD_TAG} no longer resolves to the pinned commits; "
                             "someone moved the tag — update the pin deliberately")
        temporary.replace(source)
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jobs", type=int, default=os.cpu_count() or 4)
    parser.add_argument("--clean", action="store_true", help="discard the build tree first")
    args = parser.parse_args()

    for tool in ("cmake", "git"):
        if shutil.which(tool) is None:
            raise SystemExit(f"[field-libpd] {tool} is not on PATH")

    build = WORK / "build"
    if args.clean and build.exists():
        shutil.rmtree(build)

    source = native_download_cache("libpd") / f"libpd-{LIBPD_TAG}"
    fetch(source)

    defines = {
        "CMAKE_BUILD_TYPE": "Release",
        "PD_MULTI": "ON",
        "PD_UTILS": "ON",
        "PD_EXTRA": "ON",
        "LIBPD_SHARED": "ON",
        "LIBPD_STATIC": "OFF",
        "PD_BUILD_C_EXAMPLES": "OFF",
    }
    run(["cmake", "-S", str(source), "-B", str(build)] +
        [f"-D{key}={value}" for key, value in defines.items()], "configure")
    run(["cmake", "--build", str(build), "--config", "Release", "--parallel", str(args.jobs)], "build")

    candidates = sorted(build.rglob(library_name()))
    if not candidates:
        raise SystemExit(f"[field-libpd] built, but no {library_name()} under {build}")
    library = candidates[0]
    print(f"[field-libpd] built {library}", flush=True)
    print(f"[field-libpd] point Field at it with -Dafield.pd.library={library}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
