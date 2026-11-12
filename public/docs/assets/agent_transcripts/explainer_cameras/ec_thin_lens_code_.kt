import afield.simulation.optics.*

// A thin lens bends each ray in proportion to how far from the middle it passes,
// so the rays from one point meet again on the other side.
val unit = 40.0
val axis = 180.0
val lensX = 320.0
val cyan = vec(0.4, 0.9, 1.0, 1.0)
val orange = vec(1.0, 0.6, 0.2, 1.0)
val guide = vec(1.0, 1.0, 1.0, 0.35)
var focal = 2.0
var source = 5.0

fun segment(x0: Double, y0: Double, x1: Double, y1: Double, c: Vec4, width: Double = 1.5) = fline {
    moveTo(x0, y0)
    lineTo(x1, y1)
    color = c
    thickness = width
}

fun dot(x: Double, y: Double, c: Vec4) = fline {
    moveTo(x, y)
    pointed = true
    pointSize = 2.0
    stroked = false
    color = c
}

fun draw() {
    val lens = fline {
        moveTo(lensX, axis - 110)
        for (k in 0..40) {
            val t = -1 + k / 20.0
            lineTo(lensX + 9 * (1 - t * t), axis + 110 * t)
        }
        for (k in 0..40) {
            val t = 1 - k / 20.0
            lineTo(lensX - 9 * (1 - t * t), axis + 110 * t)
        }
        filled = true
        color = vec(0.4, 0.9, 1.0, 0.25)
    }
    val sx = lensX - source * unit
    val sy = axis - 40.0
    val parts = mutableListOf(
        segment(0.0, axis, 640.0, axis, guide, 1.0),
        lens,
        dot(lensX - focal * unit, axis, guide),
        dot(lensX + focal * unit, axis, guide),
        dot(sx, sy, orange),
    )
    for (k in -4..4) {
        val h = 24.0 * k
        val slope = (axis + h - sy) / (lensX - sx) - h / (focal * unit)
        parts += segment(sx, sy, lensX, axis + h, cyan)
        val end = frameExit(lensX, axis + h, 1.0, slope, 640.0, 360.0)
        parts += segment(lensX, axis + h, end[0], end[1], cyan)
    }
    val image = ThinLens.imageDistance(focal, source)
    if (image > 0 && image * unit < 320) parts += dot(lensX + image * unit, axis + 40.0 * image / source, orange)
    showPlane(
        parts,
        channel = "rays",
        width = 600,
        height = 338,
        padding = 0.0,
        window = Rect(0.0, 0.0, 640.0, 360.0),
        interactive = false,
    )
    val where = if (image > 0) "a sharp image ${image.f2} behind the lens" else "no image: the rays spread apart"
    markdown("readout", "focal length ${focal.f1}, source ${source.f1} in front: $where")
}

draw()
controlGroup {
    slider("source distance", 1.0, 7.5, step = 0.05, initialValue = 5.0) {
        source = it
        draw()
    }
    slider("focal length", 1.0, 3.0, step = 0.05, initialValue = 2.0) {
        focal = it
        draw()
    }
}
