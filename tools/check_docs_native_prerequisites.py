#!/usr/bin/env python3
"""Verify the complete native dependency set used by documentation captures."""

from __future__ import annotations

import sys
from pathlib import Path

from build_zensical_docs import (
    NATIVE_PREREQUISITES,
    default_python_venv_to_this_tree,
)


def main() -> int:
    repo_root = Path(__file__).resolve().parent.parent
    default_python_venv_to_this_tree(repo_root)
    missing: list[str] = []
    for name, (resolve, remedy) in sorted(NATIVE_PREREQUISITES.items()):
        artifact = resolve(repo_root)
        if artifact.exists():
            print(f"{name}: {artifact}")
        else:
            missing.append(f"  {name}: no artifact at {artifact}\n    remedy: {remedy}")
    if missing:
        raise SystemExit("Missing documentation native prerequisites:\n" + "\n".join(missing))
    print("All documentation native prerequisites are present.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
