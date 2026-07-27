# Imaging and computer vision

Field's stdlib has always been able to *make* pictures. It could not, until now, **look** at one.

`afield.stdlib` now carries a CPU imaging / computer-vision family: one value type, `Img`, and the
operations that turn a raster into structure — convolution, gradients, edges, corners, lines,
blobs, pyramids, warps and motion. It is the raster counterpart to the time-domain
[timeseries toolkit](timeseries_examples.md) and the frequency-domain
[spectral family](spectral_examples.md), and it is built the same way: pure Kotlin, no external
dependency, works headless in a box.

What makes it Field's rather than a generic CV library is the last hop. Every detector here reduces
pixels to *geometry*, and geometry in Field is an [`FLine`](fline_examples.md) — so a Canny result,
a set of corners, a Hough line or an optical-flow field lands in the same drawing, animation and
export machinery as anything you drew by hand. Scalar responses colour-map through the same
[`Colormaps`](rectsl_examples.md) tables RectSL uses. And `Img` implements `ToHTML`, so evaluating
one in a box shows the picture, not `Img@3f1a2b`.

## The value type

```
Img(width, height, channels, data)
```

`width * height * channels` floats, interleaved, row-major, origin **top-left** — the same layout
`BufferedImage`, PNG and a GPU upload all want. Channels are 1 (scalar / gray / mask / gradient /
distance), 3 (RGB) or 4 (RGBA); nothing else.

Three choices worth knowing about, because they change what the numbers mean:

- **Float, not bytes.** Every operation is float math. Nothing quantises mid-pipeline, so a
  twelve-stage chain does not accumulate rounding steps; clamping happens once, on output.
- **Signed where it should be.** Gradients, Laplacians and differences legitimately go negative and
  are left that way. `colormapDiverging(...)` is the right way to look at them.
- **Gradients are in true units-per-pixel.** The Sobel derivative factor is `[-1, 0, 1]/2` and the
  smoothing factor is normalised, so `sobel().dx` on a ramp of slope *s* really is *s*, and a unit
  step edge integrates to a total gradient of exactly 1. That makes a threshold like `0.05` mean
  something, instead of depending on which textbook's unscaled kernel you happened to use.

Build one from a function, a file, a `BufferedImage`, PNG bytes or a plain `FloatArray`:

```kotlin
val ramp   = Img.generate(64, 64) { x, _ -> x / 63f }
val photo  = imgFromFile("cat.png")            // javax.imageio under the hood
val fromGl = floatsToImg(heights, w, h)        // a heightfield, a spectrogram, any scalar grid
```

## Basics — resample, luma, HSV, threshold

`resize` area-averages when it shrinks (the anti-aliased answer) and interpolates bilinearly when it
grows. `luma()` is BT.709. `otsuThreshold()` computes the best two-way split of the histogram rather
than making you guess a number.

```diffstatic_runner {data-lang=kotlin screenshot="assets/imaging_examples/basics.png" wait="1.5" capture="box" open-outputs="true" padding="20"}
:::static
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

![](assets/imaging_examples/basics.png)

`showImgGrid(...)` is the fastest way to see a whole pipeline: it lays captioned panels out side by
side, normalises anything outside `0..1` for display, and pushes one PNG to the box output through
`Img.toPngBytes()` → `PngEncoder` → `display(...)`. No AWT is involved, so it works anywhere.

## Filters — and choosing the right one

Four smoothers with genuinely different characters. The picture makes the point, but the printed
residuals against the clean source make it *checkable*: on impulse noise the median is two orders of
magnitude better than the Gaussian, and the bilateral filter — which is excellent at preserving
edges — is deliberately bad at impulses, because a salt pixel is far away in *range* and so keeps
its own weight.

```diffstatic_runner {data-lang=kotlin screenshot="assets/imaging_examples/filters.png" wait="1.5" capture="box" open-outputs="true" padding="20"}
:::static
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

