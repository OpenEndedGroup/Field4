// A square whose corners are at exactly known coordinates.
val square = Img.generate(200, 200) { x, y -> if (x in 50..149 && y in 50..149) 0.92f else 0.08f }
val harris = square.harrisCorners(threshold = 0.05, minDistance = 12, maxCount = 20)
val fast = square.fastCorners(threshold = 0.2, minDistance = 12, maxCount = 20)

val truth = listOf(50.0 to 50.0, 149.0 to 50.0, 50.0 to 149.0, 149.0 to 149.0)
val worst = truth.maxOf { (ex, ey) -> harris.minOf { hypot(it.x - ex, it.y - ey) } }

// Three lines at exactly known (rho, theta), plus a decoy ring Hough must not fit.
val lineMask = Img.zeros(200, 200, 1)
for (x in 10 until 190) lineMask.set(x, 40, 0, 1f)          // rho = 40,  theta = pi/2
for (y in 10 until 190) lineMask.set(150, y, 0, 1f)         // rho = 150, theta = 0
for (t in 0 until 240) {
    val x = 20 + t * 0.6; val y = 60 + t * 0.6
    if (x < 199 && y < 199) lineMask.set(x.toInt(), y.toInt(), 0, 1f)
}
for (i in 0 until 360) {
    val a = 2.0 * PI * i / 360
    val x = (60 + 22 * kotlin.math.cos(a)).toInt()
    val y = (150 + 22 * kotlin.math.sin(a)).toInt()
    if (x in 0..199 && y in 0..199) lineMask.set(x, y, 0, 1f)
}
val lines = houghLines(lineMask, thetaSteps = 360, threshold = 100, maxLines = 5)

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
println("hough " + lines.joinToString(", ") {
    "(rho=${"%.0f".format(it.rho)}, theta=${"%.2f".format(it.theta)}, votes=${it.votes})"
})
