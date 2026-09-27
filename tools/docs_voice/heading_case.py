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

**An acronym a heading has down-cased.** The inverse fault, and it arrived in one commit:
`ad46651e6` re-cased the reference headings on two pages and carried `GPU`, `AI`, `2D` and
`3D` down with the ordinary words, giving `### Let the ai read it` directly above a paragraph
that writes "Field's AI tools". Title Case cannot see this — the headings are *correctly*
sentence case, and the fault is in one token.

[CANONICAL] is a closed list of spellings the tree already uses and that are never an English
word in their own right, so the test is "this token, in any casing other than its canonical
one". A census of `main` at `3aeda23f4` gave **9 raw hits over 618 pages, 7 of them true** —
the six from that commit plus `mesh_examples.md`'s `### I/o`. The two misses are box names in
machine-written `assets/agent_transcripts/*/document.md` (`2. imgui vr panel`), and
`sv.collect` already excludes those pages from every surface, so the board itself sees 7/7.

The first detector written for this fault was more general and much worse: "a heading token
the page's own prose always capitalises" needs no vocabulary, and scored **12 hits with one
true**, because a heading that names an API (`RbfKernels.gaussian`), a transcript id
(`external_browser_surface_macos_smoke`) or a log marker (`END`) is not making a casing
mistake. It also fired on `funFeatures.md`, which is one of Marc's four calibration pages. A
closed list that rots slowly beats an inference that is wrong eleven times in twelve.

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

# Spellings `public/docs` prose already settles on, so any other casing of one in a heading is
# a mistake rather than a choice. **Derived, not guessed** — `--audit` re-derives it from the
# tree, and the derivation is the admission rule: a term is in when its canonical form appears
# at least 5 times in prose outside fences, code spans and comments, and at least 4x as often
# as every other casing of it combined.
#
# The first version of this tuple was written from intuition and was badly wrong. `uv` is the
# Python tool, lowercase 27 times; `wasm` 16 to 7; `yaml` 161 to 77; `glsl` 184 to 335; `sdf`
# 64 to 24; `osc` 45 to 16; `midi` is split three ways with `Midi` the plurality. Every one of
# those was on the list, and every one is a term this tree writes lowercase on purpose. Run
# `--audit` before adding anything.
CANONICAL = (
    "GPU", "AI", "API", "URL", "JSON", "HTML", "CSS", "SVG", "DSL", "REPL", "JVM", "PNG",
    "EDN", "HTTP", "DOM", "CLI", "RGBA", "RGB", "BVH", "1D", "2D", "3D", "4D", "I/O",
    "glTF", "OBJ", "STL", "PLY", "FFM", "MCP", "TCB",
)

AUDIT_MIN_CANON = 5
AUDIT_DOMINANCE = 4

# `.` and `\` are excluded on both sides so a filename heading is not a hit: the generated
# reference writes `## osc.kt` and `## mesh_sdf.kt`, which are the real names of real files.
# `/` is excluded too, so `## what lives under tools/cli` is a path and not a fault — except
# for a term that contains `/` itself, where excluding it would make `I/O` unmatchable. One
# term needs that exception today and the rule is written per-term rather than globally so
# adding a second cannot silently widen the other thirty.
def _boundary(canon: str) -> str:
    return r"[\w.\\]" if "/" in canon else r"[\w./\\]"


ACRONYM = tuple(
    (canon, re.compile(
        f"(?<!{_boundary(canon)})" + re.escape(canon) + f"(?!{_boundary(canon)})", re.I))
     for canon in CANONICAL
)

# A trailing `{ #anchor }` is Zensical's attribute-list syntax, not part of the heading text.
ATTR_LIST = re.compile(r"\{[^}]*\}\s*$")


def strip_code(text: str) -> str:
    return CODE_SPAN.sub(" ", text)


