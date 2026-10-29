import afield.geometry.vectorize.polyvector.*

// A photograph's edges, vectorized off the box's thread: an edge map holds far more ink than a
// drawing, and this takes about a minute.
val print = imgFromFile("public/docs/assets/vision/fline-viewer.jpg", 3).crop(78, 262, 128, 236).scaleBy(2.0)
val edges = print.luma().gaussianBlur(1.2).sobel().magnitude()
val strongest = edges.data.sortedDescending()[edges.data.size / 50]
val evidence =
    Img(edges.width, edges.height, 1, FloatArray(edges.data.size) { (edges.data[it] / strongest).coerceIn(0f, 1f) })
val drawing = Img(evidence.width, evidence.height, 3, FloatArray(evidence.data.size * 3) { 1f - evidence.data[it / 3] })

markdown("Vectorizing the edges of the leaf print...")
runAsync(
    onResult = { result: PolyvectorDrawing ->
        val dark = Img(print.width, print.height, 3, FloatArray(print.width * print.height * 3))
        markdown("${result.curves.size} curves, coverage ${result.coverage().f2}")
        showImgGrid(
            "curves" to print.overlay(dark, 0.55).drawFLines(result.toFLines(thickness = 1.5)),
            columns = 1,
            cellSide = 360,
        )
    },
) {
    Polyvector.vectorizeAsync(drawing)
}
