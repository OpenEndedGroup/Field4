#!/usr/bin/env python3
"""A reference TABLE whose every cell has grown a second sentence of generic advice.

Marc, 2026-09-13:

> There's a lot of measuring here of things I don't really care about -- I'd much rather
> just read about the cool language and how to use it.

The shape here is the same complaint one level down. A cheatsheet row is a lookup: a
reader's eye lands on it, takes the fact, and leaves. When every row gains a trailing
sentence of advice that restates the first in the imperative, the table stops being
scannable and the page gets longer without getting more informative:

    | `(box)` | the `Box` this code is running in |                          <- before
    | `(box)` | Returns the `Box` currently executing the code. Use it for   <- after
                Field operations that need an explicit box receiver. |

No existing board sees this. `score_voice.py` routes a `|` line into `table` and only
counts measurement rows; `table_cells.py` reaches the cells but matches the same lexical
tells as the prose scorer, and none of them is present here -- the sentences are bland,
carry no numbers, sit in no admonition and score **0**. `shape_census.py` sees repeated
sentence openings, but a markdown table row starts with `div class field` or a code span
far more often than with a word, so table bloat drowns in its own noise.

## Why a per-TABLE ratio and not a per-cell tell

The imperative tail is not the fault. Censused over the whole tree (2026-09-24,
`public/docs` plus `reference/`), a bare "second sentence opens with Use/Call/Set/Pass/
Prefer/Read" regex gives **107 hits and nearly all of them are good documentation** --
"Call once per loss evaluation", "Set to 0 to compute accelerations without moving
anything", "Prefer this to `hypot` when you only need to compare". A reference table is
*supposed* to tell you when to call a thing.

What distinguishes the fault is **mechanical repetition inside one table**. So a hit needs
both:

  - at least 60% of that table's substantive cells in its widest column carry two or more
    sentences, and
  - at least 40% of those trailing sentences open with the advisory / restating vocabulary.

A table has to be at least six substantive cells before either ratio means anything.

## What it was worth, measured

At `d72363a90` the density half alone flagged **8 tables** on the committed tree and every
one is a keep -- `geometry_from_disk.md`'s six-row symptom/cause/what-to-do matrix and
`sound_paths.md`'s eight-path inventory are dense because each cell carries a different
fact. With the advisory half as well: **0 on the whole committed tree, both surfaces.**

The same run over the *working tree* of `/Users/marc/field4`, which held a sibling
session's uncommitted rewrite of about fifty pages' tables, gave **8 tables** across
`clojure.md`, `faust.md`, `rectsl_cheatsheet.md` x2, `petri_cheatsheet.md`,
`three_output.md`, `fieldml_host_bridge.md` and `sound.md` -- and the density half alone
went 8 -> 80. So the board is at zero on what has landed and fires on exactly the shape
that is arriving. That is the `exercis\w*` precedent in `section_shape.py`: a board added
for the next one, not to score a backlog.

## Reading a hit

Do not flatten a table because a board named it. Ask of each trailing sentence: does it
add a fact, or does it re-say the first sentence with a verb in front? Keep the first,
delete the second. A table where the tails genuinely differ -- a parameter table whose
second sentences give ranges, defaults and interactions -- is a keep, and should be
recorded here as one.
"""
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import score_voice as sv  # noqa: E402

SEPARATOR = re.compile(r"^\|[\s:|-]+\|?\s*$")
SENTENCE = re.compile(r"(?<=[.!?])\s+")

# The advisory / restating openers. Narrower than the censused 107-hit set on purpose:
# `Call`, `Set` and `Read` are dropped because they name a real API obligation far more
# often than they pad ("Call after each growth step", "Set to 0 to compute accelerations
# without moving anything", "Read the caveat on `pcaWhiten` before trusting any of the
# four"). What is left either hands the reader back their own verb or opens a sentence
# whose subject is the previous sentence's object.
TAIL = re.compile(
    r"^(?:"
    r"Use|Pass|Supply|Provide|Specify|Choose|Give|Apply|Combine|Prefer|Treat|Wrap|Include"
    r"|The returned|The resulting"
    r"|This (?:is|makes|lets|allows|gives|keeps|means)"
    r")\b"
)

