import afield.graphics.three.ThreeCameraSpec

// A carved boss: a dome with a ripple, on a square tablet.
val relief =
    heightfieldMesh(80, 80, 2.0, 2.0) { x, z ->
        val r = kotlin.math.sqrt(x * x + z * z)
        0.5f * kotlin.math.exp(-r * r * 2.2f) + 0.05f * kotlin.math.cos(r * 14f) * kotlin.math.exp(-r * 1.5f)
    }
val fromEdge = relief.geodesicDistanceFromBoundary()

// One ring a level, nearest the edge first; join them into one path.
val rings = relief.evenIsolines(fromEdge, 0.06).map { level -> level.maxBy { it.size } }
val spiral = spiralFromRings(rings)
println("${rings.size} rings, spiral of ${spiral.size} points")

fun lifted(path: List<Vec3>) = path.map { vec(it.x, it.y + 0.01f, it.z) }
val view = ThreeCameraSpec(yawDegrees = 25.0, pitchDegrees = 50.0)
three(height = 360) {
    columns(2)
    panel(title = "iso-geodesic rings from the border", camera = view)
    mesh(relief.colouredBy(fromEdge), wireframe = false)
    lines(rings.map { lifted(it).toFLine3(vec(1.0, 1.0, 1.0, 0.95), 1.4) }, id = "rings", panel = 0)
    panel(title = "one spiral of ${spiral.size} points, never lifting", camera = view)
    lines([spiral.toFLine3(vec(0.95, 0.35, 0.2, 1.0), 1.6)], id = "spiral", panel = 1)
}
