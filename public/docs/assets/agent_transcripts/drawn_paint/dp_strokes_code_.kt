// Three pen strokes, drawn from code into the document's drawing, each with a pen pressure
// (0..1) as a function of how far along the stroke the pen is. Running it again replaces them.
// To paint strokes you drew yourself (Alt+D, P, with a tablet pen for pressure), delete this block.
notation {
    stroke(FLine().moveTo(80, 300).cubicTo(200, 60, 330, 420, 420, 140), name = "S",
        pressure = { 0.15 + 0.85 * sin(PI * it) })
    stroke(FLine().moveTo(470, 110).cubicTo(560, 300, 700, 330, 820, 180), name = "sweep",
        pressure = { 1.0 - 0.85 * it })
    stroke(FLine().moveTo(880, 330).cubicTo(1060, 300, 1020, 60, 900, 120).cubicTo(820, 160, 880, 260, 960, 250),
        name = "loop", pressure = { 0.15 + 0.85 * it * it })
}
// Read them back: the pressure at 50 points evenly spaced along each.
val strokes = notation.strokes.filter { it.pressures.isNotEmpty() }
if (strokes.isNotEmpty()) showSignals(*strokes.map { (it.label ?: it.id) to it.pressuresAlong(50) }.toTypedArray(),
    x = (0 until 50).map { it / 49.0 }, title = "pen pressure along each stroke",
    xTitle = "fraction of the way along", yTitle = "pressure", channel = "pressure")
println(notation.strokes.map { "${it.label ?: it.id}: ${it.points.size} points, ${it.pressures.size} pressures" })
