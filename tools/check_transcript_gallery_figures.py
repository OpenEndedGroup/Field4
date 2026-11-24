#!/usr/bin/env python3
"""Fail when the agent transcript gallery runs more than three entries without a figure.

    uv run python tools/check_transcript_gallery_figures.py
    uv run python tools/check_transcript_gallery_figures.py --max-run 3 --json

Why: `public/docs/agent_transcripts.md` is the page people browse to see what Field can do, and on
2026-11-23 it had runs of up to nineteen entries in a row with nothing to look at (254 of 393
entries had no figure). Marc's rule: never more than three entries in a row without one.

An entry is a `### ` heading; its figure is any image or video in its body (`![..](..)`, `<img`,
`<video`, or a runner fence's `screenshot=".."` / `video=".."`, which the docs build renders). A
`## ` section heading ends a run, since a new section is a new scroll context. A figure must be a real capture of that scene's code or of the guide section the entry links to,
never a stand-in (AGENTS.md section 3, "A capture exercises the real code").

`tools/test_check_transcript_gallery_figures.py` holds the positive control (a constructed page
with four bare entries fails) and the real-tree check.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
INDEX = REPO / "public/docs/agent_transcripts.md"
_HEADING = re.compile(r"^(#{2,3}) (.*)$", re.MULTILINE)
_FIGURE = re.compile(r'!\[[^\]]*\]\([^)]+\)|<img\b|<video\b|\b(?:screenshot|video)="[^"]+\.(?:png|jpg|webp|svg|mp4)"')


def bare_runs(text: str, max_run: int = 3) -> list[list[str]]:
    """Every run of more than `max_run` consecutive figureless `###` entries."""
    parts = _HEADING.split(text)
    runs: list[list[str]] = []
    run: list[str] = []

    def close() -> None:
        if len(run) > max_run:
            runs.append(list(run))
        run.clear()

    for i in range(1, len(parts), 3):
        level, title, body = parts[i], parts[i + 1].strip(), parts[i + 2]
        if level == "##" or _FIGURE.search(body):
            close()
        else:
            run.append(title)
    close()
    return runs


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--page", type=Path, default=INDEX)
    ap.add_argument("--max-run", type=int, default=3)
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)
    runs = bare_runs(args.page.read_text(encoding="utf-8"), args.max_run)
    if args.json:
        print(json.dumps({"ok": not runs, "runs": runs}, indent=2))
    elif runs:
        print(f"{args.page.name}: {len(runs)} run(s) of more than {args.max_run} entries without a figure.")
        print("Add a real capture (from the scene, or the guide section it links to) to one entry in each:")
        for r in runs:
            print(f"  {len(r)}: " + ", ".join(r))
    else:
        print(f"{args.page.name}: no run of more than {args.max_run} entries without a figure.")
    return 1 if runs else 0


if __name__ == "__main__":
    sys.exit(main())
