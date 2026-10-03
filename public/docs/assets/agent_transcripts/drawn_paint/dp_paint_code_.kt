import afield.paint.*

run {
if (!PaintCanvas.isAvailable) {
    markdown(PaintLibrary.describe())
    return@run
}
// Every stroke in the drawing, painted with a brush that presses as hard as the pen did.
// `view` is the rectangle of drawing coordinates that becomes the canvas.
val colours = listOf(rgb(0.12, 0.2, 0.55), rgb(0.85, 0.3, 0.1), rgb(0.15, 0.5, 0.25))
val canvas = PaintCanvas(900, aspect = 1.6, view = Rect(40.0, 40.0, 1040.0, 650.0))
val brush = canvas.brush(BrushKind.Round, width = 40.0)
notation.strokes.forEachIndexed { i, s ->
    brush.load(colours[i % colours.size], wipe = 1.0)
    val swell = s.pressuresAlong(64)   // empty for a mouse stroke: then the base pressure applies
    canvas.stroke(s.toFLine(), brush, pressure = if (swell.isEmpty()) 0.6..0.6 else 1.0..1.0, swell = swell)
}
// The same strokes 320 units lower, at one even pressure, to compare.
notation.strokes.forEachIndexed { i, s ->
    val lower = FLine()
    s.points.forEachIndexed { k, p -> if (k == 0) lower.moveTo(p.x, p.y + 320) else lower.lineTo(p.x, p.y + 320) }
    brush.load(colours[i % colours.size], wipe = 1.0)
    canvas.stroke(lower, brush, pressure = 0.6..0.6)
}
display("main", canvas.png())
}
