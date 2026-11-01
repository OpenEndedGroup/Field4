import afield.geometry.topology.vectorfield.*

// Each entry is (x, y, outflow, swirl): positive outflow is a source, negative a sink, swirl turns it.
// The slider scales every swirl: watch sources become spirals and saddles move.
val features = [
    [50.0, 45.0, 1.0, 0.6],
    [150.0, 40.0, -1.0, -0.8],
    [100.0, 100.0, 0.2, 1.2],
    [40.0, 115.0, -0.8, 0.0],
    [165.0, 115.0, 0.9, -0.3],
]

fun show(twist: Double) {
    val flow =
        Img.generateRGB(200, 150) { x, y ->
            var u = 0.0
            var v = 0.0
            for ((cx, cy, out, swirl) in features) {
                val dx = x - cx
                val dy = y - cy
                val k = exp(-(dx * dx + dy * dy) / 900.0)
                u += k * (out * dx - twist * swirl * dy)
                v += k * (out * dy + twist * swirl * dx)
            }
            Triple(u.toFloat(), v.toFloat(), 0f)
        }
    val topology = VectorFieldTopology.compute(flow, minStrength = 0.01)
    val black = Img(200, 150, 3, FloatArray(200 * 150 * 3))
    showImgGrid(
        "$topology" to
            black.drawFLines(
                topology.streamlines(spacing = 9.0, length = 14.0) +
                    topology.toFLines() +
                    topology.criticalPointsFLine(8.0),
                scale = 3.0,
            ),
        columns = 1,
        cellSide = 600,
    )
}
slider("swirl", min = 0.0, max = 3.0, step = 0.05, initialValue = 1.0) { show(it) }
show(1.0)
