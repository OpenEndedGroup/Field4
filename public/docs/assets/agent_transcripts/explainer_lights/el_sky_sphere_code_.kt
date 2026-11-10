import afield.graphics.lighting.*

/*
A floor under a dome of small lamps, all of them together giving out 400 W, and a black tile
held above the floor. With one lamp the tile casts a hard shadow; with hundreds the light comes
from the whole sky and only the ground right under the tile stays dark.
*/
val floor = floorMesh(3.0, 3.0, divisions = 60).withColor(0.8, 0.8, 0.8)
val golden = PI * (3 - sqrt(5.0))
val dot = icosphereMesh(1, 0.03).withColor(1.0, 1.0, 1.0)
var count = 1.0
var height = 0.3

fun draw() {
    val n = count.toInt()
    // Lamps spread evenly over the upper half of a sphere of radius 2; the first sits overhead.
    val lamps = n { i ->
        val y = if (n == 1) 1.0 else 1 - (i + 0.5) / n
        val r = sqrt(1 - y * y)
        PointLight(vec(2 * r * cos(golden * i), 2 * y, 2 * r * sin(golden * i)), power = 400.0 / n)
    }
    val tile = panelBoxMesh(0.6, 0.02, 0.6, divisions = 4).translated(0.0, height, 0.0).withColor(0.02, 0.02, 0.02)
    val dots = lamps.map { dot.translated(it.position) }.reduce { a, b -> a + b }
    three(channel = "scene", width = 640, height = 420) {
        // Only the tile casts shadows.
        mesh((floor + tile).lit(lamps, occluders = tile, exposure = 0.4), unlit = true, wireframe = false)
        mesh(dots, unlit = true, wireframe = false)
        // Frame the view on the whole set, so it holds still as the lamp changes.
        fitTo(vec(-2.0, 0.0, -2.0), vec(2.0, 2.0, 2.0))
        camera(yaw = 25.0, pitch = 30.0, distance = 2.3)
        background("#000000")
        hud(false)
    }
}

draw()
controlGroup {
    slider("lamps", 1.0, 300.0, step = 1.0, initialValue = count, law = ControlLaw.LOGARITHMIC) {
        count = it
        draw()
    }
    slider("tile height", 0.05, 0.8, step = 0.01, initialValue = height) {
        height = it
        draw()
    }
}
