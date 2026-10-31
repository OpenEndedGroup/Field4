# Field Document: imaging_vector_kernels

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. boxBlur — running sum vs the box kernel, two independent definitions

<!-- field-box id="imaging_perf_boxblur" index="0" -->

```edn [field-box id="imaging_perf_boxblur" role="properties"]
{"id" "imaging_perf_boxblur" "index" 0 "name" "boxBlur — running sum vs the box kernel, two independent definitions" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "boxBlur — running sum vs the box kernel, two independent definitions" "_tombstone" false "frame" #afield/rect [80.0 540.0 550.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "imaging_perf_boxblur_code_.kt" "lines" 62 "characters" 2748 "sha256" "002fe5795affb9320b535fe45c1043a52a8aa9f6f1f0f1282d946aa27f19ff56"}]}
```

```kotlin [field-property box="imaging_perf_boxblur" name="code" path="imaging_perf_boxblur_code_.kt"]
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
```

## 2. bilateral — a tabulated range weight instead of 169 exp() per pixel

<!-- field-box id="imaging_perf_bilateral" index="1" -->

```edn [field-box id="imaging_perf_bilateral" role="properties"]
{"id" "imaging_perf_bilateral" "index" 1 "name" "bilateral — a tabulated range weight instead of 169 exp() per pixel" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "bilateral — a tabulated range weight instead of 169 exp() per pixel" "_tombstone" false "frame" #afield/rect [80.0 1000.0 550.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "imaging_perf_bilateral_code_.kt" "lines" 52 "characters" 2468 "sha256" "bf515991330e39258b7be4007d680721dad4eb982b623beb932b4c7fc869dadf"}]}
```

```kotlin [field-property box="imaging_perf_bilateral" name="code" path="imaging_perf_bilateral_code_.kt"]
val sigmaSpace = 3.0
val sigmaRange = 0.12

// Smaller again: bilateral is O(r^2) per pixel and this is the expensive box.
val clean =
    Img.generate(640, 480) { x, y ->
        var v = 0.15f + 0.10f * (y / 480f)
        if (hypot(x - 190.0, y - 210.0) <= 120.0) v = 0.85f
        if (x in 360..560 && y in 90..300) v = 0.62f
        v
    }
val rng = kotlin.random.Random(11)
val noisy = clean.map { (it + (rng.nextFloat() - 0.5f) * 0.10f).coerceIn(0f, 1f) }

val bil = noisy.bilateral(sigmaSpace, sigmaRange)
val gauss = noisy.gaussianBlur(sigmaSpace)

val tBil = bestTimeMs(repetitions = 3, warmup = 2) { noisy.bilateral(sigmaSpace, sigmaRange) }
val tGauss = bestTimeMs(repetitions = 3, warmup = 2) { noisy.gaussianBlur(sigmaSpace) }

/*
Edge contrast across the disc boundary, and flatness in a flat patch. The bilateral
should win the first and roughly tie the second — that is what "edge-preserving" means,
stated as two numbers rather than left to the eye.
The disc boundary at y=210 is x=310. Probe 3 px either side of it — about one
sigmaSpace — because that is where a Gaussian visibly smears and a bilateral does not.
Probing 30 px out would show both "preserving" the step and prove nothing.
*/
val edgeOf: (Img) -> Float = { img -> img.at(307, 210) - img.at(313, 210) }
val flatOf: (Img) -> Float = { img -> img.crop(430, 340, 120, 100).stdDev() }

showImgGrid(
    "clean" to clean,
    "+ uniform noise" to noisy,
    "bilateral — edges kept" to bil,
    "gaussianBlur — edges lost" to gauss,
    columns = 2,
    cellSide = 300,
)
val win = 2 * kotlin.math.max(1, kotlin.math.ceil(sigmaSpace * 2.0).toInt()) + 1
println("bilateral ${noisy.width}x${noisy.height}, window ${win}x$win = ${win * win} taps per pixel")
println("  bilateral                  ${tBil.f2} ms")
println("  gaussianBlur (same sigma)  ${tGauss.f2} ms  — separable kernel")
println("edge step across the disc boundary:")
println("  bilateral    ${(edgeOf(bil)).f4}")
println("  gaussian     ${(edgeOf(gauss)).f4}   (smeared)")
println("noise left in a flat patch (stdDev), was ${(flatOf(noisy)).f4}:")
println("  bilateral    ${(flatOf(bil)).f4}")
println("  gaussian     ${(flatOf(gauss)).f4}")
println("Every one of those ${win * win} taps used to call Math.exp, because the range weight")
println("depends on the sample. It is now a 4096-entry table indexed by SQUARED intensity")
println("distance — no abs, no sqrt — read with linear interpolation, worth ~2e-6 of weight.")
```

## 3. pyrDown — fused decimation vs blur-then-throw-away

<!-- field-box id="imaging_perf_pyrdown" index="2" -->

```edn [field-box id="imaging_perf_pyrdown" role="properties"]
{"id" "imaging_perf_pyrdown" "index" 2 "name" "pyrDown — fused decimation vs blur-then-throw-away" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "pyrDown — fused decimation vs blur-then-throw-away" "_tombstone" false "frame" #afield/rect [80.0 80.0 440.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "imaging_perf_pyrdown_code_.kt" "lines" 60 "characters" 2555 "sha256" "1ffe5dc11126f6533a2041ee02930bccd5ea2597b7e481d9cd2f030b4c6bb47e"}]}
```

```kotlin [field-property box="imaging_perf_pyrdown" name="code" path="imaging_perf_pyrdown_code_.kt"]
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
```

