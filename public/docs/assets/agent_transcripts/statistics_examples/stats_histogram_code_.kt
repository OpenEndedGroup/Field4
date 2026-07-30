// buildHistogram takes no bin-count argument. It picks the SMALLEST of the
// Freedman-Diaconis, Scott and Sturges widths, floored so the count cannot exceed 2000.
// Re-derive the whole decision from the public stats and predict the bin count.
val rng = java.util.Random(11)
val skewed = (0 until 500).map { exp(0.6 * rng.nextGaussian()) }
val rng2 = java.util.Random(23)
val bimodal = (0 until 500).map {
    if (it % 2 == 0) -2.0 + 0.4 * rng2.nextGaussian() else 2.0 + 0.4 * rng2.nextGaussian()
}

// `fun Box.explain`, not `fun explain`: a named function in box code that calls `println`
// captures the script's implicit Box receiver, and K2 cannot codegen that — it fails with
// "No mapping for symbol: VALUE_PARAMETER SCRIPT_IMPLICIT_RECEIVER". Making the receiver
// an explicit extension receiver fixes it, and the call site is unchanged because the box
// body already has a Box receiver in scope.
fun Box.explain(label: String, values: List<Double>): Histogram {
    val s = buildSummaryStats(values)
    val range = s.max - s.min
    val cubeRoot = s.count.toDouble().pow(-1.0 / 3.0)
    val hFd = 2.0 * s.iqr * cubeRoot
    val hScott = 3.5 * s.sampleStdDev * cubeRoot
    val kSturges = ceil(ln(s.count.toDouble()) / ln(2.0) + 1.0).toInt()
    val hSturges = range / kSturges
    val winner = minOf(hFd, hScott, hSturges)
    val which = if (winner == hFd) "Freedman-Diaconis" else if (winner == hScott) "Scott" else "Sturges"
    val predicted = Math.round(range / winner).toInt().coerceIn(1, 2000)
    val actual = buildHistogram(values)
    println("$label: iqr=${"%.3f".format(s.iqr)} sd=${"%.3f".format(s.sampleStdDev)} range=${"%.3f".format(range)}")
    println("  FD=${"%.4f".format(hFd)}  Scott=${"%.4f".format(hScott)}  Sturges=${"%.4f".format(hSturges)} (k=$kSturges)" +
        "  -> $which wins")
    println("  predicted ${predicted} bins of ${"%.5f".format(range / predicted)}; " +
        "got ${actual.bins.size} of ${"%.5f".format(actual.binWidth)} -> agree=${predicted == actual.bins.size}")
    println("  counts sum to ${actual.count} of ${values.size}; " +
        "bins tile [min,max] with no gap=${(1 until actual.bins.size).all {
            abs(actual.bins[it].start - actual.bins[it - 1].end) < 1e-9
        }}")
    return actual
}

val hSkewed = explain("skewed  ", skewed)
val hBimodal = explain("bimodal ", bimodal)
println("The bimodal signal has the SAME n and gets ${hBimodal.bins.size} bins against " +
    "${hSkewed.bins.size}: FD and Scott are driven by the distance BETWEEN the modes " +
    "(iqr ~4) rather than the 0.4 spread within them, so the crudest rule wins. Sturges " +
    "knows nothing but n, so it is ${hBimodal.bins.size} bins for any 500-point sample.")

val svgs = listOf(
    histogramToSvg(hSkewed, widthPx = 380, heightPx = 230, padTop = 26, title = "exp(0.6 g), n=500", decimals = 3),
    histogramToSvg(hBimodal, widthPx = 380, heightPx = 230, padTop = 26, title = "two modes at -2 and +2, n=500", decimals = 3),
)
display("histograms", "<div style='display:flex;gap:12px'>" + svgs.joinToString("") { "<div>$it</div>" } + "</div>")
