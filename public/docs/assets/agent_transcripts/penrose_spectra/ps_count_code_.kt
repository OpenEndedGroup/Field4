// Exact counts of E = 0 states on Penrose patches, beside a periodic square lattice and a
// jittered 5-fold tiling with the same number of vertices.
val radii = [6.0, 14.0, 20.0, 28.0, 36.0]
val penrose = radii.map { deBruijnTiling(symmetry = 5, radius = it, scale = 1.0).vertexGraph() }
val square = penrose.map { TilingGraph.squareLattice(sqrt(it.vertexCount / PI)) }
val jittered = radii.map { deBruijnTiling(symmetry = 5, radius = it, scale = 1.0, disorder = 0.1).vertexGraph() }
val counts = table(
    "vertices" to penrose.map { it.vertexCount },
    "E = 0 states" to penrose.map { it.zeroModeCount() },
    "Penrose" to penrose.map { it.zeroModeFraction() },
    "square lattice" to square.map { it.zeroModeFraction() },
    "jittered 0.1" to jittered.map { it.zeroModeFraction() },
).apply { title = "fraction of states at E = 0" }
["Penrose", "square lattice", "jittered 0.1"].forEach { col -> counts.format(col) { (it as Double).f4 } }
display("counts", counts)
val fit = ZeroModeFit.of(penrose.takeLast(3))
val tau = (1 + sqrt(5.0)) / 2
markdown(
    "fit",
    "bulk fit over the three largest: **%.4f** · square lattice: **%.4f** · 81 − 50τ = **%.4f**"
        .format(fit.bulk, ZeroModeFit.of(square.takeLast(3)).bulk, 81 - 50 * tau),
)