def miscased_acronyms(heading: str):
    """Return (canonical, as-written) for each known term the heading spells another way."""
    bare = strip_code(ATTR_LIST.sub(" ", heading))
    out = []
    for canon, pattern in ACRONYM:
        for m in pattern.finditer(bare):
            if m.group(0) != canon:
                out.append((canon, m.group(0)))
    return out


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
    """Fence bodies hold code, and code is not prose. Keep line counts so a report can cite one.

    HTML comments go first, and for the reason `score_voice.py` found on 2026-09-13: a
    `FIELD_TEST` declaration is a comment block full of YAML whose keys start with `#`, and one
    of them contains a ``` line, so stripping fences first leaves the comment's contents in
    the prose and the rest of the page inside a phantom fence.
    """
    text = re.sub(r"<!--.*?-->", lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)
    return re.sub(r"```.*?```", lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)


def audit(root: pathlib.Path) -> int:
    """Re-derive [CANONICAL] from the tree and report where the shipped tuple disagrees.

    Cheap to run and the only honest way to add a term. Prints one row per candidate so a
    borderline call can be read rather than trusted.
    """
    import collections

    blob = []
    for page in sorted(root.rglob("*.md")):
        blob.append(page.read_text(encoding="utf-8", errors="replace"))
    text = "\n".join(blob)
    text = re.sub(r"<!--.*?-->", " ", text, flags=re.S)
    text = re.sub(r"```.*?```", " ", text, flags=re.S)
    text = strip_code(text)

    print(f"Admission rule: canonical >= {AUDIT_MIN_CANON} and >= {AUDIT_DOMINANCE}x every "
          f"other casing combined, over prose in {root} outside fences, code spans and")
    print("comments. A term below the line is one this tree writes lowercase on purpose.")
    print()
    drift = 0
    for canon, pattern in ACRONYM:
        forms = collections.Counter(m.group(0) for m in pattern.finditer(text))
        n = forms.pop(canon, 0)
        other = sum(forms.values())
        admits = n >= AUDIT_MIN_CANON and n >= AUDIT_DOMINANCE * other
        if not admits:
            drift += 1
            rest = ", ".join(f"{f} x{c}" for f, c in forms.most_common(3)) or "none"
            print(f"   DROP {canon:6} {canon} x{n}, other: {rest}")
    print()
    if drift:
        print(f"{drift} shipped term(s) no longer meet the rule. Read each, then edit CANONICAL.")
        return 1
    print(f"all {len(CANONICAL)} shipped terms still meet the rule")
    return 0


def scan_acronyms(text: str):
    """Return (level, heading, hits) for each heading that down-cases a known term.

    All four levels, unlike the Title Case board: an H1 in Title Case is ordinary English and
    a rename costs a reference regeneration, but `# Gpu state` is simply wrong.
    """
    out = []
    for line in _strip_fences(text).split("\n"):
        m = HEADING.match(line)
        if not m:
            continue
        hits = miscased_acronyms(m.group(2))
        if hits:
            out.append((len(m.group(1)), m.group(2), hits))
    return out


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

# The seven real hits on `main` at `3aeda23f4`, as headings.
ACRONYM_MUST_FIRE = [
    "### Look at it without a gpu",
    "### On the gpu instead",
    "### Let the ai read it",
    "### 2d delaunay / voronoi / hull",
    "### Weighted 2d (apollonius)",
    "### 3d voronoi",
    "### I/o",
    # Not in the census, but the same fault at the level the Title Case board declines to
    # score. This board does score it.
    "# Gpu state",
]

