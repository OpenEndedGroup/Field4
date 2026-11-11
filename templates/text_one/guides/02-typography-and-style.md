# Typography and style

A diagram text item is a complete shaped run, positioned by a graph anchor. Repeated words
can use different typefaces without sharing the wrong GPU atlas. Inline styles remain part
of the enclosing run, so word selections and attached drawing see their actual placement.

## Choose the face

```kotlin
val serif = Typefaces.named("Noto Serif", 100, strict = true)
val sans = Typefaces.named("Noto Sans", 100, strict = true)
val italicFace = serif.withStyle(italic = true)
val boldItalicFace = serif.withStyle(bold = true, italic = true)

// An exact file-backed face is useful when the family name is ambiguous:
// val face = Typefaces.fromFile("/path/to/MyFont.otf", 100)
```

The example player supplies a default face. A text item may override it:

```kotlin
val graph = readingDiagram {
    val p = point("title-point", 0, 0, pinned = true)
    text("title", "a room for words", p) {
        font = serif
        italic = true
        scale = .8f
        tracking = 1.5
    }
}
```

The preparation size is in font units. The default `player.textScale` is `.0009f` metres per
font unit; an item and its score multiply that scale. Changing the preparation size changes
physical size unless you compensate. Keeping a common preparation size makes comparisons easier.

`strict = true` refuses a missing installed family instead of silently accepting a logical
fallback. A file-backed face avoids name resolution. The selected font must contain the
characters: this is not an automatic font-fallback or complete complex-script engine.
The current shaping direction is left-to-right.

## Inline style

```kotlin
val graph = readingDiagram {
    val p = point("sentence-point", 0, 0)
    text("sentence", "The quiet water carries the light", p) {
        font = serif
        word("quiet").style { italic = true }
        word("water").style { bold = true }
        word("light").style {
            font = sans
            weight = 300.0
        }
    }
}
```

`word(value, occurrence)` selects complete word matches; `span(value, occurrence)` selects
literal text. Occurrences are zero-based. `characters(start, endExclusive)` addresses UTF-16
indices, as JVM strings do. `run.words` enumerates Unicode letter/number words, including
internal apostrophes.

Each `.style { ... }` can set `font`, `bold`, `italic` and `weight`. Later overlapping styles
win for their section; supply the complete intended override when overlapping. Bold and
italic settings can be explicitly `false` inside a style. The font can also carry its own style.

A style boundary starts a new shaping context. Kerning and ligatures survive within each
section, but not across that boundary. Boundaries cannot split a Unicode surrogate pair.
Span anchors and carets reject boundaries inside a shaped glyph cluster rather than guessing.
Inline styles currently require one baseline; use separate styled runs for multiple baselines.
Plain multiline runs are supported.

## Numeric weights

`weight` and `Typeface.withWeight(...)` accept 100–900, including intermediate numbers:

```kotlin
val medium = serif.withWeight(500)
val graph = readingDiagram {
    val p = point("weight-point", 0, 0, pinned = true)
    text("weight-word", "wave", p) {
        font = serif
        weight = 550.0
    }
}
```

This asks AWT to resolve an available face or synthesize weight. It is **not arbitrary
OpenType variation-axis instancing**, and nearby numeric requests may resolve to the same
face. Supply a named or file-backed weight when the exact result matters. The rendered
geometry is the resolved font's actual outline; this is not fragment-shader dilation.

## What is live, and what needs preparation?

Font, text, tracking and inline styles are preparation-time choices. Stop Drawing, change
and re-run the affected example, re-run Scores, then re-run Drawing. The player shapes the
text and compiles its Slug atlas outside the render loop. The renderer creates the matching
GPU buffers for those prepared shapes.

Position, rotation, item scale, text colour and score opacity remain live. A text run has
one live colour; inline styles here are font/style overrides, not independently coloured spans.
For separately coloured or independently animated words, use separate text items.
