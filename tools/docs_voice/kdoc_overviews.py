#!/usr/bin/env python3
"""File-overview KDoc, scored one overview at a time.

`score_voice.py --generated` scores a *reference page*. A reference page is 20 to 78
file overviews concatenated, so one bad heading in one overview is a score of 2 against
25,000 words and never reaches the top of any board. This file scores each overview on
its own, which is the unit somebody wrote and the unit a reader meets.

Two populations, and the second is the reason this exists:

  published   the overview has a `## <basename> { #` section on a `public/docs/reference/`
              page today -- the large majority. Measured 2026-09-25: **the only nonzero
              published rows are the two recorded standing keeps** (`CaSoundDocs.kt` and
              `LambdaSoundDocs.kt`, "What it does not do", a capability list). The
              published surface is clean, which is `--generated` being right rather than
              lucky. Read the counts the board prints; do not trust a number written here.

  queued      the overview publishes nowhere today, but its file carries a
              `@file:CompletionCategory` naming a category that *has* a page. These are
              the files `StdlibReferenceGenerator.kt` measured on 2026-09-10 and recorded
              as a gap it deliberately did not close ("six more roots is a different
              change ... it wants its own night"). They are prose queued for publication.

MEASURED 2026-09-25 (sixtieth firing), and it is why the split is worth drawing: **every
one of the eight worst-scoring overviews in the tree is queued, not published**, and six
of the eight are in `afield.solidsl` -- the package `solid_notation.md` is written about,
and the package whose page produced Marc's original 2026-09-13 complaint. `SolidField.kt`
carried `Total, and never a refusal`; his quote that night was "### The reading is total;
the *parameterisation* is where the refusals live". Same author, same week, same shape,
and the page was cleaned while the KDoc beside it was not, because no board read it.

So the ordering matters: fix the voice of a queued overview *before* the roots land, not
after. Publishing it is a one-line change to a list in `StdlibReferenceGenerator.kt`, and
on the day it happens nine `solidsl` overviews arrive on `languages.md` at once.

## em-dash density is excluded from the ranking, on purpose

An overview is 100 to 600 words. `em-dash-density`'s per-1000-word threshold is noisy at
that length -- a 107-word overview with three dashes is "1 over" -- and the task file says
in as many words not to chase em-dashes. Excluding it takes the board from 36 nonzero to
8. The count is still printed, so the signal is set aside rather than lost.

## Scoring is imported, never copied

`score_voice.score` and `check_kdoc_mirror.file_overview` are imported. A second copy of a
detector in this directory has gone stale four separate times without announcing it; both
copies calibrate green and the stale one just scores less.

    kdoc_overviews.py [src] [public/docs/reference]
"""

from __future__ import annotations

import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import check_kdoc_mirror as km  # noqa: E402
import score_voice as sv  # noqa: E402

# Below this an "overview" is a one-line blurb, and a one-line blurb has no room for the
# shapes this board looks for. 25 words is where the tree's own overviews stop being
# sentences and start being labels.
MIN_WORDS = 25

# The one family `score_voice` scores that this board does not rank on. See the docstring.
NOT_RANKED = "em-dash-density"


def score_overview(body: str):
    """(ranked score, ranked hits, detail, em-dash points) for one overview body."""
    _total, _words, _em, hits, detail, _meas = sv.score(body)
    dashes = hits.get(NOT_RANKED, 0)
    ranked = {k: v for k, v in hits.items() if k != NOT_RANKED}
    detail = {k: v for k, v in detail.items() if k != NOT_RANKED}
    return sum(ranked.values()), ranked, detail, dashes


def overviews(src_root: pathlib.Path, ref_root: pathlib.Path):
    pages = {p: p.read_text().splitlines() for p in sorted(ref_root.glob("*.md"))}
    for kt in sorted(src_root.rglob("*.kt")):
        block = km.file_overview(kt)
        if not block:
            continue
        body = "\n".join(km._body(block))
        if len(body.split()) < MIN_WORDS:
            continue
        where = [p.name for p, lines in pages.items() if km.page_section(lines, kt.name)]
        yield kt, body, (where[0] if where else None)


