run {
    if (!PaintCanvas.isAvailable) {
        markdown(PaintLibrary.describe())
        return@run
    }

    val canvas = PaintCanvas(900, aspect = 1.4, seed = 11)
    val brush = canvas.brush(BrushKind.HogFlat, width = 120.0)
    brush.load(rgb(0.1, 0.18, 0.55), amount = 5.0, stiff = 0.3)
    for (y in listOf(220, 330, 440, 550)) canvas.stroke(
        FLine().moveTo(120, y).lineTo(880, y),
        brush,
        pressure = 1.0..1.0,
    )
    display("before", canvas.png(wet = true, light = PaintLight.Raking))

    val wet = canvas.wetMm3
    // a straw, blown along a curve through the film
    val path = FLine().moveTo(150, 480).cubicTo(350, 250, 600, 600, 850, 300)
    val blown = canvas.blow(path, radius = 14.0, pressure = 0.9, tendril = 9.0)
    display("main", canvas.png(wet = true, light = PaintLight.Raking))
    markdown(
        "blow moved %.1f mm³ at %d places, branching %.2f; wet paint %.2f mm³ before, %.2f after"
            .format(blown.movedMm3, blown.stations, blown.branching, wet, canvas.wetMm3),
    )
}