MIN_CELLS = 6           # a table smaller than this has no ratio worth reading
MIN_CELL_WORDS = 4      # a value, not prose
MULTI_FRACTION = 0.60   # of the column's cells, how many carry a second sentence
ADVICE_FRACTION = 0.40  # of those, how many end in the advisory vocabulary


def cells(row):
    """Split a markdown table row into its cells. Same rule as `table_cells.py`."""
    body = row.strip()
    if body.startswith("|"):
        body = body[1:]
    if body.endswith("|"):
        body = body[:-1]
    return [c.strip() for c in re.split(r"(?<!\\)\|", body)]


def plain(cell):
    """Blank code spans and keep link text, so a `[a](b)` is worth its words and not its URL."""
    text = re.sub(r"`[^`]+`", "§", cell)
    return re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)


def sentences(text):
    return [s for s in SENTENCE.split(text) if s.strip()]


def tables(text):
    """[(first_line_no, [row_cells, ...])] for each markdown table, fences skipped.

    Fence tracking comes first, because a `diffstatic` body can contain a line that starts
    with `|` -- a Kotlin `when` arm or a shader swizzle will do it.
    """
    out, buf, start, in_fence = [], [], 0, False
    for i, line in enumerate(text.split("\n"), 1):
        if line.lstrip().startswith("```"):
            in_fence = not in_fence
            if buf:
                out.append((start, buf))
                buf = []
            continue
        if in_fence:
            continue
        stripped = line.strip()
        if stripped.startswith("|"):
            if SEPARATOR.match(stripped):
                continue
            if not buf:
                start = i
            buf.append(cells(line))
        elif buf:
            out.append((start, buf))
            buf = []
    if buf:
        out.append((start, buf))
    return out


def widest_column(rows):
    """The index of the column carrying the most prose. That is the one that bloats."""
    width = max(len(r) for r in rows)
    best, best_mean = None, 0.0
    for i in range(width):
        lengths = [len(plain(r[i]).split()) for r in rows if i < len(r)]
        mean = sum(lengths) / len(lengths) if lengths else 0.0
        if mean > best_mean:
            best_mean, best = mean, i
    return best


def scan(text):
    """[(line_no, n_advice, n_multi, n_cells, example)] for every bloated table."""
    found = []
    for lineno, rows in tables(text):
        index = widest_column(rows)
        if index is None:
            continue
        column = [plain(r[index]) for r in rows if index < len(r)]
        column = [c for c in column if len(c.split()) >= MIN_CELL_WORDS]
        if len(column) < MIN_CELLS:
            continue
        multi = [c for c in column if len(sentences(c)) >= 2]
        if len(multi) / len(column) < MULTI_FRACTION:
            continue
        advice = [c for c in multi if TAIL.match(sentences(c)[-1])]
        if not multi or len(advice) / len(multi) < ADVICE_FRACTION:
            continue
        found.append((lineno, len(advice), len(multi), len(column), advice[0]))
    return found


