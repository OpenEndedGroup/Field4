import afield.geometry.solid.manifold.JoinType
import afield.geometry.solid.manifold.ManifoldLibrary
import afield.geometry.solid.manifold.Section

prop<TextLayout>(stencilWord)
prop<Section>(stencilBlank)
prop<List<List<Any>>>(stencilPieces)
prop<List<List<Any>>>(stencilGlyphs)

stencilWord = layoutText("BRIDGE", typeface("SansSerif", 120.0))
stencilPieces = []
stencilGlyphs = stencilWord.glyphs.mapIndexed { g, p ->
    [g, p.text, p.origin.x.toDouble(), p.origin.x.toDouble() + p.glyph.advance]
}

run {
    if (!ManifoldLibrary.isAvailable) {
        println(ManifoldLibrary.describe())
        return@run
    }
    // The plate: the word's bounds, grown by a 14-unit margin with round corners.
    val (lo, hi) = Section.ofFLines(stencilWord.toFLines(), sampleDistance = 0.5).bounds
    stencilBlank = Section.square((hi.x - lo.x).toDouble(), (hi.y - lo.y).toDouble())
        .translated(lo.x.toDouble(), lo.y.toDouble())
        .offset(14.0, JoinType.ROUND)

    val letters = Section.ofFLines(stencilWord.toFLines(), sampleDistance = 0.5)
    val pieces = (stencilBlank - letters).decompose()
    stencilPieces = pieces.mapIndexed { i, s ->
        val (a, b) = s.bounds
        [i, (a.x + b.x) / 2.0, (a.y + b.y) / 2.0, s.area]
    }
    markdown(
        "cut",
        """
        ~~~
    word                        BRIDGE, ${stencilWord.glyphs.size} glyphs
    counters, from the font     ${stencilWord.holeCount}
    plate minus letters         ${pieces.size} pieces
        ~~~
    """,
    )
}
println("cut: ${stencilPieces.size} pieces")
