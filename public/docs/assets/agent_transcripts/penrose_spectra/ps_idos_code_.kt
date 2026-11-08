// N(E) for a radius-8 Penrose patch: every state (grey), and states weighted by their share in
// the patch's inner 60% (orange). Left, the whole spectrum; right, E from -0.5 to 0.5.
val graph = deBruijnTiling(symmetry = 5, radius = 8.0, scale = 1.0).vertexGraph()
val spectrum = graph.spectrum()
val inner = graph.interior(0.6 * graph.extent)
val bulk = spectrum.weightOn(inner)
val grey = vec(0.62, 0.62, 0.68, 1.0)
val orange = vec(1.0, 0.55, 0.15, 1.0)
// One panel: both curves over [from, to], with a white tick at E = 0, shifted right by dx.
fun panel(from: Double, to: Double, dx: Double): List<FLine> {
    val all = spectrum.idosLine(300.0, 300.0, from = from, to = to).apply {
        color = grey
        thickness = 3.0
    }
    val weighted = spectrum.idosLine(300.0, 300.0, weights = bulk, from = from, to = to).apply {
        color = orange
        thickness = 1.5
    }
    val zeroX = -from / (to - from) * 300.0
    val axis = fline {
        moveTo(0.0, 0.0)
        lineTo(300.0, 0.0)
        moveTo(zeroX, 0.0)
        lineTo(zeroX, 10.0)
    }
        .apply { color = vec(1.0, 1.0, 1.0, 0.7) }
    return [all, weighted, axis].map { it.transform { v -> Vec3(v.x + dx.toFloat(), v.y, 0f) } }
}
lines(
    panel(spectrum.energies.first(), spectrum.energies.last(), 0.0) + panel(-0.5, 0.5, 360.0),
    w = 660.0,
    h = 330.0,
    fitToSize = true,
)
val gap = spectrum.gaps(2, weights = bulk, minWeight = 0.3 * inner.size / graph.vertexCount)
"E from %.3f to %.3f; jump at 0 of %.4f; nearest bulk states at %.3f and %.3f; nearest of all at ±%.3f"
    .format(
        spectrum.energies.first(),
        spectrum.energies.last(),
        spectrum.zeroCount().toDouble() / graph.vertexCount,
        gap.minOf { it.first },
        gap.maxOf { it.second },
        spectrum.energies.filter { abs(it) > 1e-8 }.minOf { abs(it) },
    )
