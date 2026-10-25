run {
    if (!PaintCanvas.isAvailable) {
        markdown(PaintLibrary.describe())
        return@run
    }
    // Every stroke in the region `strokes` drew in, painted three times, one row each: the brush
    // pressing as the pen did; the pen's pressure squared, so light strokes go lighter; one even pressure.
    val region = Rect(40.0, 40.0, 1040.0, 400.0)
    val strokes = notation.strokes.filter { it.bounds.intersects(region) }
    val colours = [
        mapOf("ultramarine blue" to 2.0, "bone black" to 0.3),
        mapOf("vermilion" to 1.0),
        mapOf("viridian" to 1.0, "chrome yellow" to 0.6),
    ]
    val canvas = PaintCanvas(900, aspect = 0.9, view = Rect(40.0, 40.0, 1000.0, 1110.0))
    val brush = canvas.brush(BrushKind.Round, width = 70.0)
    fun lower(s: Mark, dy: Double) = s.toFLine() + vec(0.0, dy)
    strokes.forEachIndexed { i, s ->
        val pen = s.pressuresAlong(64)
        brush.load(colours[i % 3], wipe = 1.0)
        canvas.stroke(s.toFLine(), brush, pressure = 1.0..1.0, swell = pen)
        brush.load(colours[i % 3], wipe = 1.0)
        canvas.stroke(lower(s, 360.0), brush, pressure = 1.0..1.0, swell = pen.map { it * it })
        brush.load(colours[i % 3], wipe = 1.0)
        canvas.stroke(lower(s, 720.0), brush, pressure = 0.6..0.6)
    }
    display("main", canvas.png())
}
