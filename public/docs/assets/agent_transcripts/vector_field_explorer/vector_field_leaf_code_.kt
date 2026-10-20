import afield.geometry.topology.vectorfield.*

// The gradient of the leaf print's brightness runs uphill: its sinks (blue) are bright places, its
// sources (red) dark ones, and the separatrices from its saddles (white) are ridges and valleys.
val print = imgFromFile("public/docs/assets/vision/fline-viewer.jpg", 3).crop(78, 262, 128, 236)
val dark = Img(print.width, print.height, 3, FloatArray(print.width * print.height * 3))

fun show(blur: Double) {
    val topology = VectorFieldTopology.compute(VectorFieldTopology.gradientOf(print.luma().gaussianBlur(blur)))
    showImgGrid(
        "photograph" to print.scaleBy(2.0),
        "${topology.criticalPoints.size} points" to print.overlay(dark, 0.6).drawFLines(topology.toFLines() + topology.criticalPointsFLine(7.0), scale = 2.0),
        columns = 2, cellSide = 300,
    )
}
slider("blur", min = 3.0, max = 16.0, step = 0.5, initialValue = 7.0) { show(it) }
show(7.0)
