# Field Document: imaging_cv_examples

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 6 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. Img basics — build, resample, luma, HSV, threshold

<!-- field-box id="imaging_basics" index="0" -->

### Narrative

From `code`

An Img is width x height x channels floats, interleaved, origin top-left.
Build one straight from a function of (x, y) — the usual way to make a test image.

```edn [field-box id="imaging_basics" role="properties"]
{"id" "imaging_basics" "index" 0 "name" "Img basics — build, resample, luma, HSV, threshold" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Img basics — build, resample, luma, HSV, threshold" "_tombstone" false "frame" #afield/rect [80.0 80.0 900.0 380.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "imaging_basics_code_.kt" "lines" 32 "characters" 1493 "sha256" "bc013d971fa2fe0a30abdf3cb2a643ae49bf2518648741e8f247c5aaa4226e61"}]}
```

```kotlin [field-property box="imaging_basics" name="code" path="imaging_basics_code_.kt"]
// An Img is width x height x channels floats, interleaved, origin top-left.
// Build one straight from a function of (x, y) — the usual way to make a test image.
val card = Img.generateRGB(240, 180) { x, y ->
    val u = x / 239f
    val v = y / 179f
    val disc = if (hypot(x - 60.0, y - 60.0) <= 34.0) 1f else 0f
    val bar = if (y in 130..142 && x in 20..219) 1f else 0f
    Triple(
        (0.15f + 0.7f * u * (1f - disc) + disc * 0.9f + bar).coerceIn(0f, 1f),
        (0.15f + 0.6f * v + disc * 0.25f + bar).coerceIn(0f, 1f),
        (0.55f - 0.4f * u + disc * 0.1f + bar).coerceIn(0f, 1f)
    )
}

val gray = card.luma()                 // BT.709 perceptual grayscale
val hsv = card.rgbToHsv()              // h, s, v each in 0..1
val small = card.resize(60, 45)        // area-averaged (anti-aliased) downsample
val backUp = small.resize(240, 180)    // bilinear upsample: the detail is really gone
val t = gray.otsuThreshold()           // computed, not guessed

showImgGrid(
    "source RGB (${card.width}x${card.height})" to card,
    "luma()" to gray,
    "hue channel" to hsv.channel(0),
    "saturation channel" to hsv.channel(1),
    "resize(60,45) then back up" to backUp,
    "thresholdOtsu() @ ${"%.3f".format(t)}" to gray.threshold(t),
    columns = 3,
    cellSide = 260,
)
println("Img ${card.width}x${card.height} x${card.channels} ch, mean ${"%.3f".format(card.mean())}, " +
    "otsu ${"%.3f".format(t)}, foreground ${gray.threshold(t).data.count { it > 0.5f }} px")
```

## 2. filters — gaussian / median / bilateral / unsharp

<!-- field-box id="imaging_filters" index="1" -->

### Narrative

From `code`

A clean synthetic source, and the same source with 4% salt-and-pepper corruption.

```edn [field-box id="imaging_filters" role="properties"]
{"id" "imaging_filters" "index" 1 "name" "filters — gaussian / median / bilateral / unsharp" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "filters — gaussian / median / bilateral / unsharp" "_tombstone" false "frame" #afield/rect [80.0 500.0 900.0 380.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "imaging_filters_code_.kt" "lines" 35 "characters" 1280 "sha256" "5de046aa6b7a34bff89640fe5693dd2b6161002c7e48b34259142188c1d9ed4c"}]}
```

