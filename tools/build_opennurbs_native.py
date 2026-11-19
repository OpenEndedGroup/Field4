#!/usr/bin/env python3
"""Build `field_opennurbs_jvm` — openNURBS behind a flat C ABI — on macOS, Linux or Windows.

openNURBS is McNeel's own reader and writer for Rhino's .3dm files. The wrapper converts a file
into layers, meshes, curve paths, text and points that `afield.geometry.rhino.Rhino3dm` hands to
box code; native/field-opennurbs/src/field_opennurbs.h has the contract.

Nothing is vendored: this downloads a pinned, checksummed source tarball, and openNURBS's own
CMakeLists builds its static library, which is linked whole into the wrapper. The first run needs
the network; after that the tarball is cached in the platform's per-user Field cache, shared by
clones and preserved by Gradle ``clean``. Override the root with ``AFIELD_NATIVE_DOWNLOAD_CACHE``.

    python3 tools/build_opennurbs_native.py
    ./gradlew buildOpenNurbsNative
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
WORK = REPO_ROOT / "build" / "field-opennurbs"
WRAPPER = REPO_ROOT / "native" / "field-opennurbs"

# openNURBS 8.35, the last Rhino 8 release tag. It reads every archive version from Rhino 1 to 8.
# Its licence is McNeel's permissive openNURBS licence (with zlib's for the bundled zlib), which
# places no conditions on Field's AGPL-3.
OPENNURBS_VERSION = "8.35.26251.13001"
OPENNURBS_URL = f"https://github.com/mcneel/opennurbs/archive/refs/tags/v{OPENNURBS_VERSION}.tar.gz"
OPENNURBS_SHA256 = "95e8ba3c9374c48bc2353bb7c8eabc180a4eccc764c305257c9c8f81d147668d"


def library_name() -> str:
    if os.name == "nt":
        return "field_opennurbs_jvm.dll"
    if platform.system() == "Darwin":
        return "libfield_opennurbs_jvm.dylib"
    return "libfield_opennurbs_jvm.so"


def run(command: list[str], what: str) -> None:
    print(f"[field-opennurbs] {what}: {' '.join(command)}", flush=True)
    result = subprocess.run(command)
    if result.returncode != 0:
        raise SystemExit(f"[field-opennurbs] {what} failed with exit code {result.returncode}")


def fetch(url: str, sha256: str, dest: Path) -> None:
    if dest.exists():
        digest = hashlib.sha256(dest.read_bytes()).hexdigest()
        if digest == sha256:
            print(f"[field-opennurbs] cached {dest.name}", flush=True)
            return
        print(f"[field-opennurbs] {dest.name} has the wrong digest, re-downloading", flush=True)
        dest.unlink()
    dest.parent.mkdir(parents=True, exist_ok=True)
    print(f"[field-opennurbs] downloading {url}", flush=True)
    temporary = dest.with_name(f".{dest.name}.{os.getpid()}.tmp")
    try:
        with urllib.request.urlopen(url, timeout=180) as response, temporary.open("wb") as out:
            shutil.copyfileobj(response, out)
        digest = hashlib.sha256(temporary.read_bytes()).hexdigest()
        if digest != sha256:
            raise SystemExit(f"[field-opennurbs] checksum mismatch for {url}: got {digest}")
        temporary.replace(dest)
    finally:
        temporary.unlink(missing_ok=True)


def extract(archive: Path, into: Path) -> Path:
    if (into / ".extracted").read_text(encoding="utf-8").strip() == OPENNURBS_SHA256 if (into / ".extracted").exists() else False:
        return into
    if into.exists():
        shutil.rmtree(into)
    into.mkdir(parents=True)
    with tarfile.open(archive) as tar:
        # The tarball has one top-level directory; strip it so the layout is predictable. The
        # example .3dm files and Xcode projects are not needed to build the library.
        members = tar.getmembers()
        root = members[0].name.split("/")[0]
        for member in members:
            if member.name == root or not member.name.startswith(root + "/"):
                continue
            member.name = member.name[len(root) + 1:]
            if member.name.startswith(("example_files/", "opennurbsRhino_iOS.xcodeproj/")):
                continue
            if member.name:
                tar.extract(member, into, filter="data")
    if not (into / "opennurbs.h").exists():
        raise SystemExit(f"[field-opennurbs] {archive.name} did not contain opennurbs.h")
    (into / ".extracted").write_text(OPENNURBS_SHA256, encoding="utf-8")
    return into


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jobs", type=int, default=os.cpu_count() or 4)
    parser.add_argument("--clean", action="store_true", help="discard the build tree first")
    args = parser.parse_args()

    if shutil.which("cmake") is None:
        raise SystemExit("[field-opennurbs] cmake is not on PATH")

    build = WORK / "build"
    if args.clean and build.exists():
        shutil.rmtree(build)

    archive = native_download_cache("opennurbs") / f"opennurbs-{OPENNURBS_VERSION}.tar.gz"
    fetch(OPENNURBS_URL, OPENNURBS_SHA256, archive)
    source = extract(archive, WORK / "opennurbs-src")

    defines = {
        "CMAKE_BUILD_TYPE": "Release",
        "FIELD_OPENNURBS_SOURCE_DIR": str(source),
        "FIELD_OPENNURBS_VERSION": OPENNURBS_VERSION,
    }
    run(["cmake", "-S", str(WRAPPER), "-B", str(build)] +
        [f"-D{key}={value}" for key, value in defines.items()], "configure")
    run(["cmake", "--build", str(build), "--config", "Release", "--target", "field_opennurbs_jvm",
         "--parallel", str(args.jobs)], "build")

    candidates = [path for path in build.rglob(library_name())]
    if not candidates:
        raise SystemExit(f"[field-opennurbs] built, but no {library_name()} under {build}")
    library = candidates[0]
    print(f"[field-opennurbs] built {library}", flush=True)
    print(f"[field-opennurbs] point Field at it with -Dafield.opennurbs.library={library}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
