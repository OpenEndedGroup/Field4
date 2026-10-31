// buildSummaryStats hands back seventeen numbers. Three of them are routinely misread, so
// pin each one as arithmetic rather than trusting the label on the card.
val n = 500 // even, so the median is the mean of the two middle values
val rng = java.util.Random(11)
val signal = (0 until n).map { exp(0.6 * rng.nextGaussian()) }

val buf = SummaryStatsBuffer(maxSize = n)
buf.addAll(signal)
buf.add(Double.NaN) // refused at `add` time, and counted
buf.add(Double.POSITIVE_INFINITY) // likewise: `values` never holds a non-finite number
val s = buf.snapshot()

println("n=${s.count}  seen=${buf.samplesSeen}  refused=${buf.droppedNonFinite}  evicted=${buf.droppedByCapacity}")
println(
    "min=${s.min.f3}  q1=${s.q1.f3}  median=${s.median.f3}" + "  q3=${s.q3.f3}  max=${s.max.f3}",
)

// 1. mean > median is the signature of a right skew, and skewness agrees in sign.
println(
    "mean=${s.mean.f3} vs median=${s.median.f3}: " +
        "mean is larger = ${s.mean > s.median}, and skewness=${s.skewness.f3} > 0 = ${s.skewness > 0.0}",
)

// 2. MAD is the median absolute deviation from the median, UNSCALED. Not sigma, and not
//    half the IQR either. Compute it independently to be sure.
val sorted = signal.sorted()
val medianAgain = (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
val madByHand = signal.map { abs(it - medianAgain) }.sorted().let { (it[n / 2 - 1] + it[n / 2]) / 2.0 }
println(
    "MAD=${s.mad.f4} recomputed by hand=${madByHand.f4}  " +
        "(that is ${(s.mad / s.iqr).f3} x the IQR, and ${(s.mad / s.sampleStdDev).f3} x the sd)",
)

// 3. stddev/variance are the SAMPLE (n-1) forms; skew/kurtosis are the POPULATION (n)
//    forms with no bias correction. Both live on the same card, which is a trap.
val ratio = s.sampleStdDev / s.populationStdDev
println(
    "sampleStdDev/populationStdDev=${ratio.f6} vs sqrt(n/(n-1))=" +
        "${sqrt(n / (n - 1.0)).f6}  -> stddev is the n-1 form",
)

// The guarded small-sample returns: zeros, not NaNs.
println("n=1 sampleVariance=${buildSummaryStats(listOf(4.0)).sampleVariance} (not NaN)")
println("n=2 skewness=${buildSummaryStats(listOf(1.0, 3.0)).skewness} (needs n>2)")
println("n=3 kurtosisExcess=${buildSummaryStats(listOf(1.0, 3.0, 10.0)).kurtosisExcess} (needs n>3)")

// And the one field that is a genuine footgun: cv = sd/mean.
val centred = signal.map { it - s.mean }
println(
    "cv on this signal=${s.coeffOfVariation.f4}; " +
        "re-centred so the mean is ~0, cv=${buildSummaryStats(centred).coeffOfVariation.f1}",
)

display("card", buf.summaryHtml(widthPx = 620, chartHeightPx = 150, decimals = 3))
