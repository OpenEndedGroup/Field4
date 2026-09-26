#!/usr/bin/env python3
"""Grep one rhetorical *move* across the whole corpus, in prose only.

`score_voice.py` ranks a page against itself: it finds a bad heading, an oversized
admonition, a slab of measurement. It is blind to the fault Marc named that has no
per-page signal at all -- a move that is fine once and unmistakable in bulk, used once
per section or once per gallery entry:

    "The left panel is the sanity check, and it carries a trap."

That sentence is unremarkable alone. Across five pages the label ("is the sanity check",
"is the interesting one", "is the trap") is a habit, and reading it in bulk is what makes
it visible. So this tool inverts the axis: one shape, every page, grouped by shape.

    python3 moves.py public/docs                 # every shape with hits, grouped
    python3 moves.py public/docs --shape label   # one family, with line numbers
    python3 moves.py public/docs --list          # the shapes and what each is for
    python3 moves.py public/docs --calibrate     # check against the recorded keeps
    python3 moves.py public/docs --generated     # the reference/ pages, with KDoc owners
    python3 moves.py public/docs --assets        # the hand-authored demo front pages

CALIBRATION IS PART OF THE TOOL, for the same reason it is part of `score_voice.py`.
Every shape here is a *candidate*, never a verdict: the 2026-09-15 sweep fixed thirteen
hits and kept three after reading, and those three are the calibration set below. A sweep
that fires on "so it is the crack field" -- which names what a value is *for*, inside a
table cell -- is as broken as a detector that fires on `kotlin_overview.md`.
"""
import re
import sys
import pathlib
import argparse
import collections

# Generated pages, asset directories, and the transcript gallery (whose entries are
# machine-written `### name` blocks, not prose anybody edits by hand).
SKIP_DIRS = {"reference", "assets", "stylesheets"}
SKIP_FILES = {"agent_transcripts.md"}

