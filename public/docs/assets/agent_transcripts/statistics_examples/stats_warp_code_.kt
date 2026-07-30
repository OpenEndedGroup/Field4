// quantilewarp.kt does not measure anything. It fits a monotone 8-knot PCHIP curve from
// the measured quantiles of recent samples onto the quantiles of a target distribution.
val rng = java.util.Random(5)
val fit = DoubleArray(4000) { exp(rng.nextGaussian()) }    // log-normal: violently skewed
val held = DoubleArray(4000) { exp(rng.nextGaussian()) }   // a disjoint second draw

val warp = AdaptiveQuantileWarp()                          // target UnitNormal, 8 knots
val state = warp.update(fit)
val warped = held.map { state.evaluate(it) }
val before = buildSummaryStats(held.toList())
val after = buildSummaryStats(warped)

println("The eight default probabilities are NOT symmetric: " +
    QuantileWarpConfig().probabilities.joinToString() + " — more knots on the left tail.")
println("input knots : ${state.inputKnots.joinToString { "%.3f".format(it) }}")
println("target knots: ${state.targetKnots.joinToString { "%.3f".format(it) }}")
println("held-out BEFORE: mean ${"%.3f".format(before.mean)}  sd ${"%.3f".format(before.sampleStdDev)}" +
    "  skew ${"%.3f".format(before.skewness)}  excess kurtosis ${"%.1f".format(before.kurtosisExcess)}")
println("held-out AFTER : mean ${"%.4f".format(after.mean)}  sd ${"%.4f".format(after.sampleStdDev)}" +
    "  skew ${"%.4f".format(after.skewness)}  excess kurtosis ${"%.4f".format(after.kurtosisExcess)}")
println("  (every number above is OUT OF SAMPLE — the curve was fitted on a different draw)")

// An independent oracle for the 8-knot fit: the exact warp onto a unit normal is
// Phi^-1(Fhat(v)). Nothing in quantilewarp.kt computes that, so this tests the PCHIP
// interpolation rather than restating it.
val sortedFit = fit.sortedArray()
fun empiricalCdf(v: Double): Double {
    var lo = 0
    var hi = sortedFit.size
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (sortedFit[mid] <= v) lo = mid + 1 else hi = mid
    }
    return lo / sortedFit.size.toDouble()
}
val probes = (2..98).map { it / 100.0 }
val inputs = probes.map { sortedFit[(it * (sortedFit.size - 1)).toInt()] }
val pchip = inputs.map { state.evaluate(it) }
val exact = inputs.map { inverseStandardNormalCdf(empiricalCdf(it).coerceIn(1e-4, 1.0 - 1e-4)) }
println("worst |8-knot PCHIP - exact Phi^-1(Fhat)| over the 2nd..98th percentile: " +
    "${"%.4f".format(pchip.indices.maxOf { abs(pchip[it] - exact[it]) })}")
println("inverseStandardNormalCdf(0.975)=${"%.6f".format(inverseStandardNormalCdf(0.975))} " +
    "(published 1.959964); (0.001)=${"%.6f".format(inverseStandardNormalCdf(0.001))} (published -3.090232)")

// Sharp edge 1: outside the fitted knots, evaluate extrapolates linearly from the
// endpoint tangent, and nothing clamps it.
println("lowest input knot is ${"%.3f".format(state.inputKnots.first())}; " +
    "evaluate(0.0)=${"%.2f".format(state.evaluate(0.0))}, evaluate(-1.0)=${"%.1f".format(state.evaluate(-1.0))} " +
    "— unbounded linear extrapolation, so clamp the input yourself.")

// Sharp edge 2: successive states are SMOOTHED, not replaced. alpha defaults to 0.20, so
// the gap after k updates should be step * 0.8^k — an external closed form, not a restatement.
val srng = java.util.Random(77)
val stepping = AdaptiveQuantileWarp(QuantileWarpConfig(historyFrames = 1))
var st = stepping.update(DoubleArray(2000) { srng.nextGaussian() })
val step = 10.0
val gaps = (1..12).map {
    st = stepping.update(DoubleArray(2000) { step + srng.nextGaussian() })
    step - st.inputKnots[4]
}
println("median knot chasing a step to $step (smoothingAlpha=${QuantileWarpConfig().smoothingAlpha}):")
listOf(1, 2, 4, 8, 12).forEach { k ->
    println("  after $k updates gap=${"%.3f".format(gaps[k - 1])}  vs closed form ${step}*0.8^$k=" +
        "${"%.3f".format(step * 0.8.pow(k))}")
}

showSignals(
    "8-knot PCHIP warp" to pchip,
    "exact inverse-normal of the empirical CDF" to exact,
    x = probes,
    channel = "the fitted curve",
    title = "The warp, against the exact answer it is approximating",
    xTitle = "quantile of the fitting sample",
    yTitle = "warped value",
)
val svgs = listOf(
    histogramToSvg(
        buildHistogram(held.toList()), widthPx = 380, heightPx = 220, padTop = 26, decimals = 2,
        title = "held-out input: skew ${"%.1f".format(before.skewness)}",
    ),
    histogramToSvg(
        buildHistogram(warped), widthPx = 380, heightPx = 220, padTop = 26, decimals = 2,
        title = "after the warp: skew ${"%.3f".format(after.skewness)}",
    ),
)
display("distributions", "<div style='display:flex;gap:12px'>" + svgs.joinToString("") { "<div>$it</div>" } + "</div>")
