#!/usr/bin/env python3
"""Build `field_tetgen_jvm` — TetGen behind a flat C ABI — on macOS, Linux or Windows.

TetGen turns the closed triangle surface `afield.manifold.Solid.toTriMesh()` produces into a
conforming tetrahedral mesh, preserving the surface's sharp edges exactly. That last property is
why it is here rather than a level-set remesher; `dev_docs/TETGEN.md` has the measurement.

Unlike Ceres there is nothing to find_package: TetGen is two source files with no dependencies, so
this downloads a pinned, checksummed tarball and compiles it straight into the wrapper. Nothing is
vendored, so the first run needs the network; after that the tarball is cached in the platform's
per-user Field cache, shared by clones and preserved by Gradle ``clean``. Override the root with
``AFIELD_NATIVE_DOWNLOAD_CACHE``.

    python3 tools/build_tetgen_native.py
    ./gradlew buildTetgenNative

The one flag that is not negotiable lives in native/field-tetgen/CMakeLists.txt: predicates.cxx is
compiled at -O0, because Shewchuk's exact predicates return wrong signs when a compiler is allowed
to reassociate their arithmetic. That failure is silent — an invalid mesh, not a crash.
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

from native_download_cache import native_download_cache, seed_from_legacy_cache

REPO_ROOT = Path(__file__).resolve().parent.parent
WORK = REPO_ROOT / "build" / "field-tetgen"
LEGACY_DOWNLOADS = WORK / "downloads"
WRAPPER = REPO_ROOT / "native" / "field-tetgen"

# TetGen 1.6.0, from the Weierstrass Institute. AGPL-3 or a commercial licence; Field is AGPL-3, so
# the first applies and nothing further is needed.
TETGEN_URL = "https://www.wias-berlin.de/software/tetgen/1.5/src/tetgen1.6.0.tar.gz"
TETGEN_SHA256 = "87b5e61ebd3a471fc4f2cdd7124c2b11dd6639f4feb1f941a5d2f5110d05ce39"


def library_name() -> str:
    if os.name == "nt":
        return "field_tetgen_jvm.dll"
    if platform.system() == "Darwin":
        return "libfield_tetgen_jvm.dylib"
    return "libfield_tetgen_jvm.so"


def run(command: list[str], what: str) -> None:
    print(f"[field-tetgen] {what}: {' '.join(command)}", flush=True)
    result = subprocess.run(command)
    if result.returncode != 0:
        raise SystemExit(f"[field-tetgen] {what} failed with exit code {result.returncode}")


def fetch(url: str, sha256: str, dest: Path) -> None:
    if dest.exists():
        digest = hashlib.sha256(dest.read_bytes()).hexdigest()
        if digest == sha256:
            print(f"[field-tetgen] cached {dest.name}", flush=True)
            return
        print(f"[field-tetgen] {dest.name} has the wrong digest, re-downloading", flush=True)
        dest.unlink()
    dest.parent.mkdir(parents=True, exist_ok=True)
    print(f"[field-tetgen] downloading {url}", flush=True)
    temporary = dest.with_name(f".{dest.name}.{os.getpid()}.tmp")
    try:
        with urllib.request.urlopen(url, timeout=180) as response, temporary.open("wb") as out:
            shutil.copyfileobj(response, out)
        digest = hashlib.sha256(temporary.read_bytes()).hexdigest()
        if digest != sha256:
            raise SystemExit(f"[field-tetgen] checksum mismatch for {url}: got {digest}")
        temporary.replace(dest)
    finally:
        temporary.unlink(missing_ok=True)


def extract(archive: Path, into: Path) -> Path:
    if into.exists():
        shutil.rmtree(into)
    into.mkdir(parents=True)
    with tarfile.open(archive) as tar:
        # The tarball has a single top-level directory; strip it so the layout is predictable.
        members = tar.getmembers()
        root = members[0].name.split("/")[0]
        for member in members:
            if member.name.startswith(root + "/"):
                member.name = member.name[len(root) + 1:]
            elif member.name == root:
                continue
            if member.name:
                tar.extract(member, into, filter="data")
    if not (into / "tetgen.cxx").exists():
        raise SystemExit(f"[field-tetgen] {archive.name} did not contain tetgen.cxx")
    return into


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jobs", type=int, default=os.cpu_count() or 4)
    parser.add_argument("--clean", action="store_true", help="discard the build tree first")
    args = parser.parse_args()

    if shutil.which("cmake") is None:
        raise SystemExit("[field-tetgen] cmake is not on PATH")

    build = WORK / "build"
    if args.clean and build.exists():
        shutil.rmtree(build)

    archive = native_download_cache("tetgen") / "tetgen1.6.0.tar.gz"
    seed_from_legacy_cache(LEGACY_DOWNLOADS / archive.name, archive, TETGEN_SHA256)
    fetch(TETGEN_URL, TETGEN_SHA256, archive)
    source = extract(archive, WORK / "tetgen-src")

    defines = {
        "CMAKE_BUILD_TYPE": "Release",
        "FIELD_TETGEN_SOURCE_DIR": str(source),
    }
    run(["cmake", "-S", str(WRAPPER), "-B", str(build)] +
        [f"-D{key}={value}" for key, value in defines.items()], "configure")
    run(["cmake", "--build", str(build), "--config", "Release", "--parallel", str(args.jobs)], "build")

    candidates = [path for path in build.rglob(library_name())]
    if not candidates:
        raise SystemExit(f"[field-tetgen] built, but no {library_name()} under {build}")
    library = candidates[0]
    print(f"[field-tetgen] built {library}", flush=True)
    print(f"[field-tetgen] point Field at it with -Dafield.tetgen.library={library}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
