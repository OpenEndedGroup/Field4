#!/usr/bin/env python3
"""Prepare the optional sentence parser and prove a real CPU parse; starts no server."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT / "optional/sentence-parser"
ENTRY = ROOT / "python/fieldsentences"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify-only", action="store_true", help="Use existing packages and model files")
    parser.add_argument("--python", type=Path, help="Use an existing parser environment instead of creating one")
    args = parser.parse_args(argv)
    if args.python:
        interpreter = args.python.absolute()  # Preserve a venv executable symlink and its pyvenv.cfg.
    else:
        if not args.verify_only:
            subprocess.run(["uv", "sync", "--project", str(PROJECT), "--frozen", "--python", "3.12"], check=True)
        interpreter = PROJECT / ".venv" / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
    if not interpreter.is_file():
        parser.error(f"No interpreter at {interpreter}; run preparation without --verify-only")
    command = [str(interpreter), str(ENTRY)]
    if not args.verify_only:
        subprocess.run(command + ["--download"], cwd=ROOT, check=True)
    result = subprocess.run(command + ["--device", "cpu", "The quiet water carries the light"],
                            cwd=ROOT, capture_output=True, text=True, check=True)
    parsed = json.loads(result.stdout)
    if len(parsed) != 1 or not parsed[0].get("tokens") or not parsed[0].get("constituency"):
        raise RuntimeError("Parser did not produce a sentence with tokens and a constituency tree")
    if not any(edge["gov"] == 0 for edge in parsed[0]["dependencies"]["sd_collapsed"]):
        raise RuntimeError("Parser did not produce a rooted dependency graph")
    print(f"Verified {parsed[0]['backend']} CPU parse: {len(parsed[0]['tokens'])} tokens, schema {parsed[0]['schema']}")
    print(f"Start when needed: {interpreter} {ENTRY} --serve 9500")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except subprocess.CalledProcessError as error:
        if error.stderr:
            print(error.stderr, file=sys.stderr)
        raise SystemExit(error.returncode) from error
