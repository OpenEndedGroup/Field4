// buildSummaryStats hands back seventeen numbers. Three of them are routinely misread, so
// pin each one as arithmetic rather than trusting the label on the card.
val rng = java.util.Random(11)
val signal = (0 until 500).map { exp(0.6 * rng.nextGaussian()) }

val buf = SummaryStatsBuffer(maxSize = 500)
buf.addAll(signal)
buf.add(Double.NaN)                 // refused at `add` time, and counted
buf.add(Double.POSITIVE_INFINITY)   // likewise: `values` never holds a non-finite number
val s = buf.snapshot()

println("n=${s.count}  seen=${buf.samplesSeen}  refused=${buf.droppedNonFinite}  evicted=${buf.droppedByCapacity}")
println("min=${"%.3f".format(s.min)}  q1=${"%.3f".format(s.q1)}  median=${"%.3f".format(s.median)}" +
    "  q3=${"%.3f".format(s.q3)}  max=${"%.3f".format(s.max)}")

// 1. mean > median is the signature of a right skew, and skewness agrees in sign.
println("mean=${"%.3f".format(s.mean)} vs median=${"%.3f".format(s.median)}: " +
    "mean is larger = ${s.mean > s.median}, and skewness=${"%.3f".format(s.skewness)} > 0 = ${s.skewness > 0.0}")

// 2. MAD is the median absolute deviation from the median, UNSCALED. Not sigma, and not
//    half the IQR either. Compute it independently to be sure.
val sorted = signal.sorted()
val medianAgain = (sorted[249] + sorted[250]) / 2.0
val madByHand = signal.map { abs(it - medianAgain) }.sorted().let { (it[249] + it[250]) / 2.0 }
println("MAD=${"%.4f".format(s.mad)} recomputed by hand=${"%.4f".format(madByHand)}  " +
    "(that is ${"%.3f".format(s.mad / s.iqr)} x the IQR, and ${"%.3f".format(s.mad / s.sampleStdDev)} x the sd)")

// 3. stddev/variance are the SAMPLE (n-1) forms; skew/kurtosis are the POPULATION (n)
//    forms with no bias correction. Both live on the same card, which is a trap.
val ratio = s.sampleStdDev / s.populationStdDev
println("sampleStdDev/populationStdDev=${"%.6f".format(ratio)} vs sqrt(n/(n-1))=" +
    "${"%.6f".format(sqrt(500.0 / 499.0))}  -> stddev is the n-1 form")

// The guarded small-sample returns: zeros, not NaNs.
println("n=1 sampleVariance=${buildSummaryStats(listOf(4.0)).sampleVariance} (not NaN)")
println("n=2 skewness=${buildSummaryStats(listOf(1.0, 3.0)).skewness} (needs n>2)")
println("n=3 kurtosisExcess=${buildSummaryStats(listOf(1.0, 3.0, 10.0)).kurtosisExcess} (needs n>3)")

// And the one field that is a genuine footgun: cv = sd/mean.
val centred = signal.map { it - s.mean }
println("cv on this signal=${"%.4f".format(s.coeffOfVariation)}; " +
    "re-centred so the mean is ~0, cv=${"%.1f".format(buildSummaryStats(centred).coeffOfVariation)}")

display("card", buf.summaryHtml(widthPx = 620, chartHeightPx = 150, decimals = 3))
