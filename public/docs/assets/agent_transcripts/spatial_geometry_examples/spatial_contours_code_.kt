// A Voronoi diagram IS the set of ties in argmin |x - p_i|, so contouring the gap between
// the nearest and the second nearest site recovers it from a completely different
// algorithm. Except at exactly zero — see below.
val sites = listOf(
    vec(-1.6, -0.9), vec(1.4, -1.2), vec(2.0, 1.1), vec(-0.2, 1.7), vec(-2.1, 0.9), vec(0.4, -0.1),
)
val gap: ScalarField2D = { x, y ->
    val d = sites.map { s -> kotlin.math.hypot(x - s.x, y - s.y) }.sorted()
    d[1] - d[0]
}

// This field is NON-NEGATIVE: it touches zero on the seams and is positive everywhere
// else. Marching squares finds SIGN CHANGES, so iso 0.0 finds nothing at all, and that is
// the right answer rather than a bug. Every min-of-distances or absolute-value field has
// this property, so it is worth internalising once.
val atZero = generateIsoContours(gap, -3.4, 3.4, -2.9, 2.9, 120, 120, 0.0)
var lo = Double.MAX_VALUE
var hi = -Double.MAX_VALUE
for (i in 0..120) for (j in 0..120) {
    val v = gap(-3.4 + i * 6.8 / 120.0, -2.9 + j * 5.8 / 120.0)
    if (v < lo) lo = v
    if (v > hi) hi = v
}
println("field range %.3e .. %.4f  (never negative)".format(lo, hi))
println("iso 0.0 exactly -> ${atZero.size} contours")

val levels = listOf(0.05, 0.35, 0.8, 1.4)
val byLevel = levels.map { iso -> iso to generateIsoContours(gap, -3.4, 3.4, -2.9, 2.9, 120, 120, iso) }
byLevel.forEach { (iso, cs) ->
    println("iso %.2f -> %2d contours, %4d points".format(iso, cs.size, cs.sumOf { it.size }))
}

// A completely different use of the same machinery: pointEdgeScalarField2D turns an FLine
// into a signed field whose zero set is a tube around it, so contouring at 0.0 outlines a
// stroke and a negative iso fattens it.
val stroke = fline {
    moveTo(-2.4, 1.2); cubicTo(-1.0, -2.2, 1.2, 2.4, 2.6, -1.0)
}
val outline = generateSmoothIsoContours(stroke, radius = 0.55, spatialStep = 0.05)
val fat = generateSmoothIsoContours(stroke, radius = 0.55, spatialStep = 0.05, iso = -0.28)
println()
println("tube outlines: ${outline.size} at radius 0.55, ${fat.size} at 0.83")

fun contourLines(contours: List<List<Vec2>>, color: Vec4, thickness: Double) =
    contours.filter { it.size >= 2 }.map { c ->
        polylineFrom(c, 0, closed = isoContourClosed(c, -3.4, 3.4, -2.9, 2.9, boundaryEpsilon = 0.05)).also {
            it.color = color; it.thickness = thickness
        }
    }

val siteDots = pointCloudFLine(sites, color = vec(1.0, 1.0, 1.0, 0.9), pointSize = 1.4)
val ramp = okhslSet(levels.size, saturation = 0.82, lightness = 0.66, startHue = 0.55f)
val stacked = byLevel.flatMapIndexed { i, (_, contours) ->
    contourLines(contours, ramp[i], 1.5 - i * 0.15)
} + siteDots

showPlaneGrid(
    "iso 0.05 — the cells, minus a hairline\n${byLevel[0].second.size} contours; at iso 0.0 exactly: ${atZero.size}" to
        (contourLines(byLevel[0].second, vec(0.62, 0.9, 1.0, 0.85), 1.6) + siteDots),
    "iso 0.05 / 0.35 / 0.8 / 1.4 together\ncells shrinking inwards with rounded corners" to stacked,
    "generateSmoothIsoContours on an FLine\ntube radius 0.55 and 0.83" to listOf(
        stroke.also { it.color = vec(1.0, 1.0, 1.0, 0.30); it.thickness = 1.0 },
        *fat.map { it.also { f -> f.color = vec(0.98, 0.55, 0.42, 0.55); f.thickness = 1.3 } }.toTypedArray(),
        *outline.map { it.also { f -> f.color = vec(1.0, 0.82, 0.42, 0.95); f.thickness = 1.6 } }.toTypedArray(),
    ),
    columns = 2,
    cellWidth = 430,
    cellHeight = 280,
)
