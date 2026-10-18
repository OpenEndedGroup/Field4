#!/usr/bin/env python3
"""Build one of Field's optional native geometry libraries and run its native test.

    python3 tools/build_native_geometry.py morse-smale
    ./gradlew buildMorseSmaleNative

Each module is a self-contained CMake project under native/field-<module>/ with a flat C ABI and a
test executable. It needs only cmake and a C++17 compiler: no downloads, no third-party packages.
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
}


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

    run(["cmake", "-S", str(source), "-B", str(build), "-DCMAKE_BUILD_TYPE=Release"], tag, "configure")
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
