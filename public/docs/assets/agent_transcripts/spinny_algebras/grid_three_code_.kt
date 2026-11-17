// Multiply every point of a grid by one fixed number w = a + bε, in three algebras that differ only in ε².
fun times(a: Double, b: Double, c: Double, d: Double, eps2: Double) =
    vec(a * c + eps2 * b * d, a * d + b * c)

fun gridTimes(a: Double, b: Double, eps2: Double, ox: Double, tint: Vec4): List<FLine> {
    val s = 40.0
    val grid = (-3..3).flatMap { k ->
        listOf(
            (0..60).map { vec(k / 3.0, -1 + it / 30.0) }, // a vertical line of the grid
            (0..60).map { vec(-1 + it / 30.0, k / 3.0) }, // a horizontal one
        )
    }
    fun draw(pts: List<Vec2>, col: Vec4, t: Double) = fline {
        pts.forEachIndexed { i, p ->
            val q = times(a, b, p.x.toDouble(), p.y.toDouble(), eps2)
            if (i == 0) moveTo(ox + s * q.x, -s * q.y) else lineTo(ox + s * q.x, -s * q.y)
        }
        color = col
        thickness = t
    }
    val before = grid.map { pts -> fline {
        pts.forEachIndexed { i, p -> if (i == 0) moveTo(ox + s * p.x, -s * p.y) else lineTo(ox + s * p.x, -s * p.y) }
        color = vec(1, 1, 1, 0.15)
    } }
    return before + grid.map { draw(it, tint, 1.5) }
}

lines(
    *(gridTimes(0.9, 0.5, -1.0, 0.0, vec(0.35, 0.8, 1.0, 0.9)) +   // complex: ε² = -1
      gridTimes(0.9, 0.5, 0.0, 170.0, vec(0.95, 0.85, 0.3, 0.9)) +  // dual: ε² = 0
      gridTimes(0.9, 0.5, 1.0, 340.0, vec(1.0, 0.45, 0.55, 0.9))).toTypedArray(), // split-complex: ε² = +1
    w = 760.0, h = 260.0, fitToSize = true,
)
