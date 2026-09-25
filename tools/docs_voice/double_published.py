#!/usr/bin/env python3
"""Find figures the docs build publishes TWICE on one page.

`docs_hooks.py:2764` is the whole of it:

    if fence_name in RUNNER_FENCES and options.embed:
        if options.screenshot and screenshot_available:
            out.append(f"![{options.alt or options.box_name}]({...options.screenshot})\n")

So an `embed=true` capture fence emits its own markdown image line. An author who
then writes `![a real description](the same asset)` underneath it does not *replace*
that line, they *add* to it, and the page renders the same PNG twice, back to back.
`public/field4/chain_ca_sound/index.html` carried two `<img>` for each of its three
figures for as long as the page existed -- the first with `alt="chain-ca-rules"`, the
second with the description -- and no gate said anything.

This is not a voice fault and no other detector here could have found it:

  - `score_voice` reads prose. An image line is one line of prose that looks fine.
  - `alt_text` reads fence openers, and correctly scored these fences as missing
    `alt=`. It could not see that the missing text was sitting two lines below in a
    hand-written line, nor that the figure was doubled. Its coverage number counted
    both surfaces and so counted the duplicate as two published figures.
  - `check_fences` compares fences, and neither line here is a fence.

It is a reader-facing defect of the plainest kind -- the reader sees the picture twice
-- so it is worth its own board.

WHAT IS AND IS NOT A HIT

A hit needs all three: a capture fence with `screenshot=`, `embed` true, and a
hand-written `![...](...)` line anywhere on the same page whose target has the same
basename. Basename rather than full path, because the fence writes a docs-root-relative
spec (`assets/x/y.png`) and a hand-written line in a subdirectory page may write
`../assets/x/y.png` for the identical asset.

NOT a hit:
  - an `embed`-less `screenshot=` fence beside a hand-written line. That is the
    supported way to caption a figure yourself: the fence captures, publishes nothing,
    and your line is the only `<img>`. Several pages do this deliberately.
  - a hand-written line for an asset no fence on the page captures -- an illustration,
    a diagram, a figure captured by a different page.
  - two fences sharing an asset. That is a different fault and this tool does not claim
    to see it.

THE FIX, when there is a hit: move the hand-written line's alt text into the fence's
`alt=` and delete the line. That collapses the duplicate, gives the published `<img>`
real alternative text instead of the box-name slug, and leaves one copy of the path.
Adding `alt=` to an opener is safe: it is not one of the 34 fields in
`docs_hooks.options_digest_payload`, so no capture is invalidated, and `check_fences`
decides its exit code from recorded caret prefixes taken out of fence *bodies*
(`fence_text()` slices `lines[i+1:j]`), which an opener is not part of. It will still
LIST an opener-only edit as `changed body`, because `kotlin_fences()` prepends the
opener line to what it hashes -- that listing is informational and the script says so.

Self-test: `--live-fire`, which pins a hit, and pins both of the near misses above.
"""
from __future__ import annotations

import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import score_voice as sv  # noqa: E402

# `attrs` runs to the LAST `}` on the line. With the first-`}` form this used to
# carry, an alt string containing a Kotlin lambda -- petri_nets.md's
# `.every(4) { it.fast(2) }` -- truncated the block before `embed=true`, and the
# fence was silently dropped from the scan. `docs_hooks` never had the bug: it
# splits the line on the fence name and flattens every brace to a space.
OPENER = re.compile(r"^\s*(?:```|~~~)[a-z_]*\s*\{(?P<attrs>.*)\}\s*$")
IMAGE = re.compile(r"!\[(?P<alt>[^\]]*)\]\((?P<target>[^)\s]+)")
TRUE_VALUES = {"1", "true", "yes", "on"}


def _attr(attrs, name):
    """The value of one fence attribute, quoted or bare.

    A bare `embed=true` is real syntax -- `afield_four_graphics.md` writes it that way
    -- and a regex that only matches `embed="true"` under-reports by a factor of three.
    The forty-sixth firing's census made exactly that mistake.
    """
    m = re.search(r'\b' + re.escape(name) + r'=(?:"([^"]*)"|(\S+?))(?=\s|$)', attrs)
    if not m:
        return None
    return m.group(1) if m.group(1) is not None else m.group(2)


def embedding_captures(text):
    """Yield (lineno, basename, alt) for each fence that publishes an <img> of its own."""
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
        if not m:
            continue
        attrs = m.group("attrs")
        shot = _attr(attrs, "screenshot")
        if not shot:
            continue
        if str(_attr(attrs, "embed") or "").lower() not in TRUE_VALUES:
            continue
        yield lineno, shot.split("/")[-1], _attr(attrs, "alt")


