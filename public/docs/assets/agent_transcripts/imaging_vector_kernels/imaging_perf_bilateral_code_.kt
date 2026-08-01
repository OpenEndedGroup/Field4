// Smaller again: bilateral is O(r^2) per pixel and this is the expensive box.
val clean = Img.generate(640, 480) { x, y ->
    var v = 0.15f + 0.10f * (y / 480f)
    if (hypot(x - 190.0, y - 210.0) <= 120.0) v = 0.85f
    if (x in 360..560 && y in 90..300) v = 0.62f
    v
}
val rng = kotlin.random.Random(11)
val noisy = clean.map { (it + (rng.nextFloat() - 0.5f) * 0.10f).coerceIn(0f, 1f) }

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

val sigmaSpace = 3.0
val sigmaRange = 0.12
val bil = noisy.bilateral(sigmaSpace, sigmaRange)
val gauss = noisy.gaussianBlur(sigmaSpace)

val tBil = bestMs(3) { noisy.bilateral(sigmaSpace, sigmaRange) }
val tGauss = bestMs(3) { noisy.gaussianBlur(sigmaSpace) }

// Edge contrast across the disc boundary, and flatness in a flat patch. The bilateral
// should win the first and roughly tie the second — that is what "edge-preserving" means,
// stated as two numbers rather than left to the eye.
// The disc boundary at y=210 is x=310. Probe 3 px either side of it — about one
// sigmaSpace — because that is where a Gaussian visibly smears and a bilateral does not.
// Probing 30 px out would show both "preserving" the step and prove nothing.
val edgeOf: (Img) -> Float = { img -> img.at(307, 210) - img.at(313, 210) }
val flatOf: (Img) -> Float = { img -> img.crop(430, 340, 120, 100).stdDev() }

showImgGrid(
    "clean" to clean,
    "+ uniform noise" to noisy,
    "bilateral($sigmaSpace, $sigmaRange) — edges kept" to bil,
    "gaussianBlur($sigmaSpace) — edges gone too" to gauss,
    columns = 2,
    cellSide = 300,
)
val win = 2 * kotlin.math.max(1, kotlin.math.ceil(sigmaSpace * 2.0).toInt()) + 1
println("bilateral ${noisy.width}x${noisy.height}, window ${win}x$win = ${win * win} taps per pixel")
println("  bilateral                  ${"%.2f".format(tBil)} ms")
println("  gaussianBlur (same sigma)  ${"%.2f".format(tGauss)} ms  — separable, so far cheaper by right")
println("edge step across the disc boundary:")
println("  bilateral    ${"%.4f".format(edgeOf(bil))}")
println("  gaussian     ${"%.4f".format(edgeOf(gauss))}   (smeared)")
println("noise left in a flat patch (stdDev), was ${"%.4f".format(flatOf(noisy))}:")
println("  bilateral    ${"%.4f".format(flatOf(bil))}")
println("  gaussian     ${"%.4f".format(flatOf(gauss))}")
println("Every one of those ${win * win} taps used to call Math.exp, because the range weight")
println("depends on the sample. It is now a 4096-entry table indexed by SQUARED intensity")
println("distance — no abs, no sqrt — read with linear interpolation, worth ~2e-6 of weight.")
