#!/usr/bin/env python3
"""Two structural heading faults no other detector in this directory can see, because
neither is about a word choice.

**Title Case.** `public/docs` is sentence case, and it is sentence case by such a margin
that the exceptions are findable by machine: over 139 hand-written pages on 2026-09-22,
exactly three carried a `##` in Title Case, and two of them were real (`fabricate_renderer.md`
had thirteen of fifteen; `language_extensions.md` had eight, including `And` and `To` as
capitals). The third was `graph_connectivity_examples.md`'s `## Pathing: anchored cover + A*
overlay` — the `A` of `A*`, which is why a function word must be at least two letters to
count.

The floor is three words, so `fabricate_renderer.md`'s `## Post Stack` and `## Ambient
Occlusion` are invisible to it — two capitalised words are a product name (`Vision Pro`,
`Sonic Pi`) far more often than a cased heading. That is the right trade because **this
detector finds pages, not headings**: the page's longer headings put it on the board, and a
reader then fixes all fifteen.

Title Case is not a *voice* fault in Marc's sense and this detector does not pretend it is.
It is a house-convention fault, and it earns a place here for a duller reason: on both real
pages it sat on top of prose that had the voice fault too. A page cased by a different hand
is a page written by a different hand. Treat a hit as a reading list.

**A page that skips `##`.** `installation.md` was the only page in the tree whose sections
started at `###` under the H1, which put all three of them one rank too deep in the built
table of contents — on the page `index.md` sends a first-time reader to. One hit in 139 is
what a regression guard looks like after it has been paid; the value is that the next one
costs nothing to find.

Both boards are expected to read zero. A nonzero board is a page to open.

Usage:  heading_case.py <docs-root> [--generated] [--assets] [--live-fire]
"""
from __future__ import annotations

import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import score_voice as sv  # noqa: E402

HEADING = re.compile(r"^(#{1,4}) +(\S.*?)\s*$")

# Function words that a sentence-case heading leaves lowercase. Two letters minimum: a
# one-letter "A" is `A*`, `A → B`, or a determiner opening a clause after a colon, and all
# three are correct lowercase-insensitive. That single rule was the difference between 2/3
# and 3/3 precision on the 2026-09-22 census.
FUNCTION_WORDS = {
    "an", "the", "of", "in", "on", "to", "for", "and", "or", "but", "nor", "with", "from",
    "at", "by", "as", "is", "are", "be", "it", "its", "not", "no", "into", "over", "under",
    "up", "out", "than", "then", "when", "what", "how", "why", "if", "that", "this", "you",
    "your", "can", "do", "does", "so", "vs",
}

# A heading is exempt when the word really is capitalised in running prose. Anything with an
# internal capital or a digit (`FieldML`, `RectSL`, `glTF`, `OpenXR`, `D2`) is a name; so is
# anything inside backticks, which `strip_code` removes before the test.
CODE_SPAN = re.compile(r"`[^`]*`")
WORD = re.compile(r"[A-Za-z][A-Za-z'’]*")


def strip_code(text: str) -> str:
    return CODE_SPAN.sub(" ", text)


def title_cased(heading: str):
    """Return the capitalised function words, or [] when the heading is sentence case.

    Only the *function word* test is used. A heading whose content words are all capitalised
    but which contains no function word at all (`## Ambient Occlusion`, `## Post Stack`) is
    reported separately by `all_caps_words`, because that shape has a much higher rate of
    being three proper nouns in a row.
    """
    bare = strip_code(heading)
    words = WORD.findall(bare)
    if len(words) < 3:
        return []
    return [w for w in words[1:] if len(w) >= 2 and w.lower() in FUNCTION_WORDS and w[0].isupper()]


def all_caps_words(heading: str):
    """Every content word capitalised, no internal capitals, no digits: classic Title Case.

    Function words are excluded from the test rather than required to be capitalised, because
    Chicago-style Title Case leaves short prepositions and articles lowercase. `## Shiny Torii
    on Checkerboard Planes` is cased; `title_cased` cannot see it, because its one function
    word is correctly lowercase.
    """
    bare = strip_code(heading)
    if any(ch.isdigit() for ch in bare):
        return []
    words = [w for w in WORD.findall(bare)
             if len(w) >= 2 and w.lower() not in FUNCTION_WORDS]
    if len(words) < 3:
        return []
    # A name like `FieldML` or `RectSL` has an internal capital; such a heading is unjudgeable
    # by casing alone, so it is not reported.
    if any(any(c.isupper() for c in w[1:]) for w in words):
        return []
    if all(w[0].isupper() for w in words):
        return words
    return []


def scan_page(text: str):
    """Return (level, heading, why) for each Title Case heading, fences removed."""
    body = _strip_fences(text)
    out = []
    for line in body.split("\n"):
        m = HEADING.match(line)
        if not m:
            continue
        level, heading = len(m.group(1)), m.group(2)
        bad = title_cased(heading)
        if bad:
            out.append((level, heading, "function words capitalised: " + ", ".join(bad)))
            continue
        if all_caps_words(heading):
            out.append((level, heading, "every word capitalised"))
    return out


