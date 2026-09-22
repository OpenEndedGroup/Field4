#!/usr/bin/env python3
"""PROSE ABOUT THE DOCUMENT'S OWN HISTORY, and the evaluative label generalised.

Two families that `score_voice.py` has never seen, found by hand on the thirty-sixth
firing (2026-09-20) with four true hits across three pages -- two of which the scorer
had ranked at exactly 0.

FAMILY 1, `docs-changelog`.  A page telling a reader what an earlier draft of itself
said, before telling them what is true.  The disposal table has always covered this
("a changelog or a bug postmortem -> dev_docs/stdlib_behaviour_changes.md ... in which
case delete"), but nothing could find it, because every word in it is ordinary English
and the fault is entirely in the *subject* of the sentence.  The three tonight:

    interaction_nets_examples.md:21  "That sentence used to end ..."
    interaction_nets_examples.md:27  "This page used to end that paragraph by saying ..."
    sound.md:280                     "That first row used to read 5, and this section
                                      used to open by saying ..."

and one that `score_voice` did catch, but only because it happened to be wearing an
admonition title: `chain_lambda_sound.md:124`, `!!! warning "A published page said the
opposite, and this is the correction"`.  Strip the admonition and the scorer goes blind
to it.  That is the argument for scoring the *sentence* and not the wrapper.

The match requires a document-self subject -- sentence / page / section / paragraph /
row / bullet -- near a revision verb.  Without that subject the same verbs are ordinary
and correct documentation, and the live-fire block below pins three of them:
`staging.md`'s "getting at what it used to say" is a *Field* document's history, which
is a feature of the editor; `renderers.md`'s "scenes written before the list existed"
is a compatibility fact; `keyframer_examples.md`'s "Version 0 was written before `wet`
existed" is the same.  None of those is the page talking about itself.

FAMILY 2, `evaluative-label`.  `score_voice`'s `reader-verdict` matches the fixed string
`the interesting part` (and `the surprising part`).  The move is not attached to that
noun.  Tree-wide the same construction appears as the interesting *minority*, *stress
case*, *positive result*, *target*, *row*, *call* and *fact* -- seven nouns the fixed
pattern cannot see.  This generalises it to any noun, which is what makes it a family
rather than a string.

Both families are reported as candidates to READ.  An evaluative label that is followed
by its design fact in the same sentence is often a keep -- what goes is the label bolted
onto a fact the sentence already carries.

    python3 self_narration.py public/docs
    python3 self_narration.py public/docs --generated
    python3 self_narration.py public/docs --assets
    python3 self_narration.py --live-fire
"""

from __future__ import annotations

import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import score_voice as sv  # noqa: E402

# A subject that can only be a piece of this document.  "document" is deliberately NOT
# here: in this tree it overwhelmingly means a *Field* document, which has its own real
# version history that pages are right to describe.
DOC_SELF = (
    r"(?:that|this|the|its)\s+"
    r"(?:first\s+|last\s+|same\s+|whole\s+)?"
    r"(?:sentence|page|section|paragraph|bullet|row|claim|caption|heading|table|list)"
)

# Verbs that place the subject in a superseded state of the *text*.
REVISION = (
    r"(?:used\s+to\s+(?:say|end|read|open|claim|be|call|report|carry|list)"
    r"|no\s+longer\s+(?:can|does|says?|reads?|claims?)"
    r"|has\s+since\s+been\s+(?:corrected|rewritten|reworded|changed)"
    r"|was\s+previously\s+(?:worded|written|called|stated))"
)

SELF_TELLS = [
    # A doc-self subject within ~60 characters of a revision verb, either order.
    ("docs-changelog", re.compile(DOC_SELF + r".{0,60}?" + REVISION, re.I), 8),
    ("docs-changelog", re.compile(REVISION + r".{0,60}?" + DOC_SELF, re.I), 8),
    # Inherently self-referential, no subject needed.
    ("docs-changelog", re.compile(r"until\s+this\s+(?:page|section)\s+was\s+written", re.I), 8),
    ("docs-changelog", re.compile(r"\ba\s+published\s+page\s+said\b", re.I), 8),
    ("docs-changelog", re.compile(r"said\s+the\s+opposite\b", re.I), 8),
    ("docs-changelog", re.compile(r"\bthis\s+is\s+the\s+correction\b", re.I), 8),
    # The label, generalised off `part`.
    (
        "evaluative-label",
        re.compile(
            r"\b(?:is|are|was|were)\s+(?:still\s+)?the\s+"
            r"(?:interesting|surprising|clever|subtle|neat|exciting)\s+"
            r"(?:[a-z]+\s+){0,2}[a-z]+",
            re.I,
        ),
        6,
    ),
]

