#!/usr/bin/env python3
"""Fetch Field's pinned gltfpack (meshoptimizer's glTF optimiser) and record it for Field.

Normally invoked as ./gradlew prepareGltfpack, or from first-run setup's "gltfpack" extra. The
release archive is checksummed against optional/gltfpack/pin.json and kept in the shared native
download cache; build/gltfpack.path then names the executable. Field compresses an exported GLB with
it when it is there (GeometryScene.writeGlb, the three viewer's Export GLB) and writes the GLB
uncompressed when it is not. Normal Field builds never invoke this.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from native_download_cache import native_download_cache  # noqa: E402

PIN = ROOT / "optional/gltfpack/pin.json"
POINTER = ROOT / "build/gltfpack.path"


def digest(path: Path) -> str:
    with path.open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


def host_key(system: str | None = None, machine: str | None = None) -> str:
    """macOS ships one universal binary; Linux and Windows builds are x86-64."""
    system = system or platform.system()
    machine = (machine or platform.machine()).lower()
    if system == "Darwin":
        return "macos"
    os_name = {"Linux": "linux", "Windows": "windows"}.get(system, system.lower())
    arch = {"amd64": "x86_64", "arm64": "aarch64"}.get(machine, machine)
    return f"{os_name}-{arch}"


def fetch(url: str, sha256: str, destination: Path, offline: bool) -> None:
    if destination.is_file() and digest(destination) == sha256:
        print(f"[gltfpack] cached {destination.name}", flush=True)
        return
    if offline:
        raise RuntimeError(f"No valid cached {destination.name}; run ./gradlew prepareGltfpack once without --offline")
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(f".{destination.name}.{os.getpid()}.tmp")
    print(f"[gltfpack] downloading {url}", flush=True)
    try:
        with urllib.request.urlopen(url, timeout=180) as response, temporary.open("wb") as output:
            shutil.copyfileobj(response, output)
        if digest(temporary) != sha256:
            raise RuntimeError(f"Checksum mismatch downloading {url}")
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)


def extract_executable(archive: Path, name: str, destination: Path) -> None:
    """Take the one executable out of a verified archive; refuse anything that is not a plain file."""
    with zipfile.ZipFile(archive) as package:
        members = [m for m in package.infolist() if Path(m.filename).name == name and not m.is_dir()]
        if len(members) != 1:
            raise RuntimeError(f"{archive.name} should hold exactly one {name}, found {len(members)}")
        with package.open(members[0]) as source, destination.open("wb") as output:
            shutil.copyfileobj(source, output)
    destination.chmod(0o755)


def prepare(offline: bool) -> Path:
    pin = json.loads(PIN.read_text())
    key = host_key()
    if key not in pin["archives"]:
        raise RuntimeError(f"No pinned gltfpack for {key}; GLBs are written uncompressed. "
                           f"Put a gltfpack on PATH or set AFIELD_GLTFPACK to use your own.")
    info = pin["archives"][key]
    cache = native_download_cache(f"gltfpack/{pin['version']}")
    archive = cache / f"{key}-{Path(info['url']).name}"
    fetch(info["url"], info["sha256"], archive, offline)
    name = "gltfpack.exe" if key.startswith("windows") else "gltfpack"
    executable = cache / key / name
    marker = cache / key / ".archive-sha256"
    if not executable.is_file() or not marker.is_file() or marker.read_text().strip() != info["sha256"]:
        with tempfile.TemporaryDirectory(prefix=".extract-", dir=cache) as temporary:
            staged = Path(temporary) / name
            extract_executable(archive, name, staged)
            (cache / key).mkdir(parents=True, exist_ok=True)
            staged.replace(executable)
            marker.write_text(info["sha256"] + "\n")
    version = subprocess.run([str(executable), "-v"], capture_output=True, text=True, timeout=30)
    banner = (version.stdout + version.stderr).strip().splitlines()
    print(f"[gltfpack] {banner[0] if banner else executable}", flush=True)
    POINTER.parent.mkdir(parents=True, exist_ok=True)
    temporary = POINTER.with_name(f".gltfpack.path.{os.getpid()}.tmp")
    temporary.write_text(str(executable) + "\n")
    temporary.replace(POINTER)
    print(f"[gltfpack] recorded {executable} in {POINTER.relative_to(ROOT)}", flush=True)
    return executable


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--offline", action="store_true", help="use only the download cache")
    args = parser.parse_args()
    try:
        prepare(args.offline)
    except Exception as error:  # noqa: BLE001 — one readable line for setup and Gradle
        print(f"[gltfpack] {error}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
