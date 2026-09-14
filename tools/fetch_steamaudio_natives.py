#!/usr/bin/env python3
"""Unpack the pinned Steam Audio libraries for this host into build/steamaudio.

Steam Audio ships as prebuilt binaries only, and they are large: 113 MB across the three
platforms, 173 MB for the release zip that carries them. Nothing is committed, so the first
run needs the network; after that the zip lives in the shared native download cache and
survives `clean`, which only costs a re-extract.

`native/steamaudio/checksums.txt` is the pin. Both the zip and every file taken out of it are
checked against it, and a file is unpacked beside its target and verified before being moved
into place -- a bad digest never leaves a half-trusted shared library where the JVM's loader
could find it.

Only the host platform is extracted by default; `--all` takes every platform, which is what a
redistributable build would want.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import platform
import shutil
import sys
import urllib.request
import zipfile
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from native_download_cache import native_download_cache  # noqa: E402

TAG = "[steamaudio]"

# Keyed by the directory name used in checksums.txt, which SteamAudioNatives also uses to
# find the library at runtime.
HOST_PLATFORMS = {
    "darwin": "macos",
    "linux": "linux-x64",
    "win32": "windows-x64",
}

# The manifest also pins the headers, which are committed under native/steamaudio/include and
# so are never unpacked here.
PLATFORM_DIRECTORIES = frozenset(HOST_PLATFORMS.values())


@dataclass(frozen=True)
class Artifact:
    sha256: str
    zip_entry: str
    destination: str

    @property
    def platform_directory(self) -> str:
        return self.destination.split("/")[0]


@dataclass(frozen=True)
class Manifest:
    version: str
    url: str
    zip_sha256: str
    zip_name: str
    artifacts: list[Artifact]


def parse_manifest(path: Path) -> Manifest:
    version = url = zip_sha256 = zip_name = None
    artifacts: list[Artifact] = []
    for raw in path.read_text().splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        fields = line.split()
        match fields[0]:
            case "version":
                version = fields[1]
            case "url":
                url = fields[1]
            case "zip":
                zip_sha256, zip_name = fields[1], fields[2]
            case "file":
                artifacts.append(Artifact(fields[1], fields[2], fields[3]))
            case other:
                raise SystemExit(f"{TAG} {path.name}: unrecognised directive {other!r}")
    if not (version and url and zip_sha256 and zip_name):
        raise SystemExit(f"{TAG} {path.name} is missing a version/url/zip line")
    return Manifest(version, url, zip_sha256, zip_name, artifacts)


def digest(path: Path) -> str:
    sha = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            sha.update(block)
    return sha.hexdigest()


def host_platform_directory() -> str:
    key = sys.platform if sys.platform in HOST_PLATFORMS else sys.platform.rstrip("0123456789")
    directory = HOST_PLATFORMS.get(key)
    if directory is None:
        raise SystemExit(f"{TAG} no Steam Audio binary for platform {sys.platform!r}")
    if directory == "linux-x64" and platform.machine() not in {"x86_64", "amd64", "AMD64"}:
        raise SystemExit(
            f"{TAG} Steam Audio ships no Linux {platform.machine()} binary upstream; only "
            "linux-x64 exists. Build libphonon from source and pass -PsteamaudioLibrary=<path>."
        )
    return directory


def fetch_zip(manifest: Manifest, cache: Path) -> Path:
    destination = cache / manifest.zip_name
    if destination.exists():
        if digest(destination) == manifest.zip_sha256:
            print(f"{TAG} cached {destination.name}", flush=True)
            return destination
        print(f"{TAG} {destination.name} has the wrong digest, re-downloading", flush=True)
        destination.unlink()

    destination.parent.mkdir(parents=True, exist_ok=True)
    print(f"{TAG} downloading {manifest.url}", flush=True)
    temporary = destination.with_name(f".{destination.name}.{os.getpid()}.tmp")
    try:
        with urllib.request.urlopen(manifest.url, timeout=600) as response, temporary.open("wb") as out:
            shutil.copyfileobj(response, out)
        actual = digest(temporary)
        if actual != manifest.zip_sha256:
            raise SystemExit(
                f"{TAG} checksum mismatch for {manifest.url}\n"
                f"  expected {manifest.zip_sha256}\n  actual   {actual}\n"
                "Refusing to unpack."
            )
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)
    return destination


def extract(archive: Path, artifacts: list[Artifact], into: Path) -> int:
    unpacked = 0
    with zipfile.ZipFile(archive) as zf:
        for artifact in artifacts:
            target = into / artifact.destination
            if target.is_file() and digest(target) == artifact.sha256:
                continue
            try:
                entry = zf.getinfo(artifact.zip_entry)
            except KeyError:
                raise SystemExit(f"{TAG} {archive.name} has no entry {artifact.zip_entry!r}") from None

            target.parent.mkdir(parents=True, exist_ok=True)
            staged = target.with_name(f".{target.name}.{os.getpid()}.tmp")
            try:
                with zf.open(entry) as source, staged.open("wb") as out:
                    shutil.copyfileobj(source, out)
                actual = digest(staged)
                if actual != artifact.sha256:
                    raise SystemExit(
                        f"{TAG} checksum mismatch for {artifact.zip_entry}\n"
                        f"  expected {artifact.sha256}\n  actual   {actual}"
                    )
                staged.replace(target)
            finally:
                staged.unlink(missing_ok=True)
            print(f"{TAG} {artifact.destination}  ok", flush=True)
            unpacked += 1
    return unpacked


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True, help="path to checksums.txt")
    parser.add_argument("--out", type=Path, required=True, help="directory to unpack into")
    parser.add_argument(
        "--all",
        action="store_true",
        help="extract every platform rather than just this host's.",
    )
    arguments = parser.parse_args()

    manifest = parse_manifest(arguments.manifest)
    if arguments.all:
        wanted = [a for a in manifest.artifacts if a.platform_directory in PLATFORM_DIRECTORIES]
    else:
        directory = host_platform_directory()
        wanted = [a for a in manifest.artifacts if a.platform_directory == directory]
        if not wanted:
            raise SystemExit(f"{TAG} {arguments.manifest.name} pins nothing for {directory}")

    outstanding = [
        a for a in wanted
        if not (arguments.out / a.destination).is_file()
        or digest(arguments.out / a.destination) != a.sha256
    ]
    if not outstanding:
        print(
            f"{TAG} Steam Audio {manifest.version} verified ({len(wanted)} files) in {arguments.out}",
            flush=True,
        )
        return

    archive = fetch_zip(manifest, native_download_cache("steamaudio"))
    unpacked = extract(archive, outstanding, arguments.out)
    print(f"{TAG} Steam Audio {manifest.version} ready ({unpacked} unpacked)", flush=True)


if __name__ == "__main__":
    main()
