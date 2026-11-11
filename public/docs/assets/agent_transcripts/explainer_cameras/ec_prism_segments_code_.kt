import afield.simulation.optics.*

// Parallel rays through glass whose back face is made of flat segments. One segment each side is a
// pair of prisms: the rays turn but stay parallel. More segments, closer to a curve, and they meet.
val glass = 1.5
val axis = 180.0
val flatX = 220.0
val radius = 300.0
val rim = 130.0
val cyan = vec(0.4, 0.9, 1.0, 1.0)
val guide = vec(1.0, 1.0, 1.0, 0.35)
var segments = 1

// The curved face these segments approximate, a circle that is `rim` above the axis at its edge.
fun surfaceX(h: Double) = flatX + 10.0 + sqrt(radius * radius - h * h) - sqrt(radius * radius - rim * rim)

fun draw() {
    val n = segments
    // The facets' corners from the rim at the top to the rim at the bottom.
    val corners =
        (0..2 * n).map { k ->
            val h = -rim + rim * k / n
            h to surfaceX(h)
        }
    val outline = fline {
        moveTo(flatX, axis - rim)
        for ((h, x) in corners) lineTo(x, axis + h)
        lineTo(flatX, axis + rim)
        close()
        filled = true
        color = vec(0.4, 0.9, 1.0, 0.18)
    }
    val parts = mutableListOf(
        outline,
        fline {
            moveTo(0, axis)
            lineTo(640, axis)
            color = guide
            thickness = 1.0
        },
    )
    for (k in -6..6) {
        val h = 20.0 * k + if (k == 0) 0.0 else -0.5 * sign(k.toDouble()) // keep rays off the facet corners
        val facet = ((h + rim) / rim * n).toInt().coerceIn(0, 2 * n - 1)
        val (h0, x0) = corners[facet]
        val (h1, x1) = corners[facet + 1]
        val x = x0 + (x1 - x0) * (h - h0) / (h1 - h0)
        // Outward normal of the facet, then Snell's law leaving the glass.
        val len = sqrt((x1 - x0).pow(2) + (h1 - h0).pow(2))
        val out = Refraction.refract(1.0, 0.0, (h1 - h0) / len, -(x1 - x0) / len, glass, 1.0) ?: continue
        val end = frameExit(x, axis + h, out[0], out[1], 640.0, 360.0)
        parts += fline {
            moveTo(0.0, axis + h)
            lineTo(x, axis + h)
            lineTo(end[0], end[1])
            color = cyan
            thickness = 1.5
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
    slider("segments", 1.0, 24.0, step = 1.0, initialValue = 1.0) {
        segments = it.toInt()
        draw()
    }
}
