#!/usr/bin/env python3
"""Opt-in Loris build, with a pinned checksummed source and bundled FFT.

Needs CMake 3.24+, a C++17 compiler and network on first use. No Python, FFTW,
audio library or neural weights are required by the built executable.
"""
from __future__ import annotations
import argparse
import hashlib
import os
import shutil
import subprocess
import tarfile
import urllib.request
from pathlib import Path
from native_download_cache import native_download_cache

REVISION = "eccd42ac847b08769643b0d0c36442ef0fd27f38"
SHA256 = "7942ae03e087338104959176d5bcf6483e62c35f3e68dca3d537c1c99df82a47"
ROOT = Path(__file__).resolve().parent.parent

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jobs", type=int, default=min(8, os.cpu_count() or 2))
    args = parser.parse_args()
    archive = native_download_cache("loris") / f"{REVISION}.tar.gz"
    archive.parent.mkdir(parents=True, exist_ok=True)
    if not archive.exists():
        temporary = archive.with_suffix(f".{os.getpid()}.tmp")
        try:
            with urllib.request.urlopen(f"https://codeload.github.com/kellyfitz/loris/tar.gz/{REVISION}", timeout=120) as src, temporary.open("wb") as dst:
                shutil.copyfileobj(src, dst)
            if hashlib.sha256(temporary.read_bytes()).hexdigest() != SHA256:
                raise RuntimeError("Loris download checksum mismatch")
            temporary.replace(archive)
        finally:
            temporary.unlink(missing_ok=True)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != SHA256:
        raise RuntimeError("Loris cached archive checksum mismatch")
    work = ROOT / "build/field-loris"
    source = work / "source"
    source.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive) as tar:
        tar.extractall(source, filter="data")
    upstream = source / f"loris-{REVISION}"
    # Upstream's Unix flags/libm are unconditional on Windows. Restrict them in
    # the extracted build tree only; no vendored source is edited or committed.
    cmake = upstream / "src/CMakeLists.txt"
    text = cmake.read_text().replace('target_compile_options(loris PRIVATE -Wno-comment)',
        'if(NOT MSVC)\n target_compile_options(loris PRIVATE -Wno-comment)\nendif()')
    cmake.write_text(text.replace('if(NOT APPLE)', 'if(UNIX AND NOT APPLE)'))
    build = work / "build"
    subprocess.run(["cmake", "-S", str(ROOT / "native/field-loris"), "-B", str(build),
                    f"-DFIELD_LORIS_SOURCE={upstream}", "-DCMAKE_BUILD_TYPE=Release"], check=True)
    subprocess.run(["cmake", "--build", str(build), "--config", "Release", "--parallel", str(args.jobs)], check=True)
    print(f"Loris {REVISION} built under {build}")

if __name__ == "__main__":
    main()
