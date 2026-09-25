#!/usr/bin/env python3
"""Repeated-sentence-opening census: the targeting route `score_voice.py` cannot take.

The task file says the scorer cannot see "prose that is fine sentence by sentence and
unmistakable in bulk — one rhetorical move repeated once per section or once per gallery entry.
Grep the *shape* across the whole page, not within a section." This is that grep, mechanised.

It found the twenty-fifth firing's batch. `public/docs/functions_examples.md` carried ONE HUNDRED
captions reading "This should produce a quadratic curve over the default domain" — one per entry
of its expanded reference, test-assertion voice standing where documentation belongs — and scored
**0** on every detector, on every surface, for twenty-four firings. Nothing was wrong with any one
sentence. The census put it at the top of the ranking by a factor of three over the next page.

Read the output as *candidates to read*, not as faults. Most of the top rows are structural and
correct:

  - `video src assets`, `div div class`, `img src assets`, `min px source` — raw HTML in a page
    that embeds a gallery of clips. Not prose.
  - `code is the`, `code code code`, `code and code` — the `§`-style artefact of collapsing
    inline code spans. A row whose prefix is mostly `code` is telling you the page has many
    short spans, which is what a reference page is.
  - `pat mini source`, `piano roll of` — a repeated figure caption, i.e. a convention.

What to look for instead is a row whose prefix is an ENGLISH CLAUSE OPENING repeated once per
entry: `this should produce`, `every box is`, `the first box`, `open the same`. Those are a
rhetorical move on a schedule, and the schedule is the tell.

Deliberately excludes `reference/` and `assets/`: a generated page repeats its generator's
sentence frames by construction, so every row from one is a false positive about the page and at
best a note about the generator. Run it against the hand-written surface, which is where a human
wrote the same sentence fifty times.

## Every row read, 2026-09-25 (fifty-ninth firing), at `a88f62b96`: 68 rows, ONE true

The board had been run nightly for firings without a single recorded verdict, which is why this
section exists. Do not re-walk it row by row; re-walk it when the *shape* of a row is new.

The one true row: `afield_four_graphics.md` `there is no` x5 -- five passages explaining why a
section carries no figure, two of them by explaining the docs capture harness. Fixed.

The 67 others fall into four families, and all four are keeps:

  markup            `video src assets`, `div class field`, `div data code`, `img src assets`,
                    `min px source`, `div div class`, `div class pat`. Raw HTML and the
                    `field-reference` wrappers. ~40 rows, the bulk of the board.
  code-span collapse `code is the`, `code and code`, `code code code`, `code is a`, `code or code`.
                    The `§`-artefact the docstring above already names. Spot-checked on
                    `agent_transcripts.md`, where the underlying shape is "`x.md` is the write-up"
                    repeated per entry -- a functional signpost, not a rhetorical move.
  per-entry signpost `the first box` (8), `box is the` (8), `every box is` (6), `open the same`
                    (5), `the fourth box` (4) on `agent_transcripts.md`; `simple produces a` (12),
                    `creative code creates` (6), `simple interpolates the` (4) on
                    `functions_examples.md`; `open vision geometry` (4); `the raygen shader` (4);
                    `the vertex shader` / `the fragment shader` (4 each). A convention, and often
                    the only prose an entry has. The task file says keep these.
  ordinary English  `this is the` (5), `that is the` (4), `this is a` (4), `there is no` elsewhere,
                    `the third panel` (4), `the scene graph` (4). Varied payload under a common
                    three-word opening, which is what English does.

TWO LESSONS FOR READING THIS BOARD. First, a `code`-prefixed row is not always noise: collapse
hides the real shape, so expand one before dismissing the family (`expand.py` recipe -- re-run
`census()`'s normalisation and print the matching sentences). Second, the one true row was
**fifth from the bottom of the board**, at n=5, under forty markup rows. Rank here is a
function of markup density, not of fault severity: read the English rows at every n, not the top.

    python3 shape_census.py [root] [--min N]
"""

import collections
import os
import pathlib
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import score_voice as sv  # noqa: E402


def census(root: pathlib.Path, min_repeats: int = 4):
    rows = []
    for p in sorted(root.rglob("*.md")):
        s = str(p)
        if "/generated/" in s or "/assets/" in s or "/reference/" in s:
            continue
        text = p.read_text(errors="replace")
        if sv.is_generated(text):
            continue
        prose, *_ = sv.split_prose(text)
        # Headings are A-family work and score already; this is about body prose.
        body = " ".join(t for _, t in prose if not t.lstrip().startswith("#"))
        # Collapse inline spans and link syntax so the SHAPE survives and the payload does not.
        body = re.sub(r"`[^`]*`", " code ", body)
        body = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", body)
        # `:` counts as a sentence end: a caption frame ("The interesting part is:") ends there
        # as often as at a full stop.
        for sentence in re.split(r"(?<=[.!?:])\s+", body):
            words = re.findall(r"[A-Za-z']+", sentence)
            if len(words) < 4:
                continue
            rows.append((s, " ".join(w.lower() for w in words[:3])))

    per_page = collections.Counter(rows)
    out = [(n, prefix, path) for (path, prefix), n in per_page.items() if n >= min_repeats]
    out.sort(reverse=True)
    return out


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    root = pathlib.Path(args[0] if args else "public/docs")
    min_repeats = 4
    if "--min" in sys.argv:
        min_repeats = int(sys.argv[sys.argv.index("--min") + 1])

    out = census(root, min_repeats)
    print(f"Sentence openings repeated {min_repeats}+ times on one page, {root} "
          f"(hand-written surface only).\n"
          f"A row is a candidate to READ. See this file's docstring for which rows are noise.\n")
    print(f"{'n':>4}  {'opening':34} page")
    for n, prefix, path in out:
        print(f"{n:>4}  {prefix:34} {path}")
    print(f"\n{len(out)} row(s) at or above {min_repeats}.")


if __name__ == "__main__":
    main()
