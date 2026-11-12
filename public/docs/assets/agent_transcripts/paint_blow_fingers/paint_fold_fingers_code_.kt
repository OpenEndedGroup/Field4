run {
    if (!PaintCanvas.isAvailable) {
        markdown(PaintLibrary.describe())
        return@run
    }

    val canvas = PaintCanvas(900, aspect = 1.4, seed = 13)
    val brush = canvas.brush(BrushKind.HogFlat, width = 140.0)
    brush.load(rgb(0.55, 0.08, 0.1), amount = 5.0, stiff = 0.3)
    for (y in listOf(200, 320, 440, 560)) canvas.stroke(FLine().moveTo(150, y).lineTo(500, y), brush, pressure = 1.0..1.0)
    display("before", canvas.png(wet = true, light = PaintLight.Raking))
    val wet = canvas.wetMm3
    val fold = canvas.fold(500.0, 0.0, pressure = 0.95, spread = 0.4, peel = 0.9, ridge = 14.0)
    display("main", canvas.png(wet = true, light = PaintLight.Raking))
    markdown("fold moved %.1f mm³; wet paint %.2f mm³ before, %.2f after".format(fold.movedMm3, wet, canvas.wetMm3))
}
