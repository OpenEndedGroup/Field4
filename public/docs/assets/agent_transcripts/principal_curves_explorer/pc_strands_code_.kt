import afield.geometry.curves.principal.*

// Three noisy strands of different thickness, 1000 points each.
val random = java.util.Random(2)
val points = DoubleArray(3000 * 2)
for (i in 0 until 3000) {
    val t = random.nextDouble()
    val (x, y, spread) = when (i % 3) {
        0 -> Triple(30 + 360 * t, 50 + 25 * sin(7 * t), 2.0)
        1 -> Triple(40 + 300 * t, 130 + 40 * t * t, 7.0)
        else -> Triple(330 + 50 * cos(3 * t), 200 + 50 * sin(3 * t), 3.5)
    }
    points[2 * i] = x + spread * random.nextGaussian()
    points[2 * i + 1] = y + spread * random.nextGaussian()
}
val canvas = Img(420, 270, 3, FloatArray(420 * 270 * 3) { 0.06f })

fun show(mu: Double) {
    val forest = PrincipalCurves.fit(points, curves = 3, nodes = 45, parameters = ElasticParameters(mu = mu))
    showImgGrid("mu = $mu" to canvas.drawFLines([forest.pointsFLine(2.0)] + forest.widthFLines() + forest.toFLines(), scale = 2.0),
        channel = "fit", columns = 1, cellSide = 520)
    forest.close()
}

slider("bending (mu)", min = 0.0, max = 2.0, step = 0.05, initialValue = 0.1) { show(it) }
show(0.1)
