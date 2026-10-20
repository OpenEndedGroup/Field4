import afield.geometry.topology.trees.*

// Seven hills and three hollows. Drag the slider to prune branches shorter than its persistence.
val bumps = [
    Triple(40.0, 40.0, 1.0),
    Triple(150.0, 35.0, 0.8),
    Triple(100.0, 80.0, 0.6),
    Triple(30.0, 120.0, 0.7),
    Triple(170.0, 120.0, 0.9),
    Triple(120.0, 130.0, 0.5),
    Triple(70.0, 60.0, 0.45),
    Triple(100.0, 30.0, -0.5),
    Triple(60.0, 100.0, -0.4),
    Triple(150.0, 80.0, -0.45),
]
val field =
    Img.generate(200, 150) { x, y ->
        bumps.sumOf { (cx, cy, a) -> a * exp(-((x - cx).pow(2) + (y - cy).pow(2)) / 300.0) }.toFloat()
    }
val full = ContourTrees.contourTree(field)

fun show(persistence: Double) {
    val tree = full.simplify(persistence)
    val dots = fline {
        tree.nodes.forEach { moveTo(it.x, it.y) }
        pointed = true
        pointSize = 5.0
    }
    val plot =
        Img(200, 150, 3, FloatArray(200 * 150 * 3)).drawFLines(tree.plot(200.0, 150.0, margin = 15.0), scale = 2.0)
    showImgGrid(
        "field: ${tree.nodes.size} nodes" to
            field.normalized().colormap("viridis").drawFLines([dots], scale = 2.0, color = Vec4(1f, 1f, 1f, 1f)),
        "regions: ${tree.arcs.size} arcs" to tree.segmentationImage().scaleBy(2.0),
        "tree" to plot,
        columns = 3,
        cellSide = 300,
    )
}
slider("persistence", min = 0.0, max = 0.5, step = 0.005, initialValue = 0.03) { show(it) }
show(0.03)
