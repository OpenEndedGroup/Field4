import afield.paint.*

run {
if (!PaintCanvas.isAvailable) {
    markdown(PaintLibrary.describe())
    return@run
}
// The same strokes in a 6B pencil, on a 20 cm sheet, its pressure following the pen.
val canvas = PaintCanvas(900, aspect = 2.5, view = Rect(40.0, 40.0, 1040.0, 416.0), sizeMm = 200.0)
val pencil = canvas.pencil("6B")
for (s in notation.strokes) canvas.draw(s.toFLine(), pencil, pressure = s.pressuresAlong(32).ifEmpty { listOf(0.6) })
display("main", canvas.png())
}
