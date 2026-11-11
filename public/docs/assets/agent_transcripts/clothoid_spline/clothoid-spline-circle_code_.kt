val knots =
    (0 until 7).map { i ->
        val a = 2 * kotlin.math.PI * i / 7 + 0.3 * kotlin.math.sin(i * 1.7)
        vec(200 + 100 * kotlin.math.cos(a), 150 + 100 * kotlin.math.sin(a))
    }

val round = clothoidSplineFrom(knots, closed = true).apply {
    thickness = 3.0
    color = vec(0.5, 0.8, 1.0, 1.0)
}
val cubic = cubicSplineFrom(knots, closed = true).apply {
    thickness = 1.5
    color = vec(1.0, 0.7, 0.3, 0.9)
}
val comb = round.curvatureComb(scale = 2000.0, samples = 140).apply { color = vec(0.5, 0.8, 1.0, 0.4) }

lines(comb, cubic, round, w = 420.0, h = 320.0, fitToSize = true)
