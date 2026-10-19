import afield.geometry.topology.mapper.*

// A ring with a tail, and a separate blob, 1500 points in all.
val random = java.util.Random(4)
val points = DoubleArray(1500 * 2)
for (i in 0 until 1500) {
    val (x, y) = when {
        i < 800 -> { val t = random.nextDouble() * 2 * PI; 80 + 45 * cos(t) to 85 + 45 * sin(t) }
        i < 1150 -> { val s = random.nextDouble(); 112 + 70 * s to 117 + 40 * s }
        else -> 160 + 12 * random.nextGaussian() to 35 + 12 * random.nextGaussian()
    }
    points[2 * i] = x + 2.5 * random.nextGaussian()
    points[2 * i + 1] = y + 2.5 * random.nextGaussian()
}
val lenses = mapOf(
    "x" to Mapper.coordinate(points, axis = 0),
    "eccentricity" to Mapper.eccentricity(points),
    "density" to Mapper.density(points, k = 10),
)
val black = Img(200, 170, 3, FloatArray(200 * 170 * 3))
var lens = "eccentricity"; var intervals = 12; var overlap = 0.35

fun show() {
    val graph = Mapper.compute(points, lens = lenses.getValue(lens), intervals = intervals, overlap = overlap)
    showImgGrid("$lens: ${graph.components().size} pieces, ${graph.loops} loop(s)" to
        black.drawFLines([graph.pointsFLine(pointSize = 1.5)] + graph.toFLines(), scale = 3.0), columns = 1, cellSide = 600)
}
slider("intervals", min = 2.0, max = 30.0, step = 1.0, initialValue = 12.0) { intervals = it.toInt(); show() }
slider("overlap", min = 0.05, max = 0.8, step = 0.05, initialValue = 0.35) { overlap = it; show() }
slider("lens: x, eccentricity, density", min = 0.0, max = 2.0, step = 1.0, initialValue = 1.0) { lens = lenses.keys.toList()[it.toInt()]; show() }
show()
