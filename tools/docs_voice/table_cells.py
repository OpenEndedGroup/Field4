#!/usr/bin/env python3
"""Measure the surface `score_voice.py` drops on the floor: markdown TABLE CELLS.

`split_prose()` routes a `|` line into `table` and `continue`s, so it never reaches
`prose`. `score()` then uses `table` for exactly one thing -- counting measurement rows
for the `measurement-heavy` signal. **No BODY_TELL and no HEADING_TELL has ever been
matched against the text of a table cell.**

That matters more here than it would in another tree, because this task's own disposal
table says "three or more bullets carrying numbers -> a table". Twenty-odd firings have
been moving prose out of a scored surface and into an unscored one. A slide-label in a
`| what it does | why |` header, or a "worth knowing before you trust it" in a caveat
column, reads exactly like one in a paragraph and costs zero.

Scored:
  - every cell of every table row, code spans blanked the same way `score()` blanks them
  - the HEADER row additionally against HEADING_TELLS: a column header is read as a label
Not scored:
  - the `|---|:--:|` separator row
  - cells of fewer than three words after code spans are blanked. A `| `fbm` | 2D |` cell
    is a value, not prose, and the tells are phrases.

Surfaces match score_voice: default / --generated / --assets, same filters.
Self-test: `--live-fire` puts Marc's own 2026-09-13 quotes in a table and asserts they fire.
"""
from __future__ import annotations

import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import score_voice as sv  # noqa: E402

SEPARATOR = re.compile(r"^\|[\s:|-]+\|?\s*$")
MIN_WORDS = 3


def cells(row):
    """Split a markdown table row into its cells, keeping nothing but the text."""
    body = row.strip()
    if body.startswith("|"):
        body = body[1:]
    if body.endswith("|"):
        body = body[:-1]
    # A `\|` is an escaped pipe inside a cell, not a delimiter.
    return [c.strip() for c in re.split(r"(?<!\\)\|", body)]


def scan(text):
    """Yield (family, lineno, weight, matched, cell) for every tell in a table cell."""
    _prose, table, _heads, _admons, _bullets = sv.split_prose(text)
    header_lines = set()
    for idx, (lineno, raw, _meas) in enumerate(table):
        # `table` is a flat list across every table on the page, so `table[idx - 1]` is the
        # previous table LINE and not necessarily the line above. Without the adjacency check
        # a separator that opens a table -- `mesh_examples.md` has one -- names the last row
        # of the *previous* table as a column header, and a semicolon in that data cell then
        # scores 8 as a heading fault. Found by reading the first three hits this tool ever
        # printed; two of the three were this.
        if SEPARATOR.match(raw.strip()) and idx and table[idx - 1][0] == lineno - 1:
            header_lines.add(table[idx - 1][0])

    for lineno, raw, _meas in table:
        if SEPARATOR.match(raw.strip()):
            continue
        for cell in cells(raw):
            probe = re.sub(r"`[^`]+`", "§", cell)
            probe = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", probe)  # link text, not the URL
            if len(probe.split()) < MIN_WORDS:
                continue
            for name, pat, w in sv.BODY_TELLS:
                for m in re.finditer(pat, probe, re.I):
                    yield name, lineno, w, m.group(0), cell
            if lineno in header_lines:
                for name, pat, w in sv.HEADING_TELLS:
                    for m in re.finditer(pat, probe, re.I):
                        yield "colhead-" + name, lineno, w, m.group(0), cell


def live_fire():
    """A detector that reports zero has to be shown capable of reporting one."""
    sample = "\n".join([
        "| what it is | why |",
        "| --- | --- |",
        "| the left panel | The left panel is the sanity check, and it carries a trap. |",
        "| the numbers | Two ways the numbers will surprise you |",
        "| `fbm` | 2D |",
    ])
    found = {f for f, *_ in scan(sample)}
    print("live-fire families:", sorted(found) or "NONE")
    assert found, "table-cell scanner fired on nothing; it cannot report a real zero"
    return 0


def main():
    if "--live-fire" in sys.argv:
        return live_fire()
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("-")
                        else "public/docs")
    generated = "--generated" in sys.argv
    assets = "--assets" in sys.argv

    paths = [pathlib.Path(r["path"]) for r in sv.collect(root, generated=generated, assets=assets)]
    rows, total_cells = [], 0
    for path in paths:
        text = path.read_text(errors="replace")
        _p, table, _h, _a, _b = sv.split_prose(text)
        total_cells += sum(len(cells(r)) for _l, r, _m in table
                           if not SEPARATOR.match(r.strip()))
        hits = list(scan(text))
        if hits:
            rows.append((sum(h[2] for h in hits), path, hits))

    rows.sort(key=lambda r: -r[0])
    for score, path, hits in rows:
        print(f"{score:5d}  {path}")
        for name, lineno, _w, snip, cell in hits:
            print(f"          {name} [table-cell] :{lineno}  {snip}")
            print(f"               | {cell[:110]}")
    print(f"\nscanned {total_cells} table cell(s) on {len(paths)} page(s); "
          f"{len(rows)} page(s) nonzero, sum {sum(r[0] for r in rows)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
