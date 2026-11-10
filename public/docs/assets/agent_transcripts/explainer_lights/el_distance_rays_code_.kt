// A point light sends 600 rays into a cone. Move the square patch away and count how many reach it.
val count = 600
val golden = PI * (3 - sqrt(5.0))
// Directions spread evenly over a cone 30° around +x.
val directions = count { i ->
    val c = 1 - (1 - cos(PI / 6)) * (i + 0.5) / count
    val s = sqrt(1 - c * c)
    vec(c, s * cos(golden * i), s * sin(golden * i))
}

fun draw(distance: Double) {
    var reached = 0
    val rays = directions.map { d ->
        // Where the ray crosses the patch's plane, x = distance.
        val t = distance / d.x
        val hit = abs(t * d.y) <= 0.25 && abs(t * d.z) <= 0.25
        if (hit) reached++
        val end = if (hit) t else 4.5 / d.x
        fline {
            moveTo(0.0, 0.0, 0.0)
            lineTo(end * d.x, end * d.y, end * d.z)
            color = if (hit) vec(0.4, 0.9, 1.0, 1.0) else vec(1.0, 1.0, 1.0, 0.12)
        }
    }
    val patch =
        planeMesh(1, 1, 0.5, 0.5)
            .rotated(Quat().rotateY((-PI / 2).toFloat()))
            .translated(distance, 0.0, 0.0)
            .withColor(1.0, 0.6, 0.2)
    three(channel = "scene", width = 640, height = 380) {
        lines(rays)
        mesh(patch, unlit = true, wireframe = false, cullBackFaces = false)
        mesh(icosphereMesh(2, 0.05).withColor(1.0, 1.0, 1.0), unlit = true, wireframe = false)
        camera(yaw = 30.0, pitch = 25.0, distance = 2.2)
        background("#000000")
        hud(false)
        helpers(false)
    }
    markdown("readout", "${distance.f1} m: $reached of $count rays")
}

draw(1.0)
controlGroup {
    slider("distance", 1.0, 4.0, step = 0.1, initialValue = 1.0) { draw(it) }
}
