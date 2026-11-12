// A glowing ball and the light leaving it, drawn as rays: one ray for every quarter watt.
val golden = PI * (3 - sqrt(5.0))

fun draw(watts: Double) {
    val n = (watts * 4).roundToInt()
    val rays = n { i ->
        // Directions spread evenly over the sphere, each ray starting on the ball's surface.
        val y = 1 - 2 * (i + 0.5) / n
        val r = sqrt(1 - y * y)
        val d = vec(r * cos(golden * i), y, r * sin(golden * i))
        fline {
            moveTo(d.x * 0.2, d.y * 0.2, d.z * 0.2)
            lineTo(d.x * 1.4, d.y * 1.4, d.z * 1.4)
            color = vec(1.0, 0.9, 0.6, 0.5)
        }
    }
    // How bright the ball looks: its glow fills up and then stays white.
    val glow = min(1.0, watts / 60)
    three(channel = "scene", width = 640, height = 420) {
        lines(rays)
        // Frame the view on the rays' reach, not on the ball.
        fitTo(vec(-1.0, -1.0, -1.0), vec(1.0, 1.0, 1.0))
        mesh(icosphereMesh(3, 0.2).withColor(glow, glow * 0.95, glow * 0.8), unlit = true, wireframe = false)
        camera(yaw = 20.0, pitch = 15.0, distance = 2.2)
        background("#000000")
        hud(false)
        helpers(false)
    }
    markdown("readout", "${watts.roundToInt()} W · $n rays")
}

draw(10.0)
controlGroup {
    slider("power", 1.0, 100.0, step = 1.0, initialValue = 10.0) { draw(it) }
}
