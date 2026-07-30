// A single coefficient over a whole recording answers the wrong question when the
// relationship comes and goes. RollingDistanceCorrelation maintains its sums
// incrementally, O(w) per push; RollingXiCorrelation just recomputes, O(w log w).
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
    // Before `change`: two independent Gaussians. After: y = x^2 - 1, which Pearson cannot see.
    val yv = if (i < change) rng.nextGaussian() else xv * xv - 1.0 + 0.15 * rng.nextGaussian()
    xs += xv
    ys += yv
    dSeries += dcor.push(xv, yv) ?: 0.0     // the first push always returns null
    xiSeries += xiRoll.push(xv, yv) ?: 0.0
    // There is no RollingPearson, so take the batch one over the same window.
    val lo = maxOf(0, xs.size - w)
    val bx = xs.subList(lo, xs.size).toDoubleArray()
    val by = ys.subList(lo, ys.size).toDoubleArray()
    absR += if (bx.size >= 2) abs(StatsDependence.pearson(bx, by)) else 0.0
}

// The threshold that makes the traces readable: the 95th percentile of the dCor null at
// THIS window size, measured rather than assumed.
val r2 = java.util.Random(1234)
val k = 300
val threshold = DoubleArray(k) {
    StatsDependence.distanceCorrelation(DoubleArray(w) { r2.nextGaussian() }, DoubleArray(w) { r2.nextGaussian() })
}.sortedArray()[(k * 0.95).toInt()]

fun mean(l: List<Double>, a: Int, b: Int) = l.subList(a, b).average()
println("window=$w, regime change at i=$change, dCor null 95th pct at this w=${"%.3f".format(threshold)}")
println("  before (i=$w..${change - 1}):        dCor ${"%.3f".format(mean(dSeries, w, change))}" +
    "   xi ${"%.3f".format(mean(xiSeries, w, change))}   |r| ${"%.3f".format(mean(absR, w, change))}")
println("  settled after (i=${change + w}..${total - 1}): dCor ${"%.3f".format(mean(dSeries, change + w, total))}" +
    "   xi ${"%.3f".format(mean(xiSeries, change + w, total))}   |r| ${"%.3f".format(mean(absR, change + w, total))}")
val firstD = (change until total).firstOrNull { dSeries[it] > threshold }
val firstXi = (change until total).firstOrNull { xiSeries[it] > threshold }
println("  threshold first crossed by dCor at +${firstD?.minus(change)} samples, by xi at +${firstXi?.minus(change)}")

// Not an approximation, and this is a real cross-check rather than a restatement:
// RollingDistanceCorrelation never rebuilds a distance matrix, while the batch call
// materialises two full w x w matrices and double-centres them.
val batchLast = StatsDependence.distanceCorrelation(
    xs.takeLast(w).toDoubleArray(), ys.takeLast(w).toDoubleArray()
)
println("  incremental vs batch on the final window: |difference| = ${abs(dSeries.last() - batchLast)}")
// And rolling xi is bit-exact, because it literally calls chatterjeeXi over the window.
val batchXi = StatsDependence.chatterjeeXi(
    xs.takeLast(w).toDoubleArray(), ys.takeLast(w).toDoubleArray()
)
println("  rolling xi vs batch xi: |difference| = ${abs(xiSeries.last() - batchXi)} (exactly zero by construction)")

showSignals(
    "rolling dCor" to dSeries,
    "rolling xi" to xiSeries,
    "rolling |pearson|" to absR,
    "dCor null 95th pct at w=$w" to List(total) { threshold },
    channel = "traces",
    title = "Three rolling coefficients across one regime change (window $w)",
    xTitle = "sample",
    yTitle = "coefficient",
    marks = listOf("regime change" to change),
)
