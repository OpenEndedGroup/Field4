// After John Cage's Fontana Mix (1958): six curves, a grid, a point, and a straight line.
// Cage's performer laid a line from a point inside the grid to one outside it and measured
// where it crossed the curves. Here the line is yours: draw one across the sheet (Alt+D, then L)
// and this box reads it. Run the box first; it draws the sheet.
val colors = listOf("#e03131", "#1971c2", "#2f9e44", "#f08c00", "#9c36b5", "#0c8599")
notation("sheet") {
    frame("fontana mix", 1200, 100, 900, 520)
    for (i in 0 until 6) {
        val curve = (0..48).map { k -> vec(1230 + k * 17.5, 170 + i * 75 + 55 * sin(k / 5.0 + i * 1.9)) }
        stroke(curve, name = "curve ${i + 1}", stroke = colors[i])
    }
    rect(1530, 250, 240, 200, name = "grid", stroke = "#868e96")
    ellipse(1605, 320, 12, 12, name = "point", fill = "#868e96")
}

fun read(line: Mark) {
    val crossings = notation.intersect(line.start!!, line.end!!).filter { it.mark.name?.startsWith("curve") == true }
    println("${crossings.size} crossings along the line:")
    crossings.forEach { println("  ${it.mark.name}  %.2f of the way along".format(it.value)) }
}

notation.lines.lastOrNull { it.handDrawn }?.let(::read)
notation.onChange("fontana") { edit -> (edit.added + edit.updated).lastOrNull { it.kind == MarkType.LINE }?.let(::read) }
