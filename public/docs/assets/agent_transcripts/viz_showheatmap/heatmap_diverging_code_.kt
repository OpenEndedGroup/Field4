// A sin*sin field in [-1,1]. A fixed vmin/vmax with normalize=false anchors the coolwarm
// white midpoint at zero, so blue = negative, red = positive.
val field = Array(48) { row ->
    DoubleArray(64) { col -> sin(col * 0.18) * sin(row * 0.22) }
}
showHeatmap(field, colormap = "coolwarm", width = 640, vmin = -1.0, vmax = 1.0, normalize = false)
