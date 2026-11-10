import afield.simulation.optics.*

// A ray meets glass (index 1.5). Part of it turns as it crosses, part reflects;
// the line widths show how the light divides.
val glass = 1.5
val cyan = vec(0.4, 0.9, 1.0, 1.0)
val orange = vec(1.0, 0.6, 0.2, 1.0)
var angle = 40.0
var inside = false

fun ray(x1: Double, y1: Double, share: Double, c: Vec4) = fline {
    moveTo(320.0, 180.0)
    lineTo(x1, y1)
    color = vec(c.x, c.y, c.z, 0.25 + 0.75 * share)
    thickness = 0.8 + 5.0 * share
}

fun draw() {
    val theta = angle * PI / 180
    val (n1, n2) = if (inside) glass to 1.0 else 1.0 to glass
    val side = if (inside) 1.0 else -1.0 // the incoming ray's side of the surface: -1 above, +1 below
    val turned = Refraction.snellAngle(theta, n1, n2)
    val reflected = Refraction.reflectance(theta, n1, n2)
    val parts = mutableListOf(
        fline {
            rect(0, 180, 640, 180)
            filled = true
            stroked = false
            color = vec(0.4, 0.9, 1.0, 0.12)
        },
        fline {
            moveTo(320, 10)
            lineTo(320, 350)
            color = vec(1.0, 1.0, 1.0, 0.35)
            thickness = 1.0
        },
        fline {
            moveTo(320 - 200 * sin(theta), 180 + side * 200 * cos(theta))
            lineTo(320, 180)
            color = cyan
            thickness = 5.8
        },
        ray(320 + 200 * sin(theta), 180 + side * 200 * cos(theta), reflected, orange),
    )
    if (!turned.isNaN()) parts += ray(320 + 200 * sin(turned), 180 - side * 200 * cos(turned), 1 - reflected, cyan)
    showPlane(
        parts,
        channel = "rays",
        width = 600,
        height = 338,
        padding = 0.0,
        window = Rect(0.0, 0.0, 640.0, 360.0),
        interactive = false,
    )
    val crossing =
        if (turned.isNaN()) "none crosses: total internal reflection" else "crosses at ${(turned * 180 / PI).f1}°"
    markdown("readout", "arrives at ${angle.f1}°, ${(100 * reflected).f0}% reflects, $crossing")
}

draw()
controlGroup {
    slider("angle", 0.0, 89.0, step = 0.5, initialValue = 40.0) {
        angle = it
        draw()
    }
    checkbox("from inside the glass") {
        inside = it
        draw()
    }
}
