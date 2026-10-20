// The objective turns once every `period` seconds while the hexagon breathes. Every route is
// recomputed for each picture; they are cheap.
val period = 12.0
val tau = 0.3
val weights = doubleArrayOf(1.0, 1.0, 3.0, 1.0, 1.0, 1.0)
val window = Rect(-3.6, -3.4, 7.2, 6.8)

// Each vertex's radius wanders between 1.9 and 2.9, which keeps the hexagon convex.
val shape = { t: Double ->
    LpPolygon(
        (0 until 6).map { k ->
            val a = 2.0 * PI * k / 6 + 0.25
            val r = 2.4 + 0.5 * sin(t * 0.9 + k * 1.7)
            vec(r * cos(a), r * sin(a))
        },
    )
}

// Each frame's central path starts Newton from the previous frame's: same path, fewer steps.
val continuation = LpContinuation()
val started = AnimationClock.nowSeconds()
var nextDraw = 0.0
val draw: (Double) -> Unit = { t ->
    val polygon = shape(t)
    val theta = 2.0 * PI * t / period
    val c = vec(cos(theta), sin(theta))
    val central = continuation.centralPath(polygon, c, weights, samples = 90)
    val spiral = polygon.pdhg(c, iterations = 300, tau = tau, sigma = tau)
    val walk = polygon.simplexWalk(c)
    val o = polygon.center
    val arrow = fline {
        moveTo(o.x, o.y)
        lineTo(o.x + 0.9 * c.x, o.y + 0.9 * c.y)
        color = vec(1.0, 1.0, 1.0, 0.5)
        thickness = 1.0
    }
    showPlane(
        [
            polygon.toFLine(),
            arrow,
            spiral.trajectory.toFLine(vec(1.0, 0.55, 0.85, 0.7), 0.9),
            walk.toFLine(vec(0.6, 0.75, 1.0, 0.95), 2.4),
            central.toFLine(vec(1.0, 0.72, 0.32, 1.0), 2.0),
        ],
        title = "θ = %3.0f°".format(Math.toDegrees(theta) % 360.0),
        caption = "orange: central path · pink: PDHG · blue: simplex walk",
        width = 520,
        height = 500,
        window = window,
        interactive = false,
        yUp = true,
    )
}

animate {
    val t = AnimationClock.nowSeconds() - started
    // Draw twenty pictures a second; the loop itself runs much faster than that.
    if (t >= nextDraw) {
        draw(t)
        nextDraw = t + 1.0 / 20.0
    }
}