def hand_written_images(text):
    """Yield (lineno, basename, alt) for each markdown image line outside a fence."""
    in_fence = False
    for lineno, raw in enumerate(text.split("\n"), 1):
        stripped = raw.lstrip()
        if stripped.startswith("```") or stripped.startswith("~~~"):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        for m in IMAGE.finditer(raw):
            yield lineno, m.group("target").split("/")[-1], m.group("alt")


def scan(text):
    """Yield (image_lineno, basename, fence_lineno, fence_alt, image_alt) per duplicate."""
    captured = {}
    for lineno, base, alt in embedding_captures(text):
        captured.setdefault(base, (lineno, alt))
    for lineno, base, alt in hand_written_images(text):
        if base in captured:
            fence_lineno, fence_alt = captured[base]
            yield lineno, base, fence_lineno, fence_alt, alt


def live_fire():
    """One hit, and both near misses. A detector that reports zero must report one."""
    hit = "\n".join([
        '```diffstatic_runner {data-lang=kotlin screenshot="assets/p/a.png" '
        'embed=true name="the-box"}',
        "// body",
        "```",
        "",
        "![A real description of the figure](assets/p/a.png)",
    ])
    found = list(scan(hit))
    print("live-fire duplicates:", [(h[1], h[3], h[4][:24]) for h in found] or "NONE")
    assert len(found) == 1, f"the plain duplicate did not fire: {found}"
    assert found[0][1] == "a.png" and found[0][3] is None, found[0]

    # Bare `embed=true` above must be read as true. Quoted must be too.
    quoted = hit.replace("embed=true", 'embed="true"')
    assert len(list(scan(quoted))) == 1, "a quoted embed= stopped being read as true"

    # Near miss 1: no `embed`, so the fence publishes nothing and the hand-written line
    # is the only <img>. This is the supported way to caption a figure yourself.
    no_embed = hit.replace(" embed=true", "")
    assert not list(scan(no_embed)), "fired on an embed-less fence, which publishes no img"

    # Near miss 2: a hand-written line for an asset no fence on the page captures.
    unrelated = hit.replace("(assets/p/a.png)\n", "(assets/p/b.png)\n").replace(
        "![A real description of the figure](assets/p/a.png)",
        "![An illustration nothing captures](assets/p/b.png)")
    assert not list(scan(unrelated)), "fired on an image line with no matching fence"

    # A differently-spelled path to the SAME asset must still fire: the fence writes a
    # docs-root-relative spec and a page in a subdirectory writes a ../ one.
    relative = hit.replace("![A real description of the figure](assets/p/a.png)",
                           "![A real description of the figure](../assets/p/a.png)")
    assert len(list(scan(relative))) == 1, "a ../ spelling of the same asset was missed"

    # An alt string describing Kotlin code contains braces. With the first-`}` form
    # this file used to carry, the block was cut before `embed=true`, the fence was
    # dropped, and the duplicate went unreported. petri_nets.md:143 is the real line.
    braced = hit.replace(
        'embed=true name="the-box"',
        'embed=true alt="a ring where .every(4) { it.fast(2) } doubles the onsets"')
    found = list(scan(braced))
    assert len(found) == 1, f"a brace in an alt string hid the fence: {found}"
    assert found[0][3] and found[0][3].endswith("doubles the onsets"), found[0][3]

    print("live-fire: 1 duplicate; embed-less fence, unrelated image both silent; "
          "a braced alt string still parses")
    return 0


def main():
    if "--live-fire" in sys.argv:
        return live_fire()
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("-")
                        else "public/docs")
    surface = ("--generated" if "--generated" in sys.argv
               else "--assets" if "--assets" in sys.argv else "")
    # `collect()` returns scored rows, not paths; only `row["path"]` is wanted here.
    rows = sv.collect(root, generated="--generated" in sys.argv,
                      assets="--assets" in sys.argv)
    total = 0
    for path in [row["path"] for row in rows]:
        text = pathlib.Path(path).read_text(encoding="utf-8", errors="replace")
        hits = list(scan(text))
        if not hits:
            continue
        total += len(hits)
        print(f"\n{path}")
        for lineno, base, fence_lineno, fence_alt, image_alt in hits:
            state = "and carries alt=" if fence_alt else "with the box name as alt"
            print(f"  :{lineno} {base} is also published by the fence at "
                  f":{fence_lineno} ({state})")
            print(f"      the hand-written line says: {image_alt[:96]}")
    print(f"\n{total} figure(s) published twice{' on ' + surface.lstrip('-') if surface else ''}.")
    if total:
        print("Fix: move the hand-written alt into the fence's alt= and delete the line.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
