import afield.graphics.lighting.*

// Nine tiles of different colours under one lamp whose colour you pick. A tile can only send
// back the colours the lamp gives it.
val colours = listOf(
    vec(0.8, 0.8, 0.8), vec(0.8, 0.1, 0.1), vec(0.1, 0.7, 0.1),
    vec(0.1, 0.2, 0.8), vec(0.8, 0.7, 0.1), vec(0.1, 0.7, 0.7),
    vec(0.7, 0.1, 0.7), vec(0.8, 0.4, 0.1), vec(0.3, 0.3, 0.3),
)
val tiles = colours.withIndex().map { (i, c) ->
    floorMesh(0.5, 0.5, divisions = 8).translated((i % 3 - 1) * 0.6, 0.0, (i / 3 - 1) * 0.6).withColor(c.x, c.y, c.z)
}.reduce { a, b -> a + b }

fun draw(light: Vec4) {
    val lamp = RectLight(vec(0.0, 1.6, 0.0), width = 1.2, height = 1.2, power = 60.0, color = vec(light.x, light.y, light.z))
    three(channel = "scene", width = 640, height = 420) {
        mesh(tiles.lit(listOf(lamp), occluders = null, exposure = 0.6), unlit = true, wireframe = false)
        camera(yaw = 0.0, pitch = 60.0, distance = 2.0)
        background("#000000")
        hud(false)
    }
}

draw(vec(1.0, 1.0, 1.0, 1.0))
controlGroup {
    colorPicker("light colour", initialValue = vec(1.0, 1.0, 1.0, 1.0)) { draw(it) }
}
