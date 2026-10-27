import afield.ai.learning.pen.*

// The pen writes 500 points once, remembering every cell's output at every point. Drag the
// slider to colour the line by a cell: blue where it is low, orange where it is high. The
// "liveliest" line lists the cells that changed most, the ones worth trying first.
val start = fline { moveTo(0, 0); cubicTo(4, -14, 12, -14, 12, 0); cubicTo(12, 8, 20, 8, 24, 0) }
val writer = PenLine(start, seed = 4, recordMemory = true)
writer.grow(500)
val lively = writer.liveliestCells(12)
markdown("liveliest", "liveliest cells: ${lively.joinToString(", ")}")
var cell = lively[0]
var shown = -1
slider("cell", min = 0.0, max = 499.0, step = 1.0, initialValue = cell.toDouble()) { cell = it.toInt() }
frames {
    while (frame()) {
        if (cell == shown) continue
        shown = cell
        lines(writer.coloredByCell(cell, thickness = 2.0), channel = "cell", w = 520.0, h = 200.0, fitToSize = true)
    }
}
