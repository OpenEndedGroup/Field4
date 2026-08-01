// Layout is AWT's advances and kerning pairs, plus the things AWT does not do: \n, letter
// spacing, alignment, and a mutable transform handle per glyph.
val face = typeface("SansSerif", 120.0)

// Identity one: with kerning off and no tracking, the line is the sum of its advances. Two
// different AWT paths — accumulated positions versus per-glyph metrics — that must agree.
val plain = layoutText("minimum", face.withKerning(false))
println("advance width ${"%.4f".format(plain.advanceWidth)} vs sum of advances " +
    "${"%.4f".format(plain.glyphs.sumOf { it.glyph.advance })}")

// Identity two: tracking adds one gap per INTER-GLYPH space, not one per glyph.
val t = 0.16 * face.size
val tight = layoutText("TYPE", face)
val loose = layoutText("TYPE", face, tracking = t)
println("tracking ${"%.1f".format(t)} over ${tight.glyphs.size - 1} gaps widened the line by " +
    "${"%.4f".format(loose.advanceWidth - tight.advanceWidth)} (expected ${"%.4f".format((tight.glyphs.size - 1) * t)})")

// Kerning can only pull letters together. Logical families often have no kern table at all,
// so this prints what actually happened rather than asserting it did.
val on = layoutText("AVATAR Wa To", face.withKerning(true)).advanceWidth
val off = layoutText("AVATAR Wa To", face.withKerning(false)).advanceWidth
println("kerning: $off -> $on (${if (off - on > 0.5) "this face kerns" else "this face has no kern pairs for these letters"})")

// The diagram of the decisions rather than the letters: baselines, ascent/descent guides and
// one advance box per glyph — including for the space, which has no glyph to draw.
showPlaneGrid(
    "tracking 0 — ${"%.0f".format(tight.advanceWidth)} units wide" to
        (tight.metricsFLines() + tight.advanceBoxFLines() + tight.toFLines(thickness = 1.0)),
    "tracking 0.16em — ${"%.0f".format(loose.advanceWidth)} units wide" to
        (loose.metricsFLines() + loose.advanceBoxFLines() + loose.toFLines(thickness = 1.0)),
    columns = 1, cellWidth = 820, cellHeight = 240,
    window = afield.Rect(-20.0, -face.ascent - 20, loose.advanceWidth + 40, face.lineHeight + 40)
)

// Multi-line and alignment, measured by the real layout so the wrap is right for the
// tracking and kerning you are going to use.
val prose = wrapText(
    "Type is geometry that happens to be legible, and legibility is the one test " +
        "a plausible looking picture cannot pass.",
    face.atSize(44.0), maxWidth = 620.0
)
println("wrapped to ${prose.lines().size} lines, widest ${"%.1f".format(prose.lines().maxOf { textWidth(it, face.atSize(44.0)) })} of 620")
showImgGrid(
    *TextAlign.entries.map { a ->
        a.name.lowercase() to rasterizeText(layoutText(prose, face.atSize(44.0), align = a, lineSpacing = 1.12), 700, 380)
    }.toTypedArray(),
    columns = 3, cellSide = 420
)
