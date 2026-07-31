// Two curves. `bent` is an ordinary S-shaped cubic — its control points are far off the
// chord, so the old flatness criterion always subdivided it and its spacing was already
// fine. `straight` has its control points exactly ON the chord but bunched at the far
// end: geometrically a straight line, parameterised so that t = 0.5 is 82% of the way
// along. Its flatness is exactly 0.0, so no tolerance could ever force a subdivision.
val bent = fline {
    moveTo(20, 40)
    cubicTo(90, 200, 330, -80, 400, 90)
}

val straight = fline {
    moveTo(20, 250)
    cubicTo(340, 250, 372, 250, 400, 250)
}

val dash = 22f
val gap = 13f

// What a one-leaf mapping produced: t marched in equal steps, each dash a straight chord
// between its two ends. Reproduced explicitly rather than remembered, so the comparison
// is computed here and now on both sides.
val byParameter = { line: FLine ->
    val c = line.cursor()
    val span = (line.nodes.size - 1).toFloat()
    val total = c.lengthD()
    val out = mutableListOf<FLine>()
    var d = 0f
    while (d < total) {
        c.setT(span * d / total)
        val a = c.position()
        c.setT(span * minOf(d + dash, total) / total)
        val b = c.position()
        out.add(FLine().moveTo(a).lineTo(b))
        d += dash + gap
    }
    out
}

// The drawn length of one dash, following whatever nodes it actually has.
val drawnLength = { d: FLine ->
    (1 until d.nodes.size).sumOf { d.nodes[it].to.distance(d.nodes[it - 1].to).toDouble() }
}

val paint = { dashes: List<FLine> ->
    dashes.mapIndexed { i, d ->
        d.apply {
            thickness = 3.0
            color = if (i % 2 == 0) vec(1.0, 0.65, 0.2, 1.0) else vec(0.35, 0.95, 0.6, 1.0)
        }
    }
}

// The interior dashes are the ones that should all be `dash` long; the last one is
// whatever length is left over at the end of the curve.
val spread = { dashes: List<FLine> ->
    val lengths = dashes.dropLast(1).map(drawnLength).filter { it > 0.0 }
    if (lengths.isEmpty()) "no dashes"
    else "asked $dash, drew %.1f .. %.1f".format(lengths.min(), lengths.max())
}

val bentOld = paint(byParameter(bent))
val bentNew = paint(bent.byDashing(dash, gap))
val straightOld = paint(byParameter(straight))
val straightNew = paint(straight.byDashing(dash, gap))

showPlaneGrid(
    "S-curve, spaced by parameter\n${spread(bentOld)}" to bentOld,
    "S-curve, spaced by arc length\n${spread(bentNew)}" to bentNew,
    "collinear control points, by parameter\n${spread(straightOld)}" to straightOld,
    "collinear control points, by arc length\n${spread(straightNew)}" to straightNew,
    columns = 2,
    cellWidth = 420,
    cellHeight = 220,
)

"left column = the old spacing, right column = today's byDashing"
