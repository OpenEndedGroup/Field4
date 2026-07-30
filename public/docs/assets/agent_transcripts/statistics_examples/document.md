# Field Document: statistics_examples

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 5 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. Histograms, and the rule that picks the bins

<!-- field-box id="stats_histogram" index="0" -->

### Narrative

From `code`

buildHistogram takes no bin-count argument. It picks the SMALLEST of the
Freedman-Diaconis, Scott and Sturges widths, floored so the count cannot exceed 2000.
Re-derive the whole decision from the public stats and predict the bin count.

```edn [field-box id="stats_histogram" role="properties"]
{"id" "stats_histogram" "index" 0 "name" "Histograms, and the rule that picks the bins" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Histograms, and the rule that picks the bins" "_tombstone" false "frame" #afield/rect [80.0 470.0 820.0 360.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "stats_histogram_code_.kt" "lines" 51 "characters" 3109 "sha256" "c7d8a9ce1f761e78f059793ea6779f84a7e227ae5875dde5b4cb42e082645e48"}]}
```

```kotlin [field-property box="stats_histogram" name="code" path="stats_histogram_code_.kt"]
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
```

## 2. Six questions, six answers — and is the answer big?

<!-- field-box id="stats_dependence" index="1" -->

### Narrative

From `code`

The whole reason StatsDependence has six coefficients instead of one. Six shapes, every
coefficient on each, and the disagreements are the point.

```edn [field-box id="stats_dependence" role="properties"]
{"id" "stats_dependence" "index" 1 "name" "Six questions, six answers — and is the answer big?" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Six questions, six answers — and is the answer big?" "_tombstone" false "frame" #afield/rect [960.0 80.0 860.0 470.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "stats_dependence_code_.kt" "lines" 84 "characters" 4404 "sha256" "b28314eabc8d255382241f0fbb8b295a8a4c04d8cf5df11d14310ab468765fb2"}]}
```

```kotlin [field-property box="stats_dependence" name="code" path="stats_dependence_code_.kt"]
// The whole reason StatsDependence has six coefficients instead of one. Six shapes, every
// coefficient on each, and the disagreements are the point.
val n = 400
val rng = java.util.Random(7)
val x = DoubleArray(n) { it / (n - 1.0) * 2.0 - 1.0 }
// One noise draw, reused, so the "independent" panel is literally the noise the others
// are built out of.
val noise = DoubleArray(n) { rng.nextGaussian() }

val cases = listOf(
    "linear" to DoubleArray(n) { 0.8 * x[it] + 0.25 * noise[it] },
    "monotone, curved" to DoubleArray(n) { exp(3.0 * x[it]) },
    "parabola" to DoubleArray(n) { x[it] * x[it] },
    "sine, 1.5 cycles" to DoubleArray(n) { sin(3.0 * PI * x[it]) },
    "same mean, growing spread" to DoubleArray(n) { (0.05 + abs(x[it])) * noise[it] },
    "independent" to DoubleArray(n) { noise[it] },
)

println("shape                        r      rho     tau      xi     dCor    MIC~")
val panels = cases.map { (label, y) ->
    val r = StatsDependence.pearson(x, y)
    val rho = StatsDependence.spearman(x, y)
    val tau = StatsDependence.kendallTauB(x, y)
    val xi = StatsDependence.xi(x, y)
    val dcor = StatsDependence.distanceCorrelation(x, y)
    val mic = StatsDependence.micTicNaive(x, y).mic
    println("%-26s %6.3f  %6.3f  %6.3f  %6.3f  %6.3f  %6.3f".format(label, r, rho, tau, xi, dcor, mic))
    val lo = y.min()
    val span = (y.max() - lo).takeIf { it > 1e-12 } ?: 1.0
    // SVG y grows downward, so flip to read the shapes the way the formulas are written.
    val cloud = pointCloudFLine(
        (0 until n).map { i -> vec((x[i] + 1.0) / 2.0, 1.0 - (y[i] - lo) / span) },
        color = vec(0.48, 0.85, 1.0, 0.8),
        pointSize = 1.1,
    )
    val caption = "r=${"%.3f".format(r)} rho=${"%.3f".format(rho)} tau=${"%.3f".format(tau)}" +
        " xi=${"%.3f".format(xi)} dCor=${"%.3f".format(dcor)} MIC~=${"%.3f".format(mic)}"
    "$label\n$caption" to cloud
}

val parabola = cases[2].second
println("xi is asymmetric on purpose: xi(x, x^2)=${"%.3f".format(StatsDependence.xi(x, parabola))} " +
    "but xi(x^2, x)=${"%.3f".format(StatsDependence.xi(parabola, x))} — x is NOT a function of x^2.")
println("dCor is 1 only for a STRAIGHT line, not for any perfect relationship: " +
    "dCor(x, 3x+1)=${"%.6f".format(StatsDependence.distanceCorrelation(x, DoubleArray(n) { 3.0 * x[it] + 1.0 }))}, " +
    "dCor(x, -2x+5)=${"%.6f".format(StatsDependence.distanceCorrelation(x, DoubleArray(n) { -2.0 * x[it] + 5.0 }))}, " +
    "but dCor on the parabola is only ${"%.3f".format(StatsDependence.distanceCorrelation(x, parabola))}.")

// There are no p-values anywhere in this family, and dCor under independence is biased
// well above zero. So measure the null: break the pairing, keep both marginals.
val y = cases[4].second
val observedDcor = StatsDependence.distanceCorrelation(x, y)
val observedXi = StatsDependence.xi(x, y)
val prng = java.util.Random(31)
val reps = 400
val order = (0 until n).toMutableList()
val nullDcor = DoubleArray(reps)
val nullXi = DoubleArray(reps)
for (rep in 0 until reps) {
    order.shuffle(prng)
    val shuffled = DoubleArray(n) { y[order[it]] }
    nullDcor[rep] = StatsDependence.distanceCorrelation(x, shuffled)
    nullXi[rep] = StatsDependence.xi(x, shuffled)
}
val sortedD = nullDcor.sortedArray()
val sortedXi = nullXi.sortedArray()
println("growing-spread case: observed dCor=${"%.4f".format(observedDcor)} against a null of " +
    "mean ${"%.4f".format(sortedD.average())}, 95th pct ${"%.4f".format(sortedD[(reps * 0.95).toInt()])}, " +
    "max ${"%.4f".format(sortedD.last())} — re-pairings reaching it: ${sortedD.count { it >= observedDcor }}/$reps")
println("                     observed xi  =${"%.4f".format(observedXi)} against a null 95th pct of " +
    "${"%.4f".format(sortedXi[(reps * 0.95).toInt()])} — re-pairings reaching it: " +
    "${sortedXi.count { it >= observedXi }}/$reps. xi correctly declines to call this functional.")

// Two columns rather than three: showPlaneGrid's grid columns are `max-content`, so a long
// caption widens the card past `cellWidth` instead of wrapping inside it.
val window = Rect(0.0, 0.0, 1.0, 1.0)
val frame = window.toFLine(color = vec(0.35, 0.44, 0.56, 0.5), thickness = 1.0)
showPlaneGrid(
    *panels.map { (label, cloud) -> label to listOf(frame, cloud) }.toTypedArray(),
    columns = 2,
    cellWidth = 380,
    cellHeight = 240,
    window = window,
)
```

