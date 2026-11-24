// An L-shaped living room with a bay window in the left wall, at 1:2.
val bay =
    (0..16).map { i ->
        val a = kotlin.math.PI * i / 16
        vec(-1.0 - 0.3 * kotlin.math.sin(a), 0.25 + 0.25 * kotlin.math.cos(a))
    }
val plan = [vec(-1.0, -0.7), vec(1.0, -0.7), vec(1.0, 0.1), vec(0.1, 0.1), vec(0.1, 0.7), vec(-1.0, 0.7)] + bay
val floor = outlineMesh(plan, 0.0125)
val angle = floor.windingAngle(floor.geodesicDistanceFromBoundary())
val (supply, back) = angle.fieldDoubleSpiral(0.07)
var length = 0.0
for (arm in [supply, back]) for (i in 1 until arm.size) length += arm[i].distance(arm[i - 1])
println("${supply.size + back.size} points, ${(length * 2).f1} m of pipe")

// The winding angle's spokes, faintly, under the pipe and the walls.
fun drawn(path: List<Vec3>) = path.map { vec(it.x * 300f, it.z * 300f, 0f) }
val shown = ArrayList<FLine>()
for (k in 1..11) for (spoke in angle.mesh.isolines(angle.angle, k / 12.0)) shown +=
    drawn(spoke).toFLine3(vec(0.3, 0.3, 0.3, 1.0), 1.0)
shown += drawn((plan + plan.first()).map { vec(it.x, 0f, it.y) }).toFLine3(vec(0.45, 0.45, 0.45, 1.0), 3.0)
shown += drawn(supply).toFLine3(vec(0.9, 0.25, 0.15, 1.0), 2.0)
shown += drawn(back).toFLine3(vec(0.15, 0.4, 0.9, 1.0), 2.0)
lines(shown, w = 640.0, h = 460.0, fitToSize = true)
