// A 200-point disc, each point wobbling by up to 0.012 a frame: about a fifth of rEnter, enough to
// cross the threshold without rearranging the neighbourhood.
val n = 200
val base =
    List(n) { i ->
        val a = i * 2.39996322972865332
        val r = 0.45 * Math.sqrt((i + 0.5) / n)
        Vec3((r * Math.cos(a)).toFloat(), (r * Math.sin(a)).toFloat(), 0f)
    }
// Both panels share one window, computed from the cloud at rest, so they are drawn at the same scale.
val window = dynamicGraphWindow(base, padFraction = 0.06)

class Flicker(val appeared: Int, val vanished: Int, val standing: Int, val lines: List<FLine>)

// Run the cloud for 150 frames and count the edges created and lost along the way.
fun flicker(rExit: Double): Flicker {
    val state = DynamicPointState(base.map { Vec3(it) }.toMutableList())
    val edges = DynamicEdgeList(n)
    val hash = Vec3SpatialHash(0.06)
    val system = Vec3PersistentProximityHysteresisSystem(
        rEnter = 0.055,
        rExit = rExit,
        ttl = 0.35f,
        reinforce = 3.0,
        maxWeight = 3.0,
    )
    var appeared = 0
    var vanished = 0
    var present = HashSet<Pair<Int, Int>>()
    repeat(150) { frame ->
        for (i in 0 until n) {
            val phase = 0.31 * i + 0.16 * frame
            state.points[i] =
                Vec3(base[i])
                    .add(
                        vec(0.012 * Math.cos(phase), 0.012 * Math.sin(1.7 * phase), 0.0),
                    )
        }
        stepDynamicGraphWithExponentialDecay(1f / 60f, state, edges, hash, system, 0.9)
        val now = edges.values().map { it.i to it.j }.toHashSet()
        appeared += (now - present).size
        vanished += (present - now).size
        present = now
    }
    return Flicker(
        appeared,
        vanished,
        present.size,
        dynamicGraphFLines(state.points, edges.values(), maxWeight = 3.0, colormap = "magma"),
    )
}

// rExit == rEnter switches hysteresis off.
val on = flicker(rExit = 0.075)
val off = flicker(rExit = 0.055)
println("hysteresis on:  ${on.appeared} edges created, ${on.vanished} lost, ${on.standing} standing after 150 frames")
println(
    "hysteresis off: ${off.appeared} edges created, ${off.vanished} lost, ${off.standing} standing after 150 frames",
)

showPlaneGrid(
    "rExit 0.075: ${on.appeared} in, ${on.vanished} out" to on.lines,
    "rExit = rEnter: ${off.appeared} in, ${off.vanished} out" to off.lines,
    columns = 2,
    cellWidth = 460,
    cellHeight = 460,
    window = dynamicGraphPlaneWindow(window),
)

// Two points walked apart from 0.05 to 0.13: past rEnter (0.10), inside rExit (0.16).
fun walk(rExit: Double): Pair<Boolean, Boolean> {
    val state = DynamicPointState(mutableListOf(Vec3(0f, 0f, 0f), Vec3(0.05f, 0f, 0f)))
    val edges = DynamicEdgeList(2)
    val hash = Vec3SpatialHash(0.2)
    val system = Vec3PersistentProximityHysteresisSystem(
        rEnter = 0.10,
        rExit = rExit,
        ttl = 0.2f,
        reinforce = 4.0,
        maxWeight = 8.0,
    )
    repeat(30) { stepDynamicGraphWithExponentialDecay(1f / 60f, state, edges, hash, system, 0.2) }
    val formedWhileClose = edges.get(0, 1) != null
    state.points[1] = Vec3(0.13f, 0f, 0f)
    repeat(60) { stepDynamicGraphWithExponentialDecay(1f / 60f, state, edges, hash, system, 0.2) }
    return formedWhileClose to (edges.get(0, 1) != null)
}

for (rExit in [0.16, 0.10]) {
    val (formed, survives) = walk(rExit)
    println("rExit $rExit: formed at d=0.05 = $formed, survives d=0.13 = $survives")
}
