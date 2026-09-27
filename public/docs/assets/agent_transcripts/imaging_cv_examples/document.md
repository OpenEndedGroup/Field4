# Field Document: imaging_cv_examples

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 6 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. Img basics — build, resample, luma, HSV, threshold

<!-- field-box id="imaging_basics" index="0" -->

### Narrative

From `code`

An Img is width x height x channels floats, interleaved, origin top-left. This one is
2400x1800 — 4.3 megapixels, 52 MB of float RGB — because that is the size at which the
question "is this fast enough to actually use?" has a meaningful answer.

```edn [field-box id="imaging_basics" role="properties"]
{"id" "imaging_basics" "index" 0 "name" "Img basics — build, resample, luma, HSV, threshold" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Img basics — build, resample, luma, HSV, threshold" "_tombstone" false "frame" #afield/rect [80.0 80.0 440.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "imaging_basics_code_.kt" "lines" 38 "characters" 1845 "sha256" "21ff3c94003d65cd9489e1205c8a86389fe11247d8152188adab0046d8c612c2"}]}
```

```kotlin [field-property box="imaging_basics" name="code" path="imaging_basics_code_.kt"]
// An Img is width x height x channels floats, interleaved, origin top-left. This one is
// 2400x1800 — 4.3 megapixels, 52 MB of float RGB — because that is the size at which the
// question "is this fast enough to actually use?" has a meaningful answer.
val card = Img.generateRGB(2400, 1800) { x, y ->
    val u = x / 2399f
    val v = y / 1799f
    val disc = if (hypot(x - 600.0, y - 600.0) <= 340.0) 1f else 0f
    val bar = if (y in 1300..1420 && x in 200..2199) 1f else 0f
    Triple(
        (0.15f + 0.7f * u * (1f - disc) + disc * 0.9f + bar).coerceIn(0f, 1f),
        (0.15f + 0.6f * v + disc * 0.25f + bar).coerceIn(0f, 1f),
        (0.55f - 0.4f * u + disc * 0.1f + bar).coerceIn(0f, 1f)
    )
}

val gray = card.luma()                 // BT.709 perceptual grayscale
val hsv = card.rgbToHsv()              // h, s, v each in 0..1
val small = card.resize(600, 450)      // area-averaged (anti-aliased) downsample
val backUp = small.resize(2400, 1800)  // bilinear upsample: the detail is really gone
val t = gray.otsuThreshold()           // computed, not guessed
val mask = gray.threshold(t)

showImgGrid(
    "source RGB (${card.width}x${card.height})" to card,
    "luma()" to gray,
    "hue channel" to hsv.channel(0),
    "saturation channel" to hsv.channel(1),
    "resize(600,450) then back up" to backUp,
    "thresholdOtsu() @ ${"%.3f".format(t)}" to mask,
    columns = 3,
    cellSide = 260,
)
println("Img ${card.width}x${card.height} x${card.channels} ch, mean ${"%.3f".format(card.mean())}, " +
    "otsu ${"%.3f".format(t)}, foreground ${mask.data.count { it > 0.5f }} px")

// The threshold is a mask; a mask has outlines; outlines are geometry. Everything the
// family produces that is geometry rather than raster ends up in a lines() call.
lines(mask.toContourFLines(minPerimeter = 120, simplifyTolerance = 12.0))
```

## 2. filters — gaussian / median / bilateral / unsharp

<!-- field-box id="imaging_filters" index="1" -->

### Narrative

From `code`

A clean synthetic source, and the same source with 4% salt-and-pepper corruption.

```edn [field-box id="imaging_filters" role="properties"]
{"id" "imaging_filters" "index" 1 "name" "filters — gaussian / median / bilateral / unsharp" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "filters — gaussian / median / bilateral / unsharp" "_tombstone" false "frame" #afield/rect [80.0 500.0 430.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "imaging_filters_code_.kt" "lines" 40 "characters" 1735 "sha256" "6844c2c2185cc853bf9a225299eea8163f477e8bd123fe9dc4fa910e75d757eb"}]}
```

```kotlin [field-property box="imaging_filters" name="code" path="imaging_filters_code_.kt"]
// A clean synthetic source, and the same source with 4% salt-and-pepper corruption.
val clean = Img.generate(2400, 1800) { x, y ->
    var v = 0.12f + 0.10f * (y / 1800f)
    if (hypot(x - 600.0, y - 700.0) <= 340.0) v = 0.85f
    if (x in 1200..1850 && y in 300..950) v = 0.65f
    if (y in 1350..1420 && x in 200..2199) v = 0.95f
    v
}
val rng = kotlin.random.Random(11)
val noisy = clean.copy()
repeat((noisy.pixelCount * 0.04).toInt()) {
    noisy.data[rng.nextInt(noisy.pixelCount)] = if (rng.nextBoolean()) 0f else 1f
}

// These radii are in PIXELS, and they deliberately did not scale with the image. The
// separable filters are O(k) per pixel, so gaussianBlur is ~linear in area; median and
// bilateral are O(r^2) per pixel, so scaling their radius with the image would multiply
// their cost by 10,000x rather than 100x. Salt-and-pepper is single-pixel noise at any
// image size, so a pixel-scale radius is also the correct choice here, not a concession.
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
{"id" "imaging_features" "index" 2 "name" "features — Harris / FAST corners and Hough lines" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "features — Harris / FAST corners and Hough lines" "_tombstone" false "frame" #afield/rect [80.0 1340.0 430.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "imaging_features_code_.kt" "lines" 47 "characters" 2524 "sha256" "345d23f240d7e8e4a009ac34dcb31f33381523a86486cc62d8079934fa02574f"}]}
```

