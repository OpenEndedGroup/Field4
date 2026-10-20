// A plan to fly to: a spiral over a grid, drawn as FLines.
val spiral = fline {
    color = vec(0.35, 0.8, 1.0, 1.0)
    thickness = 2.5
    moveTo(vec(220.0, 180.0, 0.0))
    for (i in 1..400) {
        val a = i * 0.06
        val r = 4.0 + i * 0.42
        lineTo(vec(220.0 + r * cos(a), 180.0 + r * sin(a), 0.0))
    }
}
val drawing = (0..8).map { i ->
    fline {
        color = vec(1.0, 0.6, 0.2, 0.5)
        thickness = 1.0
        moveTo(vec(20.0 + i * 50.0, 10.0, 0.0))
        lineTo(vec(20.0 + i * 50.0, 350.0, 0.0))
    }
}
lines(drawing.toMutableList().apply { add(spiral) }, w = 440.0, h = 360.0, fitToSize = true)
