import afield.graphics.lighting.*

// A black wall on a white floor, lit by a ball of light. The ball always gives out 40 W;
// make it bigger and the edge of the shadow softens.
// The wall's faces sit on the floor's grid lines, so no floor vertex is half under it.
val floor = floorMesh(4.0, 3.2, divisions = 80).withColor(0.8, 0.8, 0.8)
val wall = panelBoxMesh(0.05, 0.6, 1.2, divisions = 4).translated(0.025, 0.3, 0.0).withColor(0.03, 0.03, 0.03)
val blocker = wall.bvh()
val scene = floor + wall
// The lit floor depends only on the lamp, so moving the point redraws the lines, not the light.
var litFor = ""
var litScene = scene
var size = 0.05
var altitude = 50.0
var probe = 1.15

fun draw() {
    val a = altitude * PI / 180
    val lamp = SphereLight(vec(-1.4 * cos(a), 1.4 * sin(a), 0.0), radius = size, power = 40.0, samples = 160)
    // From one point on the floor: a line to every bit of the lamp it faces, cyan where it is clear.
    val point = vec(probe, 0.0, 0.0)
    val sight = lamp.sightLines(point, vec(0.0, 1.0, 0.0), blocker)
    val rays = sight.map { s ->
        fline {
            moveTo(s.from.x.toDouble(), s.from.y.toDouble(), s.from.z.toDouble())
            lineTo(s.to.x.toDouble(), s.to.y.toDouble(), s.to.z.toDouble())
            color = if (s.clear) vec(0.4, 0.9, 1.0, 0.7) else vec(1.0, 1.0, 1.0, 0.08)
        }
    }
    if (litFor != "$size $altitude") {
        // Only the wall casts shadows; the floor need not be tested against its own triangles.
        litScene = scene.lit(listOf(lamp), occluders = wall, exposure = 0.6)
        litFor = "$size $altitude"
    }
    three(channel = "scene", width = 640, height = 420) {
        mesh(litScene, unlit = true, wireframe = false)
        mesh(lamp.mesh(), unlit = true, wireframe = false)
        mesh(icosphereMesh(2, 0.035).translated(point).withColor(1.0, 0.6, 0.2), unlit = true, wireframe = false)
        lines(rays)
        // Frame the view on the whole set, so it holds still as the lamp changes.
        fitTo(vec(-2.0, 0.0, -1.6), vec(2.0, 1.6, 1.6))
        camera(yaw = 20.0, pitch = 35.0)
        background("#000000")
        hud(false)
    }
    markdown("readout", "The orange point sees ${(100.0 * sight.count { it.clear } / sight.size.coerceAtLeast(1)).roundToInt()} % of the lamp")
}

draw()
controlGroup {
    slider("size", 0.02, 0.5, step = 0.01, initialValue = size) { size = it; draw() }
    slider("altitude", 15.0, 85.0, step = 1.0, initialValue = altitude) { altitude = it; draw() }
    slider("point", -1.5, 1.8, step = 0.01, initialValue = probe) { probe = it; draw() }
}