val gauss = noisy.gaussianBlur(2.0)     // separable, normalised: preserves the image mean
val med = noisy.median(2)               // the right tool for impulse noise
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
println(
    "residual vs clean — gaussian ${"%.4f".format(gauss.meanAbsDiff(clean))}, " +
        "median ${"%.4f".format(med.meanAbsDiff(clean))}, " +
        "bilateral ${"%.4f".format(bil.meanAbsDiff(clean))}"
)
```

![](assets/imaging_examples/filters.png)

`boxBlur(radius)` is also there, computed with running sums so its cost is independent of the
radius, and `boxBlurApproxGaussian(sigma)` chains three of them when sigma is large and you care
more about speed than about the exact kernel shape.

## Gradients, edges, and the FLines they become

`sobel()` returns a `Gradient` — `dx`, `dy`, and derived `magnitude()`, `orientation()` and a
hue-encoded `toColorImg()`. `canny()` runs the four classic stages (smooth, gradient, non-maximum
suppression, hysteresis) and returns a thin binary mask. `toContourFLines(...)` traces that mask and
hands back drawable `FLine`s, simplified with Ramer–Douglas–Peucker so a staircase pixel boundary
becomes a handful of straight runs.

```diffstatic_runner {data-lang=kotlin screenshot="assets/imaging_examples/edges.png" wait="1.5" capture="box" open-outputs="true" padding="20"}
:::static
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

![](assets/imaging_examples/edges.png)

`dx` fires on vertical edges and `dy` on horizontal ones; the orientation panel wheels its hue right
around the disc, because that is what the gradient direction does.

### When Canny "doesn't work"

A `(low, high)` pair tuned on a bright synthetic card finds almost nothing in a dark, softly-lit
photograph. That is the single most common way Canny appears to fail, and it is not Canny's fault —
the thresholds are in gradient-magnitude units and the image simply does not reach them.
`cannyAuto(...)` derives them from the image's own distribution: it takes a percentile of the
non-maximum-suppressed magnitudes as the strong seed threshold and Canny's own recommended ratio for
the weak one.

The figure below is a real repo PNG run through the whole pipeline. The fixed pair finds 252 edge
pixels — barely the car at all. `cannyAuto()` finds 1952, and traces the whole silhouette; the last
panel is those edges as 57 `FLine`s drawn back over the source.

![](assets/imaging_gallery/00-real-image.png)

## Corners

Three detectors, all on a square whose corners are at exactly known coordinates, so the answer is
checkable rather than merely plausible.

- `harrisResponse()` — `det(M) - k·trace(M)²` over the structure tensor. Large and positive at a
  corner, **negative along an edge**, near zero on flat ground; that sign structure is what the
  diverging colour map in the figure is showing.
- `shiTomasiResponse()` — the smaller eigenvalue of the same tensor. No magic `k`, and directly
  interpretable: it is the weakest intensity variation over any direction, so a large value means
  the point is localisable in *both* axes. A straight edge scores ~0, which is the aperture problem
  stated numerically. This is the response an optical-flow tracker wants, and
  `goodFeaturesToTrack(...)` is its friendlier name.
- `fastCorners()` — no gradients and no eigenvalues, just a wrap-around run-length test on a
  16-pixel circle. Much faster, and it scores each corner by the largest threshold at which it would
  still qualify.

![](assets/imaging_gallery/04-corners.png)

Run headless, Harris and Shi–Tomasi each return exactly four corners, and the worst of the four sits
**0.94 px** from the true corner of the square (sub-pixel refinement is on by default, via a
three-tap parabola fit in x and y). `cornersToFLines(...)` turns them into drawable crosses or boxes.

## Hough — global structure from local evidence

Every edge pixel votes for every line through it; a real line becomes a bright peak in `(rho, theta)`
accumulator space. It is the classic demonstration that global structure can be found from purely
local evidence with no grouping step.

The figure has three planted lines and a decoy ring. All three lines come back at their exact polar
coordinates — `(rho=150, θ=0.00)`, `(rho=40, θ=1.57)`, `(rho=28, θ=2.36)` — and the ring is
correctly *not* fitted.

![](assets/imaging_gallery/05-hough.png)

`HoughLine.clipTo(w, h)` clips the infinite line to the image rectangle, and `linesToFLines(...)`
gives you the visible segments as `FLine`s. `houghCircles(...)` is there too, for a known radius
range.

## Morphology, blobs and distance

