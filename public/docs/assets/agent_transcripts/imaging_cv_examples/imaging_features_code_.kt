// A square whose corners are at exactly known coordinates.
val square = Img.generate(2000, 2000) { x, y -> if (x in 500..1499 && y in 500..1499) 0.92f else 0.08f }
val harris = square.harrisCorners(threshold = 0.05, minDistance = 120, maxCount = 20)
val fast = square.fastCorners(threshold = 0.2, minDistance = 120, maxCount = 20)

val truth = listOf(500.0 to 500.0, 1499.0 to 500.0, 500.0 to 1499.0, 1499.0 to 1499.0)
val worst = truth.maxOf { (ex, ey) -> harris.minOf { hypot(it.x - ex, it.y - ey) } }

// Three lines at exactly known (rho, theta), plus a decoy ring Hough must not fit.
val lineMask = Img.zeros(2000, 2000, 1)
for (x in 100 until 1900) lineMask.set(x, 400, 0, 1f)        // rho = 400,  theta = pi/2
for (y in 100 until 1900) lineMask.set(1500, y, 0, 1f)       // rho = 1500, theta = 0
for (t in 0 until 2400) {
    val x = 200 + t * 0.6; val y = 600 + t * 0.6
    if (x < 1999 && y < 1999) lineMask.set(x.toInt(), y.toInt(), 0, 1f)
}
for (i in 0 until 3600) {
    val a = 2.0 * PI * i / 3600
    val x = (600 + 220 * kotlin.math.cos(a)).toInt()
    val y = (1500 + 220 * kotlin.math.sin(a)).toInt()
    if (x in 0..1999 && y in 0..1999) lineMask.set(x, y, 0, 1f)
}
// threshold is a vote count, so it scales with the number of edge pixels on a line.
// Named `houghs`, not `lines`, so it cannot shadow the lines() drawing call below.
val houghs = houghLines(lineMask, thetaSteps = 360, threshold = 1000, maxLines = 5)

showImgGrid(
    "harrisResponse (signed, coolwarm)" to square.harrisResponse().colormapDiverging("coolwarm"),
    "shiTomasiResponse (viridis)" to square.shiTomasiResponse().normalized().colormap("viridis"),
    "edge mask: 3 lines + a decoy ring" to lineMask,
    columns = 3,
    cellSide = 260,
)
println("harris ${harris.size} corners, worst ${"%.2f".format(worst)} px from the true square corners")
println("fast ${fast.size} corners at " + fast.sortedBy { it.y * 1000 + it.x }
    .joinToString(", ") { "(${it.x.toInt()},${it.y.toInt()})" })
println("hough " + houghs.joinToString(", ") {
    "(rho=${"%.0f".format(it.rho)}, theta=${"%.2f".format(it.theta)}, votes=${it.votes})"
})

// A HoughLine is an INFINITE line — (rho, theta) names a whole line, not a segment — so
// it has no drawable form until you say which rectangle you are drawing it into. That is
// what toFLine(w, h) is for, and why it is nullable: a peak can name a line that misses.
lines(
    cornersToFLines(harris, size = 40.0) +
        houghs.mapNotNull { it.toFLine(lineMask.width, lineMask.height) }
)
