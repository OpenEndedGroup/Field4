#!/usr/bin/env python3
"""Rank `##`/`###` sections whose PROSE names test classes, guards and build tasks.

`score_voice.py` scores sentences and headings. It cannot see a section that tells a
reader what the tests are *called* — an inventory nobody outside the checkout can act on —
because every sentence in it is fine. This board sees that one shape, by identifier
density, and nothing else.

Folded in 2026-09-19 at the twenty-eighth firing's request. It found `morpho_examples.md`'s
step 6 (6 identifiers in 116 words, 51.7/1k) on a page every other route scored 0, and
`fieldml_host_bridge.md`'s "Where to look" at 41.4/1k — whose seven identifiers were all
inside `??? aside "Which test holds which claim"`, which is exactly where an inventory of
test classes is supposed to live, and which is why collapsed asides are excluded.

A row is a candidate to **read**. A subject page is allowed to name its own harness:
`building_and_testing.md`'s `## Running the tests` sits on the page about running the tests.

## Two columns were deleted on 2026-09-26, after an audit that ran in three firings

This file used to carry `verification mass` (the fraction of a page's words under a `##`
whose heading names measurement, checking, agreement, provenance or proof) and `tail
verification` (whether the page's last or second-last `##` names verification). The
forty-fifth firing retired the first version of the harness column's backlog; the
forty-ninth (2026-09-22) audited the other two hit by hit and found **0 true out of 26
pages** and **0 true out of 5**, with the regex broken in four separable ways that
narrowing could not fix:

- `check\\w*` matched **`Checkerboard`** — `## Shiny Torii on Checkerboard Planes` put
  `fabricate_renderer.md` at 9.2%. The same family as `grep -i TODO` matching `toDouble`.
- `measur\\w*` was the single biggest source, 10 of the 26, and in documentation it is
  overwhelmingly **a verb the reader performs with the API**: `## Measuring a solid`,
  `## The two quality measures`, `## Height is measured from the content edge`.
- `check`/`test` also matched **instructions to the reader** — `## Headset check`,
  `## Testing the head`, `## Checking it on your own machine` — which is the most
  user-facing shape there is.
- `guard`, `provenance`, `convergence`, `agree` matched **product features**: `## The
  autosave guard`, `## Guards: \\`where { }\\`` (a DSL keyword), `## Convergence and the
  termination cap`, `## Dot product: "how much do these agree?"`.

The forty-ninth firing asked for the whole file to be deleted and was blocked on a README
count word another branch was rewriting. That branch has landed, and the sixty-third
firing (2026-09-26) executed the narrower half instead: the two audited-dead columns and
their regex are gone, the harness column — the one with a record of true hits — stays.

The immediate cause was one more false positive, on this page's own batch:
`reconstruction_plumbing.md`'s `## Make jitter and motion agree` scored it 10.9%, third on
the verification-mass board. That heading is an **instruction**, and it was one of only two
`##` in `public/docs` containing `agree` — the other being the `linear_algebra.md` dot
product already named in the audit above. A column whose top rows are two false positives
and whose every row was audited false costs each firing a read and returns nothing.

Note for whoever wants the file gone entirely: the obstacle is not the README any more, it
is that the harness column has **never** been audited hit by hit. Its four rows today are
`geometry_from_disk.md` 'Where the pictures come from', `typography_examples.md` 'Run it
yourself', `completion_ranking.md` 'Where this lives' and the `building_and_testing.md`
subject page. Read those four and the decision is available.

Usage:
    section_shape.py public/docs [--assets] [--top N]
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import score_voice as sv  # noqa: E402

# Deliberately NOT `\w*Test` bare: `TestKit`, `TestDouble` and the like are API a reader
# uses. The suffix form only, plus the two build words.
HARNESS_IDENT = re.compile(
    r"\b(?:[A-Z]\w*(?:Test|Guard|Contract)|gradlew|jvmTest|commonTest|assert[A-Z]\w*|"
    r"assertThat|assertEquals|assertTrue|assertFalse)\b"
)

FENCE = re.compile(r"^\s*(```|~~~)")
COMMENT_OPEN = re.compile(r"<!--")
COMMENT_CLOSE = re.compile(r"-->")


def harness_sections(text: str, min_words: int = 60):
    """Yield (heading, words, identifier_count) per `##`/`###` section, fences and HTML
    comments excluded.

    Collapsed `??? aside` bodies are excluded too, on the same grounds the scorer counts
    only `!!!`: a collapsed aside costs a reader nothing, and an inventory of test classes
    is what an aside is for.
    """
    out = []
    heading, body, in_fence, in_comment = None, [], False, False
    aside_indent = None

    def flush():
        joined = " ".join(body)
        n = len(joined.split())
        if heading and n >= min_words:
            out.append((heading, n, len(HARNESS_IDENT.findall(joined))))

    for line in text.splitlines():
        if in_comment:
            if COMMENT_CLOSE.search(line):
                in_comment = False
            continue
        if COMMENT_OPEN.search(line) and not COMMENT_CLOSE.search(line):
            in_comment = True
            continue
        if FENCE.match(line):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        if aside_indent is not None:
            if not line.strip():
                continue
            if len(line) - len(line.lstrip()) > aside_indent:
                continue
            aside_indent = None
        if re.match(r"^\s*\?\?\?", line):
            aside_indent = len(line) - len(line.lstrip())
            continue
        m = re.match(r"^#{2,3}\s+(.*?)\s*$", line)
        if m:
            flush()
            heading, body = m.group(1), []
            continue
        body.append(line)
    flush()
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("root")
    ap.add_argument("--assets", action="store_true")
    ap.add_argument("--top", type=int, default=25)
    a = ap.parse_args()

    # Through `score_voice.collect()`, like every other analyser here. This board used to
    # roll its own, and was the only one that ranked GENERATED pages: that is how
    # `field_audio_service_reference.md` ("Generated by scripts/generate-docs.py; do not
    # edit") reached a board whose remedy is to edit the page.
    paths = [Path(r["path"]) for r in sv.collect(Path(a.root), assets=a.assets)]

    dense, pages = [], 0
    for p in paths:
        pages += 1
        text = p.read_text(encoding="utf-8", errors="replace")
        for h, n, k in harness_sections(text):
            if k >= 3:
                dense.append((k * 1000.0 / n, k, n, p, h))

    dense.sort(reverse=True)
    print("HARNESS-IDENTIFIER DENSITY — `##`/`###` sections whose PROSE names test classes,\n"
          "guards and build tasks. Per 1000 words, 3 identifiers minimum.\n")
    print(f"{'per1k':>6}  {'n':>3}  {'words':>5}  page / section")
    for per1k, k, n, p, h in dense[: a.top]:
        print(f"{per1k:6.1f}  {k:3d}  {n:5d}  {p}\n{'':>17}{h!r}")

    print(f"\n{pages} page(s) swept; "
          f"{len(dense)} section(s) over the harness-density floor.")
    return 0


def live_fire():
    """Pin `HARNESS_IDENT` in both directions, on strings from the tree.

    The must-fire cases are the two sections this board was written for and has caught.
    The must-not-fire cases are the reason it is a suffix match and not `\\w*Test`: a
    reader uses `TestKit`, and a page that mentions a guard once in eighty words is not
    an inventory.
    """
    # `morpho_examples.md` step 6 and `fieldml_host_bridge.md` "Where to look", the two
    # recorded true hits, in the shape the identifier regex sees.
    must_fire = [
        "MorphoRoundTripTest",
        "MorphoSurfaceContract",
        "FieldMlHostBridgeTest",
        "./gradlew jvmTest",
        "assertEquals",
        "assertGeometryMatches",
        "NestingEdgeGuard",
    ]
    # Every one of these is API or ordinary prose a reader meets on a docs page.
    must_not_fire = [
        "TestKit",          # API a reader constructs
        "TestDouble",       # ditto
        "Contractible",     # not a `Contract` suffix
        "guard",            # the product feature, lower case
        "testing",          # ordinary English
        "Guardian",         # not a `Guard` suffix
        "gradle",           # the tool, not the wrapper invocation
    ]
    bad = 0
    for s in must_fire:
        if not HARNESS_IDENT.search(s):
            print(f"live-fire FAIL: should fire, silent: {s!r}")
            bad += 1
    for s in must_not_fire:
        if HARNESS_IDENT.search(s):
            print(f"live-fire FAIL: should be silent, fired: {s!r}")
            bad += 1

    # The aside exclusion, which is behaviour rather than a regex and is the half that
    # `fieldml_host_bridge.md` proved was needed. Same body, once bare and once collapsed.
    body = " ".join(["FieldMlHostBridgeTest MorphoRoundTripTest ./gradlew jvmTest"] + ["pad"] * 60)
    bare = f"## Where to look\n\n{body}\n"
    inside = "## Where to look\n\n??? aside \"Which test holds which claim\"\n\n    " + body + "\n"
    if not [1 for _h, _n, k in harness_sections(bare) if k >= 3]:
        print("live-fire FAIL: a bare harness inventory did not reach the floor")
        bad += 1
    if [1 for _h, _n, k in harness_sections(inside) if k >= 3]:
        print("live-fire FAIL: an inventory inside a `??? aside` was scored")
        bad += 1

    if not bad:
        print(f"live fire OK: {len(must_fire)} must-fire, {len(must_not_fire)} must-not-fire, "
              "and the `??? aside` exclusion holds in both directions")
    return 1 if bad else 0


if __name__ == "__main__":
    if "--live-fire" in sys.argv:
        sys.exit(live_fire())
    sys.exit(main())