MUST_FIRE = [
    # The shape itself: eight rows, every one a fact followed by the same fact as advice.
    "| form | what it does |\n"
    "| --- | --- |\n"
    "| `(box)` | Returns the `Box` currently executing the code. Use it for Field "
    "operations that need an explicit box receiver. |\n"
    "| `(display v)` | Renders a value in a box output panel. Supply a channel name to "
    "update that output independently. |\n"
    "| `(lines ls)` | Draws the supplied `FLine` collection in a 700 x 350 output. Pass "
    "the collection you want drawn. |\n"
    "| `(polyline pts)` | Builds an `FLine` through the points in sequence. Pass the "
    "resulting line to `lines` to draw it. |\n"
    "| `(color line r g b a)` | Sets line colour and returns the line. The returned value "
    "makes these helpers compose with `->`. |\n"
    "| `(animate f)` | Builds a per-frame animation around `f`. Use it when the box should "
    "keep running after the first evaluation. |\n"
    "| `(frame)` | Reads the current frame number inside an animation. This is the counter "
    "the animation body is driven by. |\n"
    "| `(prop name)` | Reads a property on the current box. Use the same property name to "
    "exchange values with other Field code. |\n",
    # BOUNDARY, and the reason this list has two entries. Exactly 6 multi-sentence cells of
    # 10 (MULTI_FRACTION to three decimals) and exactly 3 advisory of those 6 (comfortably
    # over ADVICE_FRACTION, but one word away from under it). Loosening either constant, or
    # dropping `Use`, `The returned` or `This is` from TAIL, makes this case stop firing.
    # The first fixture alone pins none of that: it is 8 of 8 and 5 of 8, so it survives
    # ADVICE_FRACTION = 0.95 and survives deleting `Use` -- both injected and confirmed
    # silent on 2026-09-24 before this case existed.
    "| op | what it does |\n"
    "| --- | --- |\n"
    "| `a` | Rotates the stack by one position. Use it before a binary operator. |\n"
    "| `b` | Duplicates the top value. The returned copy sits above the original. |\n"
    "| `c` | Drops the top value from the stack. This is the inverse of `b`. |\n"
    "| `d` | Adds the top two values. The result replaces both of them. |\n"
    "| `e` | Multiplies the top two values. Overflow wraps rather than trapping. |\n"
    "| `f` | Compares the top two values. A false comparison pushes zero. |\n"
    "| `g` | pushes the current frame number |\n"
    "| `h` | pushes the wall clock in seconds |\n"
    "| `i` | pushes a uniform random in zero to one |\n"
    "| `j` | pushes the number of values on the stack |\n",
]

