// One word different from box 1, and it is the behaviour that shipped sharp_edge.png.
val outline = listOf(
    -1.0 to -1.5, 1.0 to -1.5, 1.0 to 1.5, -1.0 to 1.5, -1.0 to -1.5)

val c = Chart(title = "the same corner, unclipped", xLabel = "x", yLabel = "y")
    .line("outline", outline.map { it.first }, outline.map { it.second })
c.width = 780.0; c.height = 440.0
c.xDomain = 0.82 to 1.06
c.yDomain = 1.32 to 1.56
c.clip = false                                  // <-- poke this one word

showChart(c)

// The panel does not crop what falls outside the frame. It fits the geometry's own
// bounds, so the axes are scaled down until the stray outline fits beside them.
val ps = c.toFLines().flatMap { l -> l.nodes.mapNotNull { it.toOrNull } }
val w = ps.maxOf { it.x } - ps.minOf { it.x }
val h = ps.maxOf { it.y } - ps.minOf { it.y }
println("drawn bounds: %.0fx%.0f for a %.0fx%.0f frame".format(w, h, c.width, c.height))
println("a panel therefore draws this figure at %.0f%% of its declared size"
    .format(100.0 * minOf(c.width / w, c.height / h)))
