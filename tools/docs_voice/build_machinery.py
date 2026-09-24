"""THE DOCS BUILD, EXPLAINED TO A READER WHO DOES NOT HAVE ONE.

Marc's 2026-09-13 complaint about `faust.md` was that "more than half of the Faust docs
is AI leaving notes for itself / showing it's working".  The largest single genus of that
in `public/docs` is **the publishing machinery named in reader-facing prose**: the docs
build, the capture harness, the docs runner, what the screenshot pipeline can and cannot
photograph.  Fifty-six lines across thirty-five pages mention one of those at
`7fc49b2ed`, and the overwhelming majority are fine -- so the family is not the words, it
is *where they sit*.

Three placements make a hit a fault, and this is what the detector scores:

`OPENER`
    a machinery word before the page's first `##`.  A reader who has just arrived has
    asked what the thing is; the build's constraints are not that.  Two of the
    fiftieth firing's six pages were this and nothing else -- `audio_ai_codec.md`'s
    third sentence ("this executable example supplies a deterministic fixture so the
    public documentation does not require a GPU worker") and `field_audio_ai.md`'s
    fourth paragraph, which held the page's only statement of what running it costs,
    phrased as a fact about the docs build.

`HEADING`
    a machinery word in a `##`.  A section of the page given over to the pipeline.

`SECTION`
    two or more machinery lines under one heading that does not itself name the build.
    This is the shape a positional test alone cannot see: `afield_four_graphics.md`'s
    `## Which sections have figures` was a five-bullet taxonomy of why the capture
    harness could not photograph seven lessons -- every reason already stated, at more
    length, in the section it described.

## What it does NOT score, and why each exclusion had to exist

- **A provenance table row.**  `| every figure on this page | executed by the docs build |`
  is the house convention and appears on at least seven pages.  Table lines are skipped.
- **A `??? aside`.**  Collapsed commentary about how the page was made costs a reader
  nothing; the disposal table's own remedy for this family is to demote into one, so a
  detector that then scored the result would be arguing with the fix.  Same exclusion
  `score_voice.py` and `admon_position.py` make.
- **Anything inside a fence.**  `ray_tracing_examples.md` carries the comment
  `# darwin: nothing on this machine can run this, and the docs build cannot stand in
  for` four times, in the code, where it belongs.
- **Pages whose subject IS the pipeline.**  `building_and_testing.md`, `staging.md`,
  `diff-player.md` and `capture_limits.md` are about building the docs; on those, the
  machinery is the thing the reader came for.  An explicit `SUBJECT_PAGES` list rather
  than a hit-count threshold, because a threshold would have exempted
  `afield_four_graphics.md` (5 hits) -- the page this detector was written for.

## What it is worth, measured

Run on the hand-written surface at `7fc49b2ed`, before the fiftieth firing's edits: **3
pages, 3 true**, and all three scored **0** on `score_voice.py`, on `moves.py`, on
`self_narration.py` and on every other board in this directory.  Two further pages
(`fabricate_renderer.md`, `diff-player.md`) were already banked on unlanded branches for
exactly this fault, found by hand, which is the argument that the family recurs.

A hit is a candidate to **read**.  The disposal is almost never deletion: the reader's
half of the fact is usually in there, stated backwards.  `field_audio_ai.md` needed
nothing removed at all, only the sentence turned round to lead with the prerequisite
instead of with what the docs build declines to do.
"""
import pathlib
import re
import sys

MACHINERY = re.compile(
    r"\b(docs build|documentation build|docs runner|docs pipeline|docs capture|"
    r"capture harness|capture pipeline|screenshot harness|publishing machine|"
    r"public documentation)\b",
    re.I,
)

# Pages whose subject is the publishing pipeline.  On these the machinery is the content.
SUBJECT_PAGES = {
    "building_and_testing.md": "the page that tells you how to build the docs",
    "staging.md": "the site's own index of how its examples are made",
    "diff-player.md": "documents the fence that the docs build rewrites",
    "capture_limits.md": "its whole subject is what a capture cannot do",
    "output_panel_sizing.md": "the panel contract as the screenshot API sees it",
}

# A section a reader reaches by asking for it.  `## Which code on this page runs` carries
# a pinned `{ #which-code-on-this-page-runs }` anchor on both pages that have it, so it is
# a link target, not something a reader wades through -- the same argument as the
# provenance table row and the collapsed aside.
PROVENANCE_HEADINGS = re.compile(
    r"^(which code on this page runs|provenance|sources?)\b", re.I
)

# Verified false positives, kept live rather than left as noise.  Re-checked every firing.
KEEPS = {
    ("puppet_browser.md", 14): "a docs build is one of three example users of the "
                               "renderer-free control half -- a capability list, and the "
                               "sentence is about what a reader can do with it",
}


