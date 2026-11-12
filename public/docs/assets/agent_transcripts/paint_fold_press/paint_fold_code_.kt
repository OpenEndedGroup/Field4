run {
    if (!PaintCanvas.isAvailable) {
        markdown(PaintLibrary.describe())
        return@run
    }

    val canvas = PaintCanvas(900, aspect = 1.4, seed = 7)
    val brush = canvas.brush(BrushKind.HogFlat, width = 70.0)
    val blobs = listOf(
        rgb(0.75, 0.1, 0.08) to FLine().moveTo(300, 150).cubicTo(380, 200, 440, 260, 500, 290),
        rgb(0.1, 0.2, 0.6) to FLine().moveTo(230, 360).cubicTo(330, 320, 420, 400, 490, 430),
        rgb(0.95, 0.75, 0.15) to FLine().moveTo(340, 560).lineTo(500, 520),
    )
    for ((color, line) in blobs) {
        brush.load(color, amount = 4.0, stiff = 0.5)
        canvas.stroke(line, brush, pressure = 1.0..1.0)
    }
    display("before", canvas.png(wet = true, light = PaintLight.Raking))

    val wet = canvas.wetMm3
    // fold down the middle (the line x = 500, running down), press, open it again
    val fold = canvas.fold(500.0, 0.0, pressure = 0.9, spread = 0.5, peel = 0.8, ridge = 18.0)
    display("main", canvas.png(wet = true, light = PaintLight.Raking))
    markdown("fold moved %.1f mm³; wet paint %.2f mm³ before, %.2f after".format(fold.movedMm3, wet, canvas.wetMm3))
}
