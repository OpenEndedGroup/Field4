// The pen nearly stops and presses: around the middle sample the radius changes faster than
// the centre moves, and the circle swallows itself.
val knots = [
    vec(0.0, 0.0, 4.0),
    vec(60.0, 20.0, 7.0),
    vec(66.0, 22.0, 30.0),
    vec(72.0, 20.0, 8.0),
    vec(150.0, 0.0, 4.0),
]
val spans = sweptCircleDegenerateSpans(knots)
println("no envelope on " + spans.joinToString { "%.4f..%.4f".format(it.start, it.endInclusive) })

val outline = sweptCircleStroke(knots)
val beziers = sweptCircleBeziers(knots)
val path = fline {
    moveTo(beziers[0][0].x, beziers[0][0].y)
    for (b in beziers) cubicTo(b[1].x, b[1].y, b[2].x, b[2].y, b[3].x, b[3].y)
}
lines(
    outline.shell.copy().apply {
        filled = true
        stroked = false
        color = vec(0.95, 0.95, 0.9, 0.35)
    },
    outline.left.copy().apply {
        color = vec(0.4, 0.9, 1.0, 1.0)
        thickness = 2.0
    },
    outline.right.copy().apply {
        color = vec(0.4, 0.9, 1.0, 1.0)
        thickness = 2.0
    },
    path.copy().apply { color = vec(1.0, 1.0, 1.0, 0.6) },
    w = 900.0,
    h = 320.0,
    fitToSize = true,
)
