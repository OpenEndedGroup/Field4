import afield.graphics.lighting.*

// Red, green and blue lamps over a white floor with a white block. Where two pools overlap
// their light adds; the block's shadows take the colours of the lamps it hides.
val floor = floorMesh(2.4, 2.4, divisions = 72).withColor(0.8, 0.8, 0.8)
val block = panelBoxMesh(0.3, 0.5, 0.3, divisions = 6).translated(0.0, 0.25, 0.0).withColor(0.8, 0.8, 0.8)
val scene = floor + block
var red = true
var green = true
var blue = true
var spread = 0.6

fun draw() {
    val lamps = listOf(
        Triple(red, 90.0, vec(1.0, 0.0, 0.0)),
        Triple(green, 210.0, vec(0.0, 1.0, 0.0)),
        Triple(blue, 330.0, vec(0.0, 0.0, 1.0)),
    ).filter { it.first }
        .map { (_, degrees, colour) ->
            val a = degrees * PI / 180
            SphereLight(
                vec(spread * cos(a), 1.2, spread * sin(a)),
                radius = 0.04,
                power = 25.0,
                color = colour,
                samples = 128,
            )
        }
    three(channel = "scene", width = 640, height = 420) {
        mesh(scene.lit(lamps, occluders = block, exposure = 1.2), unlit = true, wireframe = false)
        lamps.forEach { mesh(it.mesh(), unlit = true, wireframe = false) }
        camera(yaw = 20.0, pitch = 45.0, distance = 2.0)
        background("#000000")
        hud(false)
    }
}

draw()
controlGroup {
    checkbox("red", initialValue = true) {
        red = it
        draw()
    }
    checkbox("green", initialValue = true) {
        green = it
        draw()
    }
    checkbox("blue", initialValue = true) {
        blue = it
        draw()
    }
    slider("spread", 0.0, 1.2, step = 0.01, initialValue = spread) {
        spread = it
        draw()
    }
}
