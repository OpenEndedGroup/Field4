#!/usr/bin/env python3
"""Build one of Field's optional native geometry libraries and run its native test.

    python3 tools/build_native_geometry.py morse-smale
    ./gradlew buildMorseSmaleNative

Each module is a self-contained CMake project under native/field-<module>/ with a flat C ABI and a
test executable. morse-smale and principal-curves need only cmake and a C++17 compiler. polyvector
also needs OpenCV, Boost and Eigen (macOS: brew install opencv boost eigen; Debian: apt install
libopencv-dev libboost-dev libeigen3-dev), and on first build downloads the authors' MIT-licensed
implementation at a pinned, checksummed commit, which it patches as listed in POLYVECTOR_PATCHES.
The library lands in build/field-<module>/build/, where its Kotlin wrapper looks first.
Field builds and runs without any of them; afield.geometry.network.NativeGeometry reports which
are present.
"""

from __future__ import annotations

import argparse
import os
import platform
import shutil
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

MODULES = {
    "morse-smale": "field_morse_smale",
    "principal-curves": "field_principal_curves",
    "polyvector": "field_polyvector",
}

# Bessmeltsev & Solomon, "Vectorization of Line Drawings via Polyvector Fields" (2019), MIT licence.
POLYVECTOR_COMMIT = "d296439370b2b7398d9ef70098fa0c42f67e0e8d"
POLYVECTOR_URL = f"https://github.com/bmpix/PolyVectorization/archive/{POLYVECTOR_COMMIT}.tar.gz"
POLYVECTOR_SHA256 = "c24f8fba2b5cf6e5c85d87ac0da802ec2feb54ad6278247213d5cf514ad61785"

# Edits to the pinned source, each naming the exact text it replaces; a patch whose target is not
# found exactly once fails the build, so an upstream change cannot make one silently stop applying.
POLYVECTOR_PATCHES = [
    (
        # The tuning constants become variables field_polyvector.cpp sets before each stage.
        "Params.h",
        "const double BACKGROUND_FOREGROUND_THRESHOLD = 90.0;",
        "extern double BACKGROUND_FOREGROUND_THRESHOLD;",
    ),
    ("Params.h", "const double FRAME_FIELD_REGULARIZER_WEIGHT = 0.1;", "extern double FRAME_FIELD_REGULARIZER_WEIGHT;"),
    ("Params.h", "const double FRAME_FIELD_SMOOTHNESS_WEIGHT = 50.0;", "extern double FRAME_FIELD_SMOOTHNESS_WEIGHT;"),
    ("Params.h", "const double PRUNE_SHORT_BRANCHES_RATIO = 0.75;", "extern double PRUNE_SHORT_BRANCHES_RATIO;"),
    (
        "Params.h",
        "const int MAX_NUMBER_OF_WHITE_PIXELS_IN_A_CONTRACTIBLE_LOOP = 4;",
        "extern int MAX_NUMBER_OF_WHITE_PIXELS_IN_A_CONTRACTIBLE_LOOP;",
    ),
    (
        # cv::pointPolygonTest lives in imgproc (OpenCV 4) or geometry (OpenCV 5); upstream relied
        # on a transitive include.
        "IsLoopContractible.cpp",
        '#include "IsLoopContractible.h"',
        '#include "IsLoopContractible.h"\n#include "opencv2/imgproc.hpp"\n'
        '#if __has_include("opencv2/geometry/2d.hpp")\n#include "opencv2/geometry/2d.hpp"\n#endif',
    ),
]


def prepare_polyvector(tag: str) -> Path:
    import hashlib
    import tarfile
    import urllib.request

    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from native_download_cache import native_download_cache

    archive = native_download_cache("polyvector") / f"PolyVectorization-{POLYVECTOR_COMMIT[:12]}.tar.gz"
    if not archive.exists() or hashlib.sha256(archive.read_bytes()).hexdigest() != POLYVECTOR_SHA256:
        archive.parent.mkdir(parents=True, exist_ok=True)
        print(f"[{tag}] downloading {POLYVECTOR_URL}", flush=True)
        tmp = archive.with_suffix(".part")
        with urllib.request.urlopen(POLYVECTOR_URL, timeout=180) as response:
            tmp.write_bytes(response.read())
        digest = hashlib.sha256(tmp.read_bytes()).hexdigest()
        if digest != POLYVECTOR_SHA256:
            tmp.unlink()
            raise SystemExit(f"[{tag}] checksum mismatch for {POLYVECTOR_URL}: got {digest}")
        tmp.replace(archive)
    upstream = REPO_ROOT / "build" / tag / "upstream"
    if upstream.exists():
        shutil.rmtree(upstream)
    upstream.mkdir(parents=True)
    with tarfile.open(archive) as tar:
        tar.extractall(upstream, filter="data")
    src = next(upstream.glob("PolyVectorization-*")) / "src"
    for name, before, after in POLYVECTOR_PATCHES:
        path = src / name
        text = path.read_text(encoding="utf-8", errors="surrogateescape")
        count = text.count(before)
        if count != 1:
            raise SystemExit(f"[{tag}] patch target found {count} times in {path}: {before!r}")
        path.write_text(text.replace(before, after), encoding="utf-8", errors="surrogateescape")
    return src


def library_name(target: str) -> str:
    if os.name == "nt":
        return f"{target}.dll"
    if platform.system() == "Darwin":
        return f"lib{target}.dylib"
    return f"lib{target}.so"


def run(command: list[str], tag: str, what: str) -> None:
    print(f"[{tag}] {what}: {' '.join(command)}", flush=True)
    result = subprocess.run(command)
    if result.returncode != 0:
        raise SystemExit(f"[{tag}] {what} failed with exit code {result.returncode}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("module", choices=sorted(MODULES))
    parser.add_argument("--jobs", type=int, default=os.cpu_count() or 4)
    parser.add_argument("--clean", action="store_true", help="discard the build tree first")
    parser.add_argument("--skip-tests", action="store_true")
    args = parser.parse_args()

    tag = f"field-{args.module}"
    if shutil.which("cmake") is None:
        raise SystemExit(f"[{tag}] cmake is not on PATH")
    source = REPO_ROOT / "native" / tag
    build = REPO_ROOT / "build" / tag / "build"
    if args.clean and build.exists():
        shutil.rmtree(build)

    defines = ["-DCMAKE_BUILD_TYPE=Release"]
    if args.module == "polyvector":
        defines.append(f"-DFIELD_POLYVECTOR_SOURCE_DIR={prepare_polyvector(tag)}")
    run(["cmake", "-S", str(source), "-B", str(build)] + defines, tag, "configure")
    run(["cmake", "--build", str(build), "--config", "Release", "--parallel", str(args.jobs)], tag, "build")
    if not args.skip_tests:
        run(["ctest", "--test-dir", str(build), "-C", "Release", "--output-on-failure"], tag, "native test")

    target = MODULES[args.module]
    candidates = sorted(build.rglob(library_name(target)))
    if not candidates:
        raise SystemExit(f"[{tag}] built, but no {library_name(target)} under {build}")
    print(f"[{tag}] built {candidates[0]}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