MUST_NOT_FIRE = [
    # A dense troubleshooting matrix: multi-sentence throughout, every tail a new fact.
    # `geometry_from_disk.md:429`, 6/6 multi and 0 advisory. The reason the density half
    # alone is not the detector.
    "| symptom | cause | what to do |\n"
    "| --- | --- | --- |\n"
    "| a blank black panel | an empty mesh | A file whose contents are not what its "
    "extension says parses to `TriMesh.empty` with no exception at all. "
    "`println(mesh.summary())` prints exactly `TriMesh(empty)`. |\n"
    "| holes, or whole faces missing | inside-out winding | Back faces are culled exactly "
    "as a GPU culls them, so a wrongly-wound patch is not dark, it is gone. "
    "`fixWinding()`, then check `topology().isConsistentlyOriented`. |\n"
    "| every measurement negative | inside-out closed solid | The silhouette is unchanged "
    "and both renderers flip inward normals. `signedVolume() < 0` is the only tell. |\n"
    "| flat or faceted shading | no normals | A mesh with none is crease-shaded for you; "
    "one with wrong ones is not. Recompute with `shadedNormals(35.0)`. |\n"
    "| `isWatertight()` false | it came through STL | Or it was `flatShaded()` before it "
    "was written. `weld(1e-5)` recovers the topology. |\n"
    "| the vertex count is exactly 3x | STL, or `flatShaded()` | Both un-weld every "
    "triangle, and the reader on the other side will not weld it back. `weld` first. |\n",
    # A terse lookup table. The form this board exists to protect.
    "| form | what it does |\n"
    "| --- | --- |\n"
    "| `(box)` | the `Box` this code is running in |\n"
    "| `(display v)` | show a value in an output panel |\n"
    "| `(lines ls)` | draw `FLine`s into an output channel |\n"
    "| `(polyline pts)` | an `FLine` through a sequence of points |\n"
    "| `(color line r g b a)` | style a line, returning it, so `->` threads |\n"
    "| `(animate f)` | a per-frame animation; return it to launch it |\n"
    "| `(frame)` | the frame number inside an animation |\n",
    # Under MIN_CELLS. Three bloated rows are an author's habit, not a table's shape.
    "| form | what it does |\n"
    "| --- | --- |\n"
    "| `(box)` | Returns the `Box` currently executing. Use it for operations that need "
    "an explicit box receiver. |\n"
    "| `(display v)` | Renders a value in an output panel. Supply a channel name to "
    "update that output independently. |\n",
    # BOUNDARY, just under MULTI_FRACTION: 5 multi-sentence cells of 10. Tightening
    # MULTI_FRACTION is safe; loosening it below 0.5 fails here.
    "| op | what it does |\n"
    "| --- | --- |\n"
    "| `a` | Rotates the stack by one position. Use it before a binary operator. |\n"
    "| `b` | Duplicates the top value. The returned copy sits above the original. |\n"
    "| `c` | Drops the top value from the stack. This is the inverse of `b`. |\n"
    "| `d` | Adds the top two values. The result replaces both of them. |\n"
    "| `e` | Multiplies the top two values. Overflow wraps rather than trapping. |\n"
    "| `f` | compares the top two values, pushing zero when false |\n"
    "| `g` | pushes the current frame number |\n"
    "| `h` | pushes the wall clock in seconds |\n"
    "| `i` | pushes a uniform random in zero to one |\n"
    "| `j` | pushes the number of values on the stack |\n",
    # BOUNDARY, just under ADVICE_FRACTION: 6 multi-sentence cells of 10, only 2 advisory.
    # This is the dense-but-informative parameter table in miniature.
    "| op | what it does |\n"
    "| --- | --- |\n"
    "| `a` | Rotates the stack by one position. Use it before a binary operator. |\n"
    "| `b` | Duplicates the top value. The returned copy sits above the original. |\n"
    "| `c` | Drops the top value from the stack. Nothing is pushed in its place. |\n"
    "| `d` | Adds the top two values. The result replaces both of them. |\n"
    "| `e` | Multiplies the top two values. Overflow wraps rather than trapping. |\n"
    "| `f` | Compares the top two values. A false comparison pushes zero. |\n"
    "| `g` | pushes the current frame number |\n"
    "| `h` | pushes the wall clock in seconds |\n"
    "| `i` | pushes a uniform random in zero to one |\n"
    "| `j` | pushes the number of values on the stack |\n",
    # A table inside a fence is code, not prose.
    "```diffstatic {data-lang=kotlin}\n"
    "| form | what it does |\n"
    "| --- | --- |\n"
    "| `(box)` | Returns the `Box` currently executing the code. Use it for Field "
    "operations that need an explicit box receiver. |\n"
    "| `(display v)` | Renders a value in an output panel. Supply a channel name to "
    "update that output independently. |\n"
    "| `(lines ls)` | Draws the collection in a 700 x 350 output. Pass the collection you "
    "want drawn. |\n"
    "| `(polyline pts)` | Builds an `FLine` through the points. Pass the resulting line "
    "to `lines` to draw it. |\n"
    "| `(color l r g b a)` | Sets line colour and returns it. The returned value makes "
    "these helpers compose. |\n"
    "| `(animate f)` | Builds a per-frame animation. Use it when the box should keep "
    "running. |\n"
    "| `(frame)` | Reads the frame number. This is the counter the body is driven by. |\n"
    "```\n",
]


def live_fire():
    """Pin the four directions: the shape fires; density alone, terseness and fences do not."""
    bad = 0
    for case in MUST_FIRE:
        if not scan(case):
            print(f"MISSED {case.splitlines()[0][:70]}")
            bad += 1
    for case in MUST_NOT_FIRE:
        got = scan(case)
        if got:
            print(f"FALSE  {case.splitlines()[0][:60]} <- {got[0][4][:60]}")
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
        for hit in scan(path.read_text(errors="replace")):
            rows.append((hit[1] / hit[2], path, hit))

    rows.sort(key=lambda r: -r[0])
    print("A reference table whose cells have each grown a second sentence of advice.")
    print("Every row is a candidate to READ: keep a tail that adds a fact, delete one that")
    print("re-says the first sentence with a verb in front.")
    print()
    for fraction, path, (lineno, advice, multi, total, example) in rows:
        print(
            f"{fraction:5.0%} advisory  {advice}/{multi} multi-sentence of {total} cells  "
            f"{path}:{lineno}"
        )
        print(f"          {' '.join(example.split())[:96]}")
    print(f"\npages={scanned} tables={len(rows)}")


if __name__ == "__main__":
    main()
