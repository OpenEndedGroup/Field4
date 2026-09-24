# Field Document: imaging_vector_kernels

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. boxBlur — running sum vs the box kernel, two independent definitions

<!-- field-box id="imaging_perf_boxblur" index="0" -->

```edn [field-box id="imaging_perf_boxblur" role="properties"]
{"id" "imaging_perf_boxblur" "index" 0 "name" "boxBlur — running sum vs the box kernel, two independent definitions" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "boxBlur — running sum vs the box kernel, two independent definitions" "_tombstone" false "frame" #afield/rect [80.0 540.0 550.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "imaging_perf_boxblur_code_.kt" "lines" 66 "characters" 3090 "sha256" "8450b105d9a44c867ced429eebcb2bae475d48983d163ec9f0f1e2a38f634c98"}]}
```

```kotlin [field-property box="imaging_perf_boxblur" name="code" path="imaging_perf_boxblur_code_.kt"]
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
```

## 2. bilateral — a tabulated range weight instead of 169 exp() per pixel

<!-- field-box id="imaging_perf_bilateral" index="1" -->

### Narrative

From `code`

Smaller again: bilateral is O(r^2) per pixel and this is the expensive box.

```edn [field-box id="imaging_perf_bilateral" role="properties"]
{"id" "imaging_perf_bilateral" "index" 1 "name" "bilateral — a tabulated range weight instead of 169 exp() per pixel" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "bilateral — a tabulated range weight instead of 169 exp() per pixel" "_tombstone" false "frame" #afield/rect [80.0 1000.0 550.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "imaging_perf_bilateral_code_.kt" "lines" 60 "characters" 2816 "sha256" "aa3115536234d6146d6a0103b79aa1e35a4b9ded6141408a2d86125b5a068949"}]}
```

```kotlin [field-property box="imaging_perf_bilateral" name="code" path="imaging_perf_bilateral_code_.kt"]
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
```

## 3. pyrDown — fused decimation vs blur-then-throw-away

<!-- field-box id="imaging_perf_pyrdown" index="2" -->

### Narrative

From `code`

1280x720 rather than the benchmark's 1920x1080, so a replay of this file is quick.
A texture with detail at several scales, so an aliasing bug in the decimation would be
visible as moire rather than hiding in a smooth gradient.

```edn [field-box id="imaging_perf_pyrdown" role="properties"]
{"id" "imaging_perf_pyrdown" "index" 2 "name" "pyrDown — fused decimation vs blur-then-throw-away" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "pyrDown — fused decimation vs blur-then-throw-away" "_tombstone" false "frame" #afield/rect [80.0 80.0 440.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "imaging_perf_pyrdown_code_.kt" "lines" 68 "characters" 2941 "sha256" "361d4bd906a5b8f822652f337dbda5a8a3acbb27ec64ca3da67ac9da0c0c608c"}]}
```

```kotlin [field-property box="imaging_perf_pyrdown" name="code" path="imaging_perf_pyrdown_code_.kt"]
// 1280x720 rather than the benchmark's 1920x1080, so a replay of this file is quick.
// A texture with detail at several scales, so an aliasing bug in the decimation would be
// visible as moire rather than hiding in a smooth gradient.
val src = Img.generate(1280, 720) { x, y ->
    (0.5 +
        0.22 * kotlin.math.sin(x * 0.018) * kotlin.math.cos(y * 0.015) +
        0.16 * kotlin.math.sin((x + y) * 0.009) +
        0.12 * kotlin.math.sin(x * 0.31) * kotlin.math.cos(y * 0.27)
        ).toFloat().coerceIn(0f, 1f)
}

// Best-of-N with a warm-up. The minimum is the least-contended sample, which is the only
// statistic worth quoting on a machine that is also doing other things.
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

val sigma = 1.0

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

val tFused = bestMs(5) { src.pyrDown(sigma) }
val tNaive = bestMs(5) { naiveOf(src) }

// Same answer? The two differ only in float summation order: the fused version runs
// vertical-then-horizontal, the naive one horizontal-then-vertical. Those commute exactly
// in real arithmetic, including at the borders, because the x and y clamps are independent.
var worst = 0f
for (i in fused.data.indices) worst = maxOf(worst, kotlin.math.abs(fused.data[i] - naive.data[i]))

showImgGrid(
    "source ${src.width}x${src.height}" to src,
    "pyrDown(1.0) -> ${fused.width}x${fused.height}" to fused,
    "naive blur-then-subsample" to naive,
    "|fused - naive|, x2000" to fused.zip(naive) { a, b -> kotlin.math.abs(a - b) * 2000f },
    columns = 2,
    cellSide = 300,
)
println("pyrDown ${src.width}x${src.height} -> ${fused.width}x${fused.height}, sigma $sigma")
println("  fused (ships)              ${"%.2f".format(tFused)} ms")
println("  naive blur-then-subsample  ${"%.2f".format(tNaive)} ms")
println("  speed-up                   ${"%.2f".format(tNaive / tFused)}x")
println("  max abs difference         ${"%.2e".format(worst)}  (float summation order only)")
println("Three quarters of the naive version's blur was computed and discarded. The fused")
println("one filters only the rows and columns that survive; see ImgSimd.splitByPhase for")
println("why halving the columns does not need a gather.")
```

