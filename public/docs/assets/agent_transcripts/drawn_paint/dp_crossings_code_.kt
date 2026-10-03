import afield.paint.*

run {
if (!PaintCanvas.isAvailable) {
    markdown(PaintLibrary.describe())
    return@run
}
// Four pairs of bands, yellow over red, each crossed by one vertical stroke.
notation {
    for (k in 0 until 4) {
        val x = 60.0 + k * 250
        stroke(FLine().moveTo(x, 560).cubicTo(x + 70, 545, x + 140, 575, x + 210, 560), name = "yellow $k", pressure = { 0.8 })
        stroke(FLine().moveTo(x, 760).cubicTo(x + 70, 745, x + 140, 775, x + 210, 760), name = "red $k", pressure = { 0.8 })
        stroke(FLine().moveTo(x + 95, 480).cubicTo(x + 120, 600, x + 90, 720, x + 115, 860), name = "across $k",
            pressure = { 0.5 + 0.5 * sin(PI * it) })
    }
}
val canvas = PaintCanvas(900, aspect = 2.4, view = Rect(40.0, 460.0, 1000.0, 416.0))
fun paint(name: String, brush: PaintBrush) = notation.named(name)!!.let {
    canvas.stroke(it.toFLine(), brush, pressure = 1.0..1.0, swell = it.pressuresAlong(32), shake = 0.5)
}
val band = canvas.brush(BrushKind.HogFlat, width = 70.0)
for (k in 0 until 4) {
    paint("yellow $k", band.load(mapOf("cadmium yellow" to 1.0), wipe = 1.0))
    paint("red $k", band.load(mapOf("vermilion" to 1.0), wipe = 1.0))
}
// 1: blue straight across the wet bands, so the colours mix where they meet
paint("across 0", canvas.brush(BrushKind.HogFlat, width = 60.0).load(mapOf("ultramarine blue" to 1.0)))
// 3: a clean, dry hog-hair brush dragged through them: no paint of its own, it smears theirs
paint("across 2", canvas.brush(BrushKind.HogFlat, width = 60.0))
// 4: a clean badger blender, which only softens what is there
paint("across 3", canvas.brush(BrushKind.Badger, width = 60.0))
// 2: the same blue, after everything has dried
canvas.dry()
paint("across 1", canvas.brush(BrushKind.HogFlat, width = 60.0).load(mapOf("ultramarine blue" to 1.0)))
display("main", canvas.png())
}
