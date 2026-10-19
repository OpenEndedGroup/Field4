import afield.geometry.topology.mapper.*

// Points scattered along three letters, sliced horizontally by the lens. A and O have a loop; H has none.
val strokes = mapOf(
    "A" to [[50.0, 10.0, 15.0, 130.0], [50.0, 10.0, 85.0, 130.0], [30.0, 80.0, 70.0, 80.0]],
    "H" to [[20.0, 10.0, 20.0, 130.0], [80.0, 10.0, 80.0, 130.0], [20.0, 70.0, 80.0, 70.0]],
)
val random = java.util.Random(2)
fun letter(name: String): DoubleArray {
    val out = DoubleArray(900 * 2)
    for (i in 0 until 900) {
        val s = random.nextDouble()
        val (x, y) = if (name == "O") { val t = s * 2 * PI; 50 + 38 * cos(t) to 70 + 58 * sin(t) }
            else strokes.getValue(name)[i % 3].let { (x0, y0, x1, y1) -> x0 + s * (x1 - x0) to y0 + s * (y1 - y0) }
        out[2 * i] = x + 2.0 * random.nextGaussian(); out[2 * i + 1] = y + 2.0 * random.nextGaussian()
    }
    return out
}

val black = Img(100, 140, 3, FloatArray(100 * 140 * 3))
showImgGrid(*["A", "H", "O"].map { name ->
    val points = letter(name)
    val graph = Mapper.compute(points, lens = Mapper.coordinate(points, axis = 1), intervals = 10, overlap = 0.35)
    "$name: ${graph.loops} loop(s)" to black.drawFLines([graph.pointsFLine(pointSize = 1.5)] + graph.toFLines(minSize = 3.0, maxSize = 9.0), scale = 2.0)
}.toTypedArray(), columns = 3, cellSide = 220)
