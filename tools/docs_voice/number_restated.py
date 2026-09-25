#!/usr/bin/env python3
"""One measured number, written into a page's prose twice, with nothing keeping the two in step.

The craft rule this serves is the task file's own: *prefer the formulation with nothing to rot*.
A figure stated once rots when the code changes and a guard catches it, or it does not and a
reader is misled once. A figure stated **twice**, far apart, in two different sentences, rots
half at a time: an edit fixes the occurrence the editor was looking at and leaves the other,
and now the page disagrees with itself. That is not a hypothetical. The fifty-eighth firing
(2026-09-25) found three of them in one night, and only one had a number attached to a guard.

## What it reports

A number that

  - carries **three or more significant digits** (`206`, `1.05`, `0.262`, `66,489`, `0.8475`). Two
    are parameters, counts of things on the page, and ordinary English. Trailing zeros of an
    integer do not count, so `400` and `6000` are below the floor — deliberately conservative,
    because a round number is usually a setting rather than a measurement;
  - is **not** an exact power of two from 64 to 1,048,576 — buffer sizes, block lengths, texture
    dimensions and model widths recur across a page as a matter of course;
  - is **not** a four-digit year;

and that appears on **two or more different lines at least 30 apart**, in prose. Fences are
blanked line-by-line rather than removed, so the line numbers are the file's. Image alt text and
inline code spans are blanked too: a number inside `` `1e-8` `` is a literal a reader copies, and
alt text is a caption of a committed asset rather than a claim the prose is making.

## What it is worth, measured

Fifteen pages and thirty-five rows on the hand-written surface at `a88f62b96`; fourteen and
thirty-three on the branch that added this file, which is three of tonight's fixes showing up.
**Zero on Marc's four target-voice pages** at both ends, which is the property that makes it worth
running at all. Seven rows were read one at a time on 2026-09-25: **3 true, 4 keeps.**

True:

- `faust.md` — `2667` (the µs budget for a 128-frame block) in two different cost sections, one of
  them a measurement block mid-how-to. The page then made two throughput claims against it, 158x
  realtime and 44x, and reconciled neither. Cutting the second section fixed both faults at once.
- `chain_skinned_volume.md` — `206` vertices and `408` triangles, stated where the fixture is built
  and again 110 lines later inside an admonition explaining one row of a table.
- `completion_ranking.md` — and this one is the lesson about how to use a row. The numeral the
  board named, `249`, is stated twice and **agrees with itself** both times. Reading the passage it
  pointed at found `254` for the same caret in the neighbouring section, measured on a different
  occasion, with nothing on the page reconciling them. **A row names a passage, not a defect**; the
  defect was two lines away from the numeral that raised it.

Keeps, and the shapes to recognise:

- **A running example.** `tetgen.md`'s `66,489` is the element count of the bracket the whole page
  meshes; it recurs because the bracket recurs. Nothing to fix.
- **A deliberate diagnostic restatement.** `frame_budget.md` quotes `63.9` ms in `3,002.6` ms once
  where it measures the recording and again in a troubleshooting bullet about summed lane time.
  The second is where a confused reader looks, and one of the pair carries a `<!--derive:-->`
  marker, so a guard does hold them together.
- **A collision.** `alife_examples.md` has `17.2` as a median isoperimetric ratio and `17.2 %` as
  an evolution margin, 550 lines apart and unrelated. Three significant digits is not many.
- **A unit conversion the page keeps re-deriving.** `faust.md`'s `2.67` is what a 128-frame block
  at 48 kHz lasts, and it turns up wherever a deadline is discussed. It is a property of the
  format, not a measurement of anything, so there is nothing for it to drift away from.

So a row is a candidate to **read**, and the question to ask of it is not "is this repeated" but
**"if one of these two changed, would anything catch the other?"** If the answer is no and the two
sentences make the same claim, one of them is decoration.

Surfaces match `score_voice.py`: default / `--generated` / `--assets`, through `sv.collect()`.
Self-test: `--live-fire`.
"""
from __future__ import annotations

import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import score_voice as sv  # noqa: E402

NUMBER = re.compile(
    r"(?<![\w.#\-/])(\d[\d,]*(?:\.\d+)?(?:e[+-]?\d+)?)(?![\w.%]|\s*(?:px|em))", re.I
)
YEAR = re.compile(r"(19|20)\d\d")
POWERS_OF_TWO = {str(1 << k) for k in range(6, 21)} | {f"{1 << k:,}" for k in range(6, 21)}

