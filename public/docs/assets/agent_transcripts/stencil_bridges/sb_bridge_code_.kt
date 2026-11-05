import afield.geometry.solid.manifold.ManifoldLibrary
import afield.geometry.solid.manifold.Section

run {
    if (!ManifoldLibrary.isAvailable) {
        println(ManifoldLibrary.describe())
        return@run
    }
    val letters = Section.ofFLines(stencilWord.toFLines(), sampleDistance = 0.5)
    val (lo, hi) = letters.bounds

    // One bar, 5 units wide, down the middle of each letter Datalog listed.
    val bars = Section.union(
        needsBridge.map { g ->
            val p = stencilWord.glyphs[g]
            val mid = p.origin.x + p.glyph.advance / 2.0
            Section.square(5.0, (hi.y - lo.y).toDouble() + 2.0).translated(mid - 2.5, lo.y.toDouble() - 1.0)
        },
    )
    val before = (stencilBlank - letters).decompose()
    val after = (stencilBlank - (letters - bars)).decompose()

    // Plate in grey, islands in red; the bridged plate below it.
    fun draw(pieces: List<Section>, dy: Double) = pieces.mapIndexed { i, s ->
        val plate = s.area == pieces.maxOf { it.area }
        s.toFLine()
            .transform { it + vec(0.0, dy, 0.0) }
            .also {
                it.filled = true
                it.stroked = false
                it.color = if (plate) vec(0.62, 0.64, 0.70, 1.0) else vec(0.95, 0.30, 0.30, 1.0)
            }
    }
    val gap = (hi.y - lo.y) + 60.0
    lines(draw(before, 0.0) + draw(after, gap), w = 900.0, h = 520.0, fitToSize = true)
    markdown(
        "bridged",
        """
        ~~~
    bars cut              ${needsBridge.size}, one per listed letter
    pieces before         ${before.size}
    pieces after          ${after.size}
    plate area before     ${before.maxOf { it.area }.f2}
    plate area after      ${after.maxOf { it.area }.f2}
        ~~~
    """,
    )
    println("bridged: ${before.size} pieces before, ${after.size} after")
}
