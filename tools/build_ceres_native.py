#!/usr/bin/env python3
"""Build a *redistributable* ken_ceres_jvm on macOS, Linux or Windows.

`buildCeresNative` links the Ceres that find_package finds — Homebrew's on macOS, libceres-dev's on
Debian. That build is right for iterating on the wrapper and wrong for shipping: it carries
/opt/homebrew/opt/{ceres-solver,glog,gflags} in its load commands, so it only runs where Homebrew is
installed at the same prefix. It is also unavailable on Windows, where there is no system Ceres
worth depending on.

This script produces the shippable artifact instead, by building a static Ceres from pinned source:

  * Ceres 2.2.0 — the version every measurement in dev_docs/CERES.md was taken against.
  * Eigen 3.4.0 — pinned because Ceres 2.2.0's find_package(Eigen3 3.3) *rejects* the Eigen 5.x that
    Homebrew now installs, so "whatever is on the machine" is not a stable input.
  * MINIGLOG=ON, so there is no libglog/libgflags to link. Miniglog writes to std::cerr and gates
    nothing but by MAX_LOG_LEVEL, which Ceres defaults to 2 — two VLOG levels of it — so the smoke
    suite emits ~11k lines of "Allocating values array with N bytes". MINIGLOG_MAX_LOG_LEVEL=-3 is
    FATAL, which is what quiet_glog_once() asks of real glog (FLAGS_stderrthreshold = GLOG_FATAL).
    Miniglog's severities run the other way from glog's: FATAL -3, ERROR -2, WARNING -1, INFO 0.
  * EIGENSPARSE=ON with EIGENMETIS=OFF. Eigen's sparse Cholesky is header-only, so
    SPARSE_NORMAL_CHOLESKY — the polytope realizer's default, and Ceres silently falls back to a
    dense solve without it — costs no extra shared library. Eigen's *METIS ordering* would pull in
    libmetis, so it is off; that changes the fill-reducing ordering, not what can be solved.

The option matrix is identical on all three platforms, deliberately: a polytope should realize the
same way everywhere, and the quickest way to lose that is to let one platform pick up a solver
backend the others do not have. tools/ceres_abi/CeresAbiConformance.java asserts the sparse backend
is really there rather than trusting these flags.

This replaces the older native/ken-ceres/build_redistributable.sh, which was macOS-only for three
reasons that all live here now: it shelled out to curl/tar/shasum, it asserted self-containment with
`otool`, and sh itself is not a thing a Windows runner has.

Downloads are checksummed. Nothing is vendored, so the first run needs the network; after that the
tarballs are cached in the platform's per-user Field cache, shared by clones and preserved by
Gradle ``clean``. Override the root with ``AFIELD_NATIVE_DOWNLOAD_CACHE``.

    python3 tools/build_ceres_native.py
    ./gradlew smokeCeres -PceresLibrary=build/ken-ceres-redistributable/libken_ceres_jvm.dylib
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
import time
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_native_selfcontained import check as check_selfcontained  # noqa: E402
from native_download_cache import native_download_cache, seed_from_legacy_cache  # noqa: E402

CERES_VERSION = "2.2.0"
CERES_SHA256 = "12efacfadbfdc1bbfa203c236e96f4d3c210bed96994288b3ff0c8e7c6f350d4"
CERES_URL = f"https://github.com/ceres-solver/ceres-solver/archive/refs/tags/{CERES_VERSION}.tar.gz"

EIGEN_VERSION = "3.4.0"
EIGEN_SHA256 = "8586084f71f9bde545ee7fa6d00288b264a2b7ac3607b974e54d13e7162c1c72"
EIGEN_URL = f"https://gitlab.com/libeigen/eigen/-/archive/{EIGEN_VERSION}/eigen-{EIGEN_VERSION}.tar.gz"

# FATAL only. See the module docstring; this is Ceres' own cache variable, not a redefinition of its
# macro.
LOG_LEVEL = -3

REPO_ROOT = Path(__file__).resolve().parent.parent
WORK = REPO_ROOT / "build" / "ceres-dist"
LEGACY_DOWNLOADS = WORK / "downloads"
PREFIX = WORK / "prefix"
OUT_DIR = REPO_ROOT / "build" / "ken-ceres-redistributable"
WRAPPER_SOURCE = REPO_ROOT / "native" / "ken-ceres"

IS_WINDOWS = platform.system() == "Windows"
IS_MAC = platform.system() == "Darwin"

# Under a pipe (which is what CI is) Python block-buffers stdout, so progress arrives in one lump at
# the end, or not at all if the job is cancelled first.
try:
    sys.stdout.reconfigure(line_buffering=True)
except (AttributeError, ValueError):
    pass


def library_name() -> str:
    """What the loader looks for. Mirrors CeresNativeLibrary.defaultPath on the JVM side."""
    if IS_WINDOWS:
        return "ken_ceres_jvm.dll"
    if IS_MAC:
        return "libken_ceres_jvm.dylib"
    return "libken_ceres_jvm.so"


def available_memory_gb() -> float | None:
    """Best-effort free memory, or None if this platform will not say."""
    try:
        if IS_WINDOWS:
            import ctypes

            class MemoryStatus(ctypes.Structure):
                _fields_ = [
                    ("dwLength", ctypes.c_ulong),
                    ("dwMemoryLoad", ctypes.c_ulong),
                    ("ullTotalPhys", ctypes.c_ulonglong),
                    ("ullAvailPhys", ctypes.c_ulonglong),
                    ("ullTotalPageFile", ctypes.c_ulonglong),
                    ("ullAvailPageFile", ctypes.c_ulonglong),
                    ("ullTotalVirtual", ctypes.c_ulonglong),
                    ("ullAvailVirtual", ctypes.c_ulonglong),
                    ("ullAvailExtendedVirtual", ctypes.c_ulonglong),
                ]

            status = MemoryStatus()
            status.dwLength = ctypes.sizeof(MemoryStatus)
            ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status))
            return status.ullAvailPhys / (1024**3)
        if IS_MAC:
            total = int(subprocess.run(["sysctl", "-n", "hw.memsize"], capture_output=True, text=True).stdout)
            return total / (1024**3)
        # Linux, including every container: MemAvailable is the kernel's own estimate of what can be
        # handed out without swapping, and in a cgroup-limited container it is the limit that matters.
        for line in Path("/proc/meminfo").read_text().splitlines():
            if line.startswith("MemAvailable:"):
                return int(line.split()[1]) / (1024**2)
    except Exception:
        return None
    return None


# Ceres' and Eigen's templates make cc1plus/cl.exe peak around 1.5-2 GB per translation unit, so the
# limit on a build like this is memory, not cores. An unbounded `--parallel` on a 4-core 6 GB machine
# gets its compilers killed by the OOM killer, and the error that surfaces — "fatal error: Killed
# signal terminated program cc1plus" — names neither memory nor the job count. Found on a 6 GB
# container; GitHub's hosted runners are in the same range.
MEMORY_GB_PER_JOB = 2.0


def parallel_jobs(requested: int | None = None) -> int:
    if requested:
        return max(1, requested)
    cores = os.cpu_count() or 2
    memory = available_memory_gb()
    if memory is None:
        return max(1, min(cores, 4))
    return max(1, min(cores, int(memory // MEMORY_GB_PER_JOB)))


def run(command: list[str], what: str) -> None:
    """Run a build step, streaming its output.

    Deliberately not capture_output: a Ceres build takes minutes, and a step that prints nothing
    until it finishes is indistinguishable from a hung one. Worse, when CI cancels the job on
    timeout, captured output dies with the process — the first Windows run of this script produced a
    completely empty log for exactly that reason, so there was nothing to diagnose.
    """
    print(f"  $ {' '.join(str(c) for c in command)}", flush=True)
    result = subprocess.run([str(c) for c in command])
    if result.returncode != 0:
        raise SystemExit(f"error: {what} failed with exit code {result.returncode}")


def fetch(url: str, sha256: str, dest: Path) -> None:
    """Fetch once, verify every time.

    A cached tarball that no longer matches is a corrupted cache, not a reason to carry on.
    """
    if dest.is_file() and hashlib.sha256(dest.read_bytes()).hexdigest() == sha256:
        print(f"  cached  {dest.name}")
        return
    print(f"  fetch   {dest.name}")
    tmp = dest.with_name(f".{dest.name}.{os.getpid()}.tmp")
    try:
        with urllib.request.urlopen(url) as response, tmp.open("wb") as out:
            shutil.copyfileobj(response, out)
        actual = hashlib.sha256(tmp.read_bytes()).hexdigest()
        if actual != sha256:
            raise SystemExit(
                f"error: {dest.name} sha256 mismatch\n  expected {sha256}\n  actual   {actual}"
            )
        tmp.replace(dest)
    finally:
        tmp.unlink(missing_ok=True)


def extract(archive: Path, into: Path, expected_dir: Path) -> None:
    if expected_dir.is_dir():
        return
    print(f"  unpack  {archive.name}")
    with tarfile.open(archive) as tar:
        # filter="data" refuses absolute paths and traversal; it is the default from Python 3.14 and
        # a deprecation warning before that, so ask for it explicitly.
        try:
            tar.extractall(into, filter="data")
        except TypeError:
            tar.extractall(into)


def cmake_configure(source: Path, build: Path, defines: dict[str, str], what: str) -> None:
    command = ["cmake", "-S", source, "-B", build]
    if IS_WINDOWS:
        # Ninja would also work, but a stock windows-latest runner has MSBuild configured and Ninja
        # only sometimes; the default generator is the one that is always there.
        pass
    for key, value in defines.items():
        command.append(f"-D{key}={value}")
    run(command, what)


def cmake_build(build: Path, what: str, jobs: int, target: str | None = None) -> None:
    command = ["cmake", "--build", str(build), "--parallel", str(jobs)]
    if IS_WINDOWS:
        # Multi-config generator: without this the default is Debug, which would link a debug CRT
        # into an otherwise release artifact.
        command += ["--config", "Release"]
    if target:
        command += ["--target", target]
    run(command, what)


def msvc_runtime_defines() -> dict[str, str]:
    """Static CRT for every stage, or the wrapper and Ceres disagree and the link fails."""
    if not IS_WINDOWS:
        return {}
    return {
        "CMAKE_POLICY_DEFAULT_CMP0091": "NEW",
        "CMAKE_MSVC_RUNTIME_LIBRARY": "MultiThreaded",
    }


def find_static_ceres() -> Path:
    for candidate in ("libceres.a", "ceres.lib"):
        for path in PREFIX.rglob(candidate):
            return path
    raise SystemExit(f"error: no static Ceres under {PREFIX}")


def find_built_library(build: Path) -> Path:
    name = library_name()
    for candidate in (build / name, build / "Release" / name):
        if candidate.is_file():
            return candidate
    matches = list(build.rglob(name))
    if matches:
        return matches[0]
    raise SystemExit(f"error: {name} was not produced under {build}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--out",
        type=Path,
        default=OUT_DIR,
        help="where to put the finished library (default build/ken-ceres-redistributable)",
    )
    parser.add_argument(
        "--skip-if-present",
        action="store_true",
        help="do nothing if the output library already exists",
    )
    parser.add_argument(
        "--jobs",
        type=int,
        default=None,
        help="parallel compile jobs (default: bounded by available memory, ~2 GB per job)",
    )
    args = parser.parse_args()

    out_library = args.out / library_name()
    if args.skip_if_present and out_library.is_file():
        print(f"{out_library} already exists, nothing to do")
        return 0

    if shutil.which("cmake") is None:
        raise SystemExit(
            "error: cmake is required and was not found on PATH.\n"
            "  macOS          xcode-select --install && brew install cmake\n"
            "  Debian/Ubuntu  sudo apt install build-essential cmake\n"
            "  Fedora         sudo dnf install gcc-c++ cmake\n"
            "  Windows        winget install Kitware.CMake Microsoft.VisualStudio.2022.BuildTools"
        )

    started = time.monotonic()
    downloads = native_download_cache("ceres")
    for directory in (downloads, PREFIX, args.out):
        directory.mkdir(parents=True, exist_ok=True)

    jobs = parallel_jobs(args.jobs)
    memory = available_memory_gb()
    print(
        f"{platform.system()} {platform.machine()}, {os.cpu_count()} cores, "
        f"{f'{memory:.1f} GB available' if memory else 'unknown memory'} -> {jobs} compile jobs"
    )

    print("sources")
    ceres_tarball = downloads / f"ceres-{CERES_VERSION}.tar.gz"
    eigen_tarball = downloads / f"eigen-{EIGEN_VERSION}.tar.gz"
    seed_from_legacy_cache(LEGACY_DOWNLOADS / ceres_tarball.name, ceres_tarball, CERES_SHA256)
    seed_from_legacy_cache(LEGACY_DOWNLOADS / eigen_tarball.name, eigen_tarball, EIGEN_SHA256)
    fetch(CERES_URL, CERES_SHA256, ceres_tarball)
    fetch(EIGEN_URL, EIGEN_SHA256, eigen_tarball)
    ceres_source = WORK / f"ceres-solver-{CERES_VERSION}"
    eigen_source = WORK / f"eigen-{EIGEN_VERSION}"
    extract(ceres_tarball, WORK, ceres_source)
    extract(eigen_tarball, WORK, eigen_source)

    print(f"\neigen {EIGEN_VERSION} (headers only)")
    cmake_configure(
        eigen_source,
        WORK / "eigen-build",
        {"CMAKE_INSTALL_PREFIX": str(PREFIX), "BUILD_TESTING": "OFF"},
        "configuring Eigen",
    )
    run(["cmake", "--install", str(WORK / "eigen-build")], "installing Eigen")

    print(f"\nceres {CERES_VERSION} (static, miniglog, eigen sparse)")
    ceres_defines = {
        "CMAKE_BUILD_TYPE": "Release",
        "CMAKE_INSTALL_PREFIX": str(PREFIX),
        "Eigen3_DIR": str(PREFIX / "share" / "eigen3" / "cmake"),
        "CMAKE_POSITION_INDEPENDENT_CODE": "ON",
        "BUILD_SHARED_LIBS": "OFF",
        "MINIGLOG": "ON",
        "MINIGLOG_MAX_LOG_LEVEL": str(LOG_LEVEL),
        "GFLAGS": "OFF",
        "EIGENSPARSE": "ON",
        "EIGENMETIS": "OFF",
        "SUITESPARSE": "OFF",
        "ACCELERATESPARSE": "OFF",
        "LAPACK": "OFF",
        "USE_CUDA": "OFF",
        "BUILD_TESTING": "OFF",
        "BUILD_EXAMPLES": "OFF",
        "BUILD_BENCHMARKS": "OFF",
        "BUILD_DOCUMENTATION": "OFF",
        "PROVIDE_UNINSTALL_TARGET": "OFF",
    }
    ceres_defines.update(msvc_runtime_defines())
    if IS_WINDOWS:
        # /MP{n} is what actually parallelises this build. `cmake --build --parallel` maps to
        # MSBuild's /m, which parallelises across *projects*, and Ceres is a single project of ~100
        # translation units — so without /MP the whole thing compiles one file at a time and blows
        # past any sane CI timeout. Bounded by the same memory budget as the job count.
        ceres_defines["CMAKE_CXX_FLAGS"] = f"/bigobj /MP{jobs}"
    cmake_configure(ceres_source, WORK / "ceres-build", ceres_defines, "configuring Ceres")
    cmake_build(WORK / "ceres-build", "building Ceres", jobs)
    install = ["cmake", "--install", str(WORK / "ceres-build")]
    if IS_WINDOWS:
        install += ["--config", "Release"]
    run(install, "installing Ceres")

    print("\nwrapper (static)")
    static_ceres = find_static_ceres()
    # Miniglog installs its glog/logging.h under ceres/internal/miniglog, and ceres headers include
    # it as "glog/logging.h", so that directory has to be on the include path too.
    include_dirs = ";".join(
        str(p)
        for p in (
            PREFIX / "include",
            PREFIX / "include" / "eigen3",
            PREFIX / "include" / "ceres" / "internal" / "miniglog",
        )
    )
    wrapper_defines = {
        "CMAKE_BUILD_TYPE": "Release",
        "CMAKE_CXX_FLAGS": (f"/bigobj /MP{jobs} " if IS_WINDOWS else "") + f"-DMAX_LOG_LEVEL={LOG_LEVEL}",
        "KEN_CERES_SYSTEM_CERES": "OFF",
        "KEN_CERES_STATIC_LIB": str(static_ceres),
        "KEN_CERES_INCLUDE_DIRS": include_dirs,
    }
    wrapper_defines.update(msvc_runtime_defines())
    cmake_configure(WRAPPER_SOURCE, WORK / "wrapper", wrapper_defines, "configuring the wrapper")
    cmake_build(WORK / "wrapper", "building the wrapper", jobs)

    built = find_built_library(WORK / "wrapper")
    shutil.copy2(built, out_library)

    # The whole point of this script is the dependency list, so assert it rather than trusting the
    # flags that were supposed to produce it.
    print()
    leaked = check_selfcontained(out_library)
    if leaked:
        print(f"\nerror: {out_library.name} is not redistributable; it still links", file=sys.stderr)
        for name in leaked:
            print(f"  {name}", file=sys.stderr)
        return 1

    size_mb = out_library.stat().st_size / (1024 * 1024)
    elapsed = time.monotonic() - started
    print()
    print(f"ken_ceres_jvm -> {out_library}")
    print(f"  size          {size_mb:.1f} MB")
    print(f"  built in      {elapsed:.0f}s")
    print()
    print("verify it:")
    print(f"  java --enable-native-access=ALL-UNNAMED tools/ceres_abi/CeresAbiConformance.java {out_library}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
