val knots = (0..7).map { i -> vec(40 + i * 50, 130 + 70 * kotlin.math.sin(i * 0.9)) }

val cubic = cubicSplineFrom(knots).apply {
    thickness = 3.0
    color = vec(1.0, 0.7, 0.3, 1.0)
}
val clothoid = clothoidSplineFrom(knots).apply {
    thickness = 3.0
    color = vec(0.5, 0.8, 1.0, 1.0)
}

val cubicComb = cubic.curvatureComb(scale = 1500.0).apply { color = vec(1.0, 0.7, 0.3, 0.5) }
val clothoidComb = clothoid.curvatureComb(scale = 1500.0).apply { color = vec(0.5, 0.8, 1.0, 0.5) }

// curvature against distance, cubic above, clothoid below
val cubicPlot = (cubic.curvaturePlot(400.0, 70.0, maxCurvature = 0.05) + vec(20, 240)).apply {
    thickness = 2.0
    color = vec(1.0, 0.7, 0.3, 1.0)
}
val clothoidPlot = (clothoid.curvaturePlot(400.0, 70.0, maxCurvature = 0.05) + vec(20, 320)).apply {
    thickness = 2.0
    color = vec(0.5, 0.8, 1.0, 1.0)
}

val dots = polylineFrom(knots).apply {
    stroked = false
    pointed = true
    pointSize = 2.0
    color = vec(1.0, 1.0, 1.0, 1.0)
}

lines(cubicComb, clothoidComb, cubic, clothoid, dots, cubicPlot, clothoidPlot, w = 420.0, h = 400.0, fitToSize = true)
