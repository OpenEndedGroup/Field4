// quantilewarp.kt does not measure anything. It fits a monotone 8-knot PCHIP curve from
// the measured quantiles of recent samples onto the quantiles of a target distribution.
val rng = java.util.Random(5)
val fit = DoubleArray(4000) { exp(rng.nextGaussian()) } // log-normal: violently skewed
val held = DoubleArray(4000) { exp(rng.nextGaussian()) } // a disjoint second draw

val warp = AdaptiveQuantileWarp() // target UnitNormal, 8 knots
val state = warp.update(fit)
val warped = held.map { state.evaluate(it) }
val before = buildSummaryStats(held.toList())
val after = buildSummaryStats(warped)

println(
    "The eight default probabilities are NOT symmetric: " +
        QuantileWarpConfig().probabilities.joinToString() +
        " — more knots on the left tail.",
)
println("input knots : ${state.inputKnots.joinToString { it.f3 }}")
println("target knots: ${state.targetKnots.joinToString { it.f3 }}")
println(
    "held-out BEFORE: mean ${before.mean.f3}  sd ${before.sampleStdDev.f3}" +
        "  skew ${before.skewness.f3}  excess kurtosis ${before.kurtosisExcess.f1}",
)
println(
    "held-out AFTER : mean ${after.mean.f4}  sd ${after.sampleStdDev.f4}" +
        "  skew ${after.skewness.f4}  excess kurtosis ${after.kurtosisExcess.f4}",
)
println("  (every number above is OUT OF SAMPLE — the curve was fitted on a different draw)")

/*
An independent oracle for the 8-knot fit: the exact warp onto a unit normal is
Phi^-1(Fhat(v)). Nothing in quantilewarp.kt computes that, so this tests the PCHIP
interpolation rather than restating it.
*/
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
println(
    "worst |8-knot PCHIP - exact Phi^-1(Fhat)| over the 2nd..98th percentile: " +
        "${pchip.indices.maxOf { abs(pchip[it] - exact[it]) }.f4}",
)
println(
    "inverseStandardNormalCdf(0.975)=${inverseStandardNormalCdf(0.975).f6} " +
        "(published 1.959964); (0.001)=${inverseStandardNormalCdf(0.001).f6} (published -3.090232)",
)

// Sharp edge 1: outside the fitted knots, evaluate extrapolates linearly from the
// endpoint tangent, and nothing clamps it.
println(
    "lowest input knot is ${state.inputKnots.first().f3}; " +
        "evaluate(0.0)=${state.evaluate(0.0).f2}, evaluate(-1.0)=${state.evaluate(-1.0).f1} " +
        "— unbounded linear extrapolation, so clamp the input yourself.",
)

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
[1, 2, 4, 8, 12].forEach { k ->
    println(
        "  after $k updates gap=${gaps[k - 1].f3}  vs closed form ${step}*0.8^$k=" +
            "${(step * 0.8.pow(k)).f3}",
    )
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
val svgs = [
    histogramToSvg(
        buildHistogram(held.toList()),
        widthPx = 380,
        heightPx = 220,
        padTop = 26,
        decimals = 2,
        title = "held-out input: skew ${before.skewness.f1}",
    ),
    histogramToSvg(
        buildHistogram(warped),
        widthPx = 380,
        heightPx = 220,
        padTop = 26,
        decimals = 2,
        title = "after the warp: skew ${after.skewness.f3}",
    ),
]
showRow("distributions", svgs)
