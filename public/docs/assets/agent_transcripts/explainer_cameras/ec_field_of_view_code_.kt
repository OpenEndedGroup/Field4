import afield.simulation.optics.*

// A pinhole camera from the side. Rays cross at the hole, so the picture is upside down;
// the sensor's edges and its distance from the hole set how wide a view it takes.
val unit = 80.0
val holeX = 400.0
val axis = 150.0
val cyan = vec(0.4, 0.9, 1.0, 1.0)
val orange = vec(1.0, 0.6, 0.2, 1.0)
val guide = vec(1.0, 1.0, 1.0, 0.35)
var distance = 1.0

fun segment(x0: Double, y0: Double, x1: Double, y1: Double, c: Vec4, width: Double = 1.5) = fline {
    moveTo(x0, y0)
    lineTo(x1, y1)
    color = c
    thickness = width
}

fun draw() {
    val sensorX = holeX + distance * unit
    val half = 0.5 * unit
    // The edge rays, from each end of the sensor out through the hole.
    // Where the edge rays leave the picture.
    val low = frameExit(holeX, axis, -distance * unit, half, 640.0, 300.0)
    val high = frameExit(holeX, axis, -distance * unit, -half, 640.0, 300.0)
    val view = fline {
        moveTo(holeX, axis)
        lineTo(low[0], low[1])
        if (low[1] >= 300.0 || high[1] <= 0.0) {
            lineTo(0.0, 300.0)
            lineTo(0.0, 0.0)
        }
        lineTo(high[0], high[1])
        close()
        filled = true
        stroked = false
        color = vec(0.4, 0.9, 1.0, 0.12)
    }
    val tipX = 120.0
    val tipY = axis - 90.0
    val imageY = axis + 90.0 * distance * unit / (holeX - tipX)
    val parts = [
        view,
        segment(sensorX, axis + half, holeX, axis + half, guide),
        segment(sensorX, axis - half, holeX, axis - half, guide),
        segment(holeX, axis - half, holeX, axis - 6, guide, 2.0),
        segment(holeX, axis + 6, holeX, axis + half, guide, 2.0),
        segment(sensorX, axis - half, sensorX, axis + half, cyan, 3.0),
        segment(sensorX, axis - half, low[0], low[1], cyan, 1.0),
        segment(sensorX, axis + half, high[0], high[1], cyan, 1.0),
        segment(tipX, axis + 40, tipX, tipY, orange, 4.0),
        segment(tipX, tipY, sensorX, imageY, orange, 1.0),
        segment(sensorX, axis - 40.0 * distance * unit / (holeX - tipX), sensorX, imageY, orange, 4.0),
    ]
    showPlane(
        parts,
        channel = "side",
        width = 600,
        height = 281,
        padding = 0.0,
        window = Rect(0.0, 0.0, 640.0, 300.0),
        interactive = false,
    )
    val degrees = 2 * atan(0.5 / distance) * 180 / PI
    markdown("readout", "sensor ${distance.f2} behind the hole: a view ${degrees.f0}° wide")
}

draw()
controlGroup {
    slider("sensor distance", 0.3, 2.4, step = 0.02, initialValue = 1.0) {
        distance = it
        draw()
    }
}
