// The L-shaped room, with the supply and return ports moved round the walls.
val bay =
    (0..16).map { i ->
        val a = kotlin.math.PI * i / 16
        vec(-1.0 - 0.3 * kotlin.math.sin(a), 0.25 + 0.25 * kotlin.math.cos(a))
    }
val plan = [vec(-1.0, -0.7), vec(1.0, -0.7), vec(1.0, 0.1), vec(0.1, 0.1), vec(0.1, 0.7), vec(-1.0, 0.7)] + bay
val floor = outlineMesh(plan, 0.0125)
val fromWalls = floor.geodesicDistanceFromBoundary()
val wall = outlineWallDistance(plan)
val spacing = 0.07

// Eight ports, evenly spaced by length round the walls.
val loop = plan + plan.first()
val sides = loop.zipWithNext { a, b -> a.distance(b).toDouble() }
val perimeter = sides.sum()
fun pointRound(t: Double): Vec3 {
    var left = t * perimeter
    for ((i, side) in sides.withIndex()) {
        if (left <= side) {
            val a = loop[i]
            val b = loop[i + 1]
            val u = (left / side).toFloat()
            return vec(a.x + u * (b.x - a.x), 0f, a.y + u * (b.y - a.y))
        }
        left -= side
    }
    return vec(loop.last().x, 0f, loop.last().y)
}

// The eight layouts do not depend on each other, so lay them out on all the cores at once.
val ports = (0 until 8).map { pointRound(it / 8.0) }
val pipes =
    ports
        .parallelStream()
        .map { port ->
            val (supply, back) = floor.windingAngle(fromWalls, port = port).fieldDoubleSpiral(spacing)
            relaxPath(supply, back, spacing, wall, steps = 1000)
        }
        .toList()

val shown = ArrayList<FLine>()
val walls = loop.map { vec(it.x, 0f, it.y) }
for ((k, pipe) in pipes.withIndex()) {
    val port = ports[k]
    val dx = (k % 4) * 780f
    val dy = (k / 4) * 540f
    fun drawn(path: List<Vec3>) = path.map { vec(it.x * 300f + dx, it.z * 300f + dy, 0f) }
    shown += drawn(walls).toFLine3(vec(0.45, 0.45, 0.45, 1.0), 3.0)
    shown += drawn(pipe.first).toFLine3(vec(0.9, 0.25, 0.15, 1.0), 3.0)
    shown += drawn(pipe.second).toFLine3(vec(0.15, 0.4, 0.9, 1.0), 3.0)
    shown +=
        drawn(
            (0..24).map { i ->
                val a = 2 * kotlin.math.PI * i / 24
                vec(port.x + 0.05f * kotlin.math.cos(a).toFloat(), 0f, port.z + 0.05f * kotlin.math.sin(a).toFloat())
            },
        ).toFLine3(vec(1.0, 0.9, 0.3, 1.0), 2.5)
    println(
        "port ${k + 1}: ${(pipe.length * 2).f1} m, closest ${pipe.closest.f3}, bend ${pipe.tightestBend.f3}, crossings ${pipe.crossings}",
    )
}
lines(shown, w = 1100.0, h = 420.0, fitToSize = true)
