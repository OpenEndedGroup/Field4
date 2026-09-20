#!/usr/bin/env python3
"""Measure the last unscored prose surface in `public/docs`: figure ALT TEXT.

`score_voice.split_prose()` treats a fence opener as a delimiter --

    if stripped.startswith("```"): in_fence = not in_fence; continue

-- so the opener LINE is dropped before any tell is matched, and `in_fences.py` reads
fence *bodies* (code comments, embedded markdown headings), never the opener's
attributes. The `alt="..."` a capture fence carries has therefore been scored by
nothing, on any surface, in thirty-four firings.

It is published prose. `public/docs/assets/image-lightbox.js:76` reads the attribute
back off the rendered `<img>`, so an alt string is both the screen-reader text and the
lightbox caption -- for a blind reader it is the *only* thing the figure says. A
slide-label there is worse than one in a paragraph, because a sighted reader can
overrule a bad caption by looking at the picture and a screen-reader user cannot.

Scored:
  - every `alt="..."` in a fence opener, against BODY_TELLS
Not scored:
  - HEADING_TELLS. The first version of this tool DID score them, prefixed `alt-`, on
    the argument `table_cells.py` makes for a column header: a short label is read as a
    title. **That argument does not transfer, and the first run proved it.** It returned
    10 pages and a sum of 216 -- by far the densest board any surface has produced -- and
    every single point was a HEADING_TELL. Not one BODY_TELL fired on any of the 88
    strings. Reading them settled it: a column header is two to five words, an alt string
    is a forty-word description of a multi-panel figure, and the three families that fired
    are exactly the ones a description needs. `head-semicolon` on "on the left one orange
    square; on the right ..." is a figure enumerating its panels. `head-comma-clause` on
    "from above, where the room reads as relief" names which view. `head-negspace` on "a
    32-bit adder rotating slowly, never flattening into a line" is describing the
    animation -- the absence IS the visual fact. An alt string is prose, and only the
    prose families belong on it.
  - markdown image alt, `![...](...)`. That is ALREADY covered -- an image line is
    ordinary prose to `split_prose`, and `score()` matches BODY_TELLS against the raw
    line with only code spans blanked, so the bracket text is inside the match window.
    Scoring it here as well would double-count it.
  - alt strings under four words. "The two flocks" is a label, not prose.

Also reported, because the detector has to walk every capture fence anyway and the
number is the headline: ALT COVERAGE. A fence with `screenshot=` and no `alt=`
publishes a figure with no alternative text at all.

Surfaces match score_voice: default / --generated / --assets, same filters.
Self-test: `--live-fire` puts Marc's own 2026-09-13 quotes in an alt string and
asserts they fire. A detector that reports zero has to be shown able to report one.
"""
from __future__ import annotations

import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import score_voice as sv  # noqa: E402

# A fence opener with a brace-delimited attribute block: ```diffstatic_runner {...}
OPENER = re.compile(r"^\s*(?:```|~~~)[a-z_]*\s*\{(?P<attrs>[^}]*)\}")
ALT = re.compile(r'\balt="(?P<text>[^"]*)"')
SCREENSHOT = re.compile(r"\bscreenshot=")
MIN_WORDS = 4


def openers(text):
    """Yield (lineno, attrs) for each fence opener carrying an attribute block.

    Fence state is tracked so that a ```-looking line inside a fence body -- a nested
    fence in an embedded markdown sample -- is not read as an opener.
    """
    in_fence = False
    for lineno, raw in enumerate(text.split("\n"), 1):
        stripped = raw.lstrip()
        if not (stripped.startswith("```") or stripped.startswith("~~~")):
            continue
        if in_fence:
            in_fence = False
            continue
        in_fence = True
        m = OPENER.match(raw)
        if m:
            yield lineno, m.group("attrs")


def scan(text):
    """Yield (family, lineno, weight, matched, alt) for every tell in an alt string."""
    for lineno, attrs in openers(text):
        m = ALT.search(attrs)
        if not m:
            continue
        alt = m.group("text").strip()
        # Blank code spans the same way `score()` does, so a phrase inside a backticked
        # identifier cannot fire. Alt text does carry them: `capture-box="lb_renderer"`
        # figures describe `psi` and `dt` by name.
        probe = re.sub(r"`[^`]+`", "§", alt)
        if len(probe.split()) < MIN_WORDS:
            continue
        for name, pat, w in sv.BODY_TELLS:
            for hit in re.finditer(pat, probe, re.I):
                yield name, lineno, w, hit.group(0), alt


