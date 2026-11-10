import afield.simulation.optics.*

// Parallel rays through a lens with a spherical back face, traced exactly with Snell's law.
// Rays near the rim (orange) cross the axis before those near the middle (cyan).
val glass = 1.5
val axis = 180.0
val flatX = 140.0
val radius = 130.0
val cyan = vec(0.4, 0.9, 1.0, 1.0)
val guide = vec(1.0, 1.0, 1.0, 0.35)
var opening = 40.0

fun draw() {
    val sag = radius - sqrt(radius * radius - 90.0 * 90.0)
    val centreX = flatX + sag + 6 - radius // centre of the spherical face
    val edge = opening + 6
    val outline = fline {
        moveTo(flatX, axis - edge)
        for (k in 0..60) {
            val h = -edge + 2 * edge * k / 60
            lineTo(centreX + sqrt(radius * radius - h * h), axis + h)
        }
        lineTo(flatX, axis + edge)
        close()
        filled = true
        color = vec(0.4, 0.9, 1.0, 0.18)
    }
    val paraxial = centreX + radius + radius / (glass - 1)
    val parts = mutableListOf(
        outline,
        fline {
            moveTo(0, axis)
            lineTo(640, axis)
            color = guide
            thickness = 1.0
        },
        fline {
            moveTo(paraxial, axis - 8)
            lineTo(paraxial, axis + 8)
            color = guide
            thickness = 2.0
        },
    )
    for (k in -8..8) {
        if (k == 0) continue
        val h = opening * k / 8
        val x = centreX + sqrt(radius * radius - h * h)
        val out = Refraction.refract(1.0, 0.0, (x - centreX) / radius, h / radius, glass, 1.0) ?: continue
        val t = abs(h) / 85.0
        val end = frameExit(x, axis + h, out[0], out[1], 640.0, 360.0)
        parts += fline {
            moveTo(0.0, axis + h)
            lineTo(x, axis + h)
            lineTo(end[0], end[1])
            color = vec(0.4 + 0.6 * t, 0.9 - 0.3 * t, 1.0 - 0.8 * t, 1.0)
            thickness = 1.3
        }
    }
    showPlane(
        parts,
        channel = "side",
        width = 600,
        height = 338,
        padding = 0.0,
        window = Rect(0.0, 0.0, 640.0, 360.0),
        interactive = false,
    )
}

draw()
controlGroup {
    slider("lens radius", 5.0, 85.0, step = 1.0, initialValue = 40.0) {
        opening = it
        draw()
    }
}
