val sigma = 1.0

/*
1280x720 rather than the benchmark's 1920x1080, so a replay of this file is quick.
A texture with detail at several scales, so an aliasing bug in the decimation would be
visible as moire rather than hiding in a smooth gradient.
*/
val src =
    Img.generate(1280, 720) { x, y ->
        (0.5 +
                0.22 * kotlin.math.sin(x * 0.018) * kotlin.math.cos(y * 0.015) +
                0.16 * kotlin.math.sin((x + y) * 0.009) +
                0.12 * kotlin.math.sin(x * 0.31) * kotlin.math.cos(y * 0.27))
            .toFloat()
            .coerceIn(0f, 1f)
    }

// The naive definition, longhand: blur every pixel at full resolution, then keep one in
// four. This is exactly what pyrDown used to do internally.
val naiveOf: (Img) -> Img = { img ->
    val blurred = img.gaussianBlur(sigma)
    val nw = img.width / 2
    val nh = img.height / 2
    val out = Img.zeros(nw, nh, img.channels)
    for (y in 0 until nh) for (x in 0 until nw) for (c in 0 until img.channels) {
        out.set(x, y, c, blurred.at(x * 2, y * 2, c))
    }
    out
}

val fused = src.pyrDown(sigma)
val naive = naiveOf(src)

val tFused = bestTimeMs(repetitions = 5, warmup = 2) { src.pyrDown(sigma) }
val tNaive = bestTimeMs(repetitions = 5, warmup = 2) { naiveOf(src) }

/*
Same answer? The two differ only in float summation order: the fused version runs
vertical-then-horizontal, the naive one horizontal-then-vertical. Those commute exactly
in real arithmetic, including at the borders, because the x and y clamps are independent.
*/
var worst = 0f
for (i in fused.data.indices) worst = maxOf(worst, kotlin.math.abs(fused.data[i] - naive.data[i]))

showImgGrid(
    "source ${src.width}x${src.height}" to src,
    "pyrDown -> ${fused.width}x${fused.height}" to fused,
    "blur, then subsample" to naive,
    "|fused - naive|, x2000" to fused.zip(naive) { a, b -> kotlin.math.abs(a - b) * 2000f },
    columns = 2,
    cellSide = 300,
)
println("pyrDown ${src.width}x${src.height} -> ${fused.width}x${fused.height}, sigma $sigma")
println("  fused (ships)              ${tFused.f2} ms")
println("  naive blur-then-subsample  ${tNaive.f2} ms")
println("  speed-up                   ${(tNaive / tFused).f2}x")
println("  max abs difference         ${"%.2e".format(worst)}  (float summation order only)")
println("Three quarters of the naive version's blur was computed and discarded. The fused")
println("one filters only the rows and columns that survive; see ImgSimd.splitByPhase for")
println("why halving the columns does not need a gather.")
