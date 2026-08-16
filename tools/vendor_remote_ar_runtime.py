#!/usr/bin/env python3
"""Refresh the small, pinned browser-runtime snapshot used by Field Remote."""

from __future__ import annotations

import hashlib
import shutil
import tarfile
import tempfile
import urllib.request
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VENDOR = ROOT / "src/commonMain/resources/extensions/remote_ar/vendor"


@dataclass(frozen=True)
class Package:
    name: str
    version: str
    archive_sha256: str
    files: tuple[str, ...]

    @property
    def url(self) -> str:
        return f"https://registry.npmjs.org/{self.name}/-/{self.name}-{self.version}.tgz"


PACKAGES = (
    Package(
        "aframe",
        "1.8.0",
        "d740e4ac22d4143e20a3866b5b957238a07823c659063dba829dca1771efbddc",
        ("LICENSE", "README.md", "package.json", "dist/aframe-v1.8.0.js", "dist/aframe-v1.8.0.min.js"),
    ),
    # No `super-three` here. It was vendored as `build/three.module.js` alone, but since r16x that
    # file is only half of the module build — it imports `./three.core.js`, which this spec never
    # listed, so the copy on disk could not be loaded by anything and never was. A-Frame's browser
    # bundle already embeds that fork, and the docs viewer has its own stock three.js under
    # `src/commonMain/resources/vendor/three/`. Re-adding it means listing three.core.js too.
    Package(
        "tone",
        "15.1.22",
        "86dec0a9521dd69a59e7b824a83b132afd1ef8284bda1e7799449ab9d84d9f62",
        ("LICENSE.md", "README.md", "package.json", "build/Tone.js", "build/Tone.js.LICENSE.txt"),
    ),
)


def refresh(package: Package, temporary: Path) -> None:
    archive = temporary / f"{package.name}-{package.version}.tgz"
    urllib.request.urlretrieve(package.url, archive)
    actual = hashlib.sha256(archive.read_bytes()).hexdigest()
    if actual != package.archive_sha256:
        raise RuntimeError(f"Checksum mismatch for {package.name}@{package.version}: {actual}")

    destination = VENDOR / package.name / package.version
    if destination.exists():
        shutil.rmtree(destination)
    destination.mkdir(parents=True)
    with tarfile.open(archive, "r:gz") as source:
        for relative in package.files:
            member = source.getmember(f"package/{relative}")
            contents = source.extractfile(member)
            if contents is None:
                raise RuntimeError(f"Missing regular file {relative} in {package.name}")
            target_relative = relative.removeprefix("dist/").removeprefix("build/")
            target = destination / target_relative
            target.parent.mkdir(parents=True, exist_ok=True)
            with target.open("wb") as output:
                shutil.copyfileobj(contents, output)
    print(f"vendored {package.name}@{package.version}")


def main() -> None:
    with tempfile.TemporaryDirectory(prefix="field-remote-vendor-") as directory:
        temporary = Path(directory)
        for package in PACKAGES:
            refresh(package, temporary)


if __name__ == "__main__":
    main()
