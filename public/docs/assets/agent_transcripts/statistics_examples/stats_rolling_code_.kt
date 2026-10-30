val w = 80
val total = 600
val change = 300
val rng = java.util.Random(3)

val dcor = RollingDistanceCorrelation(w)
val xiRoll = RollingXiCorrelation(w)

val xs = ArrayList<Double>()
val ys = ArrayList<Double>()
val dSeries = ArrayList<Double>()
val xiSeries = ArrayList<Double>()
val absR = ArrayList<Double>()

for (i in 0 until total) {
    val xv = rng.nextGaussian()
    // Before `change`: two independent Gaussians. After: y = x^2 - 1, a dependence Pearson cannot see.
    val yv = if (i < change) rng.nextGaussian() else xv * xv - 1.0 + 0.15 * rng.nextGaussian()
    xs += xv
    ys += yv
    val distanceValue = dcor.push(xv, yv)
    val xiValue = xiRoll.push(xv, yv)
    // Compare with the w-sample null only once a complete window has arrived.
    val fullWindow = xs.size >= w
    dSeries += if (fullWindow) requireNotNull(distanceValue) else Double.NaN
    xiSeries += if (fullWindow) requireNotNull(xiValue) else Double.NaN
    // There is no RollingPearson, so take the batch one over the same window for comparison.
    val lo = maxOf(0, xs.size - w)
    val bx = xs.subList(lo, xs.size).toDoubleArray()
    val by = ys.subList(lo, ys.size).toDoubleArray()
    absR += if (fullWindow) abs(StatsDependence.pearson(bx, by)) else Double.NaN
}

// Calibrate each statistic against its own null distribution at this window size.
// These are pointwise reference levels, not a multiple-testing-adjusted change detector.
val r2 = java.util.Random(1234)
val k = 300
val dNull = DoubleArray(k)
val xiNull = DoubleArray(k)
repeat(k) { i ->
    val nx = DoubleArray(w) { r2.nextGaussian() }
    val ny = DoubleArray(w) { r2.nextGaussian() }
    dNull[i] = StatsDependence.distanceCorrelation(nx, ny)
    xiNull[i] = StatsDependence.chatterjeeXi(nx, ny)
}
val threshold = dNull.sortedArray()[(k * 0.95).toInt()]
val xiThreshold = xiNull.sortedArray()[(k * 0.95).toInt()]

// The incremental sums are not an approximation. Cross-check the last window against the O(w^2)
// batch call, which materialises two full w x w distance matrices to get the same answer.
val batchLast = StatsDependence.distanceCorrelation(xs.takeLast(w).toDoubleArray(), ys.takeLast(w).toDoubleArray())
val agreement = abs(dSeries.last() - batchLast)

val firstD = (change until total).firstOrNull { dSeries[it] > threshold }
val firstXi = (change until total).firstOrNull { xiSeries[it] > xiThreshold }
val dCrossing = firstD?.let { "+${it - change} samples" } ?: "not crossed"
val xiCrossing = firstXi?.let { "+${it - change} samples" } ?: "not crossed"

showSignals(
    "rolling dCor" to dSeries,
    "rolling xi" to xiSeries,
    "rolling |pearson|" to absR,
    "dCor null 95th pct at w=$w" to List(total) { threshold },
    "xi null 95th pct at w=$w" to List(total) { xiThreshold },
    channel = "traces",
    title = "Three rolling coefficients across one regime change (window $w)",
    xTitle = "sample",
    yTitle = "coefficient",
    marks = ["regime change" to change],
)
// The two windows are the same three coefficients measured twice, so they read as a table.
// The pipes in `|r|` are escaped, or they would split the cell they sit in.
markdown(
    "numbers",
    """
    | window | dCor | xi | \|r\| |
    |--------|------|----|-------|
    | window-full to the change (i=$w..${change - 1}) | ${dSeries.subList(w, change).average().f3} | ${xiSeries.subList(w, change).average().f3} | ${absR.subList(w, change).average().f3} |
    | settled after the change (i=${change + w}..${total - 1}) | ${dSeries.subList(change + w, total).average().f3} | ${xiSeries.subList(change + w, total).average().f3} | ${absR.subList(change + w, total).average().f3} |

    {muted:dCor null 95th pct ${threshold.f3}: $dCrossing.
    xi null 95th pct ${xiThreshold.f3}: $xiCrossing.}

    {muted:incremental vs batch on the final window: |difference| = $agreement}
""",
    width = 900,
)
