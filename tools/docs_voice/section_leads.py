#!/usr/bin/env python3
"""A `## Reference` whose every section lead is the same two-sentence template.

Marc, 2026-09-13:

> There's a lot of measuring here of things I don't really care about -- I'd much rather
> just read about the cool language and how to use it.

`table_advice.py` found this shape one level down, inside the cells of a single table.
This is the same fault one level *up*, in the one-paragraph lead that introduces each
table. A reader scanning a Reference section reads that paragraph eleven times in a row,
and eleven times it is a group summary followed by a second sentence that either
re-balances the table's own rows across a semicolon or restates them with an imperative
in front:

    ### Statistics

    Measure or remap intensity distributions. Threshold helpers produce masks;    <- CUT
    difference and PSNR measurements compare two images.

    ### Multiscale

    Build coarse-to-fine image representations. Gaussian pyramids hold smoothed   <- CUT
    levels; Laplacian pyramids hold differences between levels.

No board sees it. The paragraphs are short, bland, carry no numbers and sit in no
admonition, so `score_voice.py` and `self_narration.py` both give the pages **0**;
`table_advice.py` reaches the cells and not the prose above them; `shape_census.py`
drowns it in `div class field`, the opener of the markup that surrounds every one of
these tables.

## Why a per-PAGE ratio and not a per-paragraph tell

The second sentence is not the fault. Three of the ones this board's own pages carry are
the most useful sentence in their section and were **kept**:

    Use nearest sampling for discrete labels and bilinear sampling for            <- keep
      interpolated image values.
    Use corner responses when you need a score image, and corner detectors        <- keep
      when you need point coordinates.
    Delaunay supplies neighbour relations; Voronoi supplies cells; the hull       <- keep
      encloses the point set.

They are lexically indistinguishable from the sixteen that were cut. What distinguishes
the fault is that on some pages **every single lead** has the shape and on others almost
none does. So a hit needs both:

  - at least 85% of the page's section leads are *exactly two sentences* long, and
  - at least 60% of them close on the advisory-or-semicolon shape.

A page has to have at least five section leads before either ratio means anything.

## What it is worth, measured

Measured at `a88f62b96` over the hand-written surface, the separation is not close:

    imaging_examples.md           11 leads   100% two-sentence   100% templated   <- fire
    mesh_examples.md               8 leads   100% two-sentence    62% templated   <- fire
    spatial_geometry_examples.md   6 leads   100% two-sentence    83% templated   <- fire
    fieldml_cheatsheet.md          6 leads    50% two-sentence    17% templated
    geometry_from_disk.md          8 leads    38% two-sentence    25% templated
    petri_cheatsheet.md            5 leads    20% two-sentence     0% templated

The three that do not fire are the argument for the board rather than an accident of
tuning. `fieldml_cheatsheet.md`'s leads run one, two and three sentences and every one of
them carries a fact the table does not (`range start end` stops before `end`; mixing
vector widths is a type error). `petri_cheatsheet.md`'s are one sentence and terse. Read
those two before believing a hit here: they are what a good section lead looks like, in
this tree, on the same day.

`geometry_from_disk.md` at 38% is the useful near-miss. It has two templated leads and
several advisory table tails, and it is right that this board stays silent on it -- two
is not a template, it is two sentences to read and judge.

## The vocabulary, and where it comes from

Derived, not guessed -- `heading_case.py`'s lesson from 2026-09-24, where ten of
fifty-three remembered terms turned out to be words this tree writes lowercase on purpose.
`--audit` re-derives the opener census so the next firing can check for rot in one
command -- against the tree it is run on, so after the 2026-09-25 pass it reports far
fewer than the derivation below, which is the fix landing rather than the list rotting.
Over every two-sentence section lead on `public/docs` at `a88f62b96`, the
openers that recur at all are `Use` (4), `Choose` (3) and then a long tail of ones:
`Check`, `Convert`, `Compose`, `Build`, `Keep`. Those seven are shipped. `Prefer`, `Pick`
and `Apply` are shipped alongside them as direct synonyms of `Choose` and `Use` -- they
are the only three additions not measured in the tree, and they are named here so that a
later firing can delete them if they never fire.

Twelve of the thirty-three two-sentence leads carry a semicolon, and that half needs no
vocabulary at all: a semicolon in a two-clause second sentence, in this position, is
almost always the table's own rows re-said in parallel.

## Reading a hit

Do not flatten a page because a board named it. Take each lead's second sentence and ask:
is this fact in the table below? `Img` holds pixels and `Border` says what happens outside
them -- those are literally rows one and two, so it goes. Nearest sampling is for discrete
labels -- no row says that, so it stays. The first sentence is almost never the fault; it
is the group summary a reader scanning for the right table actually uses.
"""
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import score_voice as sv  # noqa: E402

# A page needs this many section leads before its ratios mean anything.
MIN_LEADS = 5

# Fraction of a page's section leads that must be exactly two sentences.
TWO_SENTENCE_FRACTION = 0.85

