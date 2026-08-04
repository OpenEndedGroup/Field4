#!/usr/bin/env python3
"""Build the Ceres wrapper from pinned source and run the ABI conformance harness against it.

One command, three platforms, no Gradle and no Kotlin:

    python3 tools/ci_ceres_abi.py

This is the whole cross-platform contract for the solver. It exists as a script rather than as steps
in a workflow file so that the CI logic can be iterated on locally and inside a container — a bug in
a YAML `run:` block costs a push, a wait and a fresh runner to find, and on Windows those minutes
bill at twice the rate.

What it deliberately does *not* do is compile Field. `./gradlew smokeCeres` is the richer test, but it
needs jvmMain's runtime classpath: ~470k lines of Kotlin against a snapshot compiler fork, plus the
190 MB LFS blob that fork lives in. What differs between macOS, Linux and Windows is the native build
and the C ABI, so that is what this checks, in minutes rather than tens of minutes.
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
HARNESS = REPO_ROOT / "tools" / "ceres_abi" / "CeresAbiConformance.java"
DEFAULT_OUT = REPO_ROOT / "build" / "ken-ceres-redistributable"

sys.path.insert(0, str(Path(__file__).resolve().parent))
from build_ceres_native import library_name  # noqa: E402


def find_java() -> str:
    """The harness needs a JDK 22 or newer, because that is where FFM stopped being a preview API."""
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = Path(java_home) / "bin" / ("java.exe" if platform.system() == "Windows" else "java")
        if candidate.is_file():
            return str(candidate)
    found = shutil.which("java")
    if found:
        return found
    raise SystemExit("error: no java on PATH and JAVA_HOME is unset; the harness needs a JDK 22+")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--library",
        type=Path,
        help="skip the build and test this library instead (e.g. a buildCeresNative result)",
    )
    parser.add_argument(
        "--out", type=Path, default=DEFAULT_OUT, help="where the build should put its output"
    )
    parser.add_argument(
        "--skip-if-present",
        action="store_true",
        help="reuse an existing built library rather than rebuilding it",
    )
    args = parser.parse_args()

    if args.library:
        library = args.library
        if not library.is_file():
            raise SystemExit(f"error: no library at {library}")
        print(f"testing an existing library: {library}\n")
    else:
        build = [sys.executable, str(REPO_ROOT / "tools" / "build_ceres_native.py"), "--out", str(args.out)]
        if args.skip_if_present:
            build.append("--skip-if-present")
        print(f"$ {' '.join(build)}", flush=True)
        result = subprocess.run(build, cwd=REPO_ROOT)
        if result.returncode != 0:
            return result.returncode
        library = args.out / library_name()
        print()

    command = [
        find_java(),
        "--enable-native-access=ALL-UNNAMED",
        str(HARNESS),
        str(library),
    ]
    print(f"$ {' '.join(command)}", flush=True)
    return subprocess.run(command, cwd=REPO_ROOT).returncode


if __name__ == "__main__":
    sys.exit(main())
