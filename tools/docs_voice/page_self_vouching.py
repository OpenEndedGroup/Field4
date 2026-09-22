#!/usr/bin/env python3
"""A page vouching for *itself* -- "every number here is asserted", "if a picture here is
wrong the build says so".

Marc, 2026-09-13, on the Faust page:

> More than half of the Faust docs is AI leaving notes for itself / showing it's working;
> move to internals if it isn't going to help a user use things.

This is the narrowest, most reliably-wrong member of that family, and it is invisible to
every other board: the sentences are short, carry no numbers, sit in no admonition, and
score 0 on `score_voice.py`. The forty-third firing found one by hand on
`fabricate_renderer.md` and swept the family with a grep; the forty-fourth built that grep
into this, because the sweep turned up a claim that was **false**.

## The distinction this draws, and it is the whole detector

A claim about the CODE, cited, is documentation doing its job:

    Both clips are asserted by `synth.faust.poly.sound`.          <- keep
    `TetgenTest.anOpenSurfaceIsRefused...` guards that the         <- keep
      process survives.
    Nothing checks them. A `size_t` declared as an `int` ...       <- keep (a limitation)

A claim about THIS PAGE is the page arguing for its own trustworthiness, which is a thing
no reader asked for and no reader can act on:

    A guard holds those numbers to the code ...                    <- fire
    Every number quoted on this page is asserted as an exact       <- fire
      value in ...
    the smoke asserts this page still contains both blocks         <- fire
      verbatim ...
    Every example below is also a `FIELD_TEST`: if a picture here   <- fire
      is wrong, the build says so.

So a hit needs **both** a guard verb and a self-reference to the page's own prose,
figures, listings or tables, in one sentence.

## What it was worth, measured

Run bare over the hand-written surface at `bec29b99d`, the guard-verb half alone gave 24
hits across 20 pages and only 4 of them were faults. With the self-reference half it gives
**4 hits, 4 true** -- `sound_paths.md`, `geometry_from_disk.md`, `document_on_disk.md` and
`fabricate_renderer.md` (the last already fixed on an unlanded branch). Marc's four pages:
**0**.

The `sound_paths.md` hit is the argument for keeping this in the repo rather than as a
one-night grep. "A guard holds those numbers to the code" was **not true**:
`SoundPathMapGuardTest` holds a Kotlin constant to a Kotlin list and holds the page's
figure captions to the page, and nothing anywhere compared the bold prose numerals to
anything. A page that vouches for itself is a page whose claim nobody checked, because
checking it is the one thing the sentence asserts is unnecessary.

## Reading a hit

Do not fix one unseen. Ask what the sentence would have a reader *do*. If the answer is
"trust the rest of the page", delete it. If the material is genuinely load-bearing --
`geometry_from_disk.md`'s ToyCar binary really does decide the counts -- keep the fact and
drop the vouching. A citation to the producer, in house style, is never the fault.

One implementation note, because it has cost this task a night twice:
`public/docs` is hard-wrapped at about 100 columns, so a line-at-a-time scan splits most
of these sentences in half and finds nothing. This joins each paragraph before matching.
See `self_narration.py`, which scans by line and is right to -- its tells are short.
"""
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import score_voice as sv  # noqa: E402

# A claim that something is mechanically checked.
GUARD = re.compile(
    r"\b(?:"
    r"fails? the build|the build (?:says|catches|will say)|"
    r"a guard holds|holds? (?:those|these|the) numbers|"
    r"guards? it\b|keeps it that way|"
    r"(?:smoke|test|guard|harness) asserts|"
    r"(?:is|are) asserted|asserted as|"
    r"CI (?:makes|checks)"
    r")",
    re.I,
)

# ... about the page's own prose, figures, listings or tables.
SELF = re.compile(
    r"\b(?:"
    r"this page|these docs|on this page|"
    r"every number (?:quoted |stated |)(?:here|above|below)|"
    r"every (?:example|figure|picture|entry|clip|listing) (?:here|above|below)|"
    r"all \w+ (?:figures|examples|numbers|entries) (?:here|above|below)|"
    r"(?:a|the) (?:picture|number|figure|listing) (?:here|above|below)|"
    r"those numbers|both blocks|the tables below|the tables above"
    r")",
    re.I,
)

