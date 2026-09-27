#!/usr/bin/env python3
"""Measure the last unscored prose surface in `public/docs`: figure ALT TEXT.

`score_voice.split_prose()` treats a fence opener as a delimiter --

    if stripped.startswith("```"): in_fence = not in_fence; continue

-- so the opener LINE is dropped before any tell is matched, and `in_fences.py` reads
fence *bodies* (code comments, embedded markdown headings), never the opener's
attributes. The `alt="..."` a capture fence carries has therefore been scored by
nothing, on any surface, in thirty-four firings.

It is published prose. `public/docs/assets/image-lightbox.js:76` reads the attribute
back off the rendered `<img>`, so an alt string is both the screen-reader text and the
lightbox caption -- for a blind reader it is the *only* thing the figure says. A
slide-label there is worse than one in a paragraph, because a sighted reader can
overrule a bad caption by looking at the picture and a screen-reader user cannot.

Scored:
  - every `alt="..."` in a fence opener, against BODY_TELLS
Not scored:
  - HEADING_TELLS. The first version of this tool DID score them, prefixed `alt-`, on
    the argument `table_cells.py` makes for a column header: a short label is read as a
    title. **That argument does not transfer, and the first run proved it.** It returned
    10 pages and a sum of 216 -- by far the densest board any surface has produced -- and
    every single point was a HEADING_TELL. Not one BODY_TELL fired on any of the 88
    strings. Reading them settled it: a column header is two to five words, an alt string
    is a forty-word description of a multi-panel figure, and the three families that fired
    are exactly the ones a description needs. `head-semicolon` on "on the left one orange
    square; on the right ..." is a figure enumerating its panels. `head-comma-clause` on
    "from above, where the room reads as relief" names which view. `head-negspace` on "a
    32-bit adder rotating slowly, never flattening into a line" is describing the
    animation -- the absence IS the visual fact. An alt string is prose, and only the
    prose families belong on it.
  - markdown image alt, `![...](...)`. That is ALREADY covered -- an image line is
    ordinary prose to `split_prose`, and `score()` matches BODY_TELLS against the raw
    line with only code spans blanked, so the bracket text is inside the match window.
    Scoring it here as well would double-count it.
  - alt strings under four words. "The two flocks" is a label, not prose.

Also reported, because the detector has to walk every capture fence anyway and the
number is the headline: ALT COVERAGE.

**That headline was wrong for five firings, and the way it was wrong is worth keeping
written down.** It counted a fence with `screenshot=` and no `alt=` as "a published
figure with no alternative text at all". A `screenshot=` fence publishes no `<img>`
unless it also carries `embed`, which `docs_hooks.parse_bool(..., default=False)`
defaults OFF: 475 of this tree's figures reach the page as hand-written
`![...](...)` markdown lines instead, and those the old count could not see at all.
So `alife_examples.md` read as 18 figures out of 18 missing while in fact carrying a
full, careful description on every one of them, and the tree-wide gap was reported as
505 when the real number was a third smaller. A number that names the wrong surface
sends the next firing to fix a page that is already right.

Coverage therefore counts what a reader actually meets, over both surfaces:

  - a `screenshot=` fence with `embed` true -- `docs_hooks` emits
    `![{alt or box_name}](...)`, so no `alt=` means the alt is the box name
  - a markdown `![...](...)` line outside a fence, covered when its alt clears the
    same four-word floor the scanner uses
  - a `screenshot=` fence *without* `embed` is not counted: it publishes nothing
    itself, and if its PNG appears at all it appears as a markdown line, counted there

Attributes go through a copy of `docs_hooks.parse_attrs`, not a regex, because the
fence syntax allows bare values: `afield_four_graphics.md` writes `embed=true`
unquoted, and an `embed="true"` regex silently misses 238 of the 339 embedding
fences.

The *block* those attributes are cut from runs to the LAST `}` on the line, not the
first. An alt string describing Kotlin code contains braces -- `petri_nets.md:143`
describes `.every(4) { it.fast(2) }` -- and a first-`}` match severs `alt="` from its
closing quote, which made `shlex.split` raise `No closing quotation` and returned
nothing at all for the whole tree. `docs_hooks` has never had the bug because it uses
no regex: it splits the opener on the fence name and flattens every brace to a space.
`double_published.py` carried the same expression and failed the other way, dropping
the fence silently. Both are pinned in `--live-fire`.

Surfaces match score_voice: default / --generated / --assets, same filters.
Self-test: `--live-fire` puts Marc's own 2026-09-13 quotes in an alt string and
asserts they fire, and pins each of the four coverage cases above. A detector that
reports zero has to be shown able to report one.
"""
from __future__ import annotations

