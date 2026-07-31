// A static 240-point background mesh with three rigid 22-point clusters crossing it.
// Cluster-internal pairs are adjacent for the whole four seconds; cluster-to-background
// pairs only in passing. The minWeight cut has to tell those apart using nothing but
// accumulated weight -- and it recovers the groups exactly.
fun disc(n: Int, r: Double) = List(n) { i ->
    val a = i * 2.39996322972865332
    val rad = r * Math.sqrt((i + 0.5) / n)
    Vec3((rad * Math.cos(a)).toFloat(), (rad * Math.sin(a)).toFloat(), 0f)
}

val nBg = 240
val perCluster = 22
val n = nBg + 3 * perCluster
val background = disc(nBg, 0.50)
val blobs = List(3) { disc(perCluster, 0.070) }
val starts = listOf(Vec3(-0.44f, -0.20f, 0f), Vec3(-0.30f, 0.40f, 0f), Vec3(0.34f, -0.44f, 0f))
val velocities = listOf(Vec3(0.20f, 0.07f, 0f), Vec3(0.16f, -0.19f, 0f), Vec3(-0.12f, 0.20f, 0f))
// Used ONLY to check the answer, never to build the graph.
val group = IntArray(n) { if (it < nBg) -1 else (it - nBg) / perCluster }

val points = MutableList(n) { Vec3(0f, 0f, 0f) }
for (i in 0 until nBg) points[i] = Vec3(background[i])
val state = DynamicPointState(points)
val edges = DynamicEdgeList(n)
val hash = Vec3SpatialHash(0.055)
val ceiling = 3.0
val system = Vec3PersistentProximityHysteresisSystem(
    rEnter = 0.062, rExit = 0.075, ttl = 0.4f, reinforce = 2.5, maxWeight = ceiling)

repeat(240) { f ->
    val t = f / 60.0
    for (c in 0 until 3) {
        val centre = Vec3(starts[c]).add(Vec3(velocities[c]).mul(t.toFloat()))
        for (k in 0 until perCluster) {
            state.points[nBg + c * perCluster + k] = Vec3(blobs[c][k]).add(centre)
        }
    }
    stepDynamicGraphWithExponentialDecay(1f / 60f, state, edges, hash, system, 0.30)
}
println(dynamicGraphSummary(state, edges))

fun componentSizes(graph: PointGraph): List<Int> {
    val parent = IntArray(graph.pointCount) { it }
    fun find(a: Int): Int {
        var x = a
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }
        return x
    }
    for (e in graph.edges) { val ra = find(e.i); val rb = find(e.j); if (ra != rb) parent[ra] = rb }
    val sizes = HashMap<Int, Int>()
    for (v in 0 until graph.pointCount) sizes.merge(find(v), 1) { a, b -> a + b }
    return sizes.values.sortedDescending()
}

val window = doubleArrayOf(-0.60, -0.60, 0.60, 0.60)
val panels = listOf(0.0, 0.5, 1.2, 2.0).map { cut ->
    val graph = edges.toPointGraph(minWeight = cut)
    val groups = componentSizes(graph).filter { it > 1 }
    val cross = graph.edges.count { group[it.i] != group[it.j] }
    println("minWeight %.1f -> %4d edges, %3d crossing a group boundary, components > 1: %s".format(
        cut, graph.edges.size, cross, groups))
    val kept = edges.values().filter { it.weight > cut }
    "w>%.1f  %de  %d groups".format(cut, graph.edges.size, groups.size) to
        dynamicGraphPanel(state.points, kept, window, 430, 430, ceiling, "magma")
}
showImg(montage(panels, columns = 2, cellSide = 430))
