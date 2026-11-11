// A quick hand-written "o": fourteen pen samples, pressure rising and falling.
val w = 20.0
val knots =
    (0..13).map { i ->
        val a = 2.2 + 2 * PI * i / 12.5
        val p = 0.3 + 0.7 * sin(PI * i / 13)
        val r = w * p.pow(1.75)
        vec(90 + 70 * cos(a) * (1 + 0.04 * i), 100 + 80 * sin(a), r)
    }

fun ink(f: FLine, dx: Double) = (f + vec(dx, 0.0)).apply {
    filled = true
    stroked = false
    color = vec(0.95, 0.95, 0.9, 1.0)
}

// pill: each span at its mean radius
val pills = knots.zipWithNext { a, b ->
    val m = (a.z + b.z) / 2
    sweptCircleStroke([vec(a.x, a.y, m), vec(b.x, b.y, m)]).shell
}
// trapezoid pill: each span straight, radius linear from end to end
val trapezoids = knots.zipWithNext { a, b -> sweptCircleStroke([a, b]).shell }
// swept spline: cubic Béziers in (x, y, r) through every sample
val spline = sweptCircleStroke(knots).shell

val samples = knots.map { k ->
    fline {
        moveTo(k.x, k.y)
        lineTo(k.x + 0.01, k.y)
    }
        .apply {
            pointed = true
            pointSize = 1.5
            color = vec(1.0, 0.4, 0.3, 1.0)
        }
}

lines(
    pills.map { ink(it, 0.0) } +
        trapezoids.map { ink(it, 220.0) } +
        ink(spline, 440.0) +
        samples +
        samples.map { it + vec(220.0, 0.0) } +
        samples.map { it + vec(440.0, 0.0) },
    w = 900.0,
    h = 300.0,
    fitToSize = true,
)
