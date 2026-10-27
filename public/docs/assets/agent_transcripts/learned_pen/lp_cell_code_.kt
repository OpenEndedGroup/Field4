import afield.ai.learning.pen.*

// The same pen, the same seed, written three times. The middle row holds one memory cell at
// +4 the whole way, the bottom row at -4. Which cell does what is something to find out:
// try other cells (0 until 500) and values.
val cell = 136
fun row(value: Double?, y: Double): FLine {
    val writer = PenLine(start = vec(0.0, y, 0.0), seed = 11)
    repeat(400) {
        if (value != null) (writer.pen as Pen).setCell(cell, value)
        writer.grow()
    }
    return writer.line
}
lines(row(null, 0.0), row(4.0, 140.0).apply { color = vec(0.5, 1.0, 0.6, 1.0) },
    row(-4.0, 280.0).apply { color = vec(1.0, 0.55, 0.6, 1.0) }, w = 520.0, h = 340.0, fitToSize = true)
