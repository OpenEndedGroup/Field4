// a path that climbs as it winds: x and y on the ground, z up
val knots = [
    vec(0, 0, 0),
    vec(140, -30, 10),
    vec(240, 40, 30),
    vec(220, 150, 50),
    vec(100, 180, 60),
    vec(20, 120, 75),
    vec(70, 60, 95),
    vec(170, 80, 120),
]
val path = clothoidSplineFrom(knots, samplesPerSegment = 64)
// its shadow on the ground is the flat spline through the same points
val shadow = clothoidSplineFrom(knots.map { vec(it.x, it.y, 0) }, samplesPerSegment = 64)

// a slanted view from above and to the side: x across, y going back, z up
fun view(p: Vec3) = vec(p.x + 0.3 * p.y, 240 - 0.6 * p.y - p.z, 0)

val drawnPath =
    path
        .transform { view(it) }
        .apply {
            thickness = 3.0
            color = vec(0.5, 0.8, 1.0, 1.0)
        }
val drawnShadow =
    shadow
        .transform { view(it) }
        .apply {
            thickness = 1.5
            color = vec(1.0, 1.0, 1.0, 0.35)
        }
val posts = knots.map { k ->
    polylineFrom([view(vec(k.x, k.y, 0)), view(k)]).apply { color = vec(1.0, 0.7, 0.3, 0.8) }
}

val curves: List<FLine> = [drawnShadow, drawnPath]
lines(posts + curves, w = 420.0, h = 320.0, fitToSize = true)
