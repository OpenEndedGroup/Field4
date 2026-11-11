# Drop caps

**8 · A dropped initial** is a paragraph with a three-line initial and an elastic connection
attached to that initial. It is one logical text run. The renderer packs the large initial and each body line into
separate Slug atlases to stay within the format’s per-path band-offset limit. All parts share
the paragraph coordinate system and pose. The usual colour, opacity, blur and pose apply to the
whole run. Font geometry is prepared once; it is not scaled/rasterized anew every frame.

```kotlin
val graph = readingDiagram {
    val paragraph = text("paragraph",
        "The river carries\nsmall things toward\nthe edge of light\nand brings them home.",
        point("paragraph-point", 0, .2),
    ) {
        dropCap(lines = 3, gapEm = .2f) {
            bold = true
            // font, italic and weight are also available, just like other typography overrides.
        }
    }
    rectangle("initial-box", paragraph.initial.inkBounds, padding = .005f) {
        width = .001f
    }
}
```

The initial's actual ink fits from the first body's cap-height to the third body's baseline.
The first three body lines are indented by the initial's width plus the gap. Subsequent lines
return to the left margin. `gapEm` is measured in the body font's em, so it scales with the run.
The glyph's left overhang is accounted for. Body line spacing stays at the normal body font's
line height, rather than increasing to accommodate the large initial on every line.

Line breaks are authored: **no automatic word wrapping or rewording** is introduced. A one-line
run still has a tall initial, but no extra body lines are invented. Blank authored lines count
among the dropped lines. Only the opening initial of the run is dropped; use another run for
another paragraph. The run must start with its initial, without leading whitespace.

`characters = 1` is the default. This counts grapheme clusters, not UTF-16 code units: an
accented letter with combining marks remains intact. Increase it for a multi-letter initial:
`dropCap(lines = 2, characters = 2)`. It cannot cross a newline. Opening punctuation is not
silently skipped: if it is first, it is part of the initial you selected.

`paragraph.initial` is a regular `DiagramTextSpan`, preserving the original source indices.
`paragraph.word("river")`, `line(1)`, `characters(...)`, bounds, underlines and carets use the
actual prepared placements. Multiline inline font overrides also work in a drop-cap run.
The initial's explicit typography takes precedence over inline styles covering that initial.
Measurements account for its lower baseline; no-descender boxes retain its dropped body.

Connect a thread to it just like any other span:

```kotlin
connection("initial-thread",
    paragraph.initial.attach(DiagramTextSite.BOUNDARY),
    aside.attach(DiagramTextSite.LEFT_MARGIN),
) {
    responseSeconds = 1.2f
}
```

Call `dropCap` while constructing the graph, before preparing the player. Changes to its font,
line count, gap or selected characters require rebuilding the player and Drawing, just like
other font/layout changes. These settings participate in the preparation cache key. Run pose,
scale, opacity and blur stay live. Independent animation of the initial would require a
separate text item; the initial here deliberately remains part of its paragraph. Gaussian blur applies to each
render part in the same coordinates, retaining the paragraph’s shared opacity and radius.

The example reserves `.8` body ems of blur and has a **Paragraph blur (body ems)** control.
It blurs the initial and all four body lines together, while the separate italic aside and
connecting thread remain sharp. Native atlas partitioning does not change the logical blur
channel; each render part gets the same radius and pose.
