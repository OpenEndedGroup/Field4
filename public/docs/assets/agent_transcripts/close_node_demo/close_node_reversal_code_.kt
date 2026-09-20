// A square that relies on close() for its fourth side, and a two-contour outline whose
// counter also relies on close(). Write those closing sides as an explicit lineTo back to
// the start and the reversal below comes out right — the defect only shows on a shape
// where the Close is carrying a segment nothing else draws.
val square = fline {
    moveTo(0, 0); lineTo(200, 0); lineTo(200, 200); lineTo(0, 200); close()
}

val glyph = fline {
    moveTo(0, 0)
    cubicTo(vec(-40, 150, 0), vec(60, 260, 0), vec(160, 250, 0))
    cubicTo(vec(260, 240, 0), vec(300, 120, 0), vec(250, 20, 0))
    close()
    moveTo(80, 70)
    cubicTo(vec(60, 140, 0), vec(110, 190, 0), vec(170, 180, 0))
    cubicTo(vec(220, 172, 0), vec(230, 110, 0), vec(200, 62, 0))
    close()
}

// The old implementation, written out here rather than remembered: one backwards walk
// over the whole node list, emitting each instruction's reverse as it goes. The `.to`
// reads are `endPointOf` so this runs at all — the crash is not what this box is about —
// but the *structure* is the old one, and the structure is the defect: a Close met on the
// way back is emitted right there, which in the reversed order is immediately after the
// moveTo, where the subpath's first instruction belongs.
val oldByReversing = { line: FLine ->
    val out = FLine()
    if (line.nodes.isNotEmpty()) {
        out.moveTo(line.currentPoint!!)
        for (i in line.nodes.size - 1 downTo 1) {
            val node = line.nodes[i]
            val lands = line.endPointOf(i - 1)
            when (node) {
                is FLine.LineTo -> out.lineTo(lands)
                is FLine.CubicTo -> out.cubicTo(node.c2, node.c1, lands)
                is FLine.MoveTo -> out.moveTo(lands)
                is FLine.Close -> out.close()
            }
        }
    }
    out
}

val ghost = { l: FLine -> l.copy { thickness = 9.0; color = vec(0.45, 0.5, 0.58, 0.55) } }
val cyan = { l: FLine -> l.copy { thickness = 3.0; color = vec(0.35, 0.85, 1.0, 0.95) } }

// How much of the shape the reversal actually retraces. A reversal that drops a segment
// is shorter than what it reversed, and that is the whole story in one number.
val coverage = { original: FLine, reversed: FLine ->
    val a = original.cursor().lengthD()
    val b = reversed.cursor().lengthD()
    "%.0f of %.0f units retraced (%.0f%%)".format(b, a, 100.0 * b / a)
}

val squareOld = oldByReversing(square)
val squareNew = square.byReversing()
val glyphOld = oldByReversing(glyph)
val glyphNew = glyph.byReversing()

showPlaneGrid(
    "closed square, old reversal\n${coverage(square, squareOld)}" to listOf(ghost(square), cyan(squareOld)),
    "closed square, byReversing today\n${coverage(square, squareNew)}" to listOf(ghost(square), cyan(squareNew)),
    "two contours, old reversal\n${coverage(glyph, glyphOld)}" to listOf(ghost(glyph), cyan(glyphOld)),
    "two contours, byReversing today\n${coverage(glyph, glyphNew)}" to listOf(ghost(glyph), cyan(glyphNew)),
    columns = 2,
    cellWidth = 420,
    cellHeight = 230,
)

// Reversing twice is the identity now, which the old walk could not manage on anything
// closed: it is not even length-preserving once, so it cannot be an involution.
val roundTrip = { l: FLine ->
    val back = l.byReversing().byReversing()
    val same = l.nodes.map { it::class.simpleName } == back.nodes.map { it::class.simpleName }
    if (same) "identical" else "DIFFERENT: ${back.nodes.map { it::class.simpleName }}"
}

listOf(
    "grey = the original, cyan = its reversal",
    "square   reverse-twice: ${roundTrip(square)}",
    "glyph    reverse-twice: ${roundTrip(glyph)}",
).joinToString("\n")
