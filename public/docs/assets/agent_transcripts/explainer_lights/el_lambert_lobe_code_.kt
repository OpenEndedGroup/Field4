// A small matte patch giving off light, seen from the side. It sends less light off to the side,
// as the cosine of the angle, and it also looks narrower from there by the same cosine.
val rays = 41

fun draw(degrees: Double) {
    val v = degrees * PI / 180
    val drawn = mutableListOf<FLine>()
    // Rays whose sines are evenly spaced: their density falls off as the cosine.
    for (i in 0 until rays) {
        val s = 2.0 * (i + 0.5) / rays - 1
        val c = sqrt(1 - s * s)
        drawn += fline {
            moveTo(320.0, 250.0)
            lineTo(320 + 210 * s, 250 - 210 * c)
            color = vec(1.0, 1.0, 1.0, 0.3)
        }
    }
    // The lobe: in each direction, the power sent that way. A circle resting on the patch.
    drawn += fline {
        moveTo(320.0, 250.0)
        for (k in 1..180) {
            val p = -PI / 2 + PI * k / 180
            lineTo(320 + 200 * cos(p) * sin(p), 250 - 200 * cos(p) * cos(p))
        }
        thickness = 2.0
        color = vec(0.4, 0.9, 1.0, 1.0)
    }
    drawn += fline {
        moveTo(270.0, 250.0)
        lineTo(370.0, 250.0)
        thickness = 6.0
        color = vec(1.0, 0.6, 0.2, 1.0)
    }
    // The viewing direction, and how wide the patch looks from there.
    drawn += fline {
        moveTo(320.0, 250.0)
        lineTo(320 + 240 * sin(v), 250 - 240 * cos(v))
        thickness = 2.0
        color = vec(1.0, 0.6, 0.2, 1.0)
    }
    val w = 50 * cos(v)
    drawn += fline {
        moveTo(320 + 240 * sin(v) - w * cos(v), 250 - 240 * cos(v) - w * sin(v))
        lineTo(320 + 240 * sin(v) + w * cos(v), 250 - 240 * cos(v) + w * sin(v))
        thickness = 5.0
        color = vec(1.0, 0.6, 0.2, 1.0)
    }
    lines(drawn, channel = "side", w = 640.0, h = 280.0, framing = LinesFraming.fixed(0.0, 0.0, 640.0, 280.0))
    val c = cos(v)
    markdown("readout", "${degrees.roundToInt()}°: ${(c * 100).roundToInt()} % of the power, ${(c * 100).roundToInt()} % of the width")
}

draw(0.0)
controlGroup {
    slider("viewing angle", -80.0, 80.0, step = 1.0, initialValue = 0.0) { draw(it) }
}
