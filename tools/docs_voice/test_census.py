#!/usr/bin/env python3
"""
`test-census` -- a user page inventorying the tests that stand behind it.

THE FAULT.  Marc, 2026-09-13, on the Faust docs: "More than half of the Faust docs is AI
leaving notes for itself / showing it's working; move to internals if it isn't going to help
a user use things."  The single most common shape that complaint lands on is a table whose
first column is an internal test identifier -- a `*Test` JVM class, or a dotted `FIELD_TEST`
id -- with a second column explaining what that test asserts.  A reader who came to make a
sound does not run `ServerSurfaceLedgerTest`; they cannot, without the repository.  The
disposal table sends a census to `dev_docs/<SUBJECT>_MEASUREMENTS.md`, cited from the page as
a backticked path with no link.

WHY NO EXISTING DETECTOR SEES IT.  `score_voice`'s `meas` counts measurement *table rows* --
rows carrying numbers.  A test census carries no numbers at all, so it scores exactly 0, and
all five commanded boards were green on `faust.md`, `pat_spatial.md` and `server_surface.md`
on the night this was written.  `split_prose` routes `|` lines into `table`, which only
`table_cells.py` reads and only for a comma-clause on column *headers*.  The surface was
unreachable, not clean.

THE DISCRIMINATOR, AND IT IS THE WHOLE VALUE OF THE FILE.  A test census is not always a
fault.  The house has a correct form for it and several pages already use it:

  - inside a collapsed `??? aside` -- `morpho_examples.md`'s "Which harness holds what",
    `dataframe.md`'s "The test behind those two numbers", the four `??? aside "Pinned by"`
    blocks on `server_surface.md`.  A collapsed aside costs a reader nothing, which is the
    same reason `score_voice`'s `admon-oversized` only counts `!!!`.
  - on a page that is itself internals -- `building_and_testing.md` is *about* running the
    tests.  Named in PAGE_EXEMPT below.

So the fault is specifically: **an always-open, top-level section of a user page whose body
is a test inventory.**  That is what this scores.  A census in an aside scores 0 and is
reported separately under `--all`, so a firing can see the keeps it is being spared.

TRUE/FALSE, measured tree-wide 2026-09-21 (fortieth firing), 137 hand-written pages:
  4 true  -- faust.md (6), server_surface.md (6), pat_spatial.md (6), keyframer_examples.md (1)
  0 false, AFTER the runnable-section rule below. Before it: 1 (onnx.md)
  2 correctly-scored-zero keeps, both live -- morpho_examples.md's `??? aside` and onnx.md's
    "Checking it on your own machine"

Both discriminators fire on a real page, which is the point: a rule that only ever passes is
a rule nobody has tested.  `keyframer_examples.md` is the weakest true and is recorded as
such -- one sentence, correctly citing `dev_docs/KEYFRAMER_COVERAGE.md`, whose only fault is
that it sits under a `## Where to go next` navigation heading.

The identifier patterns were checked against the whole tree before being committed to.  The
dotted form is deliberately narrow: it needs THREE dotted segments, because two matches
ordinary API prose (`os.sawtooth`, `fi.lowpass`) and hyphenated cheatsheet vocabulary.
Requiring three, plus a known root, took the row count on `rectsl_cheatsheet.md` and
`stacksl_cheatsheet.md` from 34 to 0 -- those were the whole of this detector's false board
on its first draft.
"""

import re
import sys
import pathlib

# A FIELD_TEST id is `<root>.<something>.<something>`. Rooted, and the roots are OBSERVED in
# the tree rather than invented: a three-segment dotted id in a table's first column occurs
# exactly 15 times across `public/docs`, on three pages, and two of those shapes are not tests
# at all -- `analysis.muscriptor.{small,medium,large}` are model sizes on
# `audio_ai_muscriptor.md`, and `launch.room.ring.fill` is a field path on `pat_spatial.md`.
# An unrooted pattern takes both as faults. The catalog itself lives at
# `build/field-tests/catalog.json`, which a build-free firing does not have, so this list will
# go stale as new roots appear. That is the intended direction: a missing root under-targets
# by one page, where a wrong one puts a firing on a page with nothing wrong with it.
TEST_ROOTS = ("synth", "faust", "onnx", "docs", "graphics", "stdlib", "examples")
DOTTED = re.compile(
    r"^\|\s*`(?:" + "|".join(TEST_ROOTS) + r")(?:[.-][a-z0-9-]+){2,}`"
)
# A JVM test class, in the first column of a table.
CLASSY = re.compile(r"^\|\s*`[A-Z][A-Za-z0-9]*(?:Guard|Contract|Smoke|Security|Ledger|Route)?[A-Za-z0-9]*Test`")
# "Five tests cover this", "the three tests that stand behind it", "39 tests between them".
PROSE = re.compile(
    r"\b(?:two|three|four|five|six|seven|eight|nine|ten|\d+)\s+tests?\s+"
    r"(?:cover|assert|pin|stand|back|guard|between)\b",
    re.I,
)
# A run of test classes listed inline -- "The checks are `ATest`, `BTest` and `CTest`".
# Two or more on one line, because one is an ordinary citation ("pinned by `FooTest`") and
# is how a page correctly points at its guard in passing.
INLINE_RUN = re.compile(r"(?:`[A-Z][A-Za-z0-9]*Test`[,\s and]+){1,}`[A-Z][A-Za-z0-9]*Test`")

