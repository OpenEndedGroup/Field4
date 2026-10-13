val curve = fline {
    moveTo(40, 210)
    cubicTo(90, 30, 230, 30, 260, 140)
    cubicTo(280, 220, 360, 230, 390, 90)
    thickness = 2.0
    color = vec(1.0, 1.0, 1.0, 1.0)
}

// thirteen locations, evenly spaced by distance
val stops = (0..12).mapNotNull { curve.locationAtFraction(it / 12f) }

val normals = stops.map { loc ->
    fline {
        moveTo(loc.point.x, loc.point.y)
        lineTo(loc.point.x + loc.normal2D.x * 22, loc.point.y + loc.normal2D.y * 22)
        thickness = 1.5
        color = vec(0.4, 0.9, 1.0, 1.0)
    }
}

// the circle that matches the bend, at three of them
val bends = listOf(stops[3], stops[7], stops[10]).map { loc ->
    val r = 1.0 / loc.signedCurvature2D
    fline {
        circle(loc.point.x + loc.normal2D.x * r, loc.point.y + loc.normal2D.y * r, kotlin.math.abs(r))
        thickness = 1.0
        color = vec(1.0, 0.6, 0.2, 0.8)
    }
}

lines(listOf(curve) + normals + bends, w = 420.0, h = 260.0, fitToSize = true)