MIN_SIGNIFICANT = 3
MIN_LINE_GAP = 30


def significant(value):
    """Significant digits of a numeral as written, which is what a reader compares."""
    digits = value.replace(",", "")
    if "." in digits:
        # A written decimal means its trailing zeros: `1.05` is three, `0.0362` is three.
        return len(digits.replace(".", "").lstrip("0"))
    return len(digits.lstrip("0").rstrip("0"))


def prose_lines(text):
    """The page's lines with fences, alt text and code spans blanked, numbering preserved."""
    out, in_fence = [], False
    for line in text.split("\n"):
        if line.lstrip().startswith("```"):
            in_fence = not in_fence
            out.append("")
            continue
        if in_fence:
            out.append("")
            continue
        stripped = re.sub(r"!\[[^\]]*\]\([^)]*\)", "", line)
        stripped = re.sub(r"`[^`]*`", "", stripped)
        out.append(stripped)
    return out


def scan(text):
    """{numeral: [line numbers]} for every numeral restated far from itself."""
    seen = {}
    for lineno, line in enumerate(prose_lines(text), 1):
        for match in NUMBER.finditer(line):
            value = match.group(1)
            if significant(value) < MIN_SIGNIFICANT:
                continue
            if value in POWERS_OF_TWO or YEAR.fullmatch(value):
                continue
            seen.setdefault(value, set()).add(lineno)
    return {
        value: sorted(lines)
        for value, lines in seen.items()
        if len(lines) > 1 and max(lines) - min(lines) >= MIN_LINE_GAP
    }


def live_fire():
    """A detector that reports zero has to be shown capable of reporting one."""
    filler = "\n".join("ordinary prose about the thing" for _ in range(40))
    restated = f"the joint zone integrates to 2667 units\n\n{filler}\n\nthat same 2667 again\n"
    adjacent = "2667 units\nand again 2667 on the next line\n"
    two_digits = f"the budget is 26 units\n\n{filler}\n\nstill 26 units\n"
    power_of_two = f"a 4096-sample buffer\n\n{filler}\n\nthe 4096 samples again\n"
    a_year = f"landed in 2026\n\n{filler}\n\nand again in 2026\n"
    in_a_fence = f"```\nval budget = 2667\n```\n{filler}\n```\nval b = 2667\n```\n"
    in_code_spans = f"`2667` here\n\n{filler}\n\nand `2667` there\n"

    # Exactly on the significance floor, so a tightening of it is caught as well as a loosening.
    boundary = f"a relative error of 0.262\n\n{filler}\n\nthat 0.262 again\n"

    assert scan(restated), "did not fire on a four-digit figure restated 40 lines away"
    assert scan(restated)["2667"] == [1, 44], f"wrong line numbers: {scan(restated)}"
    assert scan(boundary), "did not fire at exactly three significant digits"
    assert not scan(adjacent), "fired on two mentions inside the same passage"
    assert not scan(two_digits), "fired under the significant-digit floor"
    assert not scan(power_of_two), "fired on a power of two, which is a size and recurs"
    assert not scan(a_year), "fired on a four-digit year"
    assert not scan(in_a_fence), "fired on a literal inside a code fence"
    assert not scan(in_code_spans), "fired on a literal inside code spans"
    print("live-fire: fires on a restated figure, silent on adjacent / short / size / year / code")
    return 0


def main():
    if "--live-fire" in sys.argv:
        return live_fire()
    root = pathlib.Path(
        sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("-") else "public/docs"
    )
    generated = "--generated" in sys.argv
    assets = "--assets" in sys.argv

    paths = [pathlib.Path(r["path"]) for r in sv.collect(root, generated=generated, assets=assets)]
    rows = []
    for path in paths:
        hits = scan(path.read_text(errors="replace"))
        if hits:
            rows.append((len(hits), path, hits))
    rows.sort(key=lambda r: (-r[0], str(r[1])))

    print("A measured number written into one page's prose twice, 30+ lines apart.")
    print("A row is a candidate to READ: ask whether anything would catch the other")
    print("occurrence if one changed. See this file's docstring for the three keeps.\n")
    print(f"{'n':>3}  page")
    for count, path, hits in rows:
        print(f"{count:3d}  {path}")
        for value, lines in sorted(hits.items()):
            print(f"        {value!r:12s} lines {lines}")
    print(
        f"\npages={len(paths)} nonzero={len(rows)} "
        f"restatement(s)={sum(r[0] for r in rows)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