def scan(text):
    """[(kind, line_no, heading, line)] for each scoring hit in one page's markdown."""
    lines = text.split("\n")
    in_fence = False
    in_aside = False
    aside_indent = 0
    heading = None
    # (line_no, heading, line) for every candidate, plus which heading it sat under
    raw = []
    first_heading_line = None
    for n, line in enumerate(lines, 1):
        stripped = line.strip()
        if stripped.startswith("```"):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        if stripped.startswith("???"):
            in_aside = True
            aside_indent = len(line) - len(line.lstrip())
            continue
        if in_aside:
            if not stripped:
                continue
            if len(line) - len(line.lstrip()) > aside_indent:
                continue
            in_aside = False
        if stripped.startswith("## "):
            heading = re.sub(r"\s*\{[^}]*\}\s*$", "", stripped[3:]).strip()
            if first_heading_line is None:
                first_heading_line = n
            if MACHINERY.search(heading):
                raw.append(("HEADING", n, heading, stripped))
            continue
        if stripped.startswith("#"):
            continue
        if stripped.startswith("|"):  # a provenance table row is the house convention
            continue
        m = MACHINERY.search(line)
        if m:
            raw.append(("BODY", n, heading, stripped))

    out = []
    per_heading = {}
    for kind, n, head, line in raw:
        if kind == "HEADING":
            out.append((kind, n, head, line))
            continue
        if first_heading_line is None or n < first_heading_line:
            out.append(("OPENER", n, head, line))
            continue
        per_heading.setdefault(head, []).append((n, line))
    for head, hits in per_heading.items():
        if head and PROVENANCE_HEADINGS.match(head):
            continue
        if len(hits) >= 2:
            for n, line in hits:
                out.append(("SECTION", n, head, line))
    return sorted(out, key=lambda r: r[1])


def main(argv):
    root = pathlib.Path(argv[1] if len(argv) > 1 else "public/docs")
    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
    import score_voice as sv

    print(__doc__.split("\n")[0])
    print()
    total = 0
    fired_keeps = set()
    for row in sv.collect(root):
        path = pathlib.Path(row["path"])
        if path.name in SUBJECT_PAGES:
            continue
        hits = []
        for h in scan(path.read_text(encoding="utf-8")):
            if (path.name, h[1]) in KEEPS:
                fired_keeps.add((path.name, h[1]))
                continue
            hits.append(h)
        if not hits:
            continue
        print(f"  {len(hits):>2}  {path}")
        for kind, n, head, line in hits:
            where = f"under `## {head}`" if head else "before the first heading"
            print(f"        :{n:<5} {kind:<7} {where}")
            print(f"              {line[:104]}")
        total += len(hits)
    print()
    print(f"{total} hit(s). Each is a candidate to READ: the reader's half of the fact")
    print("is usually present and stated backwards. Skipped as subject pages: "
          + ", ".join(sorted(SUBJECT_PAGES)))
    stale = set(KEEPS) - fired_keeps
    if stale:
        # A keep keyed by line number stops matching the moment the line moves, and then
        # it is dead code pretending to be a verified judgement.  Say so out loud.
        print()
        for name, line in sorted(stale):
            print(f"STALE KEEP: {name}:{line} no longer matches a hit -- re-read it and "
                  "re-key or delete the entry")
        return 1
    return 0


# Live fire, so the analyser is checked by `tools/test_docs_voice_detectors.py` without
# any registration.  Every string below is real prose from `public/docs` at `7fc49b2ed`.
MUST_FIRE = [
    # audio_ai_codec.md:6 -- machinery in the page's opening paragraph
    "`CodecVisualization` used for service results; this executable example supplies a\n"
    "deterministic fixture so the public documentation does not require a GPU worker.\n",
    # field_audio_ai.md:18 -- same shape, fourth paragraph, still before the first `##`
    "Field's public docs consume a committed, model-free snapshot of the service"
    " contract. A normal\ndocs build does not initialize the submodule.\n",
    # afield_four_graphics.md -- two machinery lines under one non-machinery heading
    "# A page\n\nAn opener.\n\n## Which sections have figures\n\n"
    "The images on this page are captures: the docs build runs the box.\n"
    "The capture harness drives one box per figure.\n",
]
MUST_NOT_FIRE = [
    # the house provenance table row, on at least seven pages
    "# A page\n\n## Sources\n\n| the code fences above | executed by the docs build |\n",
    # a single body mention under one heading, which is ordinary cross-reference
    "# A page\n\n## Automation\n\n\"automation\" explains how the docs build itself uses"
    " this machinery.\n",
    # inside a fence, where ray_tracing_examples.md carries it four times
    "# A page\n\n## Tracing\n\n```kotlin\n// the docs build cannot stand in for a GPU\n```\n",
    # demoted into a collapsed aside, which is this family's own prescribed disposal
    "# A page\n\n## Figures\n\n??? aside \"How these were taken\"\n\n"
    "    The docs build runs the box against a live renderer.\n"
    "    The capture harness drives one box per figure.\n",
    # a machinery word in the page opener of a SUBJECT page is handled by the caller,
    # but a page with no machinery at all must stay silent
    "# A page\n\nAn opener with no machinery in it.\n\n## A section\n\nProse.\n",
]


def live_fire():
    """0 when every case above behaves. The name is the contract `tools/
    test_docs_voice_detectors.py` looks for -- it globs this directory and calls
    `live_fire()` on whatever defines it, so a detector that spells it `_live_fire`
    is silently ungated."""
    bad = []
    for text in MUST_FIRE:
        if not scan(text):
            bad.append(("MUST_FIRE missed", text[:60]))
    for text in MUST_NOT_FIRE:
        hits = scan(text)
        if hits:
            bad.append((f"MUST_NOT_FIRE fired {hits}", text[:60]))
    if bad:
        for why, what in bad:
            print(f"LIVE FIRE FAILED: {why}: {what!r}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    if live_fire():
        sys.exit(1)
    print(f"live fire OK: {len(MUST_FIRE)} must-fire, {len(MUST_NOT_FIRE)} must-not-fire,"
          " all correct")
    sys.exit(main(sys.argv))