## 3. Summary stats, and exactly how boring they are

<!-- field-box id="stats_summary" index="2" -->

### Narrative

From `code`

buildSummaryStats hands back seventeen numbers. Three of them are routinely misread, so
pin each one as arithmetic rather than trusting the label on the card.

```edn [field-box id="stats_summary" role="properties"]
{"id" "stats_summary" "index" 2 "name" "Summary stats, and exactly how boring they are" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Summary stats, and exactly how boring they are" "_tombstone" false "frame" #afield/rect [80.0 80.0 820.0 360.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "stats_summary_code_.kt" "lines" 44 "characters" 2671 "sha256" "6fa1646d64cbf5125e30b537bfdd585cbf472a35f307eb9a83fe63c37e0f5ac0"}]}
```

```kotlin [field-property box="stats_summary" name="code" path="stats_summary_code_.kt"]
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
```

## 4. Rolling dependence across a regime change

<!-- field-box id="stats_rolling" index="3" -->

### Narrative

From `code`

A single coefficient over a whole recording answers the wrong question when the
relationship comes and goes. RollingDistanceCorrelation maintains its sums
incrementally, O(w) per push; RollingXiCorrelation just recomputes, O(w log w).

```edn [field-box id="stats_rolling" role="properties"]
{"id" "stats_rolling" "index" 3 "name" "Rolling dependence across a regime change" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Rolling dependence across a regime change" "_tombstone" false "frame" #afield/rect [960.0 580.0 860.0 400.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "stats_rolling_code_.kt" "lines" 73 "characters" 3665 "sha256" "f70afdc082289973f056a4530565a740207ea48bb01910c3c19ba05bccdcd0f9"}]}
```

```kotlin [field-property box="stats_rolling" name="code" path="stats_rolling_code_.kt"]
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
```

## 5. Reshaping a distribution with an 8-knot warp

<!-- field-box id="stats_warp" index="4" -->

### Narrative

From `code`

quantilewarp.kt does not measure anything. It fits a monotone 8-knot PCHIP curve from
the measured quantiles of recent samples onto the quantiles of a target distribution.

```edn [field-box id="stats_warp" role="properties"]
{"id" "stats_warp" "index" 4 "name" "Reshaping a distribution with an 8-knot warp" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Reshaping a distribution with an 8-knot warp" "_tombstone" false "frame" #afield/rect [80.0 860.0 820.0 400.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "stats_warp_code_.kt" "lines" 86 "characters" 4601 "sha256" "f928c84377d5711a1a6b1fe3cb15be49c1b469aa7413794aad1116da8615b846"}]}
```

```kotlin [field-property box="stats_warp" name="code" path="stats_warp_code_.kt"]
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
```

