import afield.paint.*
import kotlin.random.Random

run {
if (!PaintCanvas.isAvailable) {
    markdown(PaintLibrary.describe())
    return@run
}

val canvas = PaintCanvas(900, aspect = 1.4, seed = 4)
val brush = canvas.brush(BrushKind.Filbert, width = 16.0)
val rnd = Random(4)

// the direction of the field at (x, y)
fun angle(x: Double, y: Double) = sin(x * 0.006) * 2.0 + cos(y * 0.008 + x * 0.002) * 1.5

repeat(260) {
    var x = rnd.nextDouble(0.0, 1000.0)
    var y = rnd.nextDouble(0.0, canvas.heightUnits)
    val line = FLine().moveTo(x, y)
    repeat(30) {
        val a = angle(x, y)
        x += cos(a) * 6
        y += sin(a) * 6
        line.lineTo(x, y)
    }
    val t = y / canvas.heightUnits
    // wipe = 0.4 keeps some of the last colour in the brush, so neighbouring strokes blend
    brush.load(rgb(0.9 - 0.6 * t, 0.4 + 0.3 * sin(x * 0.01), 0.2 + 0.6 * t), stiff = 0.9, wipe = 0.4)
    canvas.stroke(line, brush, pressure = 0.5..0.9)
}
display("main", canvas.png(relief = 0.8))
}