```kotlin [field-property box="imaging_filters" name="code" path="imaging_filters_code_.kt"]
// A clean synthetic source, and the same source with 4% salt-and-pepper corruption.
val clean = Img.generate(240, 180) { x, y ->
    var v = 0.12f + 0.10f * (y / 180f)
    if (hypot(x - 60.0, y - 70.0) <= 34.0) v = 0.85f
    if (x in 120..185 && y in 30..95) v = 0.65f
    if (y in 135..142 && x in 20..219) v = 0.95f
    v
}
val rng = kotlin.random.Random(11)
val noisy = clean.copy()
repeat((noisy.pixelCount * 0.04).toInt()) {
    noisy.data[rng.nextInt(noisy.pixelCount)] = if (rng.nextBoolean()) 0f else 1f
}

val gauss = noisy.gaussianBlur(2.0)
val med = noisy.median(2)
val bil = noisy.bilateral(sigmaSpace = 3.0, sigmaRange = 0.12)
val sharp = clean.gaussianBlur(2.0).unsharpMask(sigma = 2.0, amount = 1.4)

showImgGrid(
    "clean source" to clean,
    "+ 4% salt-and-pepper" to noisy,
    "gaussianBlur(2.0)" to gauss,
    "median(2)" to med,
    "bilateral(3.0, 0.12)" to bil,
    "blur then unsharpMask(2.0, 1.4)" to sharp,
    columns = 3,
    cellSide = 260,
)
// The median is the right tool for impulse noise; the numbers say so, not the picture.
println(
    "residual vs clean — gaussian ${"%.4f".format(gauss.meanAbsDiff(clean))}, " +
        "median ${"%.4f".format(med.meanAbsDiff(clean))}, " +
        "bilateral ${"%.4f".format(bil.meanAbsDiff(clean))}"
)
```

## 3. features — Harris / FAST corners and Hough lines

<!-- field-box id="imaging_features" index="2" -->

### Narrative

From `code`

A square whose corners are at exactly known coordinates.

```edn [field-box id="imaging_features" role="properties"]
{"id" "imaging_features" "index" 2 "name" "features — Harris / FAST corners and Hough lines" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "features — Harris / FAST corners and Hough lines" "_tombstone" false "frame" #afield/rect [80.0 1340.0 900.0 380.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "imaging_features_code_.kt" "lines" 37 "characters" 1921 "sha256" "35de8510db07eb5aee71b1120957709b7cf9fe73205d71139654bfb7ab537f00"}]}
```

```kotlin [field-property box="imaging_features" name="code" path="imaging_features_code_.kt"]
// A square whose corners are at exactly known coordinates.
val square = Img.generate(200, 200) { x, y -> if (x in 50..149 && y in 50..149) 0.92f else 0.08f }
val harris = square.harrisCorners(threshold = 0.05, minDistance = 12, maxCount = 20)
val fast = square.fastCorners(threshold = 0.2, minDistance = 12, maxCount = 20)

val truth = listOf(50.0 to 50.0, 149.0 to 50.0, 50.0 to 149.0, 149.0 to 149.0)
val worst = truth.maxOf { (ex, ey) -> harris.minOf { hypot(it.x - ex, it.y - ey) } }

// Three lines at exactly known (rho, theta), plus a decoy ring Hough must not fit.
val lineMask = Img.zeros(200, 200, 1)
for (x in 10 until 190) lineMask.set(x, 40, 0, 1f)          // rho = 40,  theta = pi/2
for (y in 10 until 190) lineMask.set(150, y, 0, 1f)         // rho = 150, theta = 0
for (t in 0 until 240) {
    val x = 20 + t * 0.6; val y = 60 + t * 0.6
    if (x < 199 && y < 199) lineMask.set(x.toInt(), y.toInt(), 0, 1f)
}
for (i in 0 until 360) {
    val a = 2.0 * PI * i / 360
    val x = (60 + 22 * kotlin.math.cos(a)).toInt()
    val y = (150 + 22 * kotlin.math.sin(a)).toInt()
    if (x in 0..199 && y in 0..199) lineMask.set(x, y, 0, 1f)
}
val lines = houghLines(lineMask, thetaSteps = 360, threshold = 100, maxLines = 5)

showImgGrid(
    "harrisResponse (signed, coolwarm)" to square.harrisResponse().colormapDiverging("coolwarm"),
    "shiTomasiResponse (viridis)" to square.shiTomasiResponse().normalized().colormap("viridis"),
    "edge mask: 3 lines + a decoy ring" to lineMask,
    columns = 3,
    cellSide = 260,
)
println("harris ${harris.size} corners, worst ${"%.2f".format(worst)} px from the true square corners")
println("fast ${fast.size} corners at " + fast.sortedBy { it.y * 1000 + it.x }
    .joinToString(", ") { "(${it.x.toInt()},${it.y.toInt()})" })
println("hough " + lines.joinToString(", ") {
    "(rho=${"%.0f".format(it.rho)}, theta=${"%.2f".format(it.theta)}, votes=${it.votes})"
})
```

