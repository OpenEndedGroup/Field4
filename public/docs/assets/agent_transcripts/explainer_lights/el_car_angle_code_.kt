// A car 1.8 m wide driving away from you, from above and through your eye. How wide it looks is
// the angle it spans, and that angle shrinks about as one over the distance.
fun draw(distance: Double) {
    val half = atan(0.9 / distance)
    val scale = 9.0
    val x = 30 + distance * scale
    val top = mutableListOf<FLine>()
    top += fline {
        moveTo(x, 100 - 0.9 * scale)
        lineTo(x + 4.5 * scale, 100 - 0.9 * scale)
        lineTo(x + 4.5 * scale, 100 + 0.9 * scale)
        lineTo(x, 100 + 0.9 * scale)
        lineTo(x, 100 - 0.9 * scale)
        filled = true
        color = vec(1.0, 0.6, 0.2, 1.0)
    }
    top += fline {
        moveTo(x, 100 - 0.9 * scale)
        lineTo(30.0, 100.0)
        lineTo(x, 100 + 0.9 * scale)
        color = vec(0.4, 0.9, 1.0, 1.0)
    }
    lines(top, channel = "above", w = 640.0, h = 200.0, framing = LinesFraming.fixed(0.0, 0.0, 640.0, 200.0))
    // The eye's view: 40° across 640 pixels. The car is 1.8 m wide and 1.5 m tall.
    val perDegree = 640.0 / 40
    val w = 2 * half * 180 / PI * perDegree
    val h = 2 * atan(0.75 / distance) * 180 / PI * perDegree
    val eye = fline {
        moveTo(320 - w / 2, 140 - h)
        lineTo(320 + w / 2, 140 - h)
        lineTo(320 + w / 2, 140.0)
        lineTo(320 - w / 2, 140.0)
        lineTo(320 - w / 2, 140 - h)
        filled = true
        color = vec(1.0, 0.6, 0.2, 1.0)
    }
    val ground = fline {
        moveTo(0.0, 140.0)
        lineTo(640.0, 140.0)
        color = vec(1.0, 1.0, 1.0, 0.35)
    }
    lines(ground, eye, channel = "view", w = 640.0, h = 160.0, framing = LinesFraming.fixed(0.0, 0.0, 640.0, 160.0))
    markdown("readout", "${distance.roundToInt()} m away: ${(2 * half * 180 / PI).f1}°")
}

draw(10.0)
controlGroup {
    slider("distance", 3.0, 60.0, step = 1.0, initialValue = 10.0) { draw(it) }
}
