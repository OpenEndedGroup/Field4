// The same `petals`, drawn as FLines.
val rings =
    (0 until petals).map { i ->
        val a = i * 2 * PI / petals
        fline {
            color = vec(0.35 + 0.6 * i / petals, 0.7, 1.0 - 0.5 * i / petals, 0.8)
            thickness = 1.5
            moveTo(vec(0.0, 0.0, 0.0))
            cubicTo(
                vec(120 * cos(a - 0.5), 120 * sin(a - 0.5), 0.0),
                vec(160 * cos(a + 0.5), 160 * sin(a + 0.5), 0.0),
                vec(0.0, 0.0, 0.0),
            )
        }
    }
lines(rings, w = 300.0, h = 300.0, fitToSize = true)
