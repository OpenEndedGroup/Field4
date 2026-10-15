import afield.graphics.paint.*

run {
if (!PaintCanvas.isAvailable) {
    markdown(PaintLibrary.describe())
    return@run
}
// The strokes from `strokes` in a 6B pencil, on a 20 cm sheet, its pressure following the pen.
val region = Rect(40.0, 40.0, 1040.0, 400.0)
val canvas = PaintCanvas(900, aspect = 3.0, view = Rect(40.0, 40.0, 1040.0, 347.0), sizeMm = 200.0)
val pencil = canvas.pencil("6B")
for (s in notation.strokes.filter { it.bounds.intersects(region) })
    canvas.draw(s.toFLine(), pencil, pressure = s.pressuresAlong(32).ifEmpty { listOf(0.6) })
display("main", canvas.png())
}