```kotlin [field-property box="imaging_features" name="code" path="imaging_features_code_.kt"]
// A square whose corners are at exactly known coordinates.
val square = Img.generate(2000, 2000) { x, y -> if (x in 500..1499 && y in 500..1499) 0.92f else 0.08f }
val harris = square.harrisCorners(threshold = 0.05, minDistance = 120, maxCount = 20)
val fast = square.fastCorners(threshold = 0.2, minDistance = 120, maxCount = 20)

val truth = listOf(500.0 to 500.0, 1499.0 to 500.0, 500.0 to 1499.0, 1499.0 to 1499.0)
val worst = truth.maxOf { (ex, ey) -> harris.minOf { hypot(it.x - ex, it.y - ey) } }

// Three lines at exactly known (rho, theta), plus a decoy ring Hough must not fit.
val lineMask = Img.zeros(2000, 2000, 1)
for (x in 100 until 1900) lineMask.set(x, 400, 0, 1f)        // rho = 400,  theta = pi/2
for (y in 100 until 1900) lineMask.set(1500, y, 0, 1f)       // rho = 1500, theta = 0
for (t in 0 until 2400) {
    val x = 200 + t * 0.6; val y = 600 + t * 0.6
    if (x < 1999 && y < 1999) lineMask.set(x.toInt(), y.toInt(), 0, 1f)
}
for (i in 0 until 3600) {
    val a = 2.0 * PI * i / 3600
    val x = (600 + 220 * kotlin.math.cos(a)).toInt()
    val y = (1500 + 220 * kotlin.math.sin(a)).toInt()
    if (x in 0..1999 && y in 0..1999) lineMask.set(x, y, 0, 1f)
}
// threshold is a vote count, so it scales with the number of edge pixels on a line.
// Named `houghs`, not `lines`, so it cannot shadow the lines() drawing call below.
val houghs = houghLines(lineMask, thetaSteps = 360, threshold = 1000, maxLines = 5)

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
println("hough " + houghs.joinToString(", ") {
    "(rho=${"%.0f".format(it.rho)}, theta=${"%.2f".format(it.theta)}, votes=${it.votes})"
})

// A HoughLine is an INFINITE line — (rho, theta) names a whole line, not a segment — so
// it has no drawable form until you say which rectangle you are drawing it into. That is
// what toFLine(w, h) is for, and why it is nullable: a peak can name a line that misses.
lines(
    cornersToFLines(harris, size = 40.0) +
        houghs.mapNotNull { it.toFLine(lineMask.width, lineMask.height) }
)
```

## 4. morphology, connected components, distance transform

<!-- field-box id="imaging_morphology" index="3" -->

### Narrative

From `code`

Three disjoint discs of known radius, then noise on top of them.

```edn [field-box id="imaging_morphology" role="properties"]
{"id" "imaging_morphology" "index" 3 "name" "morphology, connected components, distance transform" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "morphology, connected components, distance transform" "_tombstone" false "frame" #afield/rect [80.0 1760.0 450.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "imaging_morphology_code_.kt" "lines" 36 "characters" 1737 "sha256" "de7b910dd32fcce2138539c683af4d9256495cbd97dfffcbb904dbc0f87cb31a"}]}
```

```kotlin [field-property box="imaging_morphology" name="code" path="imaging_morphology_code_.kt"]
// Three disjoint discs of known radius, then noise on top of them.
val discs = listOf(Triple(550.0, 600.0, 280.0), Triple(1500.0, 550.0, 200.0), Triple(1100.0, 1350.0, 140.0))
val clean = Img.generate(2200, 1800) { x, y ->
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

// Connected components are blobs; blobs have outlines. Same story as Canny — the raster
// is the evidence, the FLines are the result you can actually build on.
lines(cleaned.toContourFLines(minPerimeter = 120, simplifyTolerance = 8.0))
```

## 5. motion — Lucas–Kanade flow and the homography RANSAC fits to it

<!-- field-box id="imaging_motion" index="4" -->

### Narrative

From `code`

Two frames related by a transform we choose, so the answer is known in advance.
Multi-octave on purpose. Lucas-Kanade is coarse-to-fine: it halves the image several
times and tracks the big picture first. A texture with only fine detail (period ~30 px)
aliases away after four halvings and the tracker has nothing to lock onto — that
version tracked 0 of 125 seeds at this size. Coarse octaves survive to the top of the
pyramid; the fine ones sharpen the final refinement.

