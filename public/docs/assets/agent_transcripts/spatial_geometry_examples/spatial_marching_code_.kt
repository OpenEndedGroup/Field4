// Marching squares has exactly two decisions in it, and both are places where a plausible
// implementation is quietly wrong. Each input below has an answer you already know.
val plane: ScalarField2D = { x, _ -> x }
val circle: ScalarField2D = { x, y -> kotlin.math.hypot(x, y) - 1.0 }
val saddle: ScalarField2D = { x, y -> x * y }

// 1. A sample EXACTLY at iso. With a symmetric strict test such a sample is neither inside
// nor outside and the crossing on every edge out of that corner is suppressed. f(x,y) = x
// at iso 0 on an even grid used to return an empty list; the unit circle over [-2,2] at
// nx = 16 came back severed into four arcs at the points where it passes through a corner.
// The convention now is the one marchingCubes uses: inside iff strictly BELOW iso.
val planeContours = generateIsoContours(plane, -1.0, 1.0, -1.0, 1.0, 4, 4, 0.0)
val aligned = generateIsoContours(circle, -2.0, 2.0, -2.0, 2.0, 16, 16, 0.0)
println("f(x,y) = x at iso 0, nx = 4 -> ${planeContours.size} contour of ${planeContours.sumOf { it.size }} points")
println("unit circle at nx = 16      -> ${aligned.size} loop of ${aligned[0].size} points")

fun perimeter(loop: List<Vec2>): Double {
    var total = 0.0
    for (i in loop.indices) {
        val a = loop[i]; val b = loop[(i + 1) % loop.size]
        total += kotlin.math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
    }
    return total
}
println("   perimeter %.4f against 2pi = %.4f".format(perimeter(aligned[0]), 2 * PI))

// 2. The saddle cell. Four corners alternating inside/outside give four crossings and two
// ways to pair them, and the two choices give different topology — so an arbitrary choice
// lets neighbouring cells disagree and the contour tears. The asymptotic decider settles it
// from the cell's own four corner values, so two cells sharing an edge always agree.
//
// f = x*y is the case where it has nothing to go on: the field is ITSELF bilinear, so the
// cell's interpolant reproduces it and the saddle value is exactly iso. Both pairings are
// then equally correct. Left to floating point that tie flips with resolution, so it is
// broken deterministically towards the pre-decider pairing. Raise nx and watch the branch
// lengths: they are not equal, and with this sampling window they are not meant to be.
val branches = generateIsoContours(saddle, -1.05, 1.03, -1.02, 1.07, 8, 8, 0.0)
println()
println("f = x*y at iso 0 -> ${branches.size} branches of ${branches.map { it.size }.joinToString(" and ")} points")
println("   (a genuine tie: the saddle sits exactly on iso, so either pairing is correct)")

fun gridLines(x0: Double, x1: Double, y0: Double, y1: Double, nx: Int, ny: Int) = fline {
    for (i in 0..nx) { val x = x0 + (x1 - x0) * i / nx; moveTo(x, y0); lineTo(x, y1) }
    for (j in 0..ny) { val y = y0 + (y1 - y0) * j / ny; moveTo(x0, y); lineTo(x1, y) }
    color = vec(0.34, 0.42, 0.54, 0.55)
    thickness = 0.8
}

fun dotted(
    contours: List<List<Vec2>>, x0: Double, x1: Double, y0: Double, y1: Double,
    colors: List<Vec4>, eps: Double,
) = contours.mapIndexed { i, c ->
    polylineFrom(c, 0, closed = isoContourClosed(c, x0, x1, y0, y1, eps)).also {
        it.color = colors[i % colors.size]; it.thickness = 2.2
        it.pointed = true; it.pointSize = 1.1
    }
}

val warm = listOf(vec(1.0, 0.78, 0.36, 0.95), vec(0.62, 0.92, 1.0, 0.95),
                  vec(0.98, 0.55, 0.62, 0.95), vec(0.6, 1.0, 0.72, 0.95))

showPlaneGrid(
    "f(x,y) = x at iso 0, grid aligned on x = 0\n${planeContours.size} contour of ${planeContours.sumOf { it.size }} points (was 0)" to
        (listOf(gridLines(-1.0, 1.0, -1.0, 1.0, 4, 4)) + dotted(planeContours, -1.0, 1.0, -1.0, 1.0, warm, 0.4)),
    "unit circle at nx = 16, aligned\n1 loop of ${aligned[0].size} points (was 4 arcs)" to
        (listOf(gridLines(-2.0, 2.0, -2.0, 2.0, 16, 16)) + dotted(aligned, -2.0, 2.0, -2.0, 2.0, warm, 0.2)),
    "f = x*y at iso 0 — the saddle cell\n${branches.size} branches of ${branches.map { it.size }.joinToString(" and ")} points" to
        (listOf(gridLines(-1.05, 1.03, -1.02, 1.07, 8, 8)) + dotted(branches, -1.05, 1.03, -1.02, 1.07, warm, 0.2)),
    columns = 2,
    cellWidth = 430,
    cellHeight = 280,
)