def _strip_fences(text: str) -> str:
    """Fence bodies hold code, and code is not prose. Keep line counts so a report can cite one."""
    return re.sub(r"```.*?```", lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)


def skips_h2(text: str) -> bool:
    body = _strip_fences(text)
    levels = {len(m.group(1)) for m in re.finditer(r"^(#{1,4}) +\S", body, re.M)}
    return 3 in levels and 2 not in levels


# `funFeatures.md` is one of Marc's own four calibration pages and it carries `## Custom
# Marking Menus`. That is the target voice by definition, so it is a keep and not a hit; the
# calibration in `score_voice.py --calibrate` exists for exactly this reason.
KEEPS = {("funFeatures.md", "Custom Marking Menus")}

MUST_FIRE = [
    "## Flow And Context Lenses",
    "## When To Promote",
    "## The Other Side Of Promotion: FieldML",
    "## Two Camera Framings Over One Scene",
    "## Shiny Torii on Checkerboard Planes",
]

MUST_NOT_FIRE = [
    # The false positive that set the two-letter floor: the `A` of `A*`.
    "## Pathing: anchored cover + A* overlay",
    # Ordinary sentence case, including one with a capitalised proper noun mid-heading.
    "## Editing the built-in shaders",
    "## What a promoted language looks like: FieldML",
    "## Two constructions of the same solid",
    "## Why Manifold reports a negative genus",
    # A heading that is all names. Internal capitals, so unjudgeable by casing.
    "## CoreML, fp16 and thread pinning",
    # Under three words: nothing to measure.
    "## Post stack",
    "## Metalness",
    # Two capitalised words is below the floor, and deliberately: `Vision Pro`, `Sonic Pi`
    # and `Wave Function` are names far more often than they are a cased heading.
    "## Ambient Occlusion",
    # Backticked API names are not prose words.
    "## `fieldProbe`, the CPU read-back probe",
]


def live_fire():
    bad = 0
    for h in MUST_FIRE:
        if not scan_page(h):
            print(f"MISSED {h}")
            bad += 1
    for h in MUST_NOT_FIRE:
        got = scan_page(h)
        if got:
            print(f"FALSE  {h} <- {got[0][2]}")
            bad += 1
    if not skips_h2("# Installation\n\n### Quick Start\n\n### Updating\n"):
        print("MISSED the h2-skipping page")
        bad += 1
    if skips_h2("# Installation\n\n## Starting Field\n\n### A detail\n"):
        print("FALSE  a page with both h2 and h3")
        bad += 1
    if bad:
        print(f"\nLIVE FIRE FAILED: {bad} case(s). Fix the detector before trusting a board.")
        return 1
    print(
        f"live fire OK: {len(MUST_FIRE) + 1} must-fire, {len(MUST_NOT_FIRE) + 1} must-not-fire, "
        "all correct"
    )
    return 0


def main():
    args = sys.argv[1:]
    if "--live-fire" in args:
        raise SystemExit(live_fire())
    if live_fire():
        raise SystemExit(1)
    print()

    positional = [a for a in args if not a.startswith("--")]
    root = pathlib.Path(positional[0] if positional else "public/docs")
    pages = sv.collect(
        root,
        generated="--generated" in args,
        assets="--assets" in args,
    )

    print("TITLE CASE in a SECTION heading — cased against the tree's convention. Not a voice")
    print("fault by itself; a reading list, because on both 2026-09-22 hits the prose beneath")
    print("had one. H1s are counted separately below and are NOT on this board: a page title")
    print("in Title Case is ordinary English, and renaming one costs a")
    print("`./gradlew generateCodebaseReference` whenever the reference cites it as `Used in:`.")
    print()
    cased = skipped = titles = 0
    for row in pages:
        # `sv.collect` returns a scored row per page, not a path; it is the only surface
        # filter in this directory that knows which pages are generated or machine-seeded,
        # which is why it is reused here rather than re-globbing.
        path = pathlib.Path(row["path"])
        text = path.read_text(encoding="utf-8", errors="replace")
        found = [r for r in scan_page(text) if (path.name, r[1]) not in KEEPS]
        titles += sum(1 for level, _, _ in found if level == 1)
        rows = [r for r in found if r[0] > 1]
        if rows:
            print(f"-- {path} ({len(rows)})")
            for level, heading, why in rows:
                print(f"   {'#' * level} {heading}   <- {why}")
            cased += len(rows)
        if skips_h2(text):
            skipped += 1
            print(f"-- {path}: sections start at `###` with no `##` above them")
    print()
    print(f"pages={len(pages)}  title-case section headings={cased}  "
          f"pages skipping h2={skipped}  (H1s in Title Case: {titles}, not scored)")


if __name__ == "__main__":
    main()