import pathlib
import re
import shlex
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import score_voice as sv  # noqa: E402

# A fence opener with a brace-delimited attribute block: ```diffstatic_runner {...}
#
# `attrs` runs to the LAST `}` on the line, not the first. A non-greedy `[^}]*` was
# what this used to say, and an alt string describing a Kotlin lambda -- petri_nets.md
# `.every(4) { it.fast(2) }` -- truncated the block mid-quoted-value, which made
# `shlex.split` raise `No closing quotation` and take down the whole board. The real
# parser has never had this problem because it does not use a regex at all:
# `docs_hooks.parse_runner_options` takes `open_line.split(runner_name, 1)[1]`, i.e.
# everything after the fence name, and `parse_attrs` then flattens every brace to a
# space. Matching to the last `}` plus that same flattening reproduces it.
OPENER = re.compile(r"^\s*(?:```|~~~)[a-z_]*\s*\{(?P<attrs>.*)\}\s*$")
ALT = re.compile(r'\balt="(?P<text>[^"]*)"')
SCREENSHOT = re.compile(r"\bscreenshot=")
# A published figure the page author wrote by hand, rather than one the fence embeds.
MARKDOWN_IMAGE = re.compile(r"!\[(?P<alt>[^\]]*)\]\((?P<src>[^)]+)\)")
# The same line, for alt text that itself contains brackets. CommonMark allows those as
# long as they balance, and eleven real figures use them -- `linear_algebra.md` quotes a
# matrix header as `mat2d [1, 3 / 2, 4]`, `pattern_language.md` names a pattern as
# `bd [sd sd]`. `[^\]]*` stops at the inner `]`, the line then fails to parse as an image
# at all, and the figure is counted as neither published nor covered. So it is not a
# false *negative* on alt quality; it is eleven figures missing from the denominator.
MARKDOWN_IMAGE_OPEN = re.compile(r"!\[")
MIN_WORDS = 4
# docs_hooks.TRUE_VALUES, so `embed=true`, `embed="1"` and `embed=on` all agree here.
TRUE_VALUES = {"1", "true", "yes", "on"}


def parse_attrs(raw):
    """A copy of `docs_hooks.parse_attrs`, so bare `embed=true` parses like it does.

    One deliberate divergence: an attribute block whose quotes do not balance is
    reported as unparseable rather than raising. `docs_hooks` raising is right --
    the docs build should refuse a malformed fence -- but a read-only board that
    dies on the first bad page reports nothing about the other 139.
    """
    flattened = raw.replace("{", " ").replace("}", " ")
    attrs = {}
    try:
        tokens = shlex.split(flattened)
    except ValueError:
        return None
    for token in tokens:
        if token.startswith(".") or token.startswith("#"):
            continue
        if "=" in token:
            key, value = token.split("=", 1)
            attrs[key.strip()] = value.strip()
        elif token:
            attrs[token.strip()] = "true"
    return attrs


def openers(text):
    """Yield (lineno, attrs) for each fence opener carrying an attribute block.

    Fence state is tracked so that a ```-looking line inside a fence body -- a nested
    fence in an embedded markdown sample -- is not read as an opener.
    """
    in_fence = False
    for lineno, raw in enumerate(text.split("\n"), 1):
        stripped = raw.lstrip()
        if not (stripped.startswith("```") or stripped.startswith("~~~")):
            continue
        if in_fence:
            in_fence = False
            continue
        in_fence = True
        m = OPENER.match(raw)
        if m:
            yield lineno, m.group("attrs")


def scan(text):
    """Yield (family, lineno, weight, matched, alt) for every tell in an alt string."""
    for lineno, attrs in openers(text):
        m = ALT.search(attrs)
        if not m:
            continue
        alt = m.group("text").strip()
        # Blank code spans the same way `score()` does, so a phrase inside a backticked
        # identifier cannot fire. Alt text does carry them: `capture-box="lb_renderer"`
        # figures describe `psi` and `dt` by name.
        probe = re.sub(r"`[^`]+`", "§", alt)
        if len(probe.split()) < MIN_WORDS:
            continue
        for name, pat, w in sv.BODY_TELLS:
            for hit in re.finditer(pat, probe, re.I):
                yield name, lineno, w, hit.group(0), alt


