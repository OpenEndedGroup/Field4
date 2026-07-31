// Six DynamicGraphSystems, one cloud, one clock, one reinforcement budget. The only thing
// that differs between panels is what the system thinks an edge means. Brightness is
// weight / 4.0 -- the shared ceiling -- so it is a FIXED scale and a dim panel is a system
// whose weights genuinely stayed low.
val cloud = List(220) { i ->
    val a = i * 2.39996322972865332
    val r = 0.5 * Math.sqrt((i + 0.5) / 220.0)
    Vec3((r * Math.cos(a)).toFloat(), (r * Math.sin(a)).toFloat(), 0f)
}
val window = dynamicGraphWindow(cloud, padFraction = 0.06)
val ceiling = 4.0

fun build(system: DynamicGraphSystem): Pair<DynamicPointState, DynamicEdgeList> {
    val state = DynamicPointState(cloud.map { Vec3(it) }.toMutableList())
    val edges = DynamicEdgeList(state.pointCount)
    val hash = Vec3SpatialHash(0.06)
    repeat(20) { stepDynamicGraph(1f / 60f, state, edges, hash, system) }
    return state to edges
}

val systems = listOf<Pair<String, DynamicGraphSystem>>(
    "kNN k=5" to Vec3BreathingAnisotropicKnnSystem(
        k = 5, ttl = 10f, reinforce = 4.0, maxWeight = ceiling, mutualOnly = true, queryRadius = 0.12),
    "flowline" to Vec3FlowlineAdvectionGraphSystem(
        influenceRadius = 0.10, stepLength = 0.08, ttl = 10f, reinforce = 4.0, maxWeight = ceiling),
    "tangent fan" to Vec3LocalTangentTriangulationSystem(
        neighborhoodK = 6, ttl = 10f, reinforce = 4.0, maxWeight = ceiling, maxNeighborRadius = 0.10),
    "hysteresis" to Vec3PersistentProximityHysteresisSystem(
        rEnter = 0.055, rExit = 0.075, ttl = 10f, reinforce = 4.0, maxWeight = ceiling),
    "routing, 1 arc" to Vec3SignalRoutingGraphSystem(
        neighborhoodK = 6, ttl = 10f, reinforce = 1.0, maxWeight = ceiling, routeCount = 1,
        maxNeighborRadius = 0.10),
    "alpha-event" to Vec3AlphaEventHypergraphCollapseSystem(
        neighborhoodK = 6, alphaRadius = 0.075, ttl = 10f, reinforce = 4.0, maxWeight = ceiling,
        maxNeighborRadius = 0.09, minArea = 1e-4)
)

// "What fraction of edges close a triangle" is the one exact structural fact separating the
// six: it is 0 % for a functional map and 100 % for anything that builds faces.
val panels = systems.map { (name, system) ->
    val (state, edges) = build(system)
    val adjacency = Array(state.pointCount) { HashSet<Int>() }
    for (e in edges.values()) { adjacency[e.i].add(e.j); adjacency[e.j].add(e.i) }
    val closed = edges.values().count { e ->
        adjacency[e.i].any { it != e.j && adjacency[e.j].contains(it) } }
    val fraction = if (edges.values().isEmpty()) 0.0 else closed.toDouble() / edges.values().size
    val wmax = edges.values().maxOfOrNull { it.weight } ?: 0.0
    println("%-16s %4d edges  %5.1f%% in a triangle  mean degree %.2f  wmax %.4f (%.1f%% of ceiling)".format(
        name, edges.values().size, 100.0 * fraction, 2.0 * edges.values().size / state.pointCount,
        wmax, 100.0 * wmax / ceiling))
    "%s: %de %d%%tri".format(name, edges.values().size, Math.round(100.0 * fraction)) to
        dynamicGraphPanel(state.points, edges.values(), window, 400, 400, ceiling, "magma")
}

showImg(montage(panels, columns = 3, cellSide = 400))