```edn [field-box id="imaging_motion" role="properties"]
{"id" "imaging_motion" "index" 4 "name" "motion — Lucas–Kanade flow and the homography RANSAC fits to it" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "motion — Lucas–Kanade flow and the homography RANSAC fits to it" "_tombstone" false "frame" #afield/rect [80.0 2180.0 520.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "imaging_motion_code_.kt" "lines" 54 "characters" 2850 "sha256" "53027a5af1dc97109b62d2d977f907bda3ede6cb19f41126683214722638ba68"}]}
```

```kotlin [field-property box="imaging_motion" name="code" path="imaging_motion_code_.kt"]
// Two frames related by a transform we choose, so the answer is known in advance.
// Multi-octave on purpose. Lucas-Kanade is coarse-to-fine: it halves the image several
// times and tracks the big picture first. A texture with only fine detail (period ~30 px)
// aliases away after four halvings and the tracker has nothing to lock onto — that
// version tracked 0 of 125 seeds at this size. Coarse octaves survive to the top of the
// pyramid; the fine ones sharpen the final refinement.
val a = Img.generate(2000, 2000) { x, y ->
    (0.5 +
        0.20 * kotlin.math.sin(x * 0.021) * kotlin.math.cos(y * 0.017) +
        0.14 * kotlin.math.sin((x + y) * 0.011) +
        0.10 * kotlin.math.sin(x * 0.21) * kotlin.math.cos(y * 0.17) +
        0.06 * kotlin.math.cos((x - 2.0 * y) * 0.07)
        ).toFloat().coerceIn(0f, 1f)
}
val planted = Affine2D.rotationAbout(0.05, 1000.0, 1000.0).compose(Affine2D.translation(50.0, -30.0))
val b = a.warpAffine(planted, border = Border.CLAMP)

// Seed only where the 2x2 tracking system is well conditioned. The displacement is now
// 10x larger in pixels, so the pyramid needs another level to keep it inside the window.
val seeds = a.goodFeaturesToTrack(maxCount = 200, minDistance = 90)
    .filter { it.x in 240.0..1760.0 && it.y in 240.0..1760.0 }
    .map { it.toVec2() }
val flow = opticalFlowLK(a, b, seeds, windowRadius = 8, levels = 5)
val fit = findHomographyRansac(
    flow.sourcePoints(), flow.targetPoints(),
    threshold = 1.5, iterations = 1200, seed = 3L
)

// How far is the recovered model from the one we planted, in pixels, on a probe grid?
var worst = 0.0
for (gx in 400..1600 step 400) for (gy in 400..1600 step 400) {
    val (fx, fy) = fit.homography.apply(gx.toDouble(), gy.toDouble())
    val (tx, ty) = planted.apply(gx.toDouble(), gy.toDouble())
    worst = maxOf(worst, hypot(fx - tx, fy - ty))
}

showImgGrid(
    "frame A" to a,
    "frame B (rot 0.05 rad + shift 50,-30)" to b,
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

// One arrow FLine per track. This is the whole family's shape in one line: raster in,
// geometry out, and the geometry goes to lines() like anything else you draw in Field.
lines(flow.toFLines(scale = 6.0, minMagnitude = 0.5))
```

## 6. gradients + Canny + the FLines the edges become

<!-- field-box id="imaging_edges" index="5" -->

```edn [field-box id="imaging_edges" role="properties"]
{"id" "imaging_edges" "index" 5 "name" "gradients + Canny + the FLines the edges become" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "gradients + Canny + the FLines the edges become" "_tombstone" false "frame" #afield/rect [80.0 920.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "imaging_edges_code_.kt" "lines" 37 "characters" 1651 "sha256" "2c2cbc8660994bd393ae92e0ab0158eeca5fca2d68c4c6db06e05047781d24dc"}]}
```

```kotlin [field-property box="imaging_edges" name="code" path="imaging_edges_code_.kt"]
val src = Img.generate(2400, 1800) { x, y ->
    var v = 0.12f + 0.08f * (y / 1800f)
    if (hypot(x - 620.0, y - 700.0) <= 340.0) v = 0.85f
    if (x in 1200..1850 && y in 300..950) v = 0.68f
    val tx = x - 2100.0; val ty = y - 400.0
    if (ty in 0.0..600.0 && kotlin.math.abs(tx) <= ty * 0.5) v = 0.95f
    v
}

// Sobel here is scaled to true units-per-pixel: a unit step edge integrates to 1.
val grad = src.sobel()
val edges = src.canny(low = 0.05, high = 0.11, sigma = 1.2)
val auto = src.cannyAuto()          // thresholds derived from this image's own gradients

// Edges are geometry, and geometry in Field is an FLine. minPerimeter and the simplify
// tolerance are pixel distances, so they scale with the image.
val flines = edges.toContourFLines(minPerimeter = 120, simplifyTolerance = 12.0)

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

// The raster above is what Canny computed; this is what it *means* — three closed
// outlines you can now transform, intersect or animate like any other Field geometry.
lines(flines)
```