ACRONYM_MUST_NOT_FIRE = [
    # The canonical spellings themselves, including the two that are not all-caps.
    "### Look at it without a GPU",
    "### 2D Delaunay / Voronoi / hull",
    "### I/O",
    "## glTF, and what a scene graph costs",
    # Terms the audit rejected: this tree writes them lowercase on purpose, so a heading that
    # does the same is correct. These four are the reason the list is derived and not guessed.
    "## Reading a yaml transcript",
    "## The JVM to wasm crossing",
    "## A glsl fragment shader",
    "## uv run, and the repo environment",
    # Real filenames in the generated reference. `osc.kt` holds `osc`; `mesh_sdf.kt` holds
    # `sdf`; the `{ #anchor }` attribute list holds both again in slug form.
    "## osc.kt { #osc-kt }",
    "## mesh_sdf.kt { #mesh-sdf-kt }",
    "### ScalarField3D { #mesh-sdf-kt-scalarfield3d }",
    # The shape that earns `ATTR_LIST`. Zensical slugs are lowercase and `-`-joined, and `-`
    # is not a boundary, so an anchor whose last segment is a term slug is reachable. No
    # generated heading has that form today — this fixture is the whole reason the strip is
    # not dead code, and deleting the strip fails the suite on this line.
    "### Imaging.kt { #imaging-kt-rgb }",
    # Backticked code is stripped before the test, so a lowercase API name is not a hit.
    "## `uiScale` and `gpuBudget`",
    # Longer words that merely contain a term. `Guide` holds `gui`, `Vrille` holds `vr`,
    # `Said` holds `ai`, `Doming` holds `dom`.
    "## A guide to what the compositor said",
    # Dotted neighbours: `scene.obj` and `scene.stl` are filenames, not the format names.
    "## Files named scene.obj and scene.stl",
    # A lowercase path segment. `/` is a boundary for every term that does not contain one,
    # so this is a path and not a casing mistake.
    "## What lives under tools/cli",
    "## api/v2 and the old route",
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
    for h in ACRONYM_MUST_FIRE:
        if not scan_acronyms(h):
            print(f"MISSED {h}")
            bad += 1
    for h in ACRONYM_MUST_NOT_FIRE:
        got = scan_acronyms(h)
        if got:
            print(f"FALSE  {h} <- {got[0][2]}")
            bad += 1
    # A heading inside a fence is not a heading.
    if scan_acronyms("```\n### Look at it without a gpu\n```\n"):
        print("FALSE  a heading inside a fence")
        bad += 1
    # Nor is one inside an HTML comment.
    if scan_acronyms("<!--\nFIELD_TEST\n### On the gpu instead\n-->\n"):
        print("FALSE  a heading inside an HTML comment")
        bad += 1
    # The ordering, which is the part that actually bites. A `FIELD_TEST` block is a comment
    # whose YAML contains a lone ``` line; strip fences first and that line pairs with the
    # page's next real fence opener, blanking every heading between them. Stripping comments
    # first is what makes this page's one real fault visible.
    if not scan_acronyms(
        "<!--\nFIELD_TEST\nexpect: |\n```\n-->\n\n### On the gpu instead\n\n```kotlin\nx\n```\n"
    ):
        print("MISSED the heading between a FIELD_TEST comment's ``` and the next real fence")
        bad += 1
    if bad:
        print(f"\nLIVE FIRE FAILED: {bad} case(s). Fix the detector before trusting a board.")
        return 1
    print(
        f"live fire OK: {len(MUST_FIRE) + len(ACRONYM_MUST_FIRE) + 1} must-fire, "
        f"{len(MUST_NOT_FIRE) + len(ACRONYM_MUST_NOT_FIRE) + 3} must-not-fire, all correct"
    )
    return 0


def main():
    args = sys.argv[1:]
    if "--live-fire" in args:
        raise SystemExit(live_fire())
    if "--audit" in args:
        where = [a for a in args if not a.startswith("--")]
        raise SystemExit(audit(pathlib.Path(where[0] if where else "public/docs")))
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
    cased = skipped = titles = miscased = 0
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
        for level, heading, hits in scan_acronyms(text):
            spellings = ", ".join(f"{was} -> {canon}" for canon, was in hits)
            print(f"-- {path}\n   {'#' * level} {heading}   <- {spellings}")
            miscased += 1
    print()
    print(f"pages={len(pages)}  title-case section headings={cased}  "
          f"down-cased acronyms in headings={miscased}  "
          f"pages skipping h2={skipped}  (H1s in Title Case: {titles}, not scored)")


if __name__ == "__main__":
    main()
