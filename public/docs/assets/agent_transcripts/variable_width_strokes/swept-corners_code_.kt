// A "z" written quickly: a sample every 1.5 units along three strokes, pressing less as it goes.
val turns = [vec(0.0, 0.0), vec(160.0, 0.0), vec(0.0, 140.0), vec(160.0, 140.0)]
val path =
    turns
        .zipWithNext { a, b ->
            val n = (a.distance(b) / 1.5).toInt()
            (0 until n).map { j -> a + (b - a) * (j.toDouble() / n) }
        }
        .flatten() + turns.last()
val raw = path.mapIndexed { i, p -> vec(p.x, p.y, 9.0 * (0.95 - 0.5 * i / path.size).pow(1.75)) }

val knots = thinPenSamples(raw)
val corners = penCorners(knots)
println("${raw.size} samples thin to ${knots.size}; corners at $corners")

fun ink(f: FLine, dx: Double) = (f + vec(dx, 0.0)).apply {
    filled = true
    stroked = false
    color = vec(0.95, 0.95, 0.9, 1.0)
}
val smooth = sweptCircleStroke(knots).shell
val sharp = sweptCircleStroke(knots, corners = corners).shell
lines(ink(smooth, 0.0), ink(sharp, 260.0), w = 900.0, h = 320.0, fitToSize = true)