# --------------------------------------------------------------------------------------
# The shapes. Each is (family, regex, what a reader loses to it).
#
# Two families dominated the first sweep and are first here. The rest fired less but
# each has at least one real fix behind it, or is a near neighbour of one that did.
# --------------------------------------------------------------------------------------
SHAPES = [
    # ---- the evaluative label bolted onto a row, panel, entry or category ----
    # Marc's own quote. The *functional* signpost ("the left panel", "the box to edit")
    # is a convention and stays; it is "is the sanity check" that goes. Fix by leading
    # with the fact, which on every one of the seven fixed instances was already sitting
    # in the very next sentence.
    ("label",
     r"\b(is|are)\s+the\s+(interesting|surprising|tricky|subtle|important|useful|awkward"
     r"|revealing|instructive|telling|significant|notable|unexpected)\s+(one|ones|bit|part|case|row|column)\b",
     "an opinion where a fact fits"),
    # `whole point` was in this list on the first draft and came out the same night: it is
    # ordinary English for "that is the purpose", and `dough_dsp_sound.md`'s "no clicks at the
    # retrigger, which is the whole point of the curved variant" is a design fact about a
    # variant. The keep below pins that. Everything left here is a *grade*.
    ("label",
     r"\b(is|are)\s+the\s+(sanity check|trap|catch|gotcha|easy failure|hard part"
     r"|real test|acid test|giveaway|tell)\b",
     "an opinion where a fact fits"),
    ("label",
     r"\bcarries\s+a\s+(trap|catch|caveat|surprise|subtlety)\b",
     "an opinion where a fact fits"),
    # Added 2026-09-15 (tenth firing). The noun list above cannot reach the form where the
    # evaluation is carried by a relative clause instead of an adjective: "One thing about
    # that capture is unrepresentative, and it is the interesting key" (server_surface),
    # "There is a second reason, and it is the one that decided the choice" (tetgen),
    # "*Directed* is the word that matters" (physics). Three real fixes, no false positives
    # on a 131-page tree. The verb list is what keeps it narrow.
    ("label",
     r"\b(is|are|was)\s+the\s+(\w+\s+)?(one|ones|key|word|thing|part|bit|row|column|panel|case)s?\s+"
     r"(that|which|whose)\s+(decid|matter|surpris|break|fail|count|does\s+not|doesn't)",
     "an opinion where a fact fits"),

    # ---- the page announcing that it is about to show its work ----
    # A reader who wants to use the thing does not need to be told that evidence is
    # coming; they need the evidence or the API. Note the deliberate gap: "so here it is
    # in full", introducing a complete example, is a functional signpost and is excluded
    # by the `in full` guard below rather than by hand.
    ("showing-work",
     r"\bso\s+here\s+it\s+is\b(?!\s+in\s+full)",
     "the page narrating itself"),
    ("showing-work",
     r"\bhere\s+is\s+the\s+(part|bit|section)\s+(where|that)\b",
     "the page narrating itself"),
    ("showing-work",
     r"\bthat\s+is\s+the\s+(whole\s+)?(argument|claim|case|point of the exercise)\b",
     "the page narrating itself"),
    ("showing-work",
     r"\bmeasured\s+rather\s+than\s+(asserted|assumed|claimed|guessed)\b",
     "the page narrating itself"),
    ("showing-work",
     r"\b(and|which)\s+(it|that)\s+(was|is)\s+checked\s+for\s+this\s+(page|document)\b",
     "the page narrating itself"),

    # ---- defending a result against an objection nobody raised ----
    ("defence",
     r"\b(that\s+is\s+not|this\s+is\s+not)\s+(a\s+)?(hand-waving|hand\s+waving|arm-waving"
     r"|vague|woolly|circular|vacuous)\b",
     "a defence nobody asked for"),
    ("defence",
     r"\bnot\s+(merely|just)\s+(a\s+)?(disclaimer|caveat|hedge|assertion)\b",
     "a defence nobody asked for"),
    ("defence",
     r"\bworth\s+(stating|saying|noting)\s+plainly\b",
     "a defence nobody asked for"),
    ("defence",
     r"\bwritten\s+into\s+the\s+\w+\s+rather\s+than\s+left\s+to\s+the\s+reader\b",
     "a defence nobody asked for"),

    # ---- the page leaving notes for itself: plans, next steps, self-assessment ----
    # "More than half of the Faust docs is AI leaving notes for itself." A *limitation*
    # is user-facing and stays; a *plan* is not.
    #
    # The discriminator is the CONDITIONAL, not the words "next step". A plan says what
    # nobody has done -- "the next step would be to". An instruction says what the reader
    # should do next -- `forth_examples.md`'s "so the usual next step is to read the error
    # rather than to add prints", which is the most useful sentence in its section. The
    # indicative form is therefore not a shape, and the keep below pins that.
    ("self-note",
     r"\b(that\s+is|this\s+is)\s+the\s+next\s+thing\s+to\s+do\b",
     "a plan on a user-facing page"),
    ("self-note",
     r"\b(the\s+)?(obvious\s+)?next\s+step\s+(here\s+)?(would|could|should)\s+be\b",
     "a plan on a user-facing page"),
    # `later pass` is NOT in this alternation: on a rendering page a later pass is a render
    # pass later in the frame. 2026-09-26, reconstruction_plumbing.md, the family's only hit
    # in public/docs, was "good candidates for a later pass" -- draw UI after reconstruction.
    ("self-note",
     r"\bfor\s+(a\s+)?(future\s+(pass|revision|run|version)|later\s+(revision|run|version))\b",
     "a plan on a user-facing page"),
    ("self-note",
     r"\b(left|leaving)\s+as\s+(an\s+exercise|future\s+work|a\s+TODO)\b",
     "a plan on a user-facing page"),

    # ---- the stakes promise, in prose rather than in a heading ----
    # score_voice.py catches this in headings (head-stakes). The same move survives one
    # line lower, where it has no per-page signal at all.
    ("stakes",
     r"\b(two|three|four|five)\s+(ways|things|places)\s+(this|it|the\s+\w+)\s+(will|can|might)\s+"
     r"(surprise|catch|bite|trip)\b",
     "buzzfeed stakes instead of a subject"),
    ("stakes",
     r"\bwill\s+surprise\s+you\b",
     "buzzfeed stakes instead of a subject"),
    ("stakes",
     r"\bhere\s+is\s+where\s+it\s+gets\s+(interesting|subtle|tricky|strange)\b",
     "buzzfeed stakes instead of a subject"),
    # Added 2026-09-15 (tenth firing). `will surprise you` above was one verb of a family.
    # "Two consequences of that layout will catch you" (physics) is one word away from the
    # heading Marc named himself, and "the case that will bite you" (spatial_geometry) is the
    # panel-label fault with the stakes verb attached. Both real; no other hits on the tree.
    ("stakes",
     r"\b(most\s+likely\s+to|will)\s+(surprise|catch|trip|bite)\s+you\b",
     "buzzfeed stakes instead of a subject"),
    ("stakes",
     r"\bthis\s+is\s+the\s+part\s+most\s+likely\s+to\s+surprise\b",
     "buzzfeed stakes instead of a subject"),
]