def markdown_images(text):
    """Yield (lineno, alt) for each hand-written image line outside a fence."""
    in_fence = False
    for lineno, raw in enumerate(text.split("\n"), 1):
        stripped = raw.lstrip()
        if stripped.startswith("```") or stripped.startswith("~~~"):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        for alt in markdown_image_alts(raw):
            yield lineno, alt


def markdown_image_alts(line):
    """Every `![alt](src)` alt string on one line, counting brackets rather than banning them.

    Scans from each `![` and tracks depth, so `![a pattern of bd [sd sd]](x.png)` yields
    the whole alt instead of stopping at the inner `]`. Requires the `(` that follows, so
    a reference-style `![alt][ref]` or a bare `![text]` is still not an image.
    """
    for opener in MARKDOWN_IMAGE_OPEN.finditer(line):
        i, depth = opener.end(), 1
        while i < len(line) and depth:
            if line[i] == "[":
                depth += 1
            elif line[i] == "]":
                depth -= 1
            i += 1
        if depth or i >= len(line) or line[i] != "(":
            continue
        yield line[opener.end() : i - 1].strip()


def coverage(text):
    """Return (published, covered) figures on this page, over both publishing surfaces.

    See the module docstring: an `embed`-less `screenshot=` fence is not a published
    figure, and a hand-written `![...](...)` line is.
    """
    published = covered = 0
    for _lineno, attrs in openers(text):
        parsed = parse_attrs(attrs)
        if parsed is None:
            continue
        if "screenshot" not in parsed:
            continue
        if parsed.get("embed", "").lower() not in TRUE_VALUES:
            continue
        published += 1
        if "alt" in parsed:
            covered += 1
    for _lineno, alt in markdown_images(text):
        published += 1
        if len(re.sub(r"`[^`]+`", "§", alt).split()) >= MIN_WORDS:
            covered += 1
    return published, covered


