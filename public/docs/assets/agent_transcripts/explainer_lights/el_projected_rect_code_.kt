// A square panel 0.8 m across and its shadow on a sphere of radius one around the eye. The area of
// that shadow is the panel's solid angle; move the panel away and it shrinks as the square.
val sphere = icosphereMesh(4, 1.0)
// Two great circles, so the sphere reads as a sphere without a wireframe in the way.
val circles = listOf(
    fline {
        moveTo(1.0, 0.0, 0.0)
        for (k in 1..96) lineTo(cos(2 * PI * k / 96), 0.0, sin(2 * PI * k / 96))
        color = vec(1.0, 1.0, 1.0, 0.35)
    },
    fline {
        moveTo(1.0, 0.0, 0.0)
        for (k in 1..96) lineTo(cos(2 * PI * k / 96), sin(2 * PI * k / 96), 0.0)
        color = vec(1.0, 1.0, 1.0, 0.35)
    },
)

fun draw(distance: Double) {
    val panel = planeMesh(12, 12, 0.8, 0.8).translated(0.0, 0.0, -distance)
    // Every point of the panel pushed onto the unit sphere, along its line to the eye.
    val shadow = panel.transformed { p -> p.normalize().mul(1.004f) }
    var omega = 0.0
    for (t in 0 until shadow.triangleCount) omega += shadow.triangleArea(t)
    // From the eye through each corner of the patch to the panel's corner.
    val corners =
        listOf(-0.4 to -0.4, 0.4 to -0.4, 0.4 to 0.4, -0.4 to 0.4).map { (x, y) ->
            fline {
                moveTo(0.0, 0.0, 0.0)
                lineTo(x, y, -distance)
                color = vec(1.0, 1.0, 1.0, 0.35)
            }
        }
    three(channel = "scene", width = 640, height = 420) {
        mesh(sphere.withColor(0.4, 0.45, 0.5), opacity = 0.1, wireframe = false)
        lines(circles + corners)
        mesh(panel.withColor(0.4, 0.9, 1.0), unlit = true, wireframe = false, cullBackFaces = false)
        mesh(shadow.withColor(1.0, 0.6, 0.2), unlit = true, wireframe = false, cullBackFaces = false)
        mesh(icosphereMesh(1, 0.03).withColor(1.0, 1.0, 1.0), unlit = true, wireframe = false)
        fitTo(vec(-1.1, -1.1, -2.4), vec(1.1, 1.1, 0.6))
        camera(yaw = 145.0, pitch = 18.0)
        background("#000000")
        hud(false)
    }
    markdown("readout", "${distance.f1} m: ${omega.f3} sr · × distance² = ${(omega * distance * distance).f2}")
}

draw(1.3)
controlGroup {
    slider("distance", 1.2, 4.0, step = 0.1, initialValue = 1.3) { draw(it) }
}