`erode` / `dilate` / `open` / `close` with square, disc or cross structuring elements;
`connectedComponents(...)` for labels plus per-blob area, bounding box, centroid and fill ratio;
`distanceTransform()` for the exact Euclidean distance to the nearest background pixel (the
Felzenszwalb–Huttenlocher two-pass algorithm, not a chamfer approximation).

```diffstatic_runner {data-lang=kotlin screenshot="assets/imaging_examples/morphology.png" wait="1.5" capture="box" open-outputs="true" padding="20"}
:::static
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

![](assets/imaging_examples/morphology.png)

Three discs in, three components out, with areas of 2419, 1226 and 584 against the analytic
`πr²` of 2463, 1257 and 616 (the shortfall is the `open(2)` erosion), and centroids at (54.8, 60.1),
(150.1, 55.0) and (110.2, 134.9) against planted centres of (55, 60), (150, 55) and (110, 135).

For sub-pixel-smooth contours rather than a pixel-staircase boundary, `isoContourFLines(iso)` adapts
the image to the `ScalarField2D` that Field's existing marching-squares extractor
([contours](fline_examples.md)) already marches — so a distance transform or a blurred response
becomes a smooth curve with no glue code.

## Multiscale

`gaussianPyramid(levels)` halves each octave (blurring first, so it does not alias);
`laplacianPyramid(levels)` splits the image into band-pass detail plus a coarse residual, and
`reconstruct()` inverts it. The round-trip is exact to within float precision — measured at
**5.96e-08** maximum absolute error on the figure below — which is what makes the decomposition
usable for multiband blending and detail transfer rather than just for looking at.

![](assets/imaging_gallery/07-pyramids.png)

(Levels below the base are magnified by integer pixel replication for display; each caption states
the real size and the zoom factor.)

## Geometry — affine and projective warps

`Affine2D` uses the same six-number layout as `java.awt.geom.AffineTransform` and SVG's
`matrix(a, b, c, d, e, f)`. `Homography` is the 3×3 projective version. `warpAffine` and
`warpPerspective` map **source → destination** and invert internally, doing the correct backwards
resample, so there are no holes.

`findHomography(src, dst)` is the Direct Linear Transform: exact through four correspondences,
least-squares through more. The points are Hartley-normalised first, which is not optional — without
it the conditioning on pixel-scale coordinates is bad enough to visibly distort the fit.
`warpQuadToRect(quad, w, h)` wraps it up as the "flatten the photographed page" operation.

![](assets/imaging_gallery/08-warps.png)

The rectified checkerboard matches the original to a mean absolute error of 0.035 over the interior.

## Motion

`opticalFlowLK(a, b, points)` is pyramidal Lucas–Kanade. Seed it with `goodFeaturesToTrack(...)` —
which selects exactly the points where the 2×2 tracking system is well conditioned — and it returns
a `FlowField` of `FlowVector`s, each of which reports whether it actually converged. A tracker that
never admits failure is lying; this one marks a track `ok = false` when the system is
ill-conditioned (a smooth region, or a straight edge) or the point leaves the frame.

The pyramid is what makes large motions tractable: solve at the coarsest octave where the
displacement is small, then propagate the estimate down. A 9-pixel shift that a single level cannot
recover comes back to within half a pixel with four.

```diffstatic_runner {data-lang=kotlin screenshot="assets/imaging_examples/motion.png" wait="2.0" capture="box" open-outputs="true" padding="20"}
:::static
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

![](assets/imaging_examples/motion.png)

`findHomographyRansac(...)` is why feature matching works in practice: half the correspondences can
be garbage and the answer is still right, because a wrong model has no reason to be *consistently*
wrong across many points. It samples four correspondences at a time, scores the consensus, keeps the
best, and refits least-squares on the inliers. The seed is explicit, so a docs capture or a test
gets the same answer every time.

`opticalFlowBlockMatch(...)` is the brute-force alternative for displacements large enough to break
Lucas–Kanade's linearisation, and `frameDifference(...)` / `updateBackground(...)` are the crude,
reliable motion cues — pair them with `open(...)` to drop sensor noise and `connectedComponents(...)`
to count what moved.

