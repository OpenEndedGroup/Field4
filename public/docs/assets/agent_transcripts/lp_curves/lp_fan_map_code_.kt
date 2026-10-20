// Choose the pencil tool and drag a corner of the white outline. When the drag ends the
// grid is recomputed for the convex hull of the edited outline.
fun redraw(polygon: LpPolygon) {
    val grid = polygon.fanMap().grid(thetas = 72, logMus = 30)
    val outline = polylineFrom(polygon.vertices, closed = true).also {
        it.color = vec(1.0, 1.0, 1.0, 0.95)
        it.thickness = 2.0
    }
    threeLines(
        [outline] + grid.thetaLines(every = 2, alpha = 0.7) + grid.muLines(every = 3),
        channel = "main",
        name = "fan-map",
        height = 520,
        width = 620,
        editable = true,
        onEditCommitted = { edited -> redraw(LpPolygon.hullOf(edited[0])) },
    )
}

redraw(LpPolygon([vec(-2.0, -1.0), vec(1.5, -1.5), vec(3.0, 1.0), vec(0.5, 2.5), vec(-2.5, 1.5)]))
