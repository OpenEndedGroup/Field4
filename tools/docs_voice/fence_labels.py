#!/usr/bin/env python3
"""Measure a surface neither `in_fences.py` nor `embedded_prose.py` reaches: reader-visible
prose held in a SINGLE-LINE Kotlin string literal inside a fence.

`in_fences.py` scores `#` markdown headings and `//` comments. `embedded_prose.py` scores the
body lines of a TRIPLE-quoted raw string opened on a line that calls an output sink. Between
them they miss every label passed as an ordinary `"…"` argument, and several of those are the
most prominent words in a box's output:

  * `Table(title = "…", note = "…")`      — a table's caption and its footnote
  * `.label("…")`, `caption = "…"`,
    `subtitle = "…"`, `header = "…"`      — plot and figure furniture
  * the EDN properties line's `"name" "…"` — the BOX TITLE, rendered in the document itself
    and in the transcript gallery, so it is read before any of the code is

A `title =` is a heading in every sense that matters to a reader, which is why the heading
families are scored against it as well as the body families.

Usage:  fence_labels.py <docs-root> [--include-hidden] [--list]
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import score_voice as sv  # noqa: E402
import in_fences as inf  # noqa: E402

# Named arguments and calls whose string argument is rendered as visible furniture.
KWARG = re.compile(
    r"\b(title|note|caption|subtitle|header|footer|label|legend|tooltip|placeholder)\s*=\s*"
    r'"((?:[^"\\]|\\.){4,})"'
)
LABEL_CALL = re.compile(r'\.(label|title|caption|note)\s*\(\s*"((?:[^"\\]|\\.){4,})"')
# The EDN properties line carries the box's own displayed name, twice (top level and inside
# "properties"). One hit per line is enough; dedupe on the text.
EDN_NAME = re.compile(r'"name"\s+"((?:[^"\\]|\\.){4,})"')

# A label is a heading for a reader, so both tell tables apply. `pat` is a STRING in
# score_voice and is compiled at each use site with re.I; do the same here rather than
# assuming a compiled pattern.
TELLS = [(n, re.compile(p, re.I), w) for (n, p, w) in sv.BODY_TELLS]

# NOT every heading family transfers to a label. Measured on 2026-09-20 over all 1460 labels
# in the tree, reading every one of the 18 pages that scored:
#   head-stakes, head-semicolon  — 3 of Marc's own 2026-09-13 quotes are caught by these in
#     label position (live-fired; see the docstring). KEPT.
#   head-negspace, head-comma-clause — 9 hits, 0 true. A `##` heading has a whole section to
#     name, so "Subject, and what it does not do" is a justification bolted on; a six-word BOX
#     TITLE naming two panels ("Blocks, and what the indent holds") or one limitation ("3 ·
#     what it cannot do") is just the label doing its job. The task file says to keep a stated
#     limitation, and in this position that is nearly all of them. DROPPED.
_LABEL_HEAD_FAMILIES = {"head-stakes", "head-semicolon"}
HEAD_TELLS = [(n, re.compile(p, re.I), w) for (n, p, w) in sv.HEADING_TELLS
              if n in _LABEL_HEAD_FAMILIES]


def labels(text: str, include_hidden: bool = False):
    """Yield (lineno, kind, label_text) for every reader-visible label in a fence."""
    for _start, lang, hidden, baked, body in inf.fence_blocks(text):
        if hidden and not include_hidden:
            continue
        for lineno, raw in body:
            if lang in ("edn",):
                seen = set()
                for m in EDN_NAME.finditer(raw):
                    t = m.group(1)
                    if t not in seen:
                        seen.add(t)
                        yield (lineno, "box-name", t, baked)
                continue
            if lang not in ("kotlin", "kt", "js", "javascript", "python", "py"):
                continue
            for m in KWARG.finditer(raw):
                yield (lineno, m.group(1), m.group(2), baked)
            for m in LABEL_CALL.finditer(raw):
                yield (lineno, m.group(1), m.group(2), baked)


# A caption is not plain prose. Two things in it are punctuation to the renderer and were
# read as punctuation by the tells on the first run of this script:
#   `&middot;` / `&mdash;` — an HTML entity ENDS IN A SEMICOLON, so every `a &middot; b`
#     figure caption scored `head-semicolon`. That was 6 of the first run's top 8 rows.
#   `${expr}` — a Kotlin template interpolation is an expression, not words, and a `,` or
#     `;` inside one is code punctuation.
# Both are replaced with a sentinel rather than deleted, for the reason score_voice.py:560
# records: deleting joins the words either side and can manufacture a tic nobody wrote.
ENTITY = re.compile(r"&[a-zA-Z][a-zA-Z0-9]{1,10};|&#\d{1,5};")
INTERP = re.compile(r"\$\{[^{}]*\}|\$[A-Za-z_][A-Za-z0-9_.]*")


def normalise(label: str) -> str:
    probe = label.replace("\\n", " ")
    probe = ENTITY.sub("§", probe)
    probe = INTERP.sub("§", probe)
    return re.sub(r"`[^`]+`", "§", probe)


def scan(text: str, include_hidden: bool = False):
    out = []
    for lineno, kind, label, baked in labels(text, include_hidden):
        probe = normalise(label)
        for name, pat, w in TELLS:
            if pat.search(probe):
                out.append((kind, lineno, name, w, label, baked))
        for name, pat, w in HEAD_TELLS:
            if pat.search(probe):
                out.append((kind, lineno, "head:" + name, w, label, baked))
    return out


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    root = Path(args[0] if args else "public/docs")
    include_hidden = "--include-hidden" in sys.argv
    listing = "--list" in sys.argv

    rows, total_labels, pages = [], 0, 0
    for path in sorted(root.rglob("*.md")):
        try:
            text = path.read_text(encoding="utf-8")
        except Exception:
            continue
        pages += 1
        found = list(labels(text, include_hidden))
        total_labels += len(found)
        if listing:
            for lineno, kind, label, _baked in found:
                print(f"{path}:{lineno}\t{kind}\t{label}")
            continue
        hits = scan(text, include_hidden)
        if hits:
            rows.append((sum(h[3] for h in hits), path, hits))

    if listing:
        print(f"\n{total_labels} label(s) across {pages} page(s).")
        return 0

    print("READER-VISIBLE LABELS INSIDE FENCES — title/note/caption/label args and box names.")
    print("Neither in_fences.py (headings + comments) nor embedded_prose.py (raw strings)")
    print("reaches these. A box's EDN `name` is its displayed title.\n")
    rows.sort(key=lambda r: (-r[0], str(r[1])))
    for score, path, hits in rows:
        print(f"{score:5d} {len(hits):4d}  {path}")
        for kind, lineno, fam, w, label, baked in hits:
            mark = "   [BAKED INTO THE COMMITTED FIGURE]" if baked else ""
            print(f"{'':17}{fam} [{kind}] :{lineno}  {label[:80]}{mark}")
    print(f"\npages={pages} labels={total_labels} nonzero={len(rows)} "
          f"sum={sum(r[0] for r in rows)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
