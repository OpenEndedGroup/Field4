#!/usr/bin/env python3
"""Fetch the pinned subset of tidalcycles/Dirt-Samples the documentation renders through.

The docs' drum and sample clips (`sound_paths.md`'s one-buffer comparison, the Boolean network
music examples, `sound.md`'s `beat-dirt`) are rendered offline from real Tidal one-shots by
`patRenderStereoDirt`. The full library is ~400 MB of third-party audio, so nothing is vendored:
this downloads only the folders those clips use, file by file, at one pinned commit, and checks
every file against the SHA-256 in the manifest.

The manifest is ``src/jvmMain/resources/afield/four/synth/dirt-samples-pinned.json``. The JVM reads
the same file off its classpath (``DirtRender.pinnedSampleRoot()``), so the folder list, the
commit and the checksums live in exactly one place.

The result goes into the per-user native download cache (``tools/native_download_cache.py``),
shared by clones and worktrees and preserved by Gradle ``clean``:

    <cache>/dirt-samples/<commit>/<folder>/<file>.wav

A file already present with the right digest is kept. A local SuperCollider ``Dirt-Samples`` quark
is used as a seed when its copy of a file has the pinned digest, so a machine that has Tidal needs
no network at all. ``--check`` verifies without downloading and exits non-zero if anything is
missing or wrong.

    python3 tools/fetch_dirt_samples.py
    python3 tools/fetch_dirt_samples.py --check
    ./gradlew fetchDocsDirtSamples
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import sys
import urllib.parse
import urllib.request
from pathlib import Path

from native_download_cache import native_download_cache

REPO_ROOT = Path(__file__).resolve().parent.parent
MANIFEST = REPO_ROOT / "src" / "jvmMain" / "resources" / "afield" / "four" / "synth" / "dirt-samples-pinned.json"


def load_manifest() -> dict:
    return json.loads(MANIFEST.read_text(encoding="utf-8"))


def pinned_root(manifest: dict | None = None) -> Path:
    """Where the pinned subset lives. `DirtRender.pinnedSampleRoot()` computes the same path."""
    manifest = manifest or load_manifest()
    return native_download_cache("dirt-samples") / manifest["commit"]


def local_quark_roots() -> list[Path]:
    """The places a Tidal install keeps the full library; the same list `DirtRender` probes."""
    home = Path.home()
    roots = []
    if override := os.environ.get("FIELD_DIRT_SAMPLES", "").strip():
        roots.append(Path(override).expanduser())
    roots += [
        home / "Library" / "Application Support" / "SuperCollider" / "downloaded-quarks" / "Dirt-Samples",
        home / ".local" / "share" / "SuperCollider" / "downloaded-quarks" / "Dirt-Samples",
        home / "Dirt-Samples",
    ]
    return [r for r in roots if r.is_dir()]


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def place(source_bytes: bytes, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    temporary = dest.with_name(f".{dest.name}.{os.getpid()}.tmp")
    try:
        temporary.write_bytes(source_bytes)
        temporary.replace(dest)
    finally:
        temporary.unlink(missing_ok=True)


def problems_in(root: Path, manifest: dict) -> list[str]:
    problems = []
    for entry in manifest["files"]:
        path = root / entry["path"]
        if not path.is_file():
            problems.append(f"missing {entry['path']}")
        elif digest(path) != entry["sha256"]:
            problems.append(f"wrong digest {entry['path']}")
    return problems


def fetch(manifest: dict, root: Path) -> None:
    seeds = local_quark_roots()
    downloaded = seeded = kept = 0
    for entry in manifest["files"]:
        rel, want = entry["path"], entry["sha256"]
        dest = root / rel
        if dest.is_file() and digest(dest) == want:
            kept += 1
            continue
        for seed in seeds:
            candidate = seed / rel
            if candidate.is_file():
                data = candidate.read_bytes()
                if hashlib.sha256(data).hexdigest() == want:
                    place(data, dest)
                    seeded += 1
                    break
        else:
            url = manifest["urlTemplate"].format(commit=manifest["commit"], path=urllib.parse.quote(rel))
            with urllib.request.urlopen(url, timeout=120) as response:
                data = response.read()
            got = hashlib.sha256(data).hexdigest()
            if got != want:
                raise SystemExit(f"[dirt-samples] checksum mismatch for {url}: got {got}, pinned {want}")
            place(data, dest)
            downloaded += 1
    print(
        f"[dirt-samples] {len(manifest['files'])} file(s) at {manifest['commit'][:12]}: "
        f"{kept} cached, {seeded} seeded from a local quark, {downloaded} downloaded",
        flush=True,
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="verify only; never download")
    args = parser.parse_args()

    manifest = load_manifest()
    root = pinned_root(manifest)
    if not args.check:
        fetch(manifest, root)
    problems = problems_in(root, manifest)
    if problems:
        print(f"[dirt-samples] {root} is incomplete ({len(problems)} problem(s)):", file=sys.stderr)
        for line in problems[:20]:
            print(f"  {line}", file=sys.stderr)
        print("  remedy: ./gradlew fetchDocsDirtSamples", file=sys.stderr)
        return 1
    # Last line is the artifact path, which is what the Gradle task logs.
    print(f"[dirt-samples] verified {', '.join(manifest['folders'])} at {root}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
