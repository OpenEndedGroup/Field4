// Parallel rays striking a surface, seen from the side. Each ray meets a tiny facet of the
// surface and leaves as from a mirror; roughness tilts the facets, so the rays fan out.
val rays = 25
var roughness = 0.0
var angle = 40.0

fun draw() {
    val a = angle * PI / 180
    val drawn = mutableListOf<FLine>()
    drawn += fline {
        moveTo(20.0, 240.0)
        lineTo(620.0, 240.0)
        color = vec(1.0, 1.0, 1.0, 0.35)
    }
    for (i in 0 until rays) {
        val x = 220 + 200.0 * i / (rays - 1)
        // Each facet's tilt, spread evenly (golden-ratio steps) over ± roughness × 50°.
        val tilt = roughness * 50 * PI / 180 * (2 * ((i * 0.618034) % 1.0) - 1)
        // Travelling down and to the right; y grows downward in this drawing.
        val dx = sin(a)
        val dy = cos(a)
        // The facet's normal points up, tilted; the ray reflects about it.
        val nx = sin(tilt)
        val ny = -cos(tilt)
        val dot = dx * nx + dy * ny
        var rx = dx - 2 * dot * nx
        var ry = dy - 2 * dot * ny
        if (ry > 0) ry = -ry
        drawn += fline {
            moveTo(x - dx * 230, 240 - dy * 230)
            lineTo(x, 240.0)
            color = vec(1.0, 0.6, 0.2, 0.6)
        }
        drawn += fline {
            moveTo(x, 240.0)
            lineTo(x + rx * 200, 240 + ry * 200)
            color = vec(0.4, 0.9, 1.0, 0.9)
        }
        drawn += fline {
            moveTo(x - 6 * cos(tilt), 240 - 6 * sin(tilt))
            lineTo(x + 6 * cos(tilt), 240 + 6 * sin(tilt))
            thickness = 2.0
            color = vec(1.0, 1.0, 1.0, 0.8)
        }
    }
    lines(drawn, channel = "side", w = 640.0, h = 280.0, framing = LinesFraming.fixed(0.0, 0.0, 640.0, 280.0))
}

draw()
controlGroup {
    slider("roughness", 0.0, 1.0, step = 0.01, initialValue = roughness) {
        roughness = it
        draw()
    }
    slider("light angle", 0.0, 70.0, step = 1.0, initialValue = angle) {
        angle = it
        draw()
    }
}
