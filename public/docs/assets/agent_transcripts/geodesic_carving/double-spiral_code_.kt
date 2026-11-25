// A 4 m by 2.8 m room, at 1:2; pipe every 0.08 units (16 cm).
val floor = heightfieldMesh(100, 70, 2.0, 1.4) { _, _ -> 0f }
val fromWalls = floor.geodesicDistanceFromBoundary()
val rings = floor.evenIsolines(fromWalls, 0.08).map { level -> level.maxBy { it.size } }
// Supply runs in to the bend in the middle, return runs back out between it.
val (supply, back) = doubleSpiralArms(rings)
var length = 0.0
for (arm in [supply, back]) for (i in 1 until arm.size) length += arm[i].distance(arm[i - 1])
println("${rings.size} rings, ${supply.size + back.size} points, ${(length * 2).f1} m of pipe")

// Seen from above: floor x to the right, floor z down the page.
fun plan(path: List<Vec3>) = path.map { vec(it.x * 300f, it.z * 300f, 0f) }
lines(
    plan(supply).toFLine3(vec(0.9, 0.25, 0.15, 1.0), 2.0),
    plan(back).toFLine3(vec(0.15, 0.4, 0.9, 1.0), 2.0),
    w = 640.0,
    h = 460.0,
    fitToSize = true,
)
