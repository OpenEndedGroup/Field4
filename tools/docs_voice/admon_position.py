"""An always-open admonition, below `score_voice.py`'s threshold, sitting mid-how-to.

`score_voice.py` counts `!!!` blocks over **14** lines and ignores where they sit. Both halves
of that are worth widening, and the reason is Marc's own complaint on 2026-09-13:

> "Mirrors, and where a TRS triple loses one" ... should be a margin note not a huge
> admonishment in the middle of how to load a file.

*In the middle of.* A "how this was checked" block at the foot of a page costs a reader
nothing -- they already have what they came for. The same block at 20% has made them wade.
And 14 lines is generous: a ten-line always-open warning a fifth of the way into a gallery is
the same fault at the same cost.

So this reports `!!!` blocks with **7 or more body lines** whose opener falls in a page's
**first three quarters**. `???` is excluded by design, exactly as in `score_voice.py`: a
collapsed aside costs a reader nothing, which is the whole reason the disposal table's remedy
for an over-long admonition is to demote it.

## What it is worth, measured

Fourteen hits on the hand-written surface at `adb32a31e`. Ten were read one at a time on
2026-09-21; **4 true, 6 keeps** (the remaining four sit on pages banked on an unlanded
branch). The four true ones are four of that firing's seven pages and **none of them was
visible on any other board** -- every one scored 0 or was already at its residual.

The remaining rows were read on 2026-09-25, when this board supplied a whole batch on a
night both `score_voice.py` and `git log` came back empty: **3 true, 4 more keeps**. Only
one of the three left the board, which is worth knowing before reading a count as progress:
a demotion to `???` removes a row, while trimming a block that is a keep at eight lines
leaves it exactly where it was. True were `marf.md:150` (a warning about latching
whose second half was the floating-point *reason* the count is approximate, moved to
`dev_docs/MARF_FIELD_CLOCKS.md`), `neural_particles.md:249` (a requirements list that ended
by disclaiming its own argument) and `chain_skinned_volume.md:141` (a note explaining one
row of the table above it -- demoted to `???` and retitled for the question a reader has).
That last one is the cleanest example of the rule below: seven lines is not the fault, and
"three quarters of the way in" is not the fault; *explaining a figure* is.

The ten keeps are the shape to recognise and leave:

- `animation_time_model.md:17` "A frame is one unit of work" -- a page's opening premise,
  deliberately always-open, and the thing the rest of the page is built on.
- `animation_time_model.md:381`, `polytope_examples.md:794` -- a stated limitation followed
  by what to do instead. The task file says keep these.
- `neural_particles.md:337` "The textbook weights do not flock, and the defaults are the
  textbook weights" -- a real trap, a design fact, and a tuning instruction.
- `keyframer_examples.md:220` -- API semantics with source citations in house style.
- `completion_ranking.md:7` -- the same shape as `animation_time_model.md:17`: a page's
  opening premise, at 1%, that the rest of the page is built on.
- `animation_time_model.md:188` "`frame()` returns `true`, or throws" -- why `loop` can
  ignore the return value, and that the throw is what runs your `.end { }`. Behaviour.
- `marf.md:251` "A space instance must copy before it writes" -- a trap for anyone writing
  their own `MarfSpace`, with the out-parameter remedy under it.
- `polytope_examples.md:881` "When the canonical form is unique" -- the guarantee a solver
  does and does not give, which the task file names as a keep in as many words.

A hit is a candidate to **read**, never to fix unseen. The distinguishing question is not
length and not position: it is whether a reader who is mid-task wants this now. A limitation
they are about to hit, yes. A note on how the page's own figures were checked, no.
"""
import pathlib
import re
import sys

OPENER = re.compile(r"^(\s*)!!!\s+(\w+)(.*)$")
MIN_BODY_LINES = 7
MAX_POSITION = 0.75


def scan(text):
    """[(body_lines, position_percent, line_no, kind, title)] for each offending block."""
    lines = text.split("\n")
    total = len(lines) or 1
    out, i = [], 0
    while i < len(lines):
        m = OPENER.match(lines[i])
        if not m:
            i += 1
            continue
        indent, kind, title = len(m.group(1)), m.group(2), m.group(3).strip()
        j, body = i + 1, 0
        while j < len(lines):
            ln = lines[j]
            if not ln.strip():
                j += 1
                continue
            if len(ln) - len(ln.lstrip()) > indent:
                body += 1
                j += 1
            else:
                break
        position = i / total
        if body >= MIN_BODY_LINES and position < MAX_POSITION:
            out.append((body, round(position * 100), i + 1, kind, title.strip('"')))
        i = j
    return out


def live_fire():
    """A detector that reports zero has to be shown capable of reporting one."""
    offender = "# A page\n\n" + "\n".join([
        '!!! warning "How these numbers were checked"',
        *["    a line of methodology" for _ in range(8)],
    ]) + "\n" + "\n".join("body" for _ in range(60))
    collapsed = offender.replace("!!! warning", "??? warning")
    at_the_end = "\n".join("body" for _ in range(60)) + "\n" + "\n".join([
        '!!! warning "How these numbers were checked"',
        *["    a line of methodology" for _ in range(8)],
    ])
    short = "# A page\n\n" + '!!! note "A short one"\n    one line\n' + \
        "\n".join("body" for _ in range(60))

    assert scan(offender), "did not fire on a long always-open block at the top of a page"
    assert not scan(collapsed), "fired on `???`, which costs a reader nothing"
    assert not scan(at_the_end), "fired on a block at the foot of a page"
    assert not scan(short), "fired on a block under the body-line floor"
    print("live-fire: fires on the offender, silent on collapsed / trailing / short")
    return 0


def main():
    if "--live-fire" in sys.argv:
        return live_fire()
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("-")
                        else "public/docs")
    rows = []
    for p in sorted(root.rglob("*.md")):
        s = str(p)
        if "/generated/" in s or "/assets/" in s:
            continue
        for hit in scan(p.read_text()):
            rows.append((hit, s))
    rows.sort(key=lambda r: (-r[0][0], r[0][1]))
    print("Always-open `!!!` admonitions of %d+ body lines in a page's first %d%%."
          % (MIN_BODY_LINES, int(MAX_POSITION * 100)))
    print("A row is a candidate to READ. Measured 4 true / 6 keeps; see this file's docstring.\n")
    print("%5s %5s  %s" % ("lines", "at", "where"))
    for (body, pos, lineno, kind, title), s in rows:
        print("%5d %4d%%  %s:%d  !!! %s %s" % (body, pos, s, lineno, kind, title[:58]))
    print("\n%d hit(s)." % len(rows))
    return 0


if __name__ == "__main__":
    sys.exit(main())
