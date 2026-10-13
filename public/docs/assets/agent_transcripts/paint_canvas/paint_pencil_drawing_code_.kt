import afield.graphics.paint.*

run {
if (!PaintCanvas.isAvailable) {
    markdown(PaintLibrary.describe())
    return@run
}

// a 20 cm sheet, so a 0.3 mm pencil point reads at this pixel width
val canvas = PaintCanvas(900, aspect = 1.5, sizeMm = 200.0)

val vase = FLine().moveTo(420, 120).cubicTo(330, 220, 300, 420, 380, 560)
    .lineTo(620, 560).cubicTo(700, 420, 670, 220, 580, 120)
canvas.sketch(vase, canvas.pencil("2B"), pressure = 0.5, passes = 3)

// hatch the shadow side with a softer pencil
val shadow = FLine().moveTo(560, 140).cubicTo(650, 240, 680, 420, 610, 550).lineTo(540, 550)
    .cubicTo(600, 420, 590, 240, 520, 140).lineTo(560, 140)
canvas.hatch(shadow, canvas.pencil("4B"), angle = 1.0, spacing = 7.0, length = 50.0)

// the table edge, ruled in chalk
canvas.draw(FLine().moveTo(80, 560).lineTo(920, 560), canvas.chalk(), pressure = 0.7, ruler = true)

display("main", canvas.png(relief = 0.0))
}