# Fraction that must also close on the advisory-or-semicolon shape.
TEMPLATE_FRACTION = 0.60

# Bare imperatives that open the second sentence. The first seven are measured in the
# tree (see the module docstring and `--audit`); the last three are synonyms shipped
# untested, and a firing that finds they never fire should delete them.
ADVISORY = (
    "Use",
    "Choose",
    "Check",
    "Convert",
    "Compose",
    "Build",
    "Keep",
    "Prefer",
    "Pick",
    "Apply",
)

ADVISORY_OPENER = re.compile(r"^(?:%s)\b" % "|".join(ADVISORY))

SENTENCE = re.compile(r"(?<=[.!?])\s+")

HEADING = re.compile(r"^#{2,4} ")

# The markup wrapper the code-reference tables on these pages are built from. Anchoring
# to it is most of the detector: without it the scan reaches every heading in the tree
# and a two-sentence paragraph under a heading is just a paragraph.
REFERENCE_DIV = 'class="field-reference"'


def section_leads(text):
    """[(line_no, heading, lead)] for each heading whose lead paragraph introduces a table.

    A "section lead" is a heading, then exactly one prose paragraph, then a
    `field-reference` div. A heading that opens straight onto the div has no lead and is
    not counted either way -- it is a section that chose not to have this paragraph, and
    scoring it would punish the very thing a fix produces.
    """
    lines = text.split("\n")
    out = []
    for i, line in enumerate(lines):
        if not HEADING.match(line):
            continue
        j = i + 1
        while j < len(lines) and not lines[j].strip():
            j += 1
        para = []
        while (
            j < len(lines)
            and lines[j].strip()
            and not lines[j].startswith(("#", "|", "<", "```", "!", "?", "-", "*"))
        ):
            para.append(lines[j].strip())
            j += 1
        if not para:
            continue
        k = j
        while k < len(lines) and not lines[k].strip():
            k += 1
        if k >= len(lines) or REFERENCE_DIV not in lines[k]:
            continue
        out.append((i + 1, line.strip(), " ".join(para)))
    return out


def templated(lead):
    """Is this lead exactly two sentences closing on the advisory-or-semicolon shape?

    Returns (is_two_sentence, is_templated).
    """
    parts = [s for s in SENTENCE.split(lead) if s.strip()]
    if len(parts) != 2:
        return False, False
    second = parts[1].strip()
    return True, bool(ADVISORY_OPENER.match(second) or ";" in second)


def scan(text):
    """(hit, leads, two_count, template_count) for one page's text."""
    leads = section_leads(text)
    if len(leads) < MIN_LEADS:
        return False, leads, 0, 0
    two = 0
    tmpl = 0
    for _, _, lead in leads:
        is_two, is_tmpl = templated(lead)
        two += is_two
        tmpl += is_tmpl
    hit = (
        two / len(leads) >= TWO_SENTENCE_FRACTION
        and tmpl / len(leads) >= TEMPLATE_FRACTION
    )
    return hit, leads, two, tmpl


def _page(blocks):
    """Assemble a fixture page from (heading, lead) pairs."""
    out = []
    for heading, lead in blocks:
        out += [f"### {heading}", "", lead, "", '<div class="field-reference" markdown>', ""]
        out += ["| Entry point | Behavior |", "| --- | --- |", "| `f()` | Does a thing. |", "", "</div>", ""]
    return "\n".join(out)


# Two sentences, advisory or semicolon second: the real shape, as shipped on
# `imaging_examples.md` before 2026-09-25.
_T = [
    ("Value type", "Load, construct and save images. `Img` holds pixels; `Border` says what is outside."),
    ("Sampling", "Change the extent or sample positions. Use nearest for labels and bilinear for values."),
    ("Channels", "Select channels or change colour. Convert between sRGB and linear light explicitly."),
    ("Statistics", "Measure intensity distributions. Threshold helpers produce masks; PSNR compares two."),
    ("Filters", "Smooth, sharpen or apply a kernel. Choose bilateral for edges and median for outliers."),
    ("Gradients", "Measure spatial change. First derivatives give direction; Laplacians give curvature."),
    ("Multiscale", "Build coarse-to-fine pyramids. Keep the Gaussian levels and difference them yourself."),
]
# Two sentences whose second is an ordinary declarative: not the shape.
_D = [
    ("Numbers", "Angles are in radians. Integer division and modulo have their own types below."),
    ("Lists", "`range start end` stops before `end`. There is no loop statement in this language."),
    ("Vectors", "`Vec2`, `Vec3` and `Vec4` add component by component. Mixing widths is a type error."),
]
# One sentence: a lead that is not the shape at all.
_O = [
    ("Directives", "Settings for the whole net, one per line, anywhere in the source."),
    ("Places", "A place is created the first time anything mentions it."),
    ("Transitions", "Written after the name, inside the brackets, in any order."),
]

