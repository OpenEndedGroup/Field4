// An angle measured by the arc it cuts from a circle. Unrolled on the right, the arc is so many
// radii long: that count is the angle in radians.
var radius = 90.0
var degrees = 60.0

fun draw() {
    val a = degrees * PI / 180
    val cx = 160.0
    val cy = 150.0
    val drawn = mutableListOf<FLine>()
    drawn += fline {
        moveTo(cx + radius, cy)
        for (k in 1..120) lineTo(cx + radius * cos(2 * PI * k / 120), cy - radius * sin(2 * PI * k / 120))
        color = vec(1.0, 1.0, 1.0, 0.35)
    }
    drawn += fline {
        moveTo(cx + radius, cy)
        lineTo(cx, cy)
        lineTo(cx + radius * cos(a), cy - radius * sin(a))
        color = vec(1.0, 1.0, 1.0, 0.8)
    }
    drawn += fline {
        moveTo(cx + radius, cy)
        for (k in 1..90) lineTo(cx + radius * cos(a * k / 90), cy - radius * sin(a * k / 90))
        thickness = 4.0
        color = vec(0.4, 0.9, 1.0, 1.0)
    }
    // The arc laid straight, drawn at 0.4 of its length, with a tick at every radius.
    val x0 = 330.0
    drawn += fline {
        moveTo(x0, 270.0)
        lineTo(x0 + radius * a * 0.4, 270.0)
        thickness = 4.0
        color = vec(0.4, 0.9, 1.0, 1.0)
    }
    var k = 0
    while (k * radius <= radius * a + 1e-9) {
        drawn += fline {
            moveTo(x0 + k * radius * 0.4, 258.0)
            lineTo(x0 + k * radius * 0.4, 282.0)
            thickness = 2.0
            color = vec(1.0, 0.6, 0.2, 1.0)
        }
        k++
    }
    lines(drawn, channel = "circle", w = 640.0, h = 300.0, framing = LinesFraming.fixed(0.0, 0.0, 640.0, 300.0))
    markdown("readout", "${degrees.roundToInt()}° = ${a.f2} radians")
}

draw()
controlGroup {
    slider("angle", 0.0, 360.0, step = 1.0, initialValue = degrees) {
        degrees = it
        draw()
    }
    slider("radius", 40.0, 120.0, step = 1.0, initialValue = radius) {
        radius = it
        draw()
    }
}
