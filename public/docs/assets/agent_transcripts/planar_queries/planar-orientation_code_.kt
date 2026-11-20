val polygons = [
    [vec(30, 30), vec(150, 30), vec(150, 120), vec(30, 120)],
    [vec(200, 30), vec(200, 130), vec(330, 80)],
    [vec(40, 170), vec(90, 260), vec(140, 170), vec(90, 200)],
    [vec(330, 250), vec(260, 270), vec(190, 180), vec(300, 160)],
]

val out = mutableListOf<FLine>()
for (points in polygons) {
    val area = signedArea2D(points)
    val bounds = pointBounds2D(points)

    // blue counter-clockwise, orange clockwise
    out +=
        polylineFrom(points + points.first()).apply {
            filled = true
            thickness = 2.0
            color = if (area > 0) vec(0.4, 0.7, 1.0, 0.6) else vec(1.0, 0.55, 0.2, 0.6)
        }
    out += fline {
        rect(bounds.x, bounds.y, bounds.w, bounds.h)
        thickness = 1.0
        color = vec(1.0, 1.0, 1.0, 0.35)
    }
    println("area ${area.f1}, bounds ${bounds.w.f1} x ${bounds.h.f1}")
}

lines(out, w = 420.0, h = 300.0, fitToSize = true)
