import afield.graphics.lighting.*

// A lamp over a floor with a block on it. The light at every vertex is computed here.
val floor = floorMesh(4.0, 4.0, divisions = 64)
val block = panelBoxMesh(0.5, 0.5, 0.5, divisions = 10).translated(0.0, 0.25, 0.0)
val scene = floor + block
var across = 0.6
var size = 0.5

fun draw() {
    val lamp = RectLight(vec(across, 1.3, 0.0), width = size, height = size, power = 30.0)
    three(channel = "scene", width = 640, height = 420) {
        // Only the block casts shadows.
        mesh(scene.lit([lamp], occluders = block, exposure = 0.5), unlit = true, wireframe = false)
        // Frame the view on the whole set, so it holds still as the lamp changes.
        fitTo(vec(-2.0, 0.0, -2.0), vec(2.0, 1.6, 2.0))
        mesh(lamp.mesh(), unlit = true, wireframe = false, cullBackFaces = false)
        camera(yaw = 35.0, pitch = 30.0, distance = 2.6)
        background("#000000")
        hud(false)
    }
}

draw()
controlGroup {
    slider("position", -1.2, 1.2, initialValue = across) {
        across = it
        draw()
    }
    slider("size", 0.05, 1.5, initialValue = size) {
        size = it
        draw()
    }
}