def report(src_root: pathlib.Path, ref_root: pathlib.Path) -> int:
    print(__doc__.split("\n")[0])
    print("A row is a candidate to READ. `queued` means the overview publishes nowhere\n"
          "today and will the day its root is added to StdlibReferenceGenerator.kt.\n")
    rows = []
    scanned = published = dashes_total = 0
    for kt, body, page in overviews(src_root, ref_root):
        scanned += 1
        if page:
            published += 1
        total, hits, detail, dashes = score_overview(body)
        dashes_total += dashes
        if total:
            rows.append((total, hits, detail, len(body.split()), kt, page))
    rows.sort(key=lambda r: -r[0])
    for total, hits, detail, words, kt, page in rows:
        where = f"-> {page}" if page else "queued"
        print(f"{total:>4} {words:>5}w  {kt}  {where}")
        for family, items in sorted(detail.items()):
            for _line, snippet in items:
                print(f"          {family}: {snippet}")
    print(f"\noverviews={scanned} published={published} queued={scanned - published} "
          f"nonzero={len(rows)} sum={sum(r[0] for r in rows)} "
          f"(em-dash points set aside: {dashes_total})")
    return 0


# A must-fire fixture is the shape; a must-not-fire fixture is the shape's nearest
# innocent neighbour. The boundary fixtures are written first, because four firings
# running have now found a control that did nothing.
MUST_FIRE = [
    # The three heading shapes the queued overviews actually carry, verbatim.
    "## What the loss does not contain, and what goes wrong because of it\n\n"
    "The residual is a signed distance difference at each sample point.\n",
    "## Agreement does not converge to zero, and saying so is the point\n\n"
    "Two lowerings of one source disagree by a bounded amount.\n",
    "## Why a third reading, and why *this* third reading\n\n"
    "The gradient reading is the transpose of the field reading.\n",
    # A body tell, so the board is not only a heading board.
    "The order of these two passes is load-bearing: the simplifier runs first.\n",
    # BOUNDARY: the lowest-weight family in the set (head-negspace, 2). If a future
    # threshold change silences a weight-2 family, this fixture is what notices.
    "## What it does not do\n\nIt does not close a surface that was never closed.\n",
]

MUST_NOT_FIRE = [
    # Ordinary reference prose: the subject, then what it does.
    "## The vocabulary\n\nEvery primitive the notation accepts, with the arity it takes\n"
    "and the type it returns. `box`, `sphere`, `cylinder` and `plane` are the solids.\n",
    # BOUNDARY: em-dashes only, and plenty of them. The whole point of NOT_RANKED is that
    # this scores zero on this board while scoring nonzero on score_voice's own.
    "A codec round trip is three things that have to be looked at together — the audio\n"
    "in, the discrete tokens, the audio out — and this file is the rendering half — the\n"
    "part that runs off the runloop — of that view.\n",
    # A genuine limitation stated as a fact rather than as negative space in a heading.
    "The fitter does not place a new point without refitting everything, so this is not\n"
    "dimensionality reduction for a model.\n",
    # A design fact answering a `Why`, which the task file says to keep.
    "## Why the cache is per-context\n\nTwo contexts render the same graph at different\n"
    "sample rates, so a shared cache would hand one of them the other's samples.\n",
]


def live_fire() -> int:
    bad = []
    for text in MUST_FIRE:
        if score_overview(text)[0] == 0:
            bad.append(("MUST_FIRE missed", text.strip().split("\n")[0][:64]))
    for text in MUST_NOT_FIRE:
        total, hits, _detail, _dashes = score_overview(text)
        if total:
            bad.append((f"MUST_NOT_FIRE fired {dict(hits)}",
                        text.strip().split("\n")[0][:64]))
    for why, what in bad:
        print(f"LIVE FIRE {why}: {what}")
    return 1 if bad else 0


def main() -> int:
    if "--live-fire" in sys.argv:
        return live_fire()
    if live_fire():
        return 1
    print(f"live fire OK: {len(MUST_FIRE)} must-fire, {len(MUST_NOT_FIRE)} must-not-fire,"
          " all correct\n")
    args = [a for a in sys.argv[1:] if not a.startswith("-")]
    src_root = pathlib.Path(args[0]) if args else pathlib.Path("src")
    ref_root = (pathlib.Path(args[1]) if len(args) > 1
                else pathlib.Path("public/docs/reference"))
    return report(src_root, ref_root)


if __name__ == "__main__":
    raise SystemExit(main())
