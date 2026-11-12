run {
    if (!PaintCanvas.isAvailable) {
        markdown(PaintLibrary.describe())
        return@run
    }

    val canvas = PaintCanvas(900, aspect = 1.4, seed = 8)
    canvas.work(FLine().rect(150, 150, 700, 420), rgb(0.12, 0.3, 0.55), handling = Handling.Broad, stiff = 0.5)
    val brush = canvas.brush(BrushKind.HogFlat, width = 90.0)
    brush.load(rgb(0.95, 0.85, 0.7), amount = 4.0, stiff = 0.5)
    canvas.stroke(FLine().moveTo(200, 360).lineTo(800, 360), brush, pressure = 1.0..1.0)
    display("before", canvas.png(wet = true, light = PaintLight.Raking))

    val wet = canvas.wetMm3
    // a palm-sized plate pressed down left of centre, peeled off toward the right
    val left = canvas.press(330.0, 360.0, radius = 150.0, pressure = 0.9, take = 0.5, peel = 0.8, ridge = 12.0)
    // and a lighter press on the right, peeled upward
    val right = canvas.press(680.0, 360.0, radius = 110.0, pressure = 0.6, dx = 0.0, dy = -1.0, take = 0.4, peel = 0.6)
    display("main", canvas.png(wet = true, light = PaintLight.Raking))
    markdown("plates lifted %.2f and %.2f mm³; wet paint %.2f mm³ before, %.2f after".format(
        left.liftedMm3, right.liftedMm3, wet, canvas.wetMm3))
}
