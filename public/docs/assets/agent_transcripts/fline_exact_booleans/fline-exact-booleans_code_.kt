fun circle(cx: Double, cy: Double, r: Double) = fline {
    val k = 0.5522847 * r
    moveTo(cx + r, cy)
    cubicTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r)
    cubicTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy)
    cubicTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r)
    cubicTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy)
    close()
}

fun blob(x: Double, y: Double) = fline {
    moveTo(x, y)
    cubicTo(x + 40, y - 30, x + 90, y + 10, x + 100, y + 50)
    cubicTo(x + 110, y + 90, x + 60, y + 70, x + 50, y + 110)
    cubicTo(x + 40, y + 150, x - 20, y + 120, x - 10, y + 80)
    cubicTo(x, y + 50, x - 30, y + 30, x, y)
    close()
}

// a dot on every node: how many segments each answer used
fun dots(f: FLine, c: Vec4) = f.nodes.mapNotNull { it.toOrNull }.map { p ->
    fline {
        moveTo(p.x + 2.5, p.y)
        cubicTo(p.x + 2.5, p.y + 1.4, p.x + 1.4, p.y + 2.5, p.x, p.y + 2.5)
        cubicTo(p.x - 1.4, p.y + 2.5, p.x - 2.5, p.y + 1.4, p.x - 2.5, p.y)
        cubicTo(p.x - 2.5, p.y - 1.4, p.x - 1.4, p.y - 2.5, p.x, p.y - 2.5)
        cubicTo(p.x + 1.4, p.y - 2.5, p.x + 2.5, p.y - 1.4, p.x + 2.5, p.y)
        filled = true
        stroked = false
        color = c
    }
}

val out = mutableListOf<FLine>()
for ((row, op) in listOf(FLineBooleanOp.Union, FLineBooleanOp.Subtract).withIndex()) {
    val y = 40.0 + row * 190
    for (col in 0..1) {
        val x = 40.0 + col * 260
        val a = blob(x + 20, y)
        val b = circle(x + 90, y + 60, 45.0)
        val r = if (col == 1) a.booleanExact(b, op).line
            else if (op == FLineBooleanOp.Union) a.byUnionWith(b) else a.byDifferenceWith(b)
        r.filled = true
        r.stroked = true
        r.thickness = 1.5
        r.color = if (col == 0) vec(1.0, 0.55, 0.35, 0.35) else vec(0.4, 0.9, 1.0, 0.35)
        out.add(r)
        out.addAll(dots(r, if (col == 0) vec(1.0, 0.4, 0.2, 1.0) else vec(0.3, 0.8, 1.0, 1.0)))
        println("$op ${if (col == 0) "Area" else "exact"}: ${r.segmentCount2D()} segments")
    }
}
lines(out, w = 460.0, h = 380.0, fitToSize = true)
