// A magnified corner of a body far larger than the window — the shape of figure that
// finds this bug, because it is the one that needs an explicit domain. The outline is
// 2 x 3; the window is 0.24 x 0.24 around one of its corners.
val outline = listOf(
    -1.0 to -1.5, 1.0 to -1.5, 1.0 to 1.5, -1.0 to 1.5, -1.0 to -1.5)

val c = Chart(title = "one corner of a 2 x 3 outline", xLabel = "x", yLabel = "y")
    .line("outline", outline.map { it.first }, outline.map { it.second })
c.width = 780.0; c.height = 440.0
c.xDomain = 0.82 to 1.06
c.yDomain = 1.32 to 1.56

showChart(c)

// `plotRect` is the rectangle the axes bound, and the one mark geometry is clipped to.
// `toFLines()` is the whole figure in pixels, and its union bounds are exactly what
// `linesToHTML` publishes to the panel. Everything is inside the 780 x 440 asked for.
val ps = c.toFLines().flatMap { l -> l.nodes.mapNotNull { it.toOrNull } }
println("plotRect: " + c.plotRect())
println("drawn:    %.0f,%.0f %.0fx%.0f".format(
    ps.minOf { it.x }, ps.minOf { it.y },
    ps.maxOf { it.x } - ps.minOf { it.x }, ps.maxOf { it.y } - ps.minOf { it.y }))