## 4. morphology, connected components, distance transform

<!-- field-box id="imaging_morphology" index="3" -->

### Narrative

From `code`

Three disjoint discs of known radius, then noise on top of them.

```edn [field-box id="imaging_morphology" role="properties"]
{"id" "imaging_morphology" "index" 3 "name" "morphology, connected components, distance transform" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "morphology, connected components, distance transform" "_tombstone" false "frame" #afield/rect [80.0 1760.0 900.0 380.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "imaging_morphology_code_.kt" "lines" 32 "characters" 1487 "sha256" "91394c51767999cc581df60758ad60a68f8c9b85285e9409c7003e8068e526f2"}]}
```

```kotlin [field-property box="imaging_morphology" name="code" path="imaging_morphology_code_.kt"]
// Three disjoint discs of known radius, then noise on top of them.
val discs = listOf(Triple(55.0, 60.0, 28.0), Triple(150.0, 55.0, 20.0), Triple(110.0, 135.0, 14.0))
val clean = Img.generate(220, 180) { x, y ->
    if (discs.any { (cx, cy, r) -> hypot(x - cx, y - cy) <= r }) 0.85f else 0.15f
}
val rng = kotlin.random.Random(3)
val noisy = clean.copy()
repeat((noisy.pixelCount * 0.03).toInt()) {
    noisy.data[rng.nextInt(noisy.pixelCount)] = if (rng.nextBoolean()) 0f else 1f
}

val mask = noisy.thresholdOtsu()
val cleaned = mask.open(2, StructuringElement.DISC).close(2, StructuringElement.DISC)
val cc = cleaned.connectedComponents(8)
val dt = cleaned.distanceTransform()

showImgGrid(
    "noisy source" to noisy,
    "thresholdOtsu()" to mask,
    "open(2,DISC).close(2,DISC)" to cleaned,
    "erode(4)" to cleaned.erode(4, StructuringElement.DISC),
    "dilate(4)" to cleaned.dilate(4, StructuringElement.DISC),
    "distanceTransform (magma)" to dt.normalized().colormap("magma"),
    columns = 3,
    cellSide = 250,
)
println("${cc.count} components after cleaning (planted ${discs.size} discs):")
cc.components.sortedByDescending { it.area }.forEach {
    println("  area=${it.area} centroid=(${"%.1f".format(it.centroidX)}, ${"%.1f".format(it.centroidY)}) " +
        "bbox=${it.boundingWidth}x${it.boundingHeight} fill=${"%.2f".format(it.fillRatio)}")
}
println("distance-transform peak ${"%.1f".format(dt.max())} (largest planted radius ${discs.maxOf { it.third }})")
```

## 5. motion — Lucas–Kanade flow and the homography RANSAC fits to it

<!-- field-box id="imaging_motion" index="4" -->

### Narrative

From `code`

Two frames related by a transform we choose, so the answer is known in advance.

```edn [field-box id="imaging_motion" role="properties"]
{"id" "imaging_motion" "index" 4 "name" "motion — Lucas–Kanade flow and the homography RANSAC fits to it" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "motion — Lucas–Kanade flow and the homography RANSAC fits to it" "_tombstone" false "frame" #afield/rect [80.0 2180.0 900.0 380.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "imaging_motion_code_.kt" "lines" 40 "characters" 1933 "sha256" "93f8443e5915928235754a84371c8958cddd60bd935230387ebb628fdc886f6e"}]}
```