`FlowField.toFLines(scale, ...)` draws the whole field as arrows, shaft plus arrowhead, so direction
is visible and not just magnitude:

![](assets/imaging_gallery/09-flow.png)

## Colour

`luma()`, `rgbToHsv()` / `hsvToRgb()`, and `toLinear()` / `toSrgb()`. That last pair matters more
than it looks: blurring, downsampling and averaging are only *correct* in linear light, and
`img.toLinear().gaussianBlur(4.0).toSrgb()` is measurably different from blurring the encoded
values. The panel below shows both, and the amplified difference between them.

![](assets/imaging_gallery/10-colour.png)

`colormap(name, lo, hi)` runs a 1-channel image through any of the `Colormaps` ramps (`viridis`,
`magma`, `inferno`, `plasma`, `turbo`, `coolwarm`, `grayscale`); `colormapDiverging(name)` picks a
symmetric range around zero, which is the correct default for anything signed.

## Where the pictures come from

Everything on this page is real output, and there are three routes to reproducing it:

| what | how |
|---|---|
| the code fences above | executed by the docs build; the image under each is that box's own output |
| the analytic figures | `python3 tools/run_field_tests.py --id imaging.gallery --port 9520` |
| the whole family, live | replay `dev_docs/agents/transcripts/imaging_cv_examples.yaml` |

The gallery is a FIELD_TEST: it builds every figure from real `Img` pipelines and **fails** if the
invariants break — if Canny stops being thinner than a raw threshold, if the four corner marks drift
more than four pixels off the four corners, if Hough stops recovering the planted lines, if the
Laplacian pyramid stops round-tripping, or if a flow arrow disagrees with the planted motion by more
than three pixels.

The transcript is a loadable document: replay it into a fresh Field with the agent backdoor
(`field_run_transcript`) and you get six live boxes you can edit. Change a sigma, a threshold or the
planted motion, re-run the box, and watch it update. See [Agent transcripts](agent_transcripts.md).

## Is it fast enough?

The family is pure Kotlin with no native dependency, which is the whole point of it — and the
obvious question that raises is whether you should just use OpenCV instead. So the repository
carries a benchmark that puts the two side by side, in the same JVM, on the same pixels:
`tools/imaging-bench`, run with

```bash
./gradlew :tools:imaging-bench:jmh
```

The filters are built on a small layer of vectorized span kernels (Java Vector API,
`imaging_simd.kt`). That was worth doing because of how `Img` stores its samples: interleaved,
`data[(y * width + x) * channels + c]`. Adding `(t - r) * channels` to a flat index therefore lands
on the same channel of the pixel `t - r` columns over — so for a fixed tap, the whole interior of a
row is one contiguous span, and a separable convolution collapses to `dst += w * src` over that
span. The vertical pass gets a second gift: its border decision is per *row*, not per sample, so the
row index is resolved once and every row takes the fast path.

Vectorizing turned out to be only half of it. The three biggest gains on this page came from doing
less work, not from doing it wider:

- **`pyrDown`** used to blur at full resolution and then throw three quarters of the result away. It
  now filters only the rows and columns that survive the 2x decimation. The horizontal half of that
  is the awkward one — output `x` reads source pixel `2x + s`, so the reads want to be stride-2 —
  but the parity of `2x + s` is fixed by the parity of `s`, so splitting each row into its even and
  odd pixels once makes every tap unit-stride again. That is a polyphase decomposition, and it needs
  no gather, which matters on a machine where a gather is emulated.
- **`boxBlur`** was already `O(1)` per pixel, so all of its gap was constant factor — and most of
  that was a vertical pass written as `for (x) for (y)`, walking one column at a time down an 8 MB
  array, a cache miss per sample. Turned inside out, the accumulator is now a whole *row* of partial
  sums, one lane per column, advanced together.
- **`bilateral`** spent its life inside `Math.exp`: the range weight depends on the sample, so the
  literal definition calls it once per tap per pixel — 169 times per pixel at these settings. It is
  now a 4096-entry table indexed by squared intensity distance, read with linear interpolation.

