import afield.graphics.lighting.*

// A white floor beside a red wall, lit by one small lamp close to the wall, so the wall is lit
// far more brightly than the floor. The solve follows the light through three bounces once,
// when the box runs; the sliders only choose what to show.
val floor = floorMesh(2.4, 2.4, divisions = 24).withColor(0.8, 0.8, 0.8)
val wall = panelBoxMesh(2.4, 1.4, 0.05, divisions = 16).translated(0.0, 0.7, -1.225).withColor(0.9, 0.06, 0.03)
val lamp = SphereLight(vec(0.0, 0.9, -0.8), radius = 0.06, power = 1.0)
val solve = Radiosity(floor + wall, listOf(lamp), bounces = 3)

var power = 20.0
var bounces = 0.0
var bounceOnly = false

fun draw() {
    val shown = solve.lit(upTo = bounces.toInt(), from = if (bounceOnly) 1 else 0,
        exposure = if (bounceOnly) 2.0 else 0.6, scale = power, toneMap = ToneMap.EXPONENTIAL)
    three(channel = "scene", width = 640, height = 420) {
        mesh(shown, unlit = true, wireframe = false)
        mesh(lamp.mesh(), unlit = true, wireframe = false)
        camera(yaw = 25.0, pitch = 28.0, distance = 2.2)
        background("#000000")
        hud(false)
    }
}

draw()
controlGroup {
    slider("power", 5.0, 60.0, step = 1.0, initialValue = power) { power = it; draw() }
    slider("bounces", 0.0, 3.0, step = 1.0, initialValue = bounces) { bounces = it; draw() }
    checkbox("bounced light only") { bounceOnly = it; draw() }
}
