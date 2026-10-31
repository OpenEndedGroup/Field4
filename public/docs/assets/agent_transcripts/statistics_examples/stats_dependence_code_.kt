// The whole reason StatsDependence has six coefficients instead of one. Six shapes, every
// coefficient on each, printed as one table so the disagreements line up in a row.
val n = 400
val rng = java.util.Random(7)
val x = DoubleArray(n) { it / (n - 1.0) * 2.0 - 1.0 }
// One noise draw, reused, so the "independent" panel is literally the noise the others
// are built out of.
val noise = DoubleArray(n) { rng.nextGaussian() }

val cases = [
    "linear" to DoubleArray(n) { 0.8 * x[it] + 0.25 * noise[it] },
    "monotone, curved" to DoubleArray(n) { exp(3.0 * x[it]) },
    "parabola" to DoubleArray(n) { x[it] * x[it] },
    "sine, 3 cycles over x = -1..1" to DoubleArray(n) { sin(3.0 * PI * x[it]) },
    "same mean, growing spread" to DoubleArray(n) { (0.05 + abs(x[it])) * noise[it] },
    "independent" to DoubleArray(n) { noise[it] },
]

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
    val caption = "r=${r.f3} rho=${rho.f3} tau=${tau.f3}" + " xi=${xi.f3} dCor=${dcor.f3} MIC~=${mic.f3}"
    "$label\n$caption" to cloud
}

val parabola = cases[2].second
println(
    "xi is asymmetric on purpose: xi(x, x^2)=${StatsDependence.xi(x, parabola).f3} " +
        "but xi(x^2, x)=${StatsDependence.xi(parabola, x).f3} — x is NOT a function of x^2.",
)
println(
    "dCor is 1 only for a STRAIGHT line, not for any perfect relationship: " +
        "dCor(x, 3x+1)=${StatsDependence.distanceCorrelation(x, DoubleArray(n) { 3.0 * x[it] + 1.0 }).f6}, " +
        "dCor(x, -2x+5)=${StatsDependence.distanceCorrelation(x, DoubleArray(n) { -2.0 * x[it] + 5.0 }).f6}, " +
        "but dCor on the parabola is only ${StatsDependence.distanceCorrelation(x, parabola).f3}.",
)

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
println(
    "growing-spread case: observed dCor=${observedDcor.f4} against a null of " +
        "mean ${sortedD.average().f4}, 95th pct ${sortedD[(reps * 0.95).toInt()].f4}, " +
        "max ${sortedD.last().f4} — re-pairings reaching it: ${sortedD.count { it >= observedDcor }}/$reps",
)
println(
    "                     observed xi  =${observedXi.f4} against a null 95th pct of " +
        "${sortedXi[(reps * 0.95).toInt()].f4} — re-pairings reaching it: " +
        "${sortedXi.count { it >= observedXi }}/$reps. xi correctly declines to call this functional.",
)

// Two columns rather than three: showPlaneGrid's grid columns are `max-content`, so a long
// caption widens the card past `cellWidth` instead of wrapping inside it.
val window = Rect(0.0, 0.0, 1.0, 1.0)
val frame = window.toFLine(color = vec(0.35, 0.44, 0.56, 0.5), thickness = 1.0)
showPlaneGrid(
    *panels.map { (label, cloud) -> label to [frame, cloud] }.toTypedArray(),
    columns = 2,
    cellWidth = 380,
    cellHeight = 240,
    window = window,
)
