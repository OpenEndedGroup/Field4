fun star(cx: Double, cy: Double) = fline {
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) 70.0 else 32.0
        val a = kotlin.math.PI * i / 5
        if (i == 0) moveTo(cx + r * kotlin.math.cos(a), cy + r * kotlin.math.sin(a))
        else lineTo(cx + r * kotlin.math.cos(a), cy + r * kotlin.math.sin(a))
    }
    close()
}

fun sCurve(x: Double, y: Double) = fline {
    moveTo(x, y)
    cubicTo(x + 40, y - 55, x + 70, y + 55, x + 110, y)
    cubicTo(x + 140, y - 35, x + 170, y + 30, x + 200, y + 5)
    lineTo(x + 215, y + 55)
}

fun dots(f: FLine, c: Vec4) = f.nodes.mapNotNull { it.toOrNull }.map { p ->
    fline {
        moveTo(p.x + 2.0, p.y)
        cubicTo(p.x + 2.0, p.y + 1.1, p.x + 1.1, p.y + 2.0, p.x, p.y + 2.0)
        cubicTo(p.x - 1.1, p.y + 2.0, p.x - 2.0, p.y + 1.1, p.x - 2.0, p.y)
        cubicTo(p.x - 2.0, p.y - 1.1, p.x - 1.1, p.y - 2.0, p.x, p.y - 2.0)
        cubicTo(p.x + 1.1, p.y - 2.0, p.x + 2.0, p.y - 1.1, p.x + 2.0, p.y)
        filled = true
        stroked = false
        color = c
    }
}

val out = mutableListOf<FLine>()
for (col in 0..1) {
    val x = 30.0 + col * 280
    val shapes = listOf(star(x + 110, 100.0), sCurve(x, 270.0))
    for (s in shapes) {
        s.thickness = 1.0
        s.color = vec(1.0, 1.0, 1.0, 0.6)
        out.add(s)
        for (d in listOf(12.0, -12.0)) {
            val o = if (col == 0) s.byOffsetting(d.toFloat()) else s.byOffsettingExact(d.toFloat())
            o.thickness = 1.5
            o.color = if (col == 0) vec(1.0, 0.55, 0.35, 1.0) else vec(0.4, 0.9, 1.0, 1.0)
            out.add(o)
            out.addAll(dots(o, if (col == 0) vec(1.0, 0.4, 0.2, 1.0) else vec(0.3, 0.8, 1.0, 1.0)))
            println("${if (col == 0) "byOffsetting" else "byOffsettingExact"} d=$d: ${o.segmentCount2D()} segments")
        }
    }
}
lines(out, w = 560.0, h = 360.0, fitToSize = true)
