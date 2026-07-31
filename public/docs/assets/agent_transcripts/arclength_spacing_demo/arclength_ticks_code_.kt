// Same two curves, ticked instead of dashed, so you can see the spacing itself rather
// than its effect on a dash.
val bent = fline {
    moveTo(20, 40)
    cubicTo(90, 200, 330, -80, 400, 90)
}

val straight = fline {
    moveTo(20, 250)
    cubicTo(340, 250, 372, 250, 400, 250)
}

val steps = 24

val ticks = { line: FLine, byArcLength: Boolean ->
    val c = line.cursor()
    val total = c.lengthD()
    val span = (line.nodes.size - 1).toFloat()
    val out = mutableListOf<FLine>()
    for (i in 0..steps) {
        val f = i.toFloat() / steps
        if (byArcLength) c.setD(total * f) else c.setT(span * f)
        val p = c.position()
        // normal2() is the tangent rotated 90 degrees and is NOT normalised — its
        // magnitude is |B'(t)|, which is in the hundreds here — so normalise it or the
        // ticks come out as a fan hundreds of units long.
        val n = c.normal2()
        val len = kotlin.math.hypot(n?.x ?: 0f, n?.y ?: 1f).coerceAtLeast(1e-6f)
        val nx = (n?.x ?: 0f) / len
        val ny = (n?.y ?: 1f) / len
        out.add(
            FLine()
                .moveTo(vec(p.x - nx * 8f, p.y - ny * 8f, p.z))
                .lineTo(vec(p.x + nx * 8f, p.y + ny * 8f, p.z))
                .apply {
                    thickness = 2.0
                    color = if (byArcLength) vec(0.35, 0.95, 0.6, 1.0) else vec(1.0, 0.45, 0.35, 1.0)
                }
        )
    }
    out + line.apply { thickness = 1.0; color = vec(0.55, 0.55, 0.65, 1.0) }
}

// The gap between consecutive tick positions, as a max/median ratio. 1.0 is perfect.
val evenness = { line: FLine, byArcLength: Boolean ->
    val c = line.cursor()
    val total = c.lengthD()
    val span = (line.nodes.size - 1).toFloat()
    val pts = (0..steps).map { i ->
        val f = i.toFloat() / steps
        if (byArcLength) c.setD(total * f) else c.setT(span * f)
        c.position()
    }
    val gaps = (1..steps).map { pts[it].distance(pts[it - 1]).toDouble() }.sorted()
    gaps.last() / gaps[gaps.size / 2]
}

showPlaneGrid(
    "S-curve, ticked by parameter\nmax/median gap %.3f".format(evenness(bent, false)) to ticks(bent, false),
    "S-curve, ticked by arc length\nmax/median gap %.3f".format(evenness(bent, true)) to ticks(bent, true),
    "collinear, ticked by parameter\nmax/median gap %.3f".format(evenness(straight, false)) to ticks(straight, false),
    "collinear, ticked by arc length\nmax/median gap %.3f".format(evenness(straight, true)) to ticks(straight, true),
    columns = 2,
    cellWidth = 420,
    cellHeight = 220,
)

// The mechanism, live. `flatness` is the OLD subdivision criterion: the distance of the
// control points from the chord. `skew` is the term added on 2026-07-31: the distance
// between the curve's mid-PARAMETER point B(0.5) and the chord's mid-POINT. Neither
// subsumes the other, which is why the criterion takes the larger of the two.
val report = { name: String, line: FLine ->
    val n = line.nodes[1] as FLine.CubicTo
    val a = line.nodes[0].to
    val b = n.to
    val ptSeg = { p: Vec3 ->
        field.graphics.FLinesAndJavaShapes.ptSegDistSq3(a.x, a.y, a.z, b.x, b.y, b.z, p.x, p.y, p.z)
    }
    val flatness = kotlin.math.sqrt(maxOf(ptSeg(n.c1), ptSeg(n.c2)))
    val mid = vec(
        0.125 * (a.x + 3 * n.c1.x + 3 * n.c2.x + b.x),
        0.125 * (a.y + 3 * n.c1.y + 3 * n.c2.y + b.y),
        0.125 * (a.z + 3 * n.c1.z + 3 * n.c2.z + b.z),
    )
    val chordMid = vec(0.5 * (a.x + b.x), 0.5 * (a.y + b.y), 0.5 * (a.z + b.z))
    val skew = mid.distance(chordMid)
    val leaves = afield.graphics.PathFlattener(line, 0.01f).mappings.size
    "%-22s flatness %8.3f   skew %8.3f   leaves %5d".format(name, flatness, skew, leaves)
}

listOf(
    report("S-curve", bent),
    report("collinear", straight),
    "",
    "flatness alone stops at one leaf whenever it reads 0.000, and no tolerance can beat 0.",
).joinToString("\n")
