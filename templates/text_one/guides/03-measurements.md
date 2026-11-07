# Measuring runs, words and baselines

Measurements come from the same prepared layouts and cubic outlines that Slug draws.
They are cached CPU data. Reading a measurement does not advance the score or physics,
compile an atlas, read GPU pixels or wait for a GPU fence.

```kotlin
val frame = player.frame()
val run = player.diagram.text("sentence")
val whole = frame.measure(run)
val water = frame.measure(run.word("water"))
```

`player.frame(time)` evaluates the score at the supplied time while using the particle
positions currently held by the player. It does not rewind the physics simulation.
Treat a frame as a snapshot: make another after changing the score time or particle state.

## Font-space measurements

X starts at the **complete run's baseline-left**; Y points upward. A selected word retains
its X position in that full run, so its left coordinate is usually not zero.

| Measurement | Meaning |
| --- | --- |
| `ink` | Tight bounds of the actual cubic outlines, including italic overhang and descenders |
| `advance` | Pen interval including selected whitespace; zero height for one baseline |
| `width` | Advance width, not ink width |
| `noDescenders` | Ink rectangles clipped at each baseline, then unioned |
| `ascent`, `descent`, `leading` | Positive metrics of the selected fonts |
| `capHeight` | Highest ink of H in the selected fonts |
| `xHeight` | Highest ink of x in the selected fonts |
| `baseline`, `lastBaseline` | First and last selected baselines; equal for a single line |
| `hasInk` | False for a whitespace-only selection, which can still have a positive advance |

These are font units. Multiply by the run's effective scale for physical lengths, or use the
anchors and FLine transforms below. Do not add a font-space coordinate directly to a metre-space
particle position.

An italic glyph may extend beyond its advance interval. A word with no descenders may still
overshoot slightly below the baseline. `bodyBounds` deliberately clips that below-baseline
part of the ink rectangle; it is not a different font or a reconstruction of the glyph outline.

## Boxes and anchors

Runs and selections expose:

```kotlin
val ink = run.inkBounds
val body = run.word("water").bodyBounds
val em = run.emBounds
val caps = run.capBounds
val lowercase = run.xHeightBounds
val pen = run.advanceBounds
```

Every box supplies `topLeft`, `topRight`, `bottomLeft`, `bottomRight`, `left`, `right`,
`top`, `bottom`, `center`, and `.at(u, v)`. The normalized origin is bottom-left, 0,0;
top-right is 1,1. Fractions outside that range deliberately extrapolate.

These anchors resolve against the current text pose and particle state. They rotate with
the run and follow item/score scale. A box is a rectangle in the text's own axes, not a world
axis-aligned bounding box enclosing a rotated rectangle.

```kotlin
val top = frame.position(run.word("water").inkBounds.top)
val baseline = frame.position(run.word("water").baseline)
```

The older convenience anchors `left`, `right` and `center` sit 35% up the font ascent;
`above` sits 80% up it. For precise contact with glyphs, use the measured box anchors.
`baselineLeft`, `baselineRight` and `baseline` use the shaped advance positions.
`below` supplies a small text-axis metre gap below the baseline.

## Words, carets and multiple lines

```kotlin
val firstWater = run.word("water")
val secondWater = run.word("water", occurrence = 1) // only if that word occurs twice
val literal = run.span("quiet water")
val tokens = run.words
val caret = run.caret(4)
```

Carets use UTF-16 indices and must lie on shaped cluster boundaries. The caret at a newline
is at the preceding line's end; the caret at the string's end uses the last baseline.
They are layout anchors, not an editor selection or an IME implementation.

Plain multiline runs have union bounds. Their advance rectangle spans the baseline separation;
`baseline` denotes the first baseline and `lastBaseline` the last. Select `run.line(index)`
for one line's own metrics and anchors. Empty lines do not contain a selectable text span.

## Font units and metre offsets

```kotlin
val local = run.word("water").local(x = 10, y = 20)
val spaced = run.word("water").baselineRight.alongText(run, x = .012f)
val worldUp = run.word("water").baselineRight.offset(y = .012f)
```

`local` uses font units from the selection's baseline-left. `alongText` uses metres in the
run's rotated axes. `offset` uses metres in the diagram's axes. This distinction matters when
text turns: a local underline should turn with it; a label held upward against gravity may not.
