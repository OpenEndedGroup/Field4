// Parallel rays falling on an orange patch of floor, seen from the side.
// Tilt them away from straight down and fewer of them land on the patch.
val rays = 12

fun draw(degrees: Double) {
    val a = degrees * PI / 180
    val across = sin(a)
    val down = cos(a)
    var landed = 0
    val drawn = mutableListOf<FLine>()
    for (i in 0 until rays) {
        // Rays sit evenly across a beam 200 pixels wide; this is where each one meets the floor.
        val x = 320 + ((i + 0.5) / rays - 0.5) * 200 / down
        val hit = abs(x - 320) <= 100
        if (hit) landed++
        // Each ray is drawn back from the floor to the top or side of the frame.
        val back = min(230 / down, if (across > 0) x / across else 1e9)
        drawn += fline {
            moveTo(x - across * back, 250 - down * back)
            lineTo(x, 250.0)
            thickness = 1.5
            color = if (hit) vec(0.4, 0.9, 1.0, 1.0) else vec(1.0, 1.0, 1.0, 0.35)
        }
    }
    drawn += fline {
        moveTo(0.0, 250.0)
        lineTo(640.0, 250.0)
        color = vec(1.0, 1.0, 1.0, 0.35)
    }
    drawn += fline {
        moveTo(220.0, 250.0)
        lineTo(420.0, 250.0)
        thickness = 6.0
        color = vec(1.0, 0.6, 0.2, 1.0)
    }
    lines(drawn, channel = "side", w = 640.0, h = 280.0, framing = LinesFraming.fixed(0.0, 0.0, 640.0, 280.0))

    // The cosine of the angle, with this angle marked.
    val curve = fline {
        moveTo(40.0, 20.0)
        for (d in 1..90) lineTo(40.0 + d * 6.4, 20.0 + 120 * (1 - cos(d * PI / 180)))
        thickness = 2.0
        color = vec(0.4, 0.9, 1.0, 1.0)
    }
    val axes = fline {
        moveTo(40.0, 10.0)
        lineTo(40.0, 140.0)
        lineTo(620.0, 140.0)
        color = vec(1.0, 1.0, 1.0, 0.35)
    }
    val here = fline {
        moveTo(40.0 + degrees * 6.4, 140.0)
        lineTo(40.0 + degrees * 6.4, 20.0 + 120 * (1 - down))
        thickness = 2.0
        color = vec(1.0, 0.6, 0.2, 1.0)
    }
    lines(axes, curve, here, channel = "cosine", w = 640.0, h = 150.0)
    markdown("readout", "$landed of $rays rays land · cos ${degrees.roundToInt()}° = ${down.f2}")
}

draw(0.0)
controlGroup {
    slider("angle", 0.0, 85.0, step = 1.0, initialValue = 0.0) { draw(it) }
}
