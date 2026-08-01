val src = Img.generate(1280, 720) { x, y ->
    var v = 0.12f + 0.10f * (y / 720f)
    if (hypot(x - 340.0, y - 300.0) <= 170.0) v = 0.85f
    if (x in 700..1050 && y in 140..430) v = 0.62f
    if (y in 560..585 && x in 100..1179) v = 0.95f
    v
}

val bestMs: (Int, () -> Unit) -> Double = { reps, block ->
    repeat(2) { block() }
    var best = Double.MAX_VALUE
    repeat(reps) {
        val t0 = System.nanoTime()
        block()
        val ms = (System.nanoTime() - t0) / 1e6
        if (ms < best) best = ms
    }
    best
}

val r = 2

// Two genuinely independent implementations of the same mean:
//   boxBlur          — a sliding running sum, O(1) per pixel at any radius
//   convolveSeparable — the box kernel evaluated tap by tap, O(k) per pixel
// They share no code, so the difference below is a real check rather than a tautology.
val running = src.boxBlur(r)
val kernelled = src.convolveSeparable(boxKernel1D(r), boxKernel1D(r))

val tRunning = bestMs(5) { src.boxBlur(r) }
val tKernel = bestMs(5) { src.convolveSeparable(boxKernel1D(r), boxKernel1D(r)) }

var worst = 0f
for (i in running.data.indices) worst = maxOf(worst, kotlin.math.abs(running.data[i] - kernelled.data[i]))

// The radius-independence is the whole claim of a running sum, so show it: r=2 and r=24
// should cost the same, while the kernel version's cost grows with r.
val tRunningBig = bestMs(3) { src.boxBlur(24) }
val tKernelBig = bestMs(3) { src.convolveSeparable(boxKernel1D(24), boxKernel1D(24)) }

showImgGrid(
    "source ${src.width}x${src.height}" to src,
    "boxBlur($r)" to running,
    "convolveSeparable(boxKernel1D($r))" to kernelled,
    "boxBlur(24) — same cost as r=2" to src.boxBlur(24),
    columns = 2,
    cellSide = 300,
)
println("boxBlur vs the box kernel, ${src.width}x${src.height}, radius $r")
println("  boxBlur (running sum)      ${"%.2f".format(tRunning)} ms")
println("  convolveSeparable(box)     ${"%.2f".format(tKernel)} ms")
println("  max abs difference         ${"%.2e".format(worst)}  (running-sum float drift)")
println("radius 24 — the O(1) claim:")
println("  boxBlur(24)                ${"%.2f".format(tRunningBig)} ms " +
    "(${"%.2f".format(tRunningBig / tRunning)}x the r=$r cost)")
println("  convolveSeparable(box 24)  ${"%.2f".format(tKernelBig)} ms " +
    "(${"%.2f".format(tKernelBig / tKernel)}x the r=$r cost)")
println("The vertical pass used to walk one column at a time — stride width*channels down")
println("a multi-megabyte array, a cache miss per sample. It now advances a whole row of")
println("accumulators together, which is both sequential and vectorized.")
println()
println("Read those two blocks together, because they say something the O(1)-beats-O(k)")
println("story alone would get wrong: at radius $r the KERNEL is the faster of the two, and")
println("only past a crossover of a few pixels does the running sum win. A running sum is")
println("serial by nature — each output waits on the previous one — while 2*$r+1 taps of")
println("separable convolution vectorize freely. O(1) is about the asymptote, not radius 2.")
