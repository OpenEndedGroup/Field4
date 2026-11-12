run {
    if (!PaintCanvas.isAvailable) {
        markdown(PaintLibrary.describe())
        return@run
    }

    val canvas = PaintCanvas(900, aspect = 1.4, seed = 9)
    val brush = canvas.brush(BrushKind.HogFlat, width = 60.0)
    for ((i, color) in listOf(rgb(0.8, 0.15, 0.1), rgb(0.95, 0.75, 0.15), rgb(0.1, 0.25, 0.6)).withIndex()) {
        brush.load(color, amount = 3.0, stiff = 0.4)
        canvas.stroke(FLine().moveTo(180 + i * 70, 150).lineTo(180 + i * 70, 560), brush, pressure = 1.0..1.0)
    }
    display("before", canvas.png(wet = true, light = PaintLight.Raking))

    val wet = canvas.wetMm3
    // three rubs across the stripes: light, firm, and a curve
    val light = canvas.rub(FLine().moveTo(120, 220).lineTo(820, 230), radius = 30.0, pressure = 0.4)
    val firm = canvas.rub(FLine().moveTo(120, 360).lineTo(820, 350), radius = 40.0, pressure = 0.9)
    val curve =
        canvas.rub(FLine().moveTo(120, 500).cubicTo(400, 420, 600, 600, 860, 480), radius = 35.0, pressure = 0.7)
    display("main", canvas.png(wet = true, light = PaintLight.Raking))
    markdown(
        "rubs moved %.1f mm³; wet paint %.2f mm³ before, %.2f after"
            .format(
                light.movedMm3 + firm.movedMm3 + curve.movedMm3,
                wet,
                canvas.wetMm3,
            ),
    )
}
