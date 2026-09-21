#!/usr/bin/env python3
"""Measure the surface `in_fences.py` claims to cover and does not: NON-HEADING prose
inside an embedded-markdown raw string.

`in_fences.py`'s own docstring says embedded markdown is "Scored as HEADINGS and prose".
`scan()` scores a `#` line via MD_HEADING and a `//` line via COMMENT. A plain prose line
inside a Kotlin `markdown("chan", \"\"\" ... \"\"\")` raw string matches neither, so it is
dropped. Those lines are rendered into box output; a reader reads them.

This script finds Kotlin raw strings opened on a line that calls an output sink, and scores
their non-heading, non-comment lines with score_voice's BODY_TELLS.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import score_voice as sv  # noqa: E402
import in_fences as inf  # noqa: E402

# The calls whose raw string becomes something a reader sees.
SINK = re.compile(r"\b(markdown|display|html|note|caption|showMarkdown)\s*\(")
MD_HEADING = inf.MD_HEADING
COMMENT = inf.COMMENT


def embedded_prose_lines(body, lang):
    """Yield (lineno, text) for prose lines inside a sink's triple-quoted raw string."""
    if lang not in ("kotlin", "kt"):
        return
    depth_open = None
    for lineno, raw in body:
        quotes = raw.count('"""')
        if depth_open is None:
            if quotes and SINK.search(raw):
                # opens here; a one-line `markdown("x", """y""")` closes on the same line
                if quotes % 2 == 1:
                    depth_open = lineno
            continue
        if quotes:
            depth_open = None
            continue
        if MD_HEADING.match(raw):
            continue  # already scored by in_fences
        if COMMENT.match(raw):
            continue  # already scored by in_fences
        yield lineno, raw


def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else "public/docs")
    rows = []
    total_lines = 0
    for path in sorted(root.rglob("*.md")):
        text = path.read_text(encoding="utf-8", errors="replace")
        hits = []
        for _s, lang, hidden, baked, body in inf.fence_blocks(text):
            if hidden:
                continue
            for lineno, raw in embedded_prose_lines(body, lang):
                total_lines += 1
                probe = re.sub(r"`[^`]+`", "§", raw)
                probe = re.sub(r"\{[a-z]+:", "", probe)  # Field's {muted:…} span syntax
                if len(probe.split()) < 4:
                    continue
                for name, pat, w in sv.BODY_TELLS:
                    for mm in re.finditer(pat, probe, re.I):
                        hits.append((name, lineno, w, mm.group(0), raw.strip()[:110]))
        if hits:
            rows.append((sum(h[2] for h in hits), path, hits))
    rows.sort(key=lambda r: -r[0])
    for score, path, hits in rows:
        print(f"{score:5d}  {path}")
        for name, lineno, w, snip, full in hits:
            print(f"          {name} [embedded-prose] :{lineno}  {snip}")
            print(f"               | {full}")
    print(f"\nscanned {total_lines} embedded-prose line(s); "
          f"{len(rows)} page(s) nonzero, sum {sum(r[0] for r in rows)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
