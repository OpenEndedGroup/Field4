// A smooth 2-D Gaussian bump -> viridis heatmap. showHeatmap min-max normalizes,
// bilinear-upsamples the coarse grid, and displays the PNG inline in one call.
val bump = Array(48) { row ->
    DoubleArray(64) { col ->
        val dx = (col - 32) / 16.0
        val dy = (row - 24) / 12.0
        exp(-(dx * dx + dy * dy))
    }
}
showHeatmap(bump, colormap = "viridis", width = 640)
