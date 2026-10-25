import kotlin.random.Random

run {
    if (!PaintCanvas.isAvailable) {
        markdown(PaintLibrary.describe())
        return@run
    }

    // Change these, then rerun: the fixed seed makes comparisons repeatable.
    val strokeCount = 260
    val stepsPerStroke = 30
    val stepLength = 6.0
    val horizontalFrequency = 0.006
    val verticalFrequency = 0.008
    val diagonalFrequency = 0.002

    val canvas = PaintCanvas(900, aspect = 1.4, seed = 4)
    val brush = canvas.brush(BrushKind.Filbert, width = 16.0)
    val rnd = Random(4)

    // the direction of the field at (x, y)
    fun angle(x: Double, y: Double) =
        sin(x * horizontalFrequency) * 2.0 + cos(y * verticalFrequency + x * diagonalFrequency) * 1.5

    repeat(strokeCount) {
        var x = rnd.nextDouble(0.0, 1000.0)
        var y = rnd.nextDouble(0.0, canvas.heightUnits)
        val line = FLine().moveTo(x, y)
        repeat(stepsPerStroke) {
            val a = angle(x, y)
            x += cos(a) * stepLength
            y += sin(a) * stepLength
            line.lineTo(x, y)
        }
        val t = y / canvas.heightUnits
        // wipe = 0.4 keeps some of the last colour in the brush, so neighbouring strokes blend
        brush.load(rgb(0.9 - 0.6 * t, 0.4 + 0.3 * sin(x * 0.01), 0.2 + 0.6 * t), stiff = 0.9, wipe = 0.4)
        canvas.stroke(line, brush, pressure = 0.5..0.9)
    }
    display("main", canvas.png(relief = 0.8))
}
