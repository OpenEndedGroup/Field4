// Four measurements and a smoothness assumption: five draws (blue), the mean (orange) and the
// 95% pointwise interval (thin orange). Move the sliders: a longer length scale makes stiffer
// functions; a noisier third measurement lets the draws pass further from it.
val xs = doubleArrayOf(-1.2, -0.35, 0.1, 0.95)
val ys = doubleArrayOf(-0.7, 0.8, 0.35, -0.4)
var lengthScale = 0.48
var thirdNoise = 0.22

fun plot(line: FLine) = line.transform { vec(it.x * 180, -it.y * 120, 0) }

fun redraw() {
    val gp = gp1D(xs, ys,
        noiseStd = doubleArrayOf(0.06, 0.06, thirdNoise, 0.06),
        kernel = GpKernels.rbf(lengthScale = lengthScale))
    val grid = DoubleArray(181) { -1.8 + 3.6 * it / 180 }
    val posterior = gp.posterior(grid)
    val draws = (0L..4L).map { seed ->
        plot(posterior.sampleFLine(grid, seed)).apply { color = vec(0.3, 0.65, 0.9, 0.7); thickness = 1.5 }
    }
    val (lower, upper) = posterior.intervalFLines(grid)
    val band = listOf(lower, upper).map { plot(it).apply { color = vec(1, 0.5, 0.2, 0.5); thickness = 1.0 } }
    val mean = plot(posterior.meanFLine(grid)).apply { color = vec(1, 0.5, 0.2, 1); thickness = 3.0 }
    val pins = plot(FLine().apply { xs.indices.forEach { moveTo(xs[it], ys[it]) } }).apply {
        stroked = false; pointed = true; pointSize = 3.0; color = vec(1, 1, 1, 1)
    }
    val frame = plot(FLine().moveTo(-1.8, -2.5).lineTo(1.8, -2.5).lineTo(1.8, 2.5).lineTo(-1.8, 2.5).close())
        .apply { color = vec(0.3, 0.3, 0.3, 1); thickness = 1.0 }
    lines(listOf(frame) + draws + band + mean + pins, w = 700.0, h = 420.0, fitToSize = true)
}

// running the box puts the sliders back at the values above
controlGroup("controls") {
    slider("length scale", min = 0.1, max = 1.5, initialValue = lengthScale) { lengthScale = it; redraw() }
        .set(lengthScale)
    slider("noise of the third measurement", min = 0.01, max = 0.8, initialValue = thirdNoise) { thirdNoise = it; redraw() }
        .set(thirdNoise)
}
redraw()