One more, if you ever write a kernel here: a register accumulator is a chain of *dependent*
multiply-adds, and on Apple silicon an FMA has about four cycles of latency while issuing about four
per cycle, so a single-accumulator chain runs at latency rather than throughput. Keeping four output
chunks in flight is what took the dense 11x11 convolution from 2.3x OpenCV to slightly faster than
it. The same change made the *separable* passes slower, which is measured and explained in
`imaging_filters.kt` — the read-modify-write it saves never leaves L1 there, and the simple one-tap
loops it replaces are code the JIT already handles perfectly.

<!-- BEGIN imaging-bench table — generated by tools/imaging_bench_doc.py, do not edit -->

| operation | before (scalar) | now | OpenCV 4.9, 1 thread | now vs OpenCV |
|---|---|---|---|---|
| Gaussian blur, sigma 2, gray | 91.59 ± 2.61 | 7.09 ± 0.07 | 7.13 ± 0.39 | **0.99x** |
| Gaussian blur, sigma 2, RGB | 263 ± 8.68 | 19.61 ± 0.15 | 21.81 ± 0.48 | **0.90x** |
| Sobel | 52.94 ± 0.13 | 4.49 ± 0.04 | 2.09 ± 0.03 | 2.14x |
| Scharr | — | 4.49 ± 0.04 | 2.21 ± 0.03 | 2.03x |
| Laplacian | — | 2.68 ± 0.02 | 1.61 ± 0.08 | 1.66x |
| Dense 11x11 convolution | — | 26.19 ± 0.21 | 27.22 ± 0.91 | **0.96x** |
| Luma (RGB to gray) | — | 1.96 ± 0.01 | 1.14 ± 0.01 | 1.72x |
| Box blur, radius 2 | — | 2.60 ± 0.03 | 1.89 ± 0.10 | 1.38x |
| Median, radius 2 | — | 425 ± 5.30 | 120 ± 7.82 | 3.53x |
| Resize to half | — | 3.65 ± 0.06 | 0.73 ± 0.01 | 4.99x |
| Bilateral | — | 749 ± 95.18 | 628 ± 4.25 | 1.19x |
| pyrDown | — | 2.07 ± 0.02 | 0.50 ± 0.02 | 4.12x |

<!-- END imaging-bench table -->

**How to read this.** "Before" is the original scalar implementation, kept in the source as
`convolveSeparableReference` rather than remembered, so the claim can be re-measured at any time.
Only the separable-convolution family has one — a dash means that operation has no preserved scalar
original to compare against, not that the speedup is unknown. Where there is a before, the gain is
12.9x (Gaussian gray), 13.4x (Gaussian RGB) and 11.8x (Sobel): near-identical, which is what you
expect when the win is "stopped calling a bounds-checked accessor once per multiply-add" rather than
anything kernel-specific.

OpenCV is pinned to one thread, because this code is single-threaded; letting OpenCV use every core
did not help at this size anyway, which is measured rather than assumed. Bold means we are faster.

**What the numbers do and do not say.** Three rows — Gaussian blur, box blur and the Laplacian — are
the same operation with the same parameters on both sides, and the benchmark *fails the run* if the
outputs stop agreeing (Gaussian to 2.4e-7, box blur to 7.8e-6, Laplacian exactly). The rest are
matched in work but not in definition, and the difference is real: our `luma` is BT.709 where
`cvtColor` is BT.601; our Sobel and Scharr are scaled to true units-per-pixel, so ours is OpenCV's
divided by a constant; our `pyrDown` takes a configurable sigma where OpenCV's is a fixed 5-tap
kernel; and `median`/`bilateral` have matched parameters but different internal algorithms. Our API
also returns a fresh `Img` each call, so part of our cost is an allocation OpenCV is not paying.

**The honest summary** is that convolution now wins and most of the rest is within striking distance.
Gaussian blur, dense convolution and the RGB blur are at or slightly past OpenCV; box blur (1.4x),
Laplacian (1.7x), Scharr and Sobel (2.0-2.1x), luma (1.7x) and bilateral (~1.2x) are close enough
that the pure-Kotlin path is the reasonable default. Three rows are still meaningfully behind, and
you should know which before choosing:

- **`median` at 3.5x** is the one real algorithmic gap left: it still sorts 25 floats per pixel, where
  OpenCV uses a sorting network. It is the obvious next thing to fix.
