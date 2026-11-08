// Left: how much of the E = 0 level sits on each vertex. Right, enlarged: the smallest strictly
// localised state found near the middle of the patch.
val tiling = deBruijnTiling(symmetry = 5, radius = 6.0, scale = 1.0)
val graph = tiling.vertexGraph()
val spectrum = graph.spectrum()
val weight = spectrum.zeroWeights()
val grey = [vec(0.30, 0.31, 0.36, 1.0), vec(0.22, 0.23, 0.28, 1.0)]
val (cx, cy) = graph.centre
// Grow a confined state at each of the twelve confined vertices nearest the centre; keep the smallest.
val tried =
    graph
        .interior(0.4 * graph.extent)
        .filter { weight[it] > 1e-6 }
        .sortedBy { hypot(graph.x[it] - cx, graph.y[it] - cy) }
        .take(12)
val psi = tried.mapNotNull { graph.confinedState(it) }.minBy { s -> s.count { abs(it) > 1e-9 } }
val support = (0 until graph.vertexCount).filter { abs(psi[it]) > 1e-9 }
val residual = (0 until graph.vertexCount).maxOf { v -> abs(graph.neighbours[v].sumOf { psi[it] }) }
// The enlarged view: the tiles within 3.5 edges of the state, 2.5 times larger, to the right.
val sx = support.map { graph.x[it] }.average()
val sy = support.map { graph.y[it] }.average()
val zoom = 2.5
val dx = 1.1 * graph.extent + 3.6 * zoom
val enlarge = { f: FLine ->
    f.transform { v -> Vec3(((v.x - sx) * zoom + dx).toFloat(), ((v.y - sy) * zoom).toFloat(), 0f) }
}
val near = tiling.tiles.filter { hypot(it.center.x - sx, it.center.y - sy) < 3.5 }
val nearTiles = DeBruijnTiling(tiling.symmetry, tiling.offsets, tiling.directions, near).lines(palette = grey)
val right = (nearTiles + graph.vertexDots(psi, maxRadius = 0.32)).map(enlarge)
lines(tiling.lines(palette = grey) + graph.vertexDots(weight) + right, w = 700.0, h = 330.0, fitToSize = true)
val byDegree =
    (0 until graph.vertexCount)
        .groupBy { graph.degree(it) }
        .toSortedMap()
        .entries
        .joinToString(", ") { (d, vs) -> "%d-edge %.3f".format(d, vs.sumOf { weight[it] } / vs.size) }
markdown(
    "measure",
    "${spectrum.zeroCount()} zero states on ${graph.vertexCount} vertices. Mean weight by vertex: $byDegree. " +
        "The state on the right: ${support.size} vertices, max |A·ψ| = %.1e.".format(residual),
)
