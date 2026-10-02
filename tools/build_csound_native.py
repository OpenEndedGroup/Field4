#!/usr/bin/env python3
"""Build libcsound — Csound as a C library — for `afield.csound`, on macOS or Linux.

`afield.csound` binds Csound's C API (`csound.h`) directly through Java FFM, so there is no
wrapper library of Field's own — only libcsound itself, double precision (`USE_DOUBLE=ON`, the
default every Csound release ships).

Only the library is built: no command-line frontends, no audio or MIDI drivers (Field's own audio
engine pulls samples out of Csound's `spout`), no Java/Python/Lua interfaces, no plugin opcodes
that need extra libraries. The opcodes compiled into libcsound itself — oscillators, envelopes,
filters, tables, `chnget`/`chnset`, the score language — are all there. libsndfile is the one
dependency (`brew install libsndfile` / `apt install libsndfile1-dev`); cmake finds it.

Nothing is vendored. The source is one pinned, checksummed release tarball, cached in the
platform's per-user Field cache (shared by clones and worktrees, preserved by Gradle ``clean``;
override the root with ``AFIELD_NATIVE_DOWNLOAD_CACHE``).

    python3 tools/build_csound_native.py
    ./gradlew buildCsoundNative

The result is ``build/field-csound/build/CsoundLib64.framework/CsoundLib64`` on macOS and
``build/field-csound/build/libcsound64.so`` on Linux; point Field at another one with
``-Dafield.csound.library=<path>`` or ``AFIELD_CSOUND_LIBRARY``.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import platform
import shutil
import subprocess
import sys
import tarfile
import urllib.request
from pathlib import Path

from native_download_cache import native_download_cache

REPO_ROOT = Path(__file__).resolve().parent.parent
WORK = REPO_ROOT / "build" / "field-csound"

# Csound 6.18.1, LGPL-2.1. The GitHub tag archive, checksummed here.
CSOUND_VERSION = "6.18.1"
CSOUND_URL = f"https://github.com/csound/csound/archive/refs/tags/{CSOUND_VERSION}.tar.gz"
CSOUND_SHA256 = "b84be8237fa9258ef60fdddf36fae27b20c11665811bf5ed12540da9a9a4414e"

OFF = [
    "BUILD_CXX_INTERFACE", "BUILD_UTILITIES", "BUILD_TESTS", "BUILD_STATIC_LIBRARY", "USE_GETTEXT",
    "USE_CURL", "USE_PORTAUDIO", "USE_PORTMIDI", "USE_JACK", "USE_ALSA", "USE_PULSEAUDIO",
    "BUILD_JAVA_INTERFACE", "BUILD_LUA_INTERFACE", "BUILD_DSSI_OPCODES", "BUILD_OSC_OPCODES",
    "BUILD_RELEASE", "BUILD_INSTALLER", "BUILD_DEPRECATED_OPCODES", "USE_LIB64",
]


def library_relative() -> Path:
    if platform.system() == "Darwin":
        return Path("CsoundLib64.framework") / "CsoundLib64"
    return Path("libcsound64.so")


def target_name() -> str:
    return "CsoundLib64" if platform.system() == "Darwin" else "libcsound64"


def run(command: list[str], what: str) -> None:
    print(f"[field-csound] {what}: {' '.join(command)}", flush=True)
    result = subprocess.run(command)
    if result.returncode != 0:
        raise SystemExit(f"[field-csound] {what} failed with exit code {result.returncode}")


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def fetch(cache: Path) -> Path:
    """The extracted, checksum-verified source tree."""
    archive = cache / f"csound-{CSOUND_VERSION}.tar.gz"
    source = cache / f"csound-{CSOUND_VERSION}"
    if not archive.exists() or sha256(archive) != CSOUND_SHA256:
        cache.mkdir(parents=True, exist_ok=True)
        temporary = archive.with_name(f".{archive.name}.{os.getpid()}.tmp")
        print(f"[field-csound] download {CSOUND_URL}", flush=True)
        with urllib.request.urlopen(CSOUND_URL) as response, temporary.open("wb") as out:
            shutil.copyfileobj(response, out)
        got = sha256(temporary)
        if got != CSOUND_SHA256:
            temporary.unlink()
            raise SystemExit(f"[field-csound] checksum mismatch for {CSOUND_URL}: {got}")
        temporary.replace(archive)
        if source.exists():
            shutil.rmtree(source)
    if not (source / "include" / "csound.h").exists():
        staging = cache / f".extract.{os.getpid()}"
        if staging.exists():
            shutil.rmtree(staging)
        with tarfile.open(archive) as tar:
            tar.extractall(staging)
        (staging / f"csound-{CSOUND_VERSION}").replace(source)
        shutil.rmtree(staging)
    else:
        print(f"[field-csound] cached {source}", flush=True)
    return source


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jobs", type=int, default=os.cpu_count() or 4)
    parser.add_argument("--clean", action="store_true", help="discard the build tree first")
    args = parser.parse_args()

    if os.name == "nt":
        raise SystemExit("[field-csound] Windows is not supported by this script; install Csound and "
                         "point AFIELD_CSOUND_LIBRARY at its csound64.dll")
    for tool in ("cmake", "flex", "bison"):
        if shutil.which(tool) is None:
            raise SystemExit(f"[field-csound] {tool} is not on PATH")

    build = WORK / "build"
    if args.clean and build.exists():
        shutil.rmtree(build)

    source = fetch(native_download_cache("csound"))
    defines = {"CMAKE_BUILD_TYPE": "Release", "USE_DOUBLE": "ON", **{k: "OFF" for k in OFF}}
    run(["cmake", "-S", str(source), "-B", str(build), "-Wno-dev"] +
        [f"-D{key}={value}" for key, value in defines.items()], "configure")
    run(["cmake", "--build", str(build), "--target", target_name(), "--parallel", str(args.jobs)], "build")

    library = build / library_relative()
    if not library.exists():
        raise SystemExit(f"[field-csound] built, but no {library}")
    print(f"[field-csound] built {library}", flush=True)
    print(f"[field-csound] point Field at it with -Dafield.csound.library={library}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