MUST_FIRE = {
    # The real page: 11 leads, all two-sentence, all templated.
    "imaging_examples.md as it shipped": _page(_T + _T[:4]),
    # Exactly on both thresholds. 8 leads; 7 two-sentence (87.5% >= 85%); 5 templated
    # (62.5% >= 60%). One lead short of either ratio and this must stop firing, which is
    # what the two boundary must-not-fire cases below check.
    "on both thresholds": _page(_T[:5] + _D[:2] + _O[:1]),
}

MUST_NOT_FIRE = {
    # Density just under: 8 leads, 6 two-sentence (75% < 85%), 5 templated.
    "density just under": _page(_T[:5] + _D[:1] + _O[:2]),
    # Vocabulary just under: 8 leads, 7 two-sentence (87.5%), 4 templated (50% < 60%).
    "shape just under": _page(_T[:4] + _D[:3] + _O[:1]),
    # A real page with good leads: fieldml_cheatsheet.md's shape.
    "leads that carry facts": _page(_D + _O),
    # Under the size floor: four leads, every one templated. Not a template, four
    # sentences to read.
    "too few leads": _page(_T[:4]),
    # The anchoring control. The same templated paragraphs under headings that do NOT
    # introduce a reference table are ordinary prose, and this must stay silent on them.
    "no reference table": "\n\n".join(f"### {h}\n\n{t}" for h, t in _T + _T[:4]),
    # A page whose sections open straight onto the table, which is what a fix produces
    # when the lead is deleted rather than trimmed.
    "no leads at all": "\n\n".join(
        f'### {h}\n\n<div class="field-reference" markdown>\n\n| a | b |\n\n</div>' for h, _ in _T
    ),
}


def live_fire():
    """Pin both halves and both thresholds, in both directions, plus the anchoring."""
    bad = 0
    for name, text in MUST_FIRE.items():
        hit, leads, two, tmpl = scan(text)
        if not hit:
            print(f"MISSED {name}: {len(leads)} leads, {two} two-sentence, {tmpl} templated")
            bad += 1
    for name, text in MUST_NOT_FIRE.items():
        hit, leads, two, tmpl = scan(text)
        if hit:
            print(f"FALSE  {name}: {len(leads)} leads, {two} two-sentence, {tmpl} templated")
            bad += 1
    if bad:
        print(f"\nLIVE FIRE FAILED: {bad} case(s). Fix the detector before trusting a board.")
        return 1
    print(
        f"live fire OK: {len(MUST_FIRE)} must-fire, {len(MUST_NOT_FIRE)} must-not-fire, "
        "all correct"
    )
    return 0


def audit(root, generated=False, assets=False):
    """Re-derive the opener census the ADVISORY list was written from."""
    openers = {}
    semis = 0
    total = 0
    for record in sv.collect(root, generated=generated, assets=assets):
        path = pathlib.Path(record["path"])
        if not path.exists():
            continue
        for _, _, lead in section_leads(path.read_text(errors="replace")):
            parts = [s for s in SENTENCE.split(lead) if s.strip()]
            if len(parts) != 2:
                continue
            total += 1
            second = parts[1].strip()
            if ";" in second:
                semis += 1
            word = re.match(r"^[`*_]*(\w+)", second)
            if word:
                openers[word.group(1)] = openers.get(word.group(1), 0) + 1
    print("Second-sentence openers over every two-sentence section lead:")
    for word, count in sorted(openers.items(), key=lambda kv: (-kv[1], kv[0])):
        mark = "  <- shipped" if word in ADVISORY else ""
        print(f"  {count:3}  {word}{mark}")
    print(f"\n{total} two-sentence lead(s); {semis} carry a semicolon")
    unmeasured = [w for w in ADVISORY if w not in openers]
    if unmeasured:
        print(f"shipped but not present in this tree: {', '.join(unmeasured)}")
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

    if "--audit" in args:
        raise SystemExit(audit(root, generated=generated, assets=assets))

    rows = []
    scanned = 0
    for record in sv.collect(root, generated=generated, assets=assets):
        path = pathlib.Path(record["path"])
        if not path.exists():
            continue
        scanned += 1
        hit, leads, two, tmpl = scan(path.read_text(errors="replace"))
        if hit:
            rows.append((len(leads), two, tmpl, path, leads))

    rows.sort(key=lambda r: -r[0])
    print("A Reference section whose every lead paragraph is the same two-sentence template.")
    print("Every row is a candidate to READ: keep a second sentence that adds a fact the")
    print("table does not have, delete one that re-says the rows it sits above.")
    print()
    for count, two, tmpl, path, leads in rows:
        print(f"{two}/{count} two-sentence, {tmpl} templated  {path}")
        for lineno, heading, lead in leads:
            is_two, is_tmpl = templated(lead)
            mark = "T" if is_tmpl else (" " if is_two else ".")
            print(f"        {mark} :{lineno}  {heading[:40]:42s} {lead[:70]}")
    print(f"\npages={scanned} nonzero={len(rows)}")


if __name__ == "__main__":
    main()
