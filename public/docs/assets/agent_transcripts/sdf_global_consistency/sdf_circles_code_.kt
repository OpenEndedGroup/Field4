// Two discs joined with min: exact outside, only a bound inside.
fun disc(cx: Double, cy: Double, r: Double) = { x: Double, y: Double -> hypot(x - cx, y - cy) - r }
val left = disc(-0.4, 0.0, 0.6)
val right = disc(0.4, 0.0, 0.6)
val union = { x: Double, y: Double -> min(left(x, y), right(x, y)) }

val pseudo = sdfGrid2D(xMin = -1.2, yMin = -1.0, xMax = 1.2, yMax = 1.0, n = 13, field = union)
val fixed = pseudo.repaired()
println("pseudo   ${pseudo.consistency()}")
println("repaired ${fixed.consistency()}")
val scale = 120.0
fun circles(grid: SdfGrid2D, dx: Double) = buildList {
    val nearest = grid.nearestSurfaceDistances()
    for (j in 0 until grid.ny) for (i in 0 until grid.nx) {
        val d = grid[i, j]
        val miss = nearest[j * grid.nx + i] - abs(d) // > 0 falls short, < 0 swallows surface
        val c = (abs(miss) / grid.h).coerceIn(0.0, 1.0)
        add(
            fline { circle((grid.x(i) + dx) * scale, grid.y(j) * scale, abs(d) * scale) }
                .apply {
                    color = if (d < 0) vec(0.15 + 0.85 * c, 0.3, 0.7 - 0.5 * c, 0.8) else vec(0.6, 0.6, 0.6, 0.15)
                },
        )
    }
    val s = grid.surfacePoints()
    for (k in 0 until s.size / 2) {
        add(
            fline { circle((s[2 * k] + dx) * scale, s[2 * k + 1] * scale, 2.0) }
                .apply {
                    filled = true
                    color = vec(1.0, 0.67, 0.0, 1.0)
                },
        )
    }
}
lines(circles(pseudo, 0.0) + circles(fixed, 2.6), channel = "circles", w = 640.0, h = 260.0, fitToSize = true)
