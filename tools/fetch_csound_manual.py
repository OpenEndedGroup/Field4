#!/usr/bin/env python3
"""Fetch the Csound Reference Manual's DocBook source, pinned to the release Field builds.

The opcode descriptions Field's Csound completion shows are generated from this source by
`./gradlew generateCsoundVocabulary` (afield.audio.csound.CsoundVocabulary), never typed by hand. The
manual is the csound/manual repository at tag 6.18.0, the manual for Csound 6.18 (the repository
has no 6.18.1 tag). It is licensed under the GNU Free Documentation License 1.2 or later (its
`copying.txt`), which Field records next to the vocabulary it ships.

The tarball is checksummed and cached in the per-user native download cache, like libcsound's
source. Prints the path of the extracted manual, the directory that holds `opcodes/`.

    python3 tools/fetch_csound_manual.py
"""

from __future__ import annotations

import hashlib
import os
import shutil
import sys
import tarfile
import urllib.request
from pathlib import Path

from native_download_cache import native_download_cache

MANUAL_TAG = "6.18.0"
MANUAL_URL = f"https://github.com/csound/manual/archive/refs/tags/{MANUAL_TAG}.tar.gz"
MANUAL_SHA256 = "260be373a943b6b81b255317d3033275dfbee249aa7bcc83e009896c03283eb5"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def fetch() -> Path:
    cache = native_download_cache("csound-manual")
    archive = cache / f"manual-{MANUAL_TAG}.tar.gz"
    source = cache / f"manual-{MANUAL_TAG}"
    if not archive.exists() or sha256(archive) != MANUAL_SHA256:
        cache.mkdir(parents=True, exist_ok=True)
        temporary = archive.with_name(f".{archive.name}.{os.getpid()}.tmp")
        print(f"[csound-manual] download {MANUAL_URL}", file=sys.stderr, flush=True)
        with urllib.request.urlopen(MANUAL_URL) as response, temporary.open("wb") as out:
            shutil.copyfileobj(response, out)
        got = sha256(temporary)
        if got != MANUAL_SHA256:
            temporary.unlink()
            raise SystemExit(f"[csound-manual] checksum mismatch for {MANUAL_URL}: {got}")
        temporary.replace(archive)
        if source.exists():
            shutil.rmtree(source)
    if not (source / "opcodes").is_dir():
        staging = cache / f".extract.{os.getpid()}"
        if staging.exists():
            shutil.rmtree(staging)
        with tarfile.open(archive) as tar:
            tar.extractall(staging)
        (staging / f"manual-{MANUAL_TAG}").replace(source)
        shutil.rmtree(staging)
    return source


if __name__ == "__main__":
    print(fetch())
