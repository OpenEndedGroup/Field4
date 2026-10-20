import afield.geometry.topology.morsesmale.*

// The leaf print from the living-room photograph. Build the complex once; the slider
// only re-reads it at a new threshold.
val print = imgFromFile("public/docs/assets/vision/fline-viewer.jpg", 3).crop(78, 262, 128, 236).scaleBy(2.0)
val complex = MorseSmale.build(print.luma().gaussianBlur(3.0))

fun show(p: Double) {
    val r = complex.simplify(p)
    showImgGrid(
        "${r.criticalPoints.size} points, ${r.separatrixCount} curves" to
            print.drawFLines(
                r.toFLines(traceDescending = false, thickness = 2.0) + r.criticalPointsFLine(5.0),
                scale = 2.0,
            ),
        channel = "ridges",
        columns = 1,
        cellSide = 520,
    )
}

slider("persistence", min = 0.0, max = 0.3, step = 0.005, initialValue = 0.05) { show(it) }
show(0.05)