# Live fire.  A detector nobody has watched fire is a detector nobody should believe.
MUST_FIRE = [
    ("That sentence used to end \"and `Run.steps` is a property of this rule book\".", "docs-changelog"),
    ("This page used to end that paragraph by saying the sharing was correct.", "docs-changelog"),
    ("That first row used to read 5, and this section used to open by saying the stand-in is not what you hear.", "docs-changelog"),
    ("A published page said the opposite, and this is the correction", "docs-changelog"),
    ("Interaction Nets examples said, until this page was written: the schedule matters.", "docs-changelog"),
    ("The fourteen that never touch `patVoice` are still the interesting minority.", "evaluative-label"),
    ("The four terms where the reducer is wrong are the interesting stress case.", "evaluative-label"),
    ("Or-reduce is the interesting positive result because it needs recursion.", "evaluative-label"),
    ("A command with no binding yet is the interesting row.", "evaluative-label"),
]

MUST_NOT_FIRE = [
    # A *Field* document's own history is a feature of the editor, not a docs changelog.
    "The document itself -- moving around it, finding things in it, and getting at what it used to say.",
    # Compatibility facts.  The subject is a scene, a version, a test -- never the page.
    "scenes written before the list existed still get the default directional light",
    "Version 0 was written before `wet` existed; a scope outgrows its own files.",
    "a test written before the atom that binds its variable is reported as a refusal",
    "including a partial batch written before an agent failure",
    # Marc's own voice, from the four calibration pages.
    "The reading is total; the parameterisation is where the refusals live.",
    "Drop a file on the canvas and it becomes a box you can run.",
]


def hits_for(text):
    """At most one hit per family per line.

    `docs-changelog` is six overlapping alternations by construction -- the two
    subject/verb orderings plus four fixed phrases -- so a single sentence that names
    two of its own parts ("That first row used to read 5, and this section used to
    open by saying ...") matched three times and scored 24 where the fault is one
    sentence.  Found by reading the first board this detector ever printed, which is
    the same way the thirty-fourth firing found `table_cells.py`'s adjacency bug.
    Longest match wins, so the reported snippet is the most specific one.
    """
    best = {}
    for name, pat, weight in SELF_TELLS:
        for m in pat.finditer(text):
            snippet = m.group(0)
            prev = best.get(name)
            if prev is None or len(snippet) > len(prev[2]):
                best[name] = (name, weight, snippet)
    return list(best.values())


def live_fire():
    bad = 0
    for sentence, family in MUST_FIRE:
        got = {n for n, _, _ in hits_for(sentence)}
        if family not in got:
            print(f"MISS  [{family}] {sentence[:78]}")
            bad += 1
    for sentence in MUST_NOT_FIRE:
        got = hits_for(sentence)
        if got:
            print(f"FALSE [{got[0][0]}] {sentence[:60]} <- matched {got[0][2]!r}")
            bad += 1
    if bad:
        print(f"\nLIVE FIRE FAILED: {bad} case(s). Fix the detector before trusting a board.")
        return 1
    print(f"live fire OK: {len(MUST_FIRE)} must-fire, {len(MUST_NOT_FIRE)} must-not-fire, all correct")
    return 0


def main():
    args = [a for a in sys.argv[1:]]
    if "--live-fire" in args:
        raise SystemExit(live_fire())
    generated = "--generated" in args
    assets = "--assets" in args
    paths = [a for a in args if not a.startswith("--")]
    root = pathlib.Path(paths[0] if paths else "public/docs")

    if live_fire():
        raise SystemExit(1)
    print()

    # Same route as table_cells.py: let collect() do all the surface filtering
    # (generated/, seeded assets, template sources) and take only its path list.
    paths = [pathlib.Path(r["path"]) for r in sv.collect(root, generated=generated, assets=assets)]

    rows = []
    scanned = 0
    for path in paths:
        if not path.exists():
            continue
        text = path.read_text(errors="replace")
        prose, _table, heads, _admons, _bullets = sv.split_prose(text)
        # Admonition titles live on the heading list; a changelog wearing one is still a
        # changelog, which is exactly how chain_lambda_sound.md hid.
        lines = list(prose) + [(n, t) for n, t in heads]
        found = []
        for lineno, ln in lines:
            scanned += 1
            blanked = re.sub(r"`[^`]*`", "``", ln)
            for name, weight, snippet in hits_for(blanked):
                owner = sv.owner_of(text, lineno) if generated else None
                found.append((name, weight, lineno, snippet, owner))
        if found:
            rows.append((sum(f[1] for f in found), path, found))

    rows.sort(key=lambda r: -r[0])
    for total, path, found in rows:
        print(f"{total:5}  {path}")
        for name, _w, lineno, snippet, owner in sorted(found, key=lambda f: f[2]):
            tail = f"   <- {owner}" if owner else ""
            print(f"          {name} :{lineno}  {snippet[:82]}{tail}")
    nonzero = len(rows)
    total = sum(r[0] for r in rows)
    print(f"\nscanned {scanned} line(s); {nonzero} page(s) nonzero, sum {total}")


if __name__ == "__main__":
    main()
