import afield.geometry.curves.principal.*

// Every pixel of the leaf print greener than it is red or blue, weighted by how much.
val print = imgFromFile("public/docs/assets/vision/fline-viewer.jpg", 3).crop(78, 262, 128, 236).scaleBy(2.0)
val points = ArrayList<Double>()
val weights = ArrayList<Double>()
for (y in 0 until print.height) for (x in 0 until print.width) {
    val green = print.at(x, y, 1) - maxOf(print.at(x, y, 0), print.at(x, y, 2))
    if (green > 0.04) {
        points.add(x.toDouble())
        points.add(y.toDouble())
        weights.add(green.toDouble())
    }
}
val forest =
    PrincipalCurves.fit(points.toDoubleArray(), weights = weights.toDoubleArray(), nodes = 40, allowBranching = true)
val dark = Img(print.width, print.height, 3, FloatArray(print.width * print.height * 3))
showImgGrid(
    "as a tree" to print.overlay(dark, 0.6).drawFLines(forest.widthFLines(1.0) + forest.toFLines(thickness = 2.5)),
    columns = 1,
    cellSide = 320,
)
