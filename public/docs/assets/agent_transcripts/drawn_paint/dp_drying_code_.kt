import afield.paint.*

run {
if (!PaintCanvas.isAvailable) {
    markdown(PaintLibrary.describe())
    return@run
}
// One yellow band, crossed in blue after longer and longer waits.
val hours = listOf(0.0, 2.0, 10.0, 33.0, 100.0)
notation {
    stroke(FLine().moveTo(60, 1080).cubicTo(400, 1070, 700, 1090, 1020, 1080), name = "slow band", pressure = { 0.85 })
    hours.indices.forEach { i ->
        val x = 150.0 + i * 190
        stroke(FLine().moveTo(x, 960).lineTo(x + 20, 1200), name = "after $i", pressure = { 0.5 + 0.5 * sin(PI * it) })
    }
}
val canvas = PaintCanvas(900, aspect = 3.2, view = Rect(40.0, 940.0, 1000.0, 312.0))
fun paint(name: String, brush: PaintBrush) = notation.named(name)!!.let {
    canvas.stroke(it.toFLine(), brush, pressure = 1.0..1.0, swell = it.pressuresAlong(32), shake = 0.4)
}
paint("slow band", canvas.brush(BrushKind.HogFlat, width = 80.0).load(mapOf("cadmium yellow" to 1.0)))
var waited = 0.0
hours.forEachIndexed { i, h ->
    canvas.wait((h - waited) * 60); waited = h      // simulated minutes
    paint("after $i", canvas.brush(BrushKind.HogFlat, width = 60.0).load(mapOf("ultramarine blue" to 1.0)))
}
display("main", canvas.png())
markdown("times", "crossed after " + hours.joinToString(", ") { "${it.toInt()} h" } + " (left to right)", width = 500)
}