# --------------------------------------------------------------------------------------
# Calibration: the instances the 2026-09-15 sweep read and *kept*. A shape that starts
# matching one of these has stopped being a candidate generator and become noise.
#
# Each entry is (page stem, the substring, why it stays).
# --------------------------------------------------------------------------------------
KEEPS = [
    ("faust", "the one thing that",
     "ordinary English naming a real distinction"),
    ("manifold", "the one thing that",
     "ordinary English naming a real distinction"),
    ("stacksl_cheatsheet", "so it is the crack field",
     "names what a value is *for*, inside a table cell"),
    ("vision_geometry", "so here it is in full",
     "introduces a complete example: a functional signpost"),
    # Added 2026-09-15 (tenth firing), when the first draft of the `self-note` family
    # flagged it: the indicative "next step" is an instruction to the reader, and this
    # one is the most useful sentence in its section.
    ("forth_examples", "the usual next step is to read the error",
     "tells the reader what to do next; a plan says what nobody has done"),
    ("dough_dsp_sound", "is the whole point of the curved variant",
     "names the PURPOSE of a variant, which is a design fact, not a grade"),
    ("vision_geometry", "which is the part a shader cannot do",
     "states a capability boundary; the clause carries the fact, not an opinion"),
    ("reconstruction_plumbing", "good candidates for a later pass",
     "a render pass drawn after reconstruction, not a plan"),
]


def prose_lines(text):
    """Yield (lineno, line) outside fenced code, HTML comments and tables.

    Fences are indexed by ordinal in tools/completion-corpus/rank-carets.tsv and are not
    editable by this pass at all, so a hit inside one is worse than useless. Table rows
    are kept -- `stacksl_cheatsheet.md`'s calibrated keep lives in a table cell, and a
    sweep that cannot see it cannot be calibrated against it.
    """
    in_fence = False
    fence = None
    for i, line in enumerate(text.splitlines(), 1):
        stripped = line.lstrip()
        if stripped.startswith("```") or stripped.startswith("~~~"):
            marker = stripped[:3]
            if not in_fence:
                in_fence, fence = True, marker
            elif marker == fence:
                in_fence, fence = False, None
            continue
        if in_fence:
            continue
        if stripped.startswith("<!--"):
            continue
        yield i, line


def is_seeded(text):
    """Whether this asset markdown is machine-seeded. Borrowed, not re-derived.

    A copy of the first-line list here would drift the moment the seeder changes its welcome,
    and then the two tools would disagree about which pages are authored. On ImportError the
    honest answer is "not seeded": a firing that cannot import gets a handful of boilerplate
    rows to skim, rather than silently losing the eight pages the surface exists for.
    """
    try:
        import score_voice
    except ImportError:
        return False
    return score_voice.is_seeded(text)


def pages(root, generated=False, assets=False):
    """The hand-written tree by default; `generated=True` inverts to the `reference/` surface.

    The default skip of `reference/` was a blind spot for eighteen firings, exactly as
    `score_voice.py`'s `DO NOT EDIT` skip was until 2026-09-16 -- and for the same reason.
    Skipping a generated page is right (editing it is reverted by the next
    `generateCodebaseReference`); *never reading its KDoc* is not. The generated surface is
    ~150k words of prose nobody had swept for a repeated move, and the first run of this flag
    found five candidates on five pages, four of them real.

    `assets=True` is the third surface, added 2026-09-17 for the same reason a third time:
    `public/docs/assets/` holds 359 markdown files and 285k words, and 8 of them are
    hand-authored demo front pages that no detector had ever read. The other 351 are seeded by
    `ensureAgentGuides` or written beside `metadata.edn`, and `score_voice.is_seeded` -- imported,
    not re-derived, so the two tools cannot disagree about what counts as authored -- tells them
    apart by content rather than by filename.
    """
    for path in sorted(pathlib.Path(root).rglob("*.md")):
        if "stylesheets" in path.parts:
            continue
        if ("assets" in path.parts) != assets:
            continue
        if assets:
            if not is_seeded(path.read_text(errors="replace")):
                yield path
            continue
        in_reference = "reference" in path.parts
        if generated != in_reference:
            continue
        if not generated and path.name in SKIP_FILES:
            continue
        yield path


def scan(root, generated=False, assets=False, census=None):
    """Return {family: [(path, lineno, shape_index, matched_text, line, span)]}.

    `census`, if given a list, is appended the pages actually read, so the caller can
    report how large the sweep was. Without that number "0 hit(s)" reads identically
    whether the board swept 141 pages or was handed an empty root -- which is the bug
    `test_no_board_is_blind_to_its_root` was added to catch, in 2026-09-25.
    """
    found = collections.defaultdict(list)
    compiled = [(fam, re.compile(rx, re.I), loss) for fam, rx, loss in SHAPES]
    for path in pages(root, generated=generated, assets=assets):
        if census is not None:
            census.append(path)
        text = path.read_text(encoding="utf-8", errors="replace")
        for lineno, line in prose_lines(text):
            for idx, (fam, rx, loss) in enumerate(compiled):
                m = rx.search(line)
                if m:
                    found[fam].append((path, lineno, idx, m.group(0), line, m.span()))
    return found