def live_fire():
    """A detector that reports zero has to be shown capable of reporting one.

    Both positive cases are Marc's own 2026-09-13 quotes, moved into an alt string.
    The negative cases are the two that matter now that HEADING_TELLS are gone: a
    descriptive alt full of semicolons must NOT fire, and a short label must not be
    scored as prose at all.
    """
    sample = "\n".join([
        '```diffstatic_runner {data-lang=kotlin screenshot="a.png" '
        'alt="The left panel is the sanity check, and it carries a trap"}',
        "// body",
        "```",
        '```diffstatic_runner {data-lang=kotlin screenshot="b.png" '
        'alt="A plot of the residual; the interesting part is that it does not compound"}',
        "// body",
        "```",
        '```diffstatic_runner {data-lang=kotlin screenshot="c.png" alt="Two flocks"}',
        "// under the four-word floor, not scored",
        "```",
        '```diffstatic_runner {data-lang=kotlin screenshot="d.png" '
        'alt="Two plan views of a 48-layer stack from overhead: on the left one orange '
        'square; on the right a scatter, never flattening into a line"}',
        "// a real description. Semicolons, a colon clause and an absence: must stay silent",
        "```",
    ])
    found = {f for f, *_ in scan(sample)}
    print("live-fire families:", sorted(found) or "NONE")
    assert found, "alt scanner fired on nothing; it cannot report a real zero"

    descriptive = [h for h in scan(sample) if h[1] == 13]
    assert not descriptive, f"fired on a plain description: {descriptive}"
    short = [h for h in scan(sample) if h[1] == 9]
    assert not short, f"scored a label under the word floor: {short}"

    # None of the four fences above carries `embed`, so none of them publishes an
    # `<img>` and none of them is a published figure. The old counter called them four.
    assert coverage(sample) == (0, 0), f"an embed-less fence was counted: {coverage(sample)}"

    published = "\n".join([
        # Quoted and bare both embed, and both are missing alt=, so both are uncovered.
        '```diffstatic_runner {data-lang=kotlin screenshot="e.png" embed="true"}',
        "// body",
        "```",
        "```diffstatic_runner {data-lang=kotlin screenshot=\"f.png\" embed=true}",
        "// body",
        "```",
        # Embedding and described: covered.
        '```diffstatic_runner {data-lang=kotlin screenshot="g.png" embed=true '
        'alt="a wireframe cloth hanging from five red pins along its top edge"}',
        "// body",
        "```",
        # Hand-written image lines: the first covered, the second a bare label, the
        # third empty. All three are figures a reader meets.
        "![a coiled space curve with a bright green spanning tree following it](h.png)",
        "![The two flocks](i.png)",
        "![](j.png)",
        # Balanced brackets inside the alt. CommonMark renders this; a `[^\\]]*` alt
        # group stopped at the inner `]` and dropped the figure from BOTH counts.
        "![a piano roll of `bd [sd sd]` over 2 cycles, drawn above `bd sd`](k.png)",
        # Not an image: no `(` after the closing bracket, so a reference-style link
        # and a bare bracketed phrase must both stay out of the denominator.
        "![a reference-style figure][ref]",
        # Inside a fence, so a sample rather than a figure.
        "```markdown",
        "![](not-a-figure.png)",
        "```",
    ])
    got = coverage(published)
    assert got == (7, 3), f"coverage miscounted the publishing surfaces: {got}"
    print("live-fire coverage: 7 published, 3 covered; embed-less fences, in-fence "
          "samples and reference-style links all excluded, bracketed alt counted")

    # An alt string describing Kotlin code contains braces, and the whole board used
    # to die on one: `[^}]*` cut the block at the inner `}`, severing `alt="` from its
    # closing quote, and `shlex.split` raised `No closing quotation` for the entire
    # tree. petri_nets.md:143 is the real line this is taken from.
    braced = "\n".join([
        '```diffstatic_runner {data-lang=kotlin screenshot="k.png" embed=true '
        'alt="a figure of eight in which .every(4) { it.fast(2) } doubles the left ring"}',
        "// body",
        "```",
    ])
    got = coverage(braced)
    assert got == (1, 1), f"a brace inside an alt string broke the attribute block: {got}"
    (_lineno, attrs), = openers(braced)
    parsed = parse_attrs(attrs)
    assert parsed is not None, "a braced attribute block was reported unparseable"
    assert parsed["alt"].endswith("doubles the left ring"), parsed["alt"]
    assert parsed["embed"] == "true", "embed= fell off the end of a braced block"
    print("live-fire braces: an alt string containing `{ }` parses whole")

    # A genuinely unbalanced quote is an authoring error the docs build should reject,
    # but a read-only board must still report on the other 139 pages.
    malformed = braced.replace('embed=true', 'embed=true stray="')
    assert coverage(malformed) == (0, 0), "an unparseable opener was not skipped"
    print("live-fire malformed: an unbalanced quote is skipped, not raised")
    return 0


def main():
    if "--live-fire" in sys.argv:
        return live_fire()
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("-")
                        else "public/docs")
    generated = "--generated" in sys.argv
    assets = "--assets" in sys.argv

    paths = [pathlib.Path(r["path"]) for r in sv.collect(root, generated=generated, assets=assets)]
    rows, scanned, published, covered = [], 0, 0, 0
    bare = []
    for path in paths:
        text = path.read_text(errors="replace")
        for _lineno, attrs in openers(text):
            if ALT.search(attrs):
                scanned += 1
        p, c = coverage(text)
        published += p
        covered += c
        if p - c:
            bare.append((p - c, path))
        hits = list(scan(text))
        if hits:
            rows.append((sum(h[2] for h in hits), path, hits))

    rows.sort(key=lambda r: -r[0])
    for score, path, hits in rows:
        print(f"{score:5d}  {path}")
        for name, lineno, _w, snip, alt in hits:
            print(f"          {name} [alt] :{lineno}  {snip}")
            print(f"               | {alt[:110]}")
    print(f"\nscanned {scanned} alt string(s) on {len(paths)} page(s); "
          f"{len(rows)} page(s) nonzero, sum {sum(r[0] for r in rows)}")

    pct = round(100 * covered / published, 1) if published else 0.0
    print(f"\nALT COVERAGE: {covered} of {published} published figures carry alt text "
          f"({pct}%). {published - covered} publish with the box name or nothing.")
    print("  Counted: embedding capture fences, and hand-written image lines. "
          "An embed-less fence publishes no <img> and is not counted; see the docstring.")
    if "--coverage" in sys.argv:
        bare.sort(key=lambda r: -r[0])
        for n, path in bare[:30]:
            print(f"  {n:4d} without alt  {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
