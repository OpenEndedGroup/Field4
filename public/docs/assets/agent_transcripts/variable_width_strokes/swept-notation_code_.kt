// Write a pressure stroke onto the drawing layer, then read it back as pen samples.
notation {
    stroke(
        (0..40).map { i -> vec(-300.0 + 9.0 * i, 420.0 + 40.0 * sin(i / 5.0)) },
        name = "ink",
        pressure = { 0.15 + 0.85 * sin(PI * it).pow(0.7) },
    )
}
val mark = notation.named("ink")!!
val knots = mark.points.zip(mark.pressures) { p, pressure -> vec(p.x, p.y, 12.0 * pressure.pow(1.75)) }
val outline = sweptCircleStroke(knots)
println("${knots.size} pen samples, ${sweptCircleDegenerateSpans(knots).size} spans without an envelope")
lines(
    outline.shell.copy().apply {
        filled = true
        stroked = false
        color = vec(0.95, 0.95, 0.9, 1.0)
    },
    w = 900.0,
    h = 260.0,
    fitToSize = true,
)