- **`resize` to half at 5.0x** has data-dependent inner loops that do not vectorize.
- **`pyrDown` at 4.1x**, which is *not* a defect and will not be "fixed": ours applies
  `gaussianKernel1D(sigma)` — 9 taps at the default — where OpenCV's `pyrDown` is a fixed 5-tap
  `[1,4,6,4,1]/16`. We do close to twice the arithmetic because the filter is better and the sigma is
  yours to choose. Against its own previous self, which blurred everything and then discarded three
  quarters of it, it is 3.8x faster (7.79 ms to 2.07 ms).

These were measured on a 10-core Apple Silicon laptop, with a load average of 5-6 and the benchmark
JVM the only busy process — quiet for a laptop, not a dedicated benchmark box. The error bars are
printed rather than hidden: a wide interval on a short benchmark means scheduler noise, not a real
difference, so treat any two numbers whose intervals overlap as a tie. `bilateral` is the one arm
whose interval is genuinely wide (13% relative), so read it as "about 1.2x". The `opencv` columns
double as a control across runs, since that code does not change; they drifted about 3% between this
run and the previous one, which is the scale of machine variation you should assume when comparing
against an older copy of this table.

## Reference

| | |
|---|---|
| **value type** | `Img`, `Border`, `imgFromFile`, `imgFromBytes`, `floatsToImg`, `toBufferedImage`, `toPngBytes`, `savePng` |
| **sampling / shape** | `sampleNearest`, `sampleBilinear`, `crop`, `pad`, `resize`, `scaleBy`, `flipX`, `flipY`, `rotate90`, `transpose` |
| **channels / colour** | `channel`, `split`, `mergeChannels`, `luma`, `toRGB`, `toRGBA`, `toLinear`, `toSrgb`, `rgbToHsv`, `hsvToRgb` |
| **statistics** | `normalized`, `normalizedPercentile`, `histogram`, `equalize`, `otsuThreshold`, `threshold`, `thresholdOtsu`, `thresholdAdaptive`, `meanAbsDiff`, `psnr` |
| **filters** | `Kernel1D`, `Kernel2D`, `convolveSeparable`, `convolve2D`, `correlate2D`, `gaussianBlur`, `boxBlur`, `boxBlurApproxGaussian`, `median`, `bilateral`, `unsharpMask` |
| **gradients** | `Gradient`, `sobel`, `sobelMagnitude`, `scharr`, `gaussianGradient`, `laplacian`, `laplacianOfGaussian`, `differenceOfGaussians` |
| **multiscale** | `pyrDown`, `pyrUp`, `gaussianPyramid`, `laplacianPyramid` |
| **edges / features** | `canny`, `cannyAuto`, `nonMaximumSuppression`, `hysteresis`, `harrisResponse`, `shiTomasiResponse`, `harrisCorners`, `shiTomasiCorners`, `goodFeaturesToTrack`, `fastCorners`, `peakCorners`, `structureTensor` |
| **lines / blobs** | `houghLines`, `houghCircles`, `erode`, `dilate`, `open`, `close`, `morphologicalGradient`, `topHat`, `blackHat`, `connectedComponents`, `distanceTransform`, `traceContours` |
| **geometry / motion** | `Affine2D`, `Homography`, `findHomography`, `findHomographyRansac`, `warpAffine`, `warpPerspective`, `warpQuadToRect`, `rotate`, `opticalFlowLK`, `opticalFlowBlockMatch`, `frameDifference`, `updateBackground` |
| **Field integration** | `toContourFLines`, `contoursToFLines`, `cornersToFLines`, `linesToFLines`, `circlesToFLines`, `FlowField.toFLines`, `isoContourFLines`, `asScalarField2D`, `colormap`, `colormapDiverging`, `maskToRGBA`, `overlay`, `hstack`, `vstack`, `showImg`, `showImgGrid`, `simplifyPolyline` |

Source: `src/jvmMain/kotlin/afield/stdlib/imaging.kt`, `imaging_filters.kt`,
`imaging_features.kt`, `imaging_geometry.kt`, `imaging_field.kt`.
