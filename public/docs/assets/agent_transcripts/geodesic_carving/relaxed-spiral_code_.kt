// The L-shaped room again: the field spiral, then the same pipe relaxed.
val bay =
    (0..16).map { i ->
        val a = kotlin.math.PI * i / 16
        vec(-1.0 - 0.3 * kotlin.math.sin(a), 0.25 + 0.25 * kotlin.math.cos(a))
    }
val plan = [vec(-1.0, -0.7), vec(1.0, -0.7), vec(1.0, 0.1), vec(0.1, 0.1), vec(0.1, 0.7), vec(-1.0, 0.7)] + bay
val floor = outlineMesh(plan, 0.0125)
val spacing = 0.07
val (supply, back) = floor.windingAngle(floor.geodesicDistanceFromBoundary()).fieldDoubleSpiral(spacing)
val wall = outlineWallDistance(plan)
val relaxed = relaxPath(supply, back, spacing, wall)
println("closest lap ${relaxed.closest.f3}, tightest bend ${relaxed.tightestBend.f3}, crossings ${relaxed.crossings}")

// Field spiral on the left, relaxed on the right.
val walls = (plan + plan.first()).map { vec(it.x, 0f, it.y) }
fun drawn(path: List<Vec3>, dx: Float) = path.map { vec(it.x * 300f + dx, it.z * 300f, 0f) }
val shown = ArrayList<FLine>()
for (dx in [0f, 780f]) shown += drawn(walls, dx).toFLine3(vec(0.45, 0.45, 0.45, 1.0), 3.0)
shown += drawn(supply, 0f).toFLine3(vec(0.9, 0.25, 0.15, 1.0), 2.0)
shown += drawn(back, 0f).toFLine3(vec(0.15, 0.4, 0.9, 1.0), 2.0)
shown += drawn(relaxed.first, 780f).toFLine3(vec(0.9, 0.25, 0.15, 1.0), 2.0)
shown += drawn(relaxed.second, 780f).toFLine3(vec(0.15, 0.4, 0.9, 1.0), 2.0)
lines(shown, w = 1000.0, h = 400.0, fitToSize = true)
