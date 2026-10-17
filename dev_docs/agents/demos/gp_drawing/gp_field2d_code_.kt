// A scalar field pinned at four points: the mean, two whole-field draws on one colour scale,
// and the standard deviation, dark at the observations. Change the values or the seeds and run again.
val points = listOf(vec(-0.8, -0.5), vec(0.55, -0.5), vec(-0.3, 0.65), vec(0.65, 0.55))
val field = gp2D(points, doubleArrayOf(0.9, -0.7, -0.8, 0.65),
    kernel = GpKernels.matern(1.5, doubleArrayOf(0.55, 0.8)), noiseStd = 0.04)
val side = 40
val queries = Array(side * side) { i ->
    doubleArrayOf(-1.2 + 2.4 * (i % side) / (side - 1),
                  -1.2 + 2.4 * (i / side) / (side - 1))
}
val joint = field.posterior(queries)
fun grid(values: DoubleArray) = Array(side) { row -> DoubleArray(side) { col -> values[row * side + col] } }

// panels side by side, separated by a thin gap
fun row(vararg panels: Array<DoubleArray>, gap: Double) = Array(side) { r ->
    panels.flatMapIndexed { i, p -> (if (i > 0) List(2) { gap } else emptyList()) + p[r].toList() }.toDoubleArray()
}

// the mean and two whole-field draws share one colour scale; then the standard deviation
showHeatmap(row(grid(joint.mean), grid(joint.sample(seed = 57)), grid(joint.sample(seed = 58)), gap = 0.0),
    vmin = -2.5, vmax = 2.5, normalize = false, colormap = "coolwarm", width = 720, channel = "mean, two draws")
showHeatmap(grid(joint.variance.map { sqrt(it) }.toDoubleArray()),
    vmin = 0.0, normalize = false, colormap = "magma", width = 240, channel = "standard deviation")
