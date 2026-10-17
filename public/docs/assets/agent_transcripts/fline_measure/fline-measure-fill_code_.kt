// two circles drawn the same way round: a ring
fun ring(dx: Double) = fline {
    circle(110 + dx, 120, 95)
    circle(110 + dx, 120, 40)
    thickness = 2.0
    color = vec(1.0, 1.0, 1.0, 1.0)
}

// dots that are inside under each fill rule: non-zero on the left, even-odd on the right
fun insideDots(dx: Double, rule: FLineFillRule) = fline {
    val shape = ring(dx)
    for (i in 0..24) for (j in 0..24) {
        val p = Vec2((15 + dx + i * 8).toFloat(), (25 + j * 8).toFloat())
        if (shape.contains2D(p, rule)) {
            moveTo(p.x, p.y)
            lineTo(p.x + 0.01, p.y)
        }
    }
    pointed = true
    pointSize = 0.7
    stroked = false
    color = vec(1.0, 0.6, 0.2, 1.0)
}

lines(
    ring(0.0),
    ring(240.0),
    insideDots(0.0, FLineFillRule.NonZero),
    insideDots(240.0, FLineFillRule.EvenOdd),
    w = 460.0,
    h = 240.0,
    fitToSize = true,
)
