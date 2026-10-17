import afield.graphics.paint.*

run {
if (!PaintCanvas.isAvailable) {
    markdown(PaintLibrary.describe())
    return@run
}

val canvas = PaintCanvas(900, aspect = 1.6)
val brush = canvas.brush(BrushKind.Filbert, width = 45.0)

brush.load(rgb(0.15, 0.3, 0.65))
canvas.stroke(FLine().moveTo(80, 140).cubicTo(330, 40, 630, 240, 920, 120), brush)

brush.load(rgb(0.95, 0.7, 0.15))
canvas.stroke(FLine().moveTo(80, 330).cubicTo(330, 230, 630, 430, 920, 310), brush)

// no reload: three more passes, and the brush runs out along the way
val zigzag = FLine().moveTo(80, 470)
for (i in 1..3) zigzag.lineTo(if (i % 2 == 1) 920 else 80, 470 + i * 40)
canvas.stroke(zigzag, brush)

display("main", canvas.png())
}
