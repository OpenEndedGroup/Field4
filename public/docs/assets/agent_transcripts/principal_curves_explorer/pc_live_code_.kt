import afield.geometry.curves.principal.*

/*
Launch this one (Alt+Up). 2000 points scatter around a wave that travels and bends; one
forest follows them, refined five iterations a frame. The graph is never regrown, so its
nodes keep their identities from frame to frame.
*/
val random = java.util.Random(3)
val offsets = DoubleArray(2000) { random.nextGaussian() * 4 }
val along = DoubleArray(2000) { random.nextDouble() }
fun points(time: Double) =
    DoubleArray(4000).also { p ->
        for (i in 0 until 2000) {
            val t = along[i]
            p[2 * i] = 20 + 360 * t
            p[2 * i + 1] = 110 + 50 * sin(5 * t + time) * cos(time * 0.3) + offsets[i] * (1 + t)
        }
    }
val forest = PrincipalCurves.fit(points(0.0), nodes = 24)
val canvas = Img(400, 220, 3, FloatArray(400 * 220 * 3) { 0.06f })

loop {
    forest.setPoints(points(frameNumber / 30.0))
    forest.refine(5)
    showImgGrid(
        "frame $frameNumber" to
            canvas.drawFLines([forest.pointsFLine(1.5)] + forest.widthFLines() + forest.toFLines(), scale = 2.0),
        columns = 1,
        cellSide = 600,
    )
}
