#!/usr/bin/env python3
"""Copy the pinned Field Audio contract into Field's committed public docs.

This tool is deliberately explicit. Normal Field docs builds consume the committed
snapshot and never initialize the submodule, install Python extras, or run a model.
"""

from __future__ import annotations

import argparse
from pathlib import Path


def synchronize(source: Path, target: Path, *, check: bool = False) -> bool:
    if not source.is_file():
        raise RuntimeError(
            f"Field Audio reference is unavailable at {source}. "
            "Initialize services/field-audio-ai before requesting a refresh."
        )
    expected = source.read_bytes()
    actual = target.read_bytes() if target.is_file() else None
    if actual == expected:
        return False
    if check:
        raise RuntimeError(
            f"Field Audio documentation snapshot is stale: run "
            f"python3 tools/sync_field_audio_docs.py"
        )
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(expected)
    return True


def main(argv: list[str] | None = None) -> int:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="fail instead of updating")
    parser.add_argument(
        "--source",
        type=Path,
        default=root / "services/field-audio-ai/docs/reference.md",
    )
    parser.add_argument(
        "--target",
        type=Path,
        default=root / "public/docs/field_audio_service_reference.md",
    )
    args = parser.parse_args(argv)
    try:
        changed = synchronize(args.source, args.target, check=args.check)
    except RuntimeError as error:
        parser.error(str(error))
    print("updated Field Audio documentation snapshot" if changed else "Field Audio documentation snapshot is current")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
