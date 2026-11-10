import afield.graphics.lighting.*

/*
What one point on the floor sees: a hemisphere of directions. The lamp, pushed onto that
hemisphere (orange) and then flattened straight down onto the disc beneath (cyan). The share of
the disc the flattened lamp covers is the share of the lamp's radiance that reaches the point.
*/
val dome =
    icosphereMesh(4, 1.0).let { ball ->
        ball.filterTriangles { t -> ball.triangle(t).let { (a, b, c) -> a.y + b.y + c.y > 0 } }
    }
val floor = floorMesh(2.6, 2.6, divisions = 48)
val rim = fline {
    moveTo(1.0, 0.0, 0.0)
    for (k in 1..96) lineTo(cos(2 * PI * k / 96), 0.0, sin(2 * PI * k / 96))
    color = vec(1.0, 1.0, 1.0, 0.4)
}
val side = 1.0

fun draw(across: Double) {
    val lamp = RectLight(vec(across, 1.2, 0.0), width = side, height = side, power = 20.0)
    val outline = planeMesh(12, 12, side, side).transformed { p -> vec(p.x + across, 1.2, p.y) }
    val onDome = outline.transformed { p -> p.normalize().mul(1.004f) }
    val flat = onDome.transformed { p -> vec(p.x, 0.004, p.z) }
    var covered = 0.0
    for (t in 0 until flat.triangleCount) covered += flat.triangleArea(t)
    // Irradiance two ways: summed over the lamp by afield.graphics.lighting, and the lamp's
    // radiance times the flattened area it covers.
    val radiance = lamp.power / (PI * side * side)
    val e = irradianceAt(vec(0.0, 0.0, 0.0), vec(0.0, 1.0, 0.0), lamp).x
    val sight =
        listOf(-0.5 to -0.5, 0.5 to -0.5, 0.5 to 0.5, -0.5 to 0.5).map { (x, z) ->
            fline {
                moveTo(0.0, 0.0, 0.0)
                lineTo(across + x, 1.2, z)
                color = vec(1.0, 1.0, 1.0, 0.3)
            }
        }
    three(channel = "scene", width = 640, height = 420) {
        mesh(floor.lit([lamp], occluders = null, exposure = 0.35), unlit = true, wireframe = false)
        mesh(dome.withColor(0.4, 0.45, 0.5), opacity = 0.12, wireframe = false, cullBackFaces = false)
        lines(sight + rim)
        mesh(lamp.mesh(), unlit = true, wireframe = false, cullBackFaces = false)
        mesh(onDome.withColor(1.0, 0.6, 0.2), unlit = true, wireframe = false, cullBackFaces = false)
        mesh(flat.withColor(0.4, 0.9, 1.0), unlit = true, wireframe = false, cullBackFaces = false)
        fitTo(vec(-1.1, 0.0, -1.1), vec(1.1, 1.25, 1.1))
        camera(yaw = 20.0, pitch = 30.0)
        background("#000000")
        hud(false)
    }
    markdown("readout", "${(100 * covered / PI).f1} % of the disc · ${(radiance * covered).f2} W/m² (summed: ${e.f2})")
}

draw(0.0)
controlGroup {
    slider("lamp position", -2.0, 2.0, step = 0.05, initialValue = 0.0) { draw(it) }
}
