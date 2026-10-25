val r = 2
val comparisonRadius = 24

val src =
    Img.generate(1280, 720) { x, y ->
        var v = 0.12f + 0.10f * (y / 720f)
        if (hypot(x - 340.0, y - 300.0) <= 170.0) v = 0.85f
        if (x in 700..1050 && y in 140..430) v = 0.62f
        if (y in 560..585 && x in 100..1179) v = 0.95f
        v
    }

/*
Two independent implementations of the same mean:
  boxBlur          — a sliding running sum, O(1) per pixel at any radius
  convolveSeparable — the box kernel evaluated tap by tap, O(k) per pixel
They share no code, so the difference below is a real check rather than a tautology.
*/
val running = src.boxBlur(r)
val kernelled = src.convolveSeparable(boxKernel1D(r), boxKernel1D(r))

val tRunning = bestTimeMs(repetitions = 5, warmup = 2) { src.boxBlur(r) }
val tKernel = bestTimeMs(repetitions = 5, warmup = 2) { src.convolveSeparable(boxKernel1D(r), boxKernel1D(r)) }

var worst = 0f
for (i in running.data.indices) worst = maxOf(worst, kotlin.math.abs(running.data[i] - kernelled.data[i]))

// Compare two radii on this machine. Running sums do constant work per pixel;
// a separable kernel visits more taps as its radius grows.
val tRunningBig = bestTimeMs(repetitions = 3, warmup = 2) { src.boxBlur(comparisonRadius) }
val tKernelBig = bestTimeMs(repetitions = 3, warmup = 2) {
    src.convolveSeparable(boxKernel1D(comparisonRadius), boxKernel1D(comparisonRadius))
}

showImgGrid(
    "source ${src.width}x${src.height}" to src,
    "boxBlur($r)" to running,
    "box kernel, r = $r" to kernelled,
    "boxBlur($comparisonRadius)" to src.boxBlur(comparisonRadius),
    columns = 2,
    cellSide = 300,
)
println("boxBlur vs the box kernel, ${src.width}x${src.height}, radius $r")
println("  boxBlur (running sum)      ${tRunning.f2} ms")
println("  convolveSeparable(box)     ${tKernel.f2} ms")
println("  max abs difference         ${"%.2e".format(worst)}  (running-sum float drift)")
println("radius $comparisonRadius — compare with radius $r:")
println(
    "  boxBlur($comparisonRadius)                ${tRunningBig.f2} ms " +
        "(${(tRunningBig / tRunning).f2}x the r=$r cost)",
)
println(
    "  convolveSeparable(box $comparisonRadius)  ${tKernelBig.f2} ms " +
        "(${(tKernelBig / tKernel).f2}x the r=$r cost)",
)
println("The vertical running-sum pass advances a row of accumulators together,")
println("keeping adjacent reads contiguous rather than walking down one column.")
println()
val faster = if (tRunning < tKernel) "running sum" else "separable kernel"
println("At radius $r the $faster was faster in this run.")
println("A running sum depends on the preceding output; a short kernel can vectorize")
println("across taps. Compare the timings at both radii before choosing an implementation.")
