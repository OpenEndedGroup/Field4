val knots = [vec(60, 80), vec(200, 40), vec(330, 110), vec(300, 240), vec(170, 200), vec(80, 260)]

val shape = clothoidSplineFrom(knots, closed = true).apply {
    thickness = 3.0
    color = vec(0.5, 0.8, 1.0, 1.0)
}
val fill = shape.copy().apply {
    stroked = false
    filled = true
    color = vec(0.5, 0.8, 1.0, 0.15)
}
val comb = shape.curvatureComb(scale = 1200.0, samples = 200).apply { color = vec(1.0, 1.0, 1.0, 0.35) }
val dots = polylineFrom(knots).apply {
    stroked = false
    pointed = true
    pointSize = 2.0
    color = vec(1.0, 1.0, 1.0, 1.0)
}

lines(fill, comb, shape, dots, w = 420.0, h = 320.0, fitToSize = true)
