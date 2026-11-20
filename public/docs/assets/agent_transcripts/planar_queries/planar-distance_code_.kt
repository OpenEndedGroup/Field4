// segments as (ax, ay, bx, by)
val segments = [
    // an X: a proper crossing
    [30.0, 30.0, 150.0, 150.0],
    [30.0, 150.0, 150.0, 30.0],
    // a T: one segment ends on the other
    [200.0, 40.0, 340.0, 40.0],
    [270.0, 40.0, 270.0, 140.0],
    // a V: a shared endpoint
    [200.0, 200.0, 270.0, 260.0],
    [270.0, 260.0, 340.0, 200.0],
    // two collinear pieces that overlap
    [30.0, 230.0, 120.0, 230.0],
    [90.0, 230.0, 160.0, 230.0],
]

val drawn = segments.map { s ->
    fline {
        moveTo(s[0], s[1])
        lineTo(s[2], s[3])
        thickness = 2.0
        color = vec(1.0, 1.0, 1.0, 1.0)
    }
}

// each dot shaded by its distance to the nearest segment
val reach = 40.0
val dots = mutableListOf<FLine>()
for (gx in 0..20) for (gy in 0..14) {
    val x = 10.0 + gx * 19.0
    val y = 10.0 + gy * 19.0
    val d = segments.minOf { s -> pointSegmentDistance2D(x, y, s[0], s[1], s[2], s[3]) }
    val near = 1.0 - (d / reach).coerceIn(0.0, 1.0)
    dots += fline {
        circle(x, y, 1.0 + 4.0 * near)
        filled = true
        stroked = false
        color = vec(0.3 + 0.1 * near, 0.5 + 0.4 * near, 1.0, 0.25 + 0.6 * near)
    }
}

// every pair: a proper crossing, a touch only, or apart
val marks = mutableListOf<FLine>()
var crossing = 0
var touching = 0
for (i in segments.indices) for (j in i + 1 until segments.size) {
    val (a, b) = segments[i] to segments[j]
    val proper = segmentsCrossProperly2D(a[0], a[1], a[2], a[3], b[0], b[1], b[2], b[3])
    val touch = segmentsTouch2D(a[0], a[1], a[2], a[3], b[0], b[1], b[2], b[3])
    if (!touch) continue
    if (proper) crossing++ else touching++
    // a crossing is where the lines meet; a touch is the endpoint nearest the other segment
    val ends = [a[0] to a[1], a[2] to a[3], b[0] to b[1], b[2] to b[3]]
    val (mx, my) =
        if (proper) drawn[i].crossings2D(drawn[j]).first().point.let { it.x.toDouble() to it.y.toDouble() }
        else
            ends.minBy { (x, y) ->
                val other = if (a[0] == x && a[1] == y || a[2] == x && a[3] == y) b else a
                pointSegmentDistance2D(x, y, other[0], other[1], other[2], other[3])
            }
    marks += fline {
        circle(mx, my, 7.0)
        thickness = 2.5
        color = if (proper) vec(1.0, 0.55, 0.2, 1.0) else vec(1.0, 0.9, 0.2, 1.0)
    }
}

print("$crossing proper crossings, $touching touching pairs")

lines(dots + drawn + marks, w = 420.0, h = 300.0, fitToSize = true)
