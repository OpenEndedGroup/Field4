// A Typeface is a font at a size. Logical families (SansSerif, Serif, Monospaced) always
// resolve; Typefaces.fromFile("...ttf") loads one off disk through Font.createFont, and
// Typefaces.firstAvailable("Futura", "Avenir") picks the first face you actually have.
val face = typeface("SansSerif", 140.0)
println("$face — ascent ${"%.1f".format(face.ascent)}, descent ${"%.1f".format(face.descent)}, " +
    "line height ${"%.1f".format(face.lineHeight)}")

// A Glyph carries its rings twice over, and the two are guaranteed to agree: `contours` are
// flattened polygons classified by nesting depth, `outline` is the same rings as an FLine of
// the font's real cubics.
for (c in listOf('o', 'l', 'B')) {
    val g = face.glyph(c)
    println("'$c' glyph #${g.glyphCode}: ${g.contourCount} contours (${g.holeCount} holes), " +
        "advance ${"%.1f".format(g.advance)}, ink area ${"%.0f".format(g.inkArea)}")
    g.contours.forEachIndexed { i, ring ->
        println("    ring $i: depth ${ring.depth}, ${if (ring.isHole) "HOLE " else "outer"}, " +
            "signed area ${"%+.0f".format(ring.signedArea)}, ${ring.points.size} points")
    }
}

// The convention this family enforces, stated once: outer rings positive, holes negative,
// with nesting depth (containment, not direction) as the authority. That is why the counter
// of an `o` is a hole under either fill rule instead of a filled blob.

// And the headline: a word, filled from those contours by the family's own scanline filler.
// Not Graphics2D.drawString — this raster is evidence about the extraction.
val word = layoutText("Handgloves", face)
showImgGrid(
    "Handgloves — filled from its own contours" to rasterizeText(word, 1600, 380),
    columns = 1, cellSide = 860
)
println("laid out: ${word.contourCount} contours, ${word.holeCount} holes, " +
    "${"%.1f".format(word.advanceWidth)} units wide, ink ${"%.1f".format(100 * rasterizeText(word, 400, 120).inkFraction())}%")

// The same word again, but as VECTORS this time: showText fills the FLines through
// drawToSVG, so the counters come out holes there too — Skia fills non-zero, and that is the
// convention doing the work rather than the rasteriser being clever.
//
// Its own channel, and that is not decoration: `display(channel, …)` REPLACES a channel
// rather than appending to it, so a second figure on "main" silently erases the first. Two
// figures in one box means two channels. displayOpen marks the card open before the content
// lands, so it arrives expanded instead of as a collapsed header.
displayOpen("vectors")
showText(
    word, channel = "vectors",
    title = "the same layout as filled FLines, through drawToSVG", showBaseline = true, height = 220
)