SENTENCE = re.compile(r"(?<=[.!?])\s+")


def paragraphs(text):
    """[(first_line_no, joined_text)] for each prose paragraph, fences removed.

    Joining is the point: these pages wrap at ~100 columns and every sentence this
    detector looks for is longer than that.
    """
    out, buf, start, in_fence = [], [], 0, False
    for i, line in enumerate(text.split("\n")):
        if line.lstrip().startswith("```"):
            in_fence = not in_fence
            if buf:
                out.append((start, " ".join(buf)))
                buf = []
            continue
        if in_fence:
            continue
        if not line.strip():
            if buf:
                out.append((start, " ".join(buf)))
                buf = []
            continue
        if not buf:
            start = i + 1
        buf.append(line.strip())
    if buf:
        out.append((start, " ".join(buf)))
    return out


def scan(text):
    """[(line_no, sentence)] for every sentence that vouches for the page it is on."""
    found = []
    for lineno, para in paragraphs(text):
        for sentence in SENTENCE.split(para):
            if GUARD.search(sentence) and SELF.search(sentence):
                found.append((lineno, " ".join(sentence.split())))
    return found


MUST_FIRE = [
    "A guard holds those numbers to the code: `SoundPathMap.SOURCE_COUNT` is `8`, and a "
    "path that arrives in the tree and not in the tables below fails the build.",
    "Every number quoted on this page is asserted as an exact value in `MeshFromDiskTest.kt`.",
    "the smoke asserts this page still contains both blocks verbatim, so a change fails a test.",
    "Every example below is also a `FIELD_TEST`: if a picture here is wrong, the build says so.",
]

MUST_NOT_FIRE = [
    # A claim about the code, cited. The house convention, and the commonest shape.
    "Both clips are asserted by `synth.faust.poly.sound`, and the peak counts too.",
    "`TetgenTest.anOpenSurfaceIsRefusedWithoutTakingTheProcessDown` guards that the "
    "process survives.",
    # A stated limitation. The task file says keep these.
    "Nothing checks them. A `size_t` declared as an `int` is undefined behaviour.",
    # A user-facing consequence that happens to mention the build.
    "A name that does not resolve to a member of the annotated class fails the build.",
    # Self-reference with no guard claim: ordinary signposting.
    "Every example below is a live box you can edit.",
    # A guard claim about a sibling page, which is a cross-reference, not vouching.
    "`mesh_examples.md` carries the same caveat.",
]


def live_fire():
    """Pin all four directions: fires on vouching, silent on citation, limitation, signposting."""
    bad = 0
    for sentence in MUST_FIRE:
        if not scan(sentence):
            print(f"MISSED {sentence[:70]}")
            bad += 1
    for sentence in MUST_NOT_FIRE:
        got = scan(sentence)
        if got:
            print(f"FALSE  {sentence[:70]} <- {got[0][1][:50]}")
            bad += 1
    if bad:
        print(f"\nLIVE FIRE FAILED: {bad} case(s). Fix the detector before trusting a board.")
        return 1
    print(
        f"live fire OK: {len(MUST_FIRE)} must-fire, {len(MUST_NOT_FIRE)} must-not-fire, "
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

    generated = "--generated" in args
    assets = "--assets" in args
    positional = [a for a in args if not a.startswith("--")]
    root = pathlib.Path(positional[0] if positional else "public/docs")

    rows = []
    scanned = 0
    for record in sv.collect(root, generated=generated, assets=assets):
        path = pathlib.Path(record["path"])
        if not path.exists():
            continue
        scanned += 1
        found = scan(path.read_text(errors="replace"))
        if found:
            rows.append((len(found), path, found))

    rows.sort(key=lambda r: -r[0])
    print("A page vouching for itself. Every row is a candidate to READ; the fix is to")
    print("keep the fact and drop the vouching, never to delete a cited claim about code.")
    print()
    for count, path, found in rows:
        print(f"{count:3}  {path}")
        for lineno, sentence in found:
            print(f"          :{lineno}  {sentence[:96]}")
    print(f"\npages={scanned} nonzero={len(rows)} sum={sum(r[0] for r in rows)}")


if __name__ == "__main__":
    main()
