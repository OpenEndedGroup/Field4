// Drag the sliders: the tiling is rebuilt, its E = 0 states counted exactly and their weight drawn
// on the vertices. 5-fold with no disorder is the Penrose tiling, at about 0.098.
var symmetry = 5.0
var disorder = 0.0

val draw = {
    val tiling = deBruijnTiling(symmetry = symmetry.toInt(), radius = 25.0 / symmetry, scale = 1.0, disorder = disorder)
    val graph = tiling.vertexGraph()
    val weight = graph.spectrum().zeroWeights()
    val grey = [vec(0.30, 0.31, 0.36, 1.0), vec(0.22, 0.23, 0.28, 1.0)]
    lines(tiling.lines(palette = grey) + graph.vertexDots(weight), w = 480.0, h = 480.0, fitToSize = true)
    markdown(
        "measure",
        "%d-fold, disorder %.2f: **%d** E = 0 states of %d vertices, fraction **%.4f** (Penrose: 81 − 50τ = 0.0983)"
            .format(
                symmetry.toInt(),
                disorder,
                graph.zeroModeCount(),
                graph.vertexCount,
                graph.zeroModeFraction(),
            ),
    )
}

slider("symmetry", min = 3.0, max = 12.0, step = 1.0, initialValue = 5.0, channel = "symmetry") {
    symmetry = it
    draw()
}
slider("disorder", min = 0.0, max = 0.8, step = 0.01, initialValue = 0.0, channel = "disorder") {
    disorder = it
    draw()
}
draw()
