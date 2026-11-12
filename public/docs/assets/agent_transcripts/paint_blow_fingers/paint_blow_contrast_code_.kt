run {
    if (!PaintCanvas.isAvailable) {
        markdown(PaintLibrary.describe())
        return@run
    }

    val canvas = PaintCanvas(900, aspect = 2.4, seed = 12)
    val brush = canvas.brush(BrushKind.HogFlat, width = 130.0)
    brush.load(rgb(0.1, 0.18, 0.55), amount = 5.0, stiff = 0.3)
    for (y in listOf(140, 260)) canvas.stroke(FLine().moveTo(60, y).lineTo(940, y), brush, pressure = 1.0..1.0)
    val report = StringBuilder()
    for ((x, contrast) in listOf(170.0 to 1.5, 500.0 to 8.0, 830.0 to 200.0)) {
        val b = canvas.blow(FLine().moveTo(x, 200).lineTo(x + 2, 200), radius = 16.0, pressure = 1.0, contrast = contrast, tendril = 4.0)
        report.append("contrast %.1f: branching %.2f  \n".format(contrast, b.branching))
    }
    display("main", canvas.png(wet = true, light = PaintLight.Raking))
    markdown(report.toString())
}