# Pages that are *about* the build and the tests. A census is the subject here, not a
# digression from it.
PAGE_EXEMPT = {"building_and_testing.md", "field_audio_service_reference.md"}


def scan(path):
    """Yield (heading_line, heading, n_rows, collapsed, prose_hits) per census found."""
    text = path.read_text(errors="replace")
    if "DO NOT EDIT" in text[:400].upper() or "DO NOT HAND-EDIT" in text[:400].upper():
        return
    lines = text.splitlines()
    in_fence = False
    head = (0, "(page top)")
    # An admonition body is indented 4 spaces under its marker; `???` is collapsed.
    collapsed_until = -1
    found = {}
    runnable = set()
    for i, line in enumerate(lines, 1):
        if line.startswith("```"):
            in_fence = not in_fence
            continue
        if in_fence:
            # A section that hands the reader a command to run the tests THEMSELVES is not
            # internals -- it is the one thing a test id is genuinely useful to a user for.
            # `onnx.md`'s "Checking it on your own machine" was this detector's only false
            # positive before the rule, and it is a model of the genre: two smoke ids, what
            # each defends, and a copy-pasteable `run_field_tests.py` line.
            if "run_field_tests.py" in line or "./gradlew" in line:
                runnable.add(head)
            continue
        m = re.match(r"^(#{2,4})\s+(.*)", line)
        if m:
            head = (i, m.group(2).strip())
            collapsed_until = -1
            continue
        if re.match(r"^\?\?\?", line):
            collapsed_until = i
            continue
        if re.match(r"^!!!", line):
            collapsed_until = -1
            continue
        indented = line.startswith("    ") or line.startswith("\t")
        inside_aside = collapsed_until > 0 and (indented or not line.strip())
        body = line.lstrip() if indented else line
        tabular = DOTTED.match(body) or CLASSY.match(body)
        summary = PROSE.search(body) or INLINE_RUN.search(body)
        if not (tabular or summary):
            continue
        rec = found.setdefault(head, {"rows": 0, "summary": False, "collapsed": True})
        rec["rows"] += 1
        rec["summary"] = rec["summary"] or bool(summary)
        if not inside_aside:
            rec["collapsed"] = False
    for (ln, h), rec in found.items():
        # Three or more inventory rows is a table census; a "N tests cover this" summary
        # sentence is one on its own, however short the list it introduces.
        if rec["rows"] >= 3 or rec["summary"]:
            yield ln, h, rec["rows"], rec["collapsed"] or (ln, h) in runnable


def main():
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "public/docs")
    show_all = "--all" in sys.argv
    faults, keeps = [], []
    for p in sorted(root.rglob("*.md")):
        if "/assets/" in str(p):
            continue
        for ln, h, rows, collapsed in scan(p):
            rel = str(p).replace("public/docs/", "")
            row = (rel, ln, h, rows)
            if rel in PAGE_EXEMPT or collapsed:
                why = "exempt page" if rel in PAGE_EXEMPT else "accepted form"
                keeps.append(row + (why,))
            else:
                faults.append(row)
    print("test-census -- always-open test inventories on user pages\n")
    for rel, ln, h, rows in faults:
        print(f"  {rel}:{ln}  ({rows} rows)  {h}")
    print(f"\n{len(faults)} fault(s); {len(keeps)} census(es) in an accepted form.")
    if show_all:
        print("\nAccepted, scored 0:")
        for rel, ln, h, rows, why in keeps:
            print(f"  {rel}:{ln}  ({rows} rows)  {h}   [{why}]")


if __name__ == "__main__":
    main()
