#!/usr/bin/env python3
"""Shared, clean-safe download cache for pinned native dependencies."""

from __future__ import annotations

import hashlib
import os
import shutil
import sys
from pathlib import Path


def native_download_cache(component: str) -> Path:
    """Return a per-user cache shared by clones and worktrees.

    ``AFIELD_NATIVE_DOWNLOAD_CACHE`` is primarily for CI and tests. The default follows each
    platform's cache convention, so Gradle ``clean`` removes build products without throwing away
    checksummed source archives that are expensive or occasionally slow to fetch.
    """
    if override := os.environ.get("AFIELD_NATIVE_DOWNLOAD_CACHE", "").strip():
        root = Path(override).expanduser()
    elif os.name == "nt":
        root = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData" / "Local")) / "Field4" / "Cache"
    elif sys.platform == "darwin":
        root = Path.home() / "Library" / "Caches" / "Field4"
    else:
        root = Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache")) / "field4"
    return root / "native-downloads" / component


def seed_from_legacy_cache(legacy: Path, destination: Path, sha256: str) -> bool:
    """Copy one valid archive out of an old worktree-local cache without exposing a partial file."""
    if destination.exists() or not legacy.is_file():
        return False
    if hashlib.sha256(legacy.read_bytes()).hexdigest() != sha256:
        return False
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(f".{destination.name}.{os.getpid()}.tmp")
    shutil.copy2(legacy, temporary)
    temporary.replace(destination)
    print(f"  seeded  {destination.name} from the legacy build cache", flush=True)
    return True
