// An eye looking at a wide lamp, from the side. One cell of the eye takes in a fixed cone of
// directions; move the lamp away and the cone covers more of it.
fun draw(distance: Double) {
    val half = 5 * PI / 180
    val x = 60 + distance * 130
    val reach = (x - 60) * tan(half)
    val drawn = mutableListOf<FLine>()
    drawn += fline {
        moveTo(x, 20.0)
        lineTo(x, 260.0)
        thickness = 4.0
        color = vec(1.0, 1.0, 1.0, 0.5)
    }
    drawn += fline {
        moveTo(x, 140 - reach)
        lineTo(x, 140 + reach)
        thickness = 8.0
        color = vec(1.0, 0.6, 0.2, 1.0)
    }
    drawn += fline {
        moveTo(x, 140 - reach)
        lineTo(60.0, 140.0)
        lineTo(x, 140 + reach)
        color = vec(0.4, 0.9, 1.0, 1.0)
    }
    drawn += fline {
        moveTo(40.0, 125.0)
        lineTo(60.0, 140.0)
        lineTo(40.0, 155.0)
        thickness = 3.0
        color = vec(1.0, 1.0, 1.0, 0.8)
    }
    lines(drawn, channel = "side", w = 640.0, h = 280.0, framing = LinesFraming.fixed(0.0, 0.0, 640.0, 280.0))
    val d2 = distance * distance
    markdown("readout", "${distance.f1} m: ${d2.f1}× the area, each ${d2.f1}× fainter")
}

draw(1.0)
controlGroup {
    slider("distance", 1.0, 4.0, step = 0.1, initialValue = 1.0) { draw(it) }
}
