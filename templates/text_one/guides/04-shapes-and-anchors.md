# FLines in relationship to text

Use particles for the things that have physical state. Use anchors for meaningful locations
on that state. Use paths to resolve those anchors into fresh centrelines each frame.
The renderer thickens those centrelines independently for the two current eye views.

## A box, a rule and a dot

```kotlin
val graph = readingDiagram {
    val p = point("title-point", 0, 0)
    val title = text("title", "a small room", p)
    rectangle("title-box", title.inkBounds, padding = .010f) {
        width = .001f
        color = vec(1f, 1f, 1f, .25f)
    }
    val rule = underline("room-rule", title.word("room"), gap = .018f) {
        width = .0015f
        dashed(.025f, duty = .6f)
    }
    dot("beside-word", title.word("room").baselineRight.alongText(title, x = .025f)) {
        radius = .004f
        shape = DiagramPointShape.RING
    }
    dot("on-rule", rule.at(.5f)) { radius = .003f }
}
```

Padding, gap, line width, dash period and marker radius are metres. Rectangle padding and
underline gaps stay physical sizes as the text scales; their orientation follows the text.
Path anchors use full **untrimmed world arclength**. A bead halfway along a rule does not
move merely because the rule is drawing in. Give the bead its own score cue when it should
appear only after the stroke reaches it.

## Arbitrary local shapes

`textPath` supplies measurements as its receiver and transforms the resulting FLine through
the exact glyph transform. Coordinates here are font units, with Y up:

```kotlin
textPath(
    "cap-ruler",
    title,
    shape = {
        FLine().moveTo(advance.left, capHeight + 10f).lineTo(advance.right, capHeight + 10f)
    },
) {
    width = .0008f
    dashed(.018f)
}
```

Draw a local bracket against the ink bounds:

```kotlin
textPath(
    "left-bracket",
    title,
    shape = {
        val x = ink.left - 12f
        FLine()
            .moveTo(x + 8f, ink.bottom - 6f)
            .lineTo(x, ink.bottom - 6f)
            .lineTo(x, ink.top + 6f)
            .lineTo(x + 8f, ink.top + 6f)
    },
)
```

Or transform an existing local FLine inside a path:

```kotlin
path(
    "custom",
    shape = {
        val local = FLine().moveTo(0, 0).lineTo(40, 20).lineTo(80, 0)
        textLine(title, local)
    },
)
```

`textLine` transforms cubic control points too. It returns a copy; it does not gradually
accumulate a transform into your source FLine. These paths follow text translation,
rotation, item scale and score scale. Add a score transform to the path itself only when
you intend an additional independent motion.

## Metre-space drawing between anchors

For geometry that spans several text items or graph points, resolve anchors directly:

```kotlin
line("join", first.word("water").inkBounds.right, second.word("light").inkBounds.left) {
    width = .001f
    arrow = .012f
}
arc("above", first.inkBounds.top, second.inkBounds.top, rise = .08f)
```

`line` and `arc` retain their live endpoint relationships. A custom `path` can call
`position(anchor)` for the same reason. Paths are cached within a frame; cyclic anchor/path
dependencies fail with the offending path name instead of recursing indefinitely.

## Stroke and marker vocabulary

Stroke ink has width, RGBA colour, dash period/duty/phase, draw-from/draw-to fractions and
arrow size. Node colours and widths can override the line's ink. Separate contours are not
bridged. Closed contours remain closed.

Markers support `DISC`, `RING`, `DIAMOND` and `LEAF`. Each marker has a live radius and
colour; score entrances can add opacity, offset and scale independently.

The current text_one shader treatment makes all ink white. Structure comes from opacity,
coverage and thickness. Stored RGB values are still useful if you later change the fragment
shaders back to colour. The green/paint compositor can also change the apparent translucency:
use additive mode while diagnosing whether an opacity change is reaching the source image.

Unrevealed tessellation segments are rejected in the vertex shader. Rounded reveal caps are
antialiased at the active boundary; the rest of the curve must not appear as anticipatory dots.