```kotlin [field-property box="imaging_motion" name="code" path="imaging_motion_code_.kt"]
// Two frames related by a transform we choose, so the answer is known in advance.
val a = Img.generate(200, 200) { x, y ->
    (0.5 + 0.24 * kotlin.math.sin(x * 0.21) * kotlin.math.cos(y * 0.17) +
        0.16 * kotlin.math.sin((x + y) * 0.11)).toFloat().coerceIn(0f, 1f)
}
val planted = Affine2D.rotationAbout(0.05, 100.0, 100.0).compose(Affine2D.translation(5.0, -3.0))
val b = a.warpAffine(planted, border = Border.CLAMP)

// Seed only where the 2x2 tracking system is well conditioned.
val seeds = a.goodFeaturesToTrack(maxCount = 200, minDistance = 9)
    .filter { it.x in 24.0..176.0 && it.y in 24.0..176.0 }
    .map { it.toVec2() }
val flow = opticalFlowLK(a, b, seeds, windowRadius = 8, levels = 3)
val fit = findHomographyRansac(
    flow.sourcePoints(), flow.targetPoints(),
    threshold = 1.5, iterations = 1200, seed = 3L
)

// How far is the recovered model from the one we planted, in pixels, on a probe grid?
var worst = 0.0
for (gx in 40..160 step 40) for (gy in 40..160 step 40) {
    val (fx, fy) = fit.homography.apply(gx.toDouble(), gy.toDouble())
    val (tx, ty) = planted.apply(gx.toDouble(), gy.toDouble())
    worst = maxOf(worst, hypot(fx - tx, fy - ty))
}

showImgGrid(
    "frame A" to a,
    "frame B (rot 0.05 rad + shift 5,-3)" to b,
    "|A - B| (inferno)" to frameDifference(a, b).normalized().colormap("inferno"),
    "B warped back by the fitted model" to b.warpPerspective(fit.homography.inverse(), border = Border.CLAMP),
    columns = 2,
    cellSide = 280,
)
val (mdx, mdy) = flow.medianDisplacement()
println("${flow.tracked().size}/${seeds.size} tracked; median displacement " +
    "(${"%.2f".format(mdx)}, ${"%.2f".format(mdy)}) px")
println("RANSAC ${fit.inlierCount} inliers of ${flow.tracked().size} after ${fit.iterations} iterations")
println("recovered model agrees with the planted transform to ${"%.3f".format(worst)} px over the probe grid")
println(fit.homography.toString())
```

## 6. gradients + Canny + the FLines the edges become

<!-- field-box id="imaging_edges" index="5" -->

```edn [field-box id="imaging_edges" role="properties"]
{"id" "imaging_edges" "index" 5 "name" "gradients + Canny + the FLines the edges become" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "gradients + Canny + the FLines the edges become" "_tombstone" false "frame" #afield/rect [80.0 920.0 900.0 380.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "imaging_edges_code_.kt" "lines" 32 "characters" 1357 "sha256" "caac0a81ee1c46220a70313b44e2ee80cfa73a2c26f0d3e4baa3caa6a4efa844"}]}
```

```kotlin [field-property box="imaging_edges" name="code" path="imaging_edges_code_.kt"]
val src = Img.generate(240, 180) { x, y ->
    var v = 0.12f + 0.08f * (y / 180f)
    if (hypot(x - 62.0, y - 70.0) <= 34.0) v = 0.85f
    if (x in 120..185 && y in 30..95) v = 0.68f
    val tx = x - 210.0; val ty = y - 40.0
    if (ty in 0.0..60.0 && kotlin.math.abs(tx) <= ty * 0.5) v = 0.95f
    v
}

// Sobel here is scaled to true units-per-pixel: a unit step edge integrates to 1.
val grad = src.sobel()
val edges = src.canny(low = 0.05, high = 0.11, sigma = 1.2)
val auto = src.cannyAuto()          // thresholds derived from this image's own gradients

// Edges are geometry, and geometry in Field is an FLine.
val flines = edges.toContourFLines(minPerimeter = 12, simplifyTolerance = 1.2)

showImgGrid(
    "source" to src,
    "sobel().dx (signed, coolwarm)" to grad.dx.colormapDiverging("coolwarm"),
    "sobel().dy (signed, coolwarm)" to grad.dy.colormapDiverging("coolwarm"),
    "magnitude (inferno)" to grad.magnitude().normalized().colormap("inferno"),
    "orientation as hue x magnitude" to grad.toColorImg(),
    "canny(0.05, 0.11)" to edges,
    columns = 3,
    cellSide = 260,
)
println(
    "|grad| max ${"%.3f".format(grad.magnitude().max())}; " +
        "canny ${edges.data.count { it > 0.5f }} px, cannyAuto ${auto.data.count { it > 0.5f }} px; " +
        "${flines.size} FLines, ${flines.sumOf { it.nodes.size }} nodes total"
)
```