def owner(path, lineno):
    """The source file whose KDoc published this line, or None off the generated surface.

    Without it you get a line number in a file you are forbidden to edit. Borrowed from
    `score_voice.py` rather than re-derived, so the two tools agree about who owns a hit.
    """
    try:
        import score_voice
    except ImportError:
        return None
    try:
        return score_voice.owner_of(path.read_text(encoding="utf-8", errors="replace"), lineno)
    except Exception:
        return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("root")
    ap.add_argument("--shape", help="one family: label, showing-work, defence, self-note, stakes")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--calibrate", action="store_true")
    ap.add_argument("--generated", action="store_true",
                    help="sweep the generated reference/ pages instead, reporting the owning "
                         "source file -- THE FIX IS IN THE KDoc, not the .md")
    ap.add_argument("--assets", action="store_true",
                    help="sweep the hand-authored demo front pages under assets/ instead; "
                         "machine-seeded workspace guides are excluded by content")
    args = ap.parse_args()

    if args.list:
        by_family = collections.OrderedDict()
        for fam, rx, loss in SHAPES:
            by_family.setdefault(fam, [loss, 0])[1] += 1
        for fam, (loss, n) in by_family.items():
            print(f"  {fam:<14} {n} pattern(s)   {loss}")
        return 0

    swept = []
    found = scan(args.root, generated=args.generated, assets=args.assets,
                 census=swept)

    if args.calibrate and args.assets:
        print("No recorded keeps on the assets surface yet. Run --calibrate without --assets;\n"
              "and record any keep you decide here in KEEPS, with its reason.")
        return 0

    if args.calibrate and args.generated:
        # KEEPS records hand-written pages only, so there is nothing to check here yet.
        # Calibrate the hand-written surface (the default) and read every generated hit.
        print("No recorded keeps on the generated surface yet. Run --calibrate without "
              "--generated;\nand record any keep you decide here in KEEPS, with its reason.")
        return 0

    if args.calibrate:
        # Every recorded keep must still be *absent* from the hit list. The sweep read
        # these and decided; a shape that re-flags one of them will send the next firing
        # to re-litigate a settled call.
        #
        # Compare SPANS, not lines. `stacksl_cheatsheet.md:275` is a single 250-character
        # table row carrying both a recorded keep ("so it is the crack field") and, 60
        # characters later, a genuine unfixed label ("`worley3 z` is the interesting
        # one"). A line-level check calls that a calibration failure and sends the next
        # firing off to weaken a working shape.
        bad = []
        for stem, substring, why in KEEPS:
            for fam, hits in found.items():
                for path, lineno, idx, matched, line, span in hits:
                    if path.stem != stem:
                        continue
                    at = line.lower().find(substring.lower())
                    if at < 0:
                        continue
                    if span[0] < at + len(substring) and at < span[1]:
                        bad.append((stem, substring, why, fam, lineno, SHAPES[idx][1]))
        if bad:
            print("CALIBRATION FAILED: a recorded keep is being flagged again.")
            for stem, substring, why, fam, lineno, rx in bad:
                print(f"  {stem}.md:{lineno}  [{fam}]  {substring!r}")
                print(f"      stays because: {why}")
                print(f"      fired: {rx}")
            print("\nFix the shape, not the page.")
            return 1
        print(f"OK: none of the {len(KEEPS)} recorded keeps is flagged.")
        for stem, substring, why in KEEPS:
            print(f"  {stem}.md  {substring!r}\n      {why}")
        return 0

    families = [args.shape] if args.shape else [f for f, _, _ in SHAPES]
    seen = set()
    total = 0
    if args.generated:
        print("Generated pages. THE FIX IS IN THE KDoc, NOT THE .md — editing these is reverted\n"
              "by the next `./gradlew generateCodebaseReference`. Owners are the source files.")
    for fam in families:
        if fam in seen:
            continue
        seen.add(fam)
        hits = found.get(fam, [])
        if not hits:
            continue
        total += len(hits)
        by_page = collections.Counter(h[0].name for h in hits)
        print(f"\n=== {fam}  ({len(hits)} hit(s) on {len(by_page)} page(s)) ===")
        print(f"    {SHAPES[hits[0][2]][2]}")
        for path, lineno, idx, matched, line, span in hits:
            src = f"  <- {owner(path, lineno)}" if args.generated else ""
            print(f"  {path.name}:{lineno}  -> {matched!r}{src}")
            print(f"      {line.strip()[:160]}")
    print(f"\n{total} hit(s) across {len(seen)} famil(y/ies), on {len(swept)} page(s). "
          f"Every one is a candidate to *read*, not a fault to fix.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
