import afield.simulation.optics.*

// A lens focused at one distance. Points between the near and far limits make blur circles
// smaller than a pixel (here 0.04), so everything in the shaded band looks sharp.
val unit = 60.0
val lensX = 380.0
val axis = 150.0
val pixel = 0.04
val cyan = vec(0.4, 0.9, 1.0, 1.0)
val orange = vec(1.0, 0.6, 0.2, 1.0)
val guide = vec(1.0, 1.0, 1.0, 0.35)
var aperture = 1.0
var focal = 1.0
var focus = 3.0

fun cone(objectDistance: Double, sensorX: Double, c: Vec4, width: Double) =
    listOf(-1.0, 1.0).map { side ->
        val image = ThinLens.imageDistance(focal, objectDistance)
        val edge = side * aperture / 2 * unit
        fline {
            moveTo(lensX - objectDistance * unit, axis)
            lineTo(lensX, axis + edge)
            // Towards the image point, stopping at the sensor.
            lineTo(sensorX, axis + edge * (1 - (sensorX - lensX) / (image * unit)))
            color = c
            thickness = width
        }
    }

fun draw() {
    val sensor = ThinLens.imageDistance(focal, focus)
    val sensorX = lensX + sensor * unit
    val (near, far) = ThinLens.depthOfField(focal, aperture, focus, pixel).let { it[0] to it[1] }
    val farX = if (far.isInfinite()) 0.0 else max(0.0, lensX - far * unit)
    val parts = mutableListOf(
        fline {
            rect(farX, axis - 70, lensX - near * unit - farX, 140)
            filled = true
            stroked = false
            color = vec(0.4, 0.9, 1.0, 0.12)
        },
        fline {
            moveTo(0, axis)
            lineTo(640, axis)
            color = guide
            thickness = 1.0
        },
        fline {
            moveTo(lensX, axis - aperture / 2 * unit)
            lineTo(lensX, axis + aperture / 2 * unit)
            color = cyan
            thickness = 6.0
        },
        fline {
            moveTo(sensorX, axis - 60)
            lineTo(sensorX, axis + 60)
            color = guide
            thickness = 3.0
        },
    )
    parts += cone(focus, sensorX, cyan, 1.5)
    parts += cone(near, sensorX, orange, 1.0)
    if (!far.isInfinite() && far * unit < lensX) parts += cone(far, sensorX, orange, 1.0)
    showPlane(
        parts,
        channel = "side",
        width = 600,
        height = 281,
        padding = 0.0,
        window = Rect(0.0, 0.0, 640.0, 300.0),
        interactive = false,
    )
    val farText = if (far.isInfinite()) "to infinity" else "to ${far.f2}"
    markdown("readout", "f/${(focal / aperture).f1}, focused at ${focus.f2}: sharp from ${near.f2} $farText")
}

draw()
controlGroup {
    slider("aperture", 0.05, 2.0, step = 0.01, initialValue = 1.0) {
        aperture = it
        draw()
    }
    slider("focal length", 0.6, 1.6, step = 0.01, initialValue = 1.0) {
        focal = it
        draw()
    }
    slider("focus distance", 1.8, 6.0, step = 0.01, initialValue = 3.0) {
        focus = it
        draw()
    }
}