def coverage(text):
    """Return (figures, with_alt) for capture fences on this page."""
    figures = with_alt = 0
    for _lineno, attrs in openers(text):
        if SCREENSHOT.search(attrs):
            figures += 1
            if ALT.search(attrs):
                with_alt += 1
    return figures, with_alt


def live_fire():
    """A detector that reports zero has to be shown capable of reporting one.

    Both positive cases are Marc's own 2026-09-13 quotes, moved into an alt string.
    The negative cases are the two that matter now that HEADING_TELLS are gone: a
    descriptive alt full of semicolons must NOT fire, and a short label must not be
    scored as prose at all.
    """
    sample = "\n".join([
        '```diffstatic_runner {data-lang=kotlin screenshot="a.png" '
        'alt="The left panel is the sanity check, and it carries a trap"}',
        "// body",
        "```",
        '```diffstatic_runner {data-lang=kotlin screenshot="b.png" '
        'alt="A plot of the residual; the interesting part is that it does not compound"}',
        "// body",
        "```",
        '```diffstatic_runner {data-lang=kotlin screenshot="c.png" alt="Two flocks"}',
        "// under the four-word floor, not scored",
        "```",
        '```diffstatic_runner {data-lang=kotlin screenshot="d.png" '
        'alt="Two plan views of a 48-layer stack from overhead: on the left one orange '
        'square; on the right a scatter, never flattening into a line"}',
        "// a real description. Semicolons, a colon clause and an absence: must stay silent",
        "```",
    ])
    found = {f for f, *_ in scan(sample)}
    print("live-fire families:", sorted(found) or "NONE")
    assert found, "alt scanner fired on nothing; it cannot report a real zero"

    descriptive = [h for h in scan(sample) if h[1] == 13]
    assert not descriptive, f"fired on a plain description: {descriptive}"
    short = [h for h in scan(sample) if h[1] == 9]
    assert not short, f"scored a label under the word floor: {short}"

    figures, with_alt = coverage(sample)
    assert (figures, with_alt) == (4, 4), f"coverage miscounted: {figures}, {with_alt}"
    print("live-fire coverage: 4 of 4; descriptive alt and short label both silent")
    return 0


def main():
    if "--live-fire" in sys.argv:
        return live_fire()
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("-")
                        else "public/docs")
    generated = "--generated" in sys.argv
    assets = "--assets" in sys.argv

    paths = [pathlib.Path(r["path"]) for r in sv.collect(root, generated=generated, assets=assets)]
    rows, scanned, figures, with_alt = [], 0, 0, 0
    bare = []
    for path in paths:
        text = path.read_text(errors="replace")
        for _lineno, attrs in openers(text):
            if ALT.search(attrs):
                scanned += 1
        f, a = coverage(text)
        figures += f
        with_alt += a
        if f - a:
            bare.append((f - a, path))
        hits = list(scan(text))
        if hits:
            rows.append((sum(h[2] for h in hits), path, hits))

    rows.sort(key=lambda r: -r[0])
    for score, path, hits in rows:
        print(f"{score:5d}  {path}")
        for name, lineno, _w, snip, alt in hits:
            print(f"          {name} [alt] :{lineno}  {snip}")
            print(f"               | {alt[:110]}")
    print(f"\nscanned {scanned} alt string(s) on {len(paths)} page(s); "
          f"{len(rows)} page(s) nonzero, sum {sum(r[0] for r in rows)}")

    pct = round(100 * with_alt / figures, 1) if figures else 0.0
    print(f"\nALT COVERAGE: {with_alt} of {figures} capture fences carry alt text ({pct}%). "
          f"{figures - with_alt} published figures have none.")
    if "--coverage" in sys.argv:
        bare.sort(key=lambda r: -r[0])
        for n, path in bare[:30]:
            print(f"  {n:4d} without alt  {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
