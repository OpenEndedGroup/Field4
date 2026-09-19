// Clipping is not only about lines. Every mark kind is cut against plotRect, and the
// interesting cases are the ones that used to be cut WRONG rather than not at all:
// a bar was clamped in y but not in x, and `coerceIn` left a bar wholly off the axis
// as a one-pixel smear along the frame instead of nothing.
val c = Chart(title = "half of everything is outside", xLabel = "x", yLabel = "y")
    .area("area", (0..40).toList(), (0..40).map { it * 0.6 - 6.0 })
    .line("line", (0..40).toList(), (0..40).map { 22.0 - it * 0.9 })
    .points("points", (0..40).toList(), (0..40).map { 4.0 + it * 0.4 })
    .rule(11.0, "an in-window rule")
    .rule(99.0, "a rule off the axis")
c.xDomain = 8.0 to 32.0
c.yDomain = 0.0 to 20.0

showChart(c)

// Nothing outside the plot rectangle, and the off-axis rule is not drawn at all --
// off the axis it has no honest position, so it gets none. Count only the MARK lines:
// the tick labels, the title and the footnote live outside plotRect on purpose, and
// counting those would be measuring the wrong thing.
val plot = c.plotRect()
println("plotRect: $plot")
listOf("area", "line", "points").forEach { name ->
    val ns = c.toFLines().filter { it.label?.startsWith(name) == true }
        .flatMap { l -> l.nodes.mapNotNull { it.toOrNull } }
    val over: Double = ns.maxOf { p ->
        val px = p.x.toDouble(); val py = p.y.toDouble()
        maxOf(plot.x - px, px - (plot.x + plot.w), plot.y - py, py - (plot.y + plot.h), 0.0)
    }
    println("  %-7s %3d nodes, worst %.2f px outside plotRect".format(name, ns.size, over))
}
// `points` is the one that is not zero, and it is not a clip failure. A dot is dropped
// when its CENTRE leaves the window, not cut in half, so a dot sitting on the edge puts
// its own radius -- 3 px by default -- outside. Half a disc hanging off the axis reads
// as a rendering artefact; a whole dot on the line does not.
println("lines labelled 'a rule off the axis': " +
    c.toFLines().count { it.label == "a rule off the axis" })
println("lines labelled 'an in-window rule':  " +
    c.toFLines().count { it.label == "an in-window rule" })
