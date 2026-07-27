package afield.examples.imaging

import afield.graphics.FLine
import afield.stdlib.*
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/*
FIELD_TEST
id: imaging.gallery
runner: jvm-main
entrypoint: afield.examples.imaging.ImagingGallery
tags: [smoke, examples, imaging, cv, capture, cpu]
timeoutSec: 240
expect:
  exitCode: 0
  stdoutContains:
    - "imaging gallery rendered"
*/

/**
 * Headless **capture gallery** for the `afield.stdlib` imaging / computer-vision family — the
 * honest visual proof that the pixels come out right, in the pattern
 * [afield.examples.functions.FunctionsPlotGallery] and
 * [afield.examples.rectsl.RectSLDataVizProof] established.
 *
 * Rendering path: real [Img] pipelines → CPU AWT `Graphics2D` compositing → `BufferedImage` →
 * `ImageIO` PNG, under `java.awt.headless=true`. No browser, no GPU, no Vulkan, no Field window.
 * Every pixel in every panel is the output of a real stdlib call; AWT only draws the frames,
 * captions and the `FLine` overlays.
 *
 * As a FIELD_TEST it renders to a temp dir and asserts **correctness, not plausibility**:
 * each panel declares invariants (edges within N pixels of the true boundary, exactly 4 corners
 * on a square's 4 corners, the right blob count, flow arrows pointing the right way) and the run
 * fails if any of them breaks. It writes NO committed assets during the test. Pass an output dir
 * as `args[0]` (or set `IMAGING_GALLERY_DIR`) to regenerate the committed docs captures under
 * `public/docs/assets/imaging_gallery/`.
 *
 * Adding a panel: append one [Panel] to [PANELS]. Everything else — layout, captions, the
 * painted-fraction guard, the PNG write — is shared.
 */
object ImagingGallery {

    // Dark-mode palette shared with the other docs figures.
    private val BG = Color(20, 22, 28)
    private val PANEL_BG = Color(28, 30, 38)
    private val FRAME = Color(58, 63, 75)
    private val TEXT = Color(206, 210, 220)
    private val DIM = Color(138, 144, 160)
    private val CYAN = Color(72, 202, 228)
    private val ORANGE = Color(240, 156, 74)
    private val GREEN = Color(120, 214, 140)
    private val PINK = Color(232, 120, 168)

    /**
     * One gallery figure: a [name] (its PNG filename), a [title], a list of captioned image
     * [tiles], optional vector [overlays] drawn on top of a named tile, and a [check] that must
     * pass for the run to succeed.
     */
    class Panel(
        val name: String,
        val title: String,
        val build: () -> Figure
    )

    /**
     * A captioned raster tile.
     *
     * [zoom] magnifies the tile for display by integer pixel replication (nearest-neighbour, no
     * interpolation, no invented detail) — used for pyramid levels, where a 12x12 image is the
     * point but a 12x12 thumbnail is unreadable. The caption always states the true size.
     */
    class Tile(
        val caption: String,
        val img: Img,
        val overlays: List<Overlay> = emptyList(),
        val zoom: Int = 1
    ) {
        init { require(zoom >= 1) { "zoom must be >= 1, got $zoom" } }

        /** The image as displayed: [img] pixel-replicated [zoom] times, or [img] itself when 1. */
        fun display(): Img = if (zoom == 1) img else nearestZoom(img, zoom)
    }

    /** Caption suffix noting a display-only pixel zoom, so nothing about the figure is implicit. */
    private fun zoomNote(k: Int): String = if (k <= 1) "" else " (shown ${k}x, nearest)"

    /** Integer nearest-neighbour magnification — pure pixel replication. */
    private fun nearestZoom(img: Img, k: Int): Img {
        val w = img.width * k
        val h = img.height * k
        val out = FloatArray(w * h * img.channels)
        var i = 0
        for (y in 0 until h) for (x in 0 until w) for (c in 0 until img.channels) {
            out[i++] = img.data[img.index(x / k, y / k, c)]
        }
        return Img(w, h, img.channels, out)
    }

    /** Vector geometry drawn over a tile, in that tile's image coordinates. */
    class Overlay(val lines: List<FLine>, val color: Color, val width: Float = 1.4f, val dots: Boolean = false)

    /** A built figure: the tiles to lay out plus a one-line factual summary printed to stdout. */
    class Figure(val tiles: List<Tile>, val summary: String)

    // ==========================================================================================
    // Synthetic sources — deterministic, and with analytically known answers.
    // ==========================================================================================

    // Every synthetic source below is 10x larger in each direction than the panel it will be drawn
    // into (the compositor downsamples in `scaled`, below). That is deliberate: at 160x160 a filter
    // benchmark is dominated by JIT warmup and cache residency, and the gallery would be attesting
    // that the code works on toy inputs. At 1600x1600 — 2.6 Mpx, a real photograph — it is
    // attesting that it works at the size someone would actually use, and the panels look the same
    // because they were always a downsampled view of a larger truth.

    /** A bright rounded-corner-free square on a dark ground; corners at exactly (x0,y0)…(x1,y1). */
    private fun squareImg(w: Int = 1600, h: Int = 1600, x0: Int = 400, y0: Int = 400, x1: Int = 1199, y1: Int = 1199) =
        Img.generate(w, h) { x, y -> if (x in x0..x1 && y in y0..y1) 0.92f else 0.08f }

    /**
     * A test card: a disc, a square, a triangle and a thin bar on a soft gradient, plus a little
     * texture. Deterministic and rich enough to exercise every detector.
     */
    private fun testCard(w: Int = 2400, h: Int = 2000): Img = Img.generate(w, h) { x, y ->
        var v = 0.10f + 0.10f * (y / h.toFloat())
        // disc
        if (hypot(x - 560.0, y - 600.0) <= 300.0) v = 0.85f
        // square
        if (x in 1100..1650 && y in 300..850) v = 0.70f
        // triangle
        val tx = x - 1800.0
        val ty = y - 400.0
        if (ty in 0.0..550.0 && abs(tx) <= ty * 0.55) v = 0.95f
        // thin bar
        if (y in 1500..1560 && x in 240..2150) v = 0.90f
        // small blobs
        if (hypot(x - 1300.0, y - 1500.0) <= 100.0) v = 0.60f
        if (hypot(x - 1700.0, y - 1650.0) <= 70.0) v = 0.60f
        v
    }

    /**
     * A smooth, well-textured field — the source Lucas–Kanade and the pyramids like.
     *
     * **Multi-octave on purpose.** The original was a single fine octave (period ~30 px), which was
     * fine at 200x200 and silently fatal at 2000x2000: a coarse-to-fine tracker halves the image
     * four times, by which point a 30 px period has aliased away to nothing and there is no
     * structure left at the coarse levels to lock onto. Measured, not theorised — that version
     * tracked 0 of 125 seeds. Real images carry structure at every scale, so this sums coarse
     * octaves (period ~300 px, which survives to the top of the pyramid) with the fine detail that
     * sharpens the final refinement.
     */
    private fun textured(w: Int = 2000, h: Int = 2000): Img = Img.generate(w, h) { x, y ->
        (0.5 +
            0.20 * sin(x * 0.021) * cos(y * 0.017) +   // coarse: survives 4 halvings
            0.14 * sin((x + y) * 0.011) +              // coarse
            0.10 * sin(x * 0.21) * cos(y * 0.17) +     // fine: sharpens the last refinement
            0.06 * cos((x - 2.0 * y) * 0.07)           // fine
            ).toFloat().coerceIn(0f, 1f)
    }

    /** A checkerboard "document" seen flat, for the perspective-rectification panel. */
    private fun document(w: Int = 2000, h: Int = 1400): Img = Img.generate(w, h) { x, y ->
        val cell = if (((x / 200) + (y / 200)) % 2 == 0) 0.86f else 0.14f
        // a border so the rectification is obviously aligned
        if (x < 30 || y < 30 || x >= w - 30 || y >= h - 30) 0.5f else cell
    }

    /** Salt-and-pepper corruption of [src], deterministic for [seed]. */
    private fun saltPepper(src: Img, fraction: Double, seed: Int): Img {
        val out = src.copy()
        val rng = kotlin.random.Random(seed)
        val n = (out.pixelCount * fraction).toInt()
        repeat(n) {
            val p = rng.nextInt(out.pixelCount)
            out.data[p] = if (rng.nextBoolean()) 0f else 1f
        }
        return out
    }

    // ==========================================================================================
    // The panels
    // ==========================================================================================

    val PANELS: List<Panel> = listOf(

        Panel("01-filters", "Smoothing — Gaussian, box, median, bilateral, unsharp") {
            val src = saltPepper(testCard(), 0.04, 11)
            val gauss = src.gaussianBlur(2.0)
            val med = src.median(2)
            val bil = src.bilateral(3.0, 0.12)
            val sharp = testCard().gaussianBlur(2.0).unsharpMask(2.0, 1.4)
            // The median must beat the Gaussian at removing impulse noise.
            val clean = testCard()
            val eMed = med.meanAbsDiff(clean)
            val eGauss = gauss.meanAbsDiff(clean)
            check(eMed < eGauss) { "median ($eMed) should beat the Gaussian ($eGauss) on impulse noise" }
            Figure(
                listOf(
                    Tile("source + 4% salt-and-pepper", src),
                    Tile("gaussianBlur(2.0)", gauss),
                    Tile("median(2)", med),
                    Tile("bilateral (edge-preserving; keeps impulses)", bil),
                    Tile("unsharpMask(2.0, 1.4)", sharp)
                ),
                "median residual ${"%.4f".format(eMed)} < gaussian ${"%.4f".format(eGauss)}"
            )
        },

        Panel("02-gradients", "Gradients — Sobel dx / dy, magnitude, orientation, Laplacian, DoG") {
            val src = testCard()
            val grad = src.sobel()
            val mag = grad.magnitude()
            // A unit-ish step: the bar's edges must show up clearly.
            check(mag.max() > 0.3f) { "gradient magnitude peaked at only ${mag.max()}" }
            check(mag.at(1200, 1200) < 0.02f) { "flat ground has a gradient of ${mag.at(1200, 1200)}" }
            Figure(
                listOf(
                    Tile("source", src),
                    Tile("sobel().dx (coolwarm, signed)", grad.dx.colormapDiverging("coolwarm")),
                    Tile("sobel().dy (coolwarm, signed)", grad.dy.colormapDiverging("coolwarm")),
                    Tile("magnitude (inferno)", mag.normalized().colormap("inferno")),
                    Tile("orientation (hue) x magnitude", grad.toColorImg()),
                    Tile("differenceOfGaussians(1, 3)", src.differenceOfGaussians(1.0, 3.0).colormapDiverging())
                ),
                "|grad| max ${"%.3f".format(mag.max())}, flat ground ${"%.4f".format(mag.at(120, 120))}"
            )
        },

        Panel("03-canny", "Canny — non-maximum suppression + hysteresis, and the FLines it becomes") {
            val src = testCard()
            val raw = src.gaussianBlur(1.0).sobelMagnitude().threshold(0.05f)
            val edges = src.canny(0.05, 0.11, sigma = 1.2)
            val nOn = edges.data.count { it > 0.5f }
            val nRaw = raw.data.count { it > 0.5f }
            // "Thin" means edge pixels scale with PERIMETER, not area — so the bound is expressed
            // against sqrt(pixelCount) and holds at any image size. An absolute count would have to
            // be retuned every time the source grows, which is exactly the bug this replaced.
            val thinness = nOn / sqrt(src.pixelCount.toDouble())
            check(thinness in 0.5..8.0) { "expected a thin edge map, got $nOn px (thinness $thinness)" }
            check(nOn < nRaw) { "Canny ($nOn) should be thinner than a raw threshold ($nRaw)" }
            val flines = edges.toContourFLines(minPerimeter = 120, simplifyTolerance = 12.0)
            Figure(
                listOf(
                    Tile("source", src),
                    Tile("raw |grad| > 0.05 (thick)", raw),
                    Tile("canny(0.05, 0.11) (thin)", edges),
                    Tile("edges -> ${flines.size} FLines", src, listOf(Overlay(flines, CYAN, 1.4f)))
                ),
                "canny $nOn px vs raw threshold $nRaw px; ${flines.size} FLines"
            )
        },

        Panel("04-corners", "Corners — Harris, Shi–Tomasi and FAST on a square with known corners") {
            val src = squareImg(1600, 1600, 400, 400, 1199, 1199)
            // minDistance is a separation in pixels, so it scales with the image; the accuracy
            // bounds below do NOT — corner localisation is a per-pixel property and stays sub-5px
            // however large the picture gets. That distinction is the whole trick to scaling a
            // test: scale the lengths, hold the accuracies.
            val harris = src.harrisCorners(threshold = 0.05, minDistance = 120, maxCount = 20)
            val shi = src.shiTomasiCorners(threshold = 0.05, minDistance = 120, maxCount = 20)
            val fast = src.fastCorners(threshold = 0.2, minDistance = 120, maxCount = 20)
            val truth = listOf(400.0 to 400.0, 1199.0 to 400.0, 400.0 to 1199.0, 1199.0 to 1199.0)
            check(harris.size == 4) { "Harris found ${harris.size} corners, expected 4" }
            check(shi.size == 4) { "Shi-Tomasi found ${shi.size} corners, expected 4" }
            for ((ex, ey) in truth) {
                check(harris.any { hypot(it.x - ex, it.y - ey) < 4.0 }) { "no Harris corner near ($ex, $ey)" }
                check(shi.any { hypot(it.x - ex, it.y - ey) < 4.0 }) { "no Shi-Tomasi corner near ($ex, $ey)" }
                check(fast.any { hypot(it.x - ex, it.y - ey) < 5.0 }) { "no FAST corner near ($ex, $ey)" }
            }
            val worst = truth.maxOf { (ex, ey) -> harris.minOf { hypot(it.x - ex, it.y - ey) } }
            Figure(
                listOf(
                    Tile("harrisResponse (coolwarm, signed)", src.harrisResponse().colormapDiverging("coolwarm")),
                    Tile("shiTomasiResponse (viridis)", src.shiTomasiResponse().normalized().colormap("viridis")),
                    Tile(
                        "harrisCorners -> ${harris.size}", src,
                        listOf(Overlay(afield.stdlib.cornersToFLines(harris, 70.0), ORANGE, 2f))
                    ),
                    Tile(
                        "fastCorners -> ${fast.size}", src,
                        listOf(Overlay(afield.stdlib.cornersToFLines(fast, 70.0, box = true), GREEN, 2f))
                    )
                ),
                "4/4 Harris corners within ${"%.2f".format(worst)} px of the true square corners"
            )
        },

        Panel("05-hough", "Hough — recovering global lines from purely local edge evidence") {
            // Three lines at exactly known angles, plus a decoy disc.
            val src = Img.zeros(2000, 2000, 1)
            for (x in 100 until 1900) src.set(x, 400, 0, 1f)           // horizontal, rho = 400
            for (y in 100 until 1900) src.set(1500, y, 0, 1f)          // vertical,   rho = 1500
            for (t in 0 until 2400) {                                  // a 45-degree diagonal
                val x = 200 + t * 0.6
                val y = 600 + t * 0.6
                if (x < 1999 && y < 1999) src.set(x.toInt(), y.toInt(), 0, 1f)
            }
            for (i in 0 until 3600) {                                  // decoy circle
                val a = 2 * PI * i / 3600
                val x = (600 + 220 * cos(a)).toInt(); val y = (1500 + 220 * sin(a)).toInt()
                if (x in 0..1999 && y in 0..1999) src.set(x, y, 0, 1f)
            }
            val lines = houghLines(src, thetaSteps = 360, threshold = 1000, maxLines = 5)
            check(lines.size >= 3) { "expected >= 3 Hough lines, got ${lines.size}" }
            check(lines.any { abs(it.theta - PI / 2) < 0.05 && abs(it.rho - 400.0) < 5.0 }) {
                "horizontal line (rho=400, theta=pi/2) not recovered: $lines"
            }
            check(lines.any { it.theta < 0.05 && abs(it.rho - 1500.0) < 5.0 }) {
                "vertical line (rho=1500, theta=0) not recovered: $lines"
            }
            val flines = afield.stdlib.linesToFLines(lines, 2000, 2000)
            Figure(
                listOf(
                    Tile("edge mask (3 lines + a decoy ring)", src),
                    Tile("houghLines -> ${lines.size}", src, listOf(Overlay(flines, ORANGE, 1.8f)))
                ),
                "lines " + lines.joinToString(", ") { "(rho=${"%.0f".format(it.rho)}, θ=${"%.2f".format(it.theta)}, v=${it.votes})" }
            )
        },

        Panel("06-morphology", "Morphology + connected components + distance transform") {
            val noisy = saltPepper(testCard(), 0.03, 5)
            val mask = noisy.thresholdOtsu()
            val cleaned = mask.open(2, StructuringElement.DISC).close(2, StructuringElement.DISC)
            val cc = cleaned.connectedComponents(8)
            val big = cc.filterByArea(8000)
            check(big.size in 3..8) { "expected ~4 large blobs after cleaning, got ${big.size}: ${big.map { it.area }}" }
            val dt = cleaned.distanceTransform()
            // The disc in the test card has radius 30; its distance-transform peak must be ~30.
            val discPeak = dt.crop(260, 300, 600, 600).max()
            check(discPeak > 200f) { "distance transform peaked at only $discPeak inside the r=300 disc" }
            val contours = cleaned.toContourFLines(minPerimeter = 240, simplifyTolerance = 15.0)
            Figure(
                listOf(
                    Tile("noisy source", noisy),
                    Tile("thresholdOtsu()", mask),
                    Tile("open(2,DISC).close(2,DISC)", cleaned),
                    Tile("distanceTransform (magma)", dt.normalized().colormap("magma")),
                    Tile("erode(3) vs dilate(3)", afield.stdlib.hstack(cleaned.erode(3), cleaned.dilate(3), gap = 4)),
                    Tile(
                        "${cc.count} components, ${contours.size} contour FLines", cleaned,
                        listOf(Overlay(contours, PINK, 1.6f))
                    )
                ),
                "${cc.count} components (${big.size} with area >= 8000), distance-transform peak ${"%.1f".format(discPeak)}"
            )
        },

        Panel("07-pyramids", "Multiscale — Gaussian pyramid and an invertible Laplacian pyramid") {
            val src = textured(1920, 1920)
            val gp = src.gaussianPyramid(levels = 5, minSize = 120)
            val lp = src.laplacianPyramid(levels = 4, minSize = 120)
            val back = lp.reconstruct()
            var worst = 0f
            for (i in src.data.indices) worst = maxOf(worst, abs(src.data[i] - back.data[i]))
            check(worst < 1e-4f) { "Laplacian pyramid round-trip error $worst" }
            // Display zoom only: each level is pixel-replicated up to ~the base size so the
            // coarsening reads as blockiness instead of as a 12x12 speck. Real sizes in captions.
            fun zoomFor(im: Img) = maxOf(1, 1920 / maxOf(im.width, im.height))
            val tiles = ArrayList<Tile>()
            gp.levels.forEachIndexed { i, l ->
                tiles.add(Tile("gaussian L$i — ${l.width}x${l.height}" + zoomNote(zoomFor(l)), l, zoom = zoomFor(l)))
            }
            lp.bands.forEachIndexed { i, b ->
                val cm = b.colormapDiverging()
                tiles.add(Tile("laplacian band $i — ${b.width}x${b.height}" + zoomNote(zoomFor(b)), cm, zoom = zoomFor(b)))
            }
            tiles.add(
                Tile(
                    "residual — ${lp.residual.width}x${lp.residual.height}" + zoomNote(zoomFor(lp.residual)),
                    lp.residual, zoom = zoomFor(lp.residual)
                )
            )
            tiles.add(Tile("reconstruct() (err ${"%.1e".format(worst)})", back))
            Figure(tiles, "pyramid ${gp.levels.map { "${it.width}" }.joinToString("->")}; reconstruction max error ${"%.2e".format(worst)}")
        },

        Panel("08-warps", "Geometry — affine warps, and a homography rectifying a perspective view") {
            val doc = document(2000, 1400)
            val h = Homography(doubleArrayOf(1.0, 0.16, 180.0, 0.09, 1.0, 140.0, 0.00016, 0.00013, 1.0))
            val projected = doc.warpPerspective(h, 2600, 2000, border = Border.ZERO)
            val quad = listOf(Vec2(0f, 0f), Vec2(1999f, 0f), Vec2(1999f, 1399f), Vec2(0f, 1399f)).map { h.apply(it) }
            val rectified = projected.warpQuadToRect(quad, 2000, 1400)
            val err = doc.crop(120, 120, 1760, 1160).meanAbsDiff(rectified.crop(120, 120, 1760, 1160))
            check(err < 0.10) { "rectification error $err is too large" }
            val quadLine = afield.stdlib.contourToFLine(quad, closed = true)
            Figure(
                listOf(
                    Tile("source document", doc),
                    Tile("rotate(0.35 rad)", doc.rotate(0.35, border = Border.CLAMP)),
                    Tile("warpAffine(shear + scale)", doc.warpAffine(Affine2D(1.0, 0.22, 0.3, 0.9, 0.0, 0.0), 2400, 1800, border = Border.ZERO)),
                    Tile("projected through a homography", projected, listOf(Overlay(listOf(quadLine), ORANGE, 2f))),
                    Tile("warpQuadToRect (err ${"%.3f".format(err)})", rectified)
                ),
                "rectification mean-abs error ${"%.4f".format(err)} over the interior"
            )
        },

        Panel("09-flow", "Motion — Lucas–Kanade flow and the homography RANSAC fits to it") {
            val a = textured(2000, 2000)
            val planted = Affine2D.rotationAbout(0.05, 1000.0, 1000.0).compose(Affine2D.translation(50.0, -30.0))
            val b = a.warpAffine(planted, border = Border.CLAMP)
            val seeds = a.goodFeaturesToTrack(maxCount = 220, minDistance = 90)
                .filter { it.x in 240.0..1760.0 && it.y in 240.0..1760.0 }
                .map { it.toVec2() }
            check(seeds.size >= 30) { "expected >= 30 seeds, got ${seeds.size}" }
            val flow = opticalFlowLK(a, b, seeds, windowRadius = 8, levels = 5)
            check(flow.tracked().size >= seeds.size / 2) { "only ${flow.tracked().size}/${seeds.size} tracked" }
            val fit = findHomographyRansac(flow.sourcePoints(), flow.targetPoints(), threshold = 1.5, iterations = 1200, seed = 3L)
            // Every arrow must agree with the planted motion at its own location.
            var worstArrow = 0.0
            for (v in flow.tracked()) {
                val (tx, ty) = planted.apply(v.x0, v.y0)
                worstArrow = maxOf(worstArrow, hypot(v.x1 - tx, v.y1 - ty))
            }
            check(worstArrow < 3.0) { "an LK arrow disagrees with the planted motion by $worstArrow px" }
            var worstFit = 0.0
            for (gx in 400..1600 step 400) for (gy in 400..1600 step 400) {
                val (fx, fy) = fit.homography.apply(gx.toDouble(), gy.toDouble())
                val (tx, ty) = planted.apply(gx.toDouble(), gy.toDouble())
                worstFit = maxOf(worstFit, hypot(fx - tx, fy - ty))
            }
            check(worstFit < 2.0) { "RANSAC's homography is off by up to $worstFit px" }
            val arrows = flow.toFLines(scale = 6.0, minMagnitude = 2.0, headSize = 40.0)
            val seedDots = afield.stdlib.cornersToFLines(
                seeds.map { afield.stdlib.Corner(it.x.toDouble(), it.y.toDouble(), 1.0) }, 25.0
            )
            Figure(
                listOf(
                    Tile("frame A", a, listOf(Overlay(seedDots, DIM, 1f))),
                    Tile("frame B (rotate 0.05 + shift 50,-30)", b),
                    Tile("|A - B|", afield.stdlib.frameDifference(a, b).normalized().colormap("inferno")),
                    Tile(
                        "LK flow x6 — ${flow.tracked().size} tracks", a,
                        listOf(Overlay(arrows, ORANGE, 1.5f))
                    )
                ),
                "${flow.tracked().size}/${seeds.size} tracked, worst arrow ${"%.2f".format(worstArrow)} px, " +
                    "RANSAC ${fit.inlierCount} inliers, model within ${"%.2f".format(worstFit)} px"
            )
        },

        Panel("10-colour", "Colour — luma, HSV, sRGB-vs-linear blur, and the Colormaps bridge") {
            val rgb = Img.generateRGB(2000, 1400) { x, y ->
                val u = x / 1999f
                val v = y / 1399f
                Triple(
                    (0.5f + 0.5f * sin((u * 6.0).toFloat())).coerceIn(0f, 1f),
                    (0.5f + 0.5f * sin((v * 5.0 + 1.0).toFloat())).coerceIn(0f, 1f),
                    (0.5f + 0.5f * sin(((u + v) * 4.0 + 2.0).toFloat())).coerceIn(0f, 1f)
                )
            }
            val hsv = rgb.rgbToHsv()
            // Blurring in linear light vs in sRGB gives measurably different results.
            // sigma scales WITH the picture here, unlike the pixel-scale radii elsewhere: this
            // source's pattern is defined in normalised coordinates, so it is the same image
            // enlarged, and a 4 px blur on a 10x larger copy barely touches it (the sRGB-vs-linear
            // difference collapsed to 3.7e-5, below this panel's own threshold). Blurring the same
            // FRACTION of the picture keeps it the same demonstration.
            val naive = rgb.gaussianBlur(40.0)
            val correct = rgb.toLinear().gaussianBlur(40.0).toSrgb()
            val delta = naive.meanAbsDiff(correct)
            check(delta > 0.002) { "sRGB and linear-light blurs are suspiciously identical ($delta)" }
            val roundTrip = hsv.hsvToRgb().meanAbsDiff(rgb)
            check(roundTrip < 1e-5) { "HSV round-trip error $roundTrip" }
            val ramp = Img.generate(2000, 240) { x, _ -> x / 1999f }
            Figure(
                listOf(
                    Tile("source RGB", rgb),
                    Tile("luma() (BT.709)", rgb.luma()),
                    Tile("hue channel", hsv.channel(0)),
                    Tile("saturation channel", hsv.channel(1)),
                    Tile("blur in sRGB (naive)", naive),
                    Tile("blur in linear light (correct)", correct),
                    Tile("|difference| x20 (inferno)", (naive - correct).abs().luma().map { (it * 20f).coerceAtMost(1f) }.colormap("inferno")),
                    Tile(
                        "Colormaps: viridis / turbo / magma / coolwarm",
                        afield.stdlib.vstack(
                            ramp.colormap("viridis"), ramp.colormap("turbo"),
                            ramp.colormap("magma"), ramp.colormap("coolwarm"), gap = 3
                        )
                    )
                ),
                "sRGB-vs-linear blur differs by ${"%.4f".format(delta)}; HSV round-trip ${"%.2e".format(roundTrip)}"
            )
        }
    )

    // ==========================================================================================
    // Rendering
    // ==========================================================================================

    private const val TILE_MAX = 240
    private const val GAP = 14
    private const val PAD = 18
    private const val CAPTION_H = 22
    private const val TITLE_H = 34
    private const val COLUMNS = 3

    private val TITLE_FONT = Font(Font.SANS_SERIF, Font.BOLD, 15)
    private val CAPTION_FONT = Font(Font.SANS_SERIF, Font.PLAIN, 11)
    private val SUMMARY_FONT = Font(Font.SANS_SERIF, Font.ITALIC, 11)

    /**
     * Lay the figure out on a **uniform cell grid**: every cell is as wide/tall as the largest
     * tile, tiles are centred in their cell and captions are ellipsised to the cell width. That
     * costs some whitespace on a panel with wildly different tile sizes (the pyramid one) but it
     * is the only way rows stay aligned and captions never collide.
     */
    private fun renderPanel(panel: Panel, fig: Figure): BufferedImage {
        data class Placed(val tile: Tile, val w: Int, val h: Int)

        val placed = fig.tiles.map { t ->
            val d = t.display()
            val s = minOf(1.0, TILE_MAX.toDouble() / maxOf(d.width, d.height))
            Placed(t, maxOf(1, (d.width * s).toInt()), maxOf(1, (d.height * s).toInt()))
        }
        val cellW = placed.maxOf { it.w }
        val cellH = placed.maxOf { it.h }
        val columns = minOf(COLUMNS, placed.size)
        val rows = placed.chunked(columns)

        // Measure the title and summary so the figure is never narrower than its own text.
        val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics()
        probe.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val titleW = probe.getFontMetrics(TITLE_FONT).stringWidth(panel.title)
        val summaryW = probe.getFontMetrics(SUMMARY_FONT).stringWidth(fig.summary)
        probe.dispose()

        val gridW = columns * cellW + GAP * (columns - 1)
        val W = maxOf(gridW, titleW, summaryW) + PAD * 2
        val H = TITLE_H + rows.size * (cellH + CAPTION_H) + GAP * (rows.size - 1) + PAD * 2 + 18

        val out = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        g.color = BG
        g.fillRect(0, 0, W, H)

        g.color = TEXT
        g.font = TITLE_FONT
        g.drawString(panel.title, PAD.toFloat(), 24f)

        var y = TITLE_H + PAD
        for (row in rows) {
            row.forEachIndexed { col, p ->
                val cellX = PAD + col * (cellW + GAP)
                val x = cellX + (cellW - p.w) / 2
                g.color = PANEL_BG
                g.fillRect(x - 1, y - 1, p.w + 2, p.h + 2)
                val disp = p.tile.display()
                val scaled = if (p.w == disp.width && p.h == disp.height) disp else disp.resize(p.w, p.h)
                g.drawImage(scaled.toRGB().toBufferedImage(), x, y, null)
                // Vector overlays, in the tile's (scaled) image coordinates.
                for (ov in p.tile.overlays) {
                    g.color = ov.color
                    g.stroke = BasicStroke(ov.width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    val sx = p.w.toDouble() / p.tile.img.width
                    val sy = p.h.toDouble() / p.tile.img.height
                    for (fl in ov.lines) drawFLine(g, fl, x.toDouble(), y.toDouble(), sx, sy, ov.dots)
                }
                g.color = FRAME
                g.stroke = BasicStroke(1f)
                g.drawRect(x - 1, y - 1, p.w + 1, p.h + 1)
                g.color = DIM
                g.font = CAPTION_FONT
                g.drawString(ellipsise(g, p.tile.caption, cellW), cellX.toFloat(), (y + cellH + 15).toFloat())
            }
            y += cellH + CAPTION_H + GAP
        }

        g.color = DIM
        g.font = SUMMARY_FONT
        g.drawString(fig.summary, PAD.toFloat(), (H - 12).toFloat())
        g.dispose()
        return out
    }

    /** Trim [s] with a trailing ellipsis so it fits in [maxW] pixels in the current font. */
    private fun ellipsise(g: java.awt.Graphics2D, s: String, maxW: Int): String {
        val fm = g.fontMetrics
        if (fm.stringWidth(s) <= maxW) return s
        var cut = s.length
        while (cut > 1 && fm.stringWidth(s.take(cut) + "…") > maxW) cut--
        return s.take(cut) + "…"
    }

    /** Draw an [FLine]'s nodes as a polyline (cubics are flattened by sampling their endpoints). */
    private fun drawFLine(
        g: java.awt.Graphics2D,
        line: FLine,
        ox: Double,
        oy: Double,
        sx: Double,
        sy: Double,
        dots: Boolean
    ) {
        val path = Path2D.Double()
        var pen = false
        for (n in line.nodes) {
            if (n is FLine.Close) {
                if (pen) path.closePath()
                continue
            }
            if (n.storage.isEmpty()) continue
            val px = ox + n.to.x * sx
            val py = oy + n.to.y * sy
            when (n) {
                is FLine.MoveTo -> { path.moveTo(px, py); pen = true }
                is FLine.CubicTo -> {
                    val c1 = n.c1; val c2 = n.c2
                    if (!pen) { path.moveTo(px, py); pen = true }
                    else path.curveTo(ox + c1.x * sx, oy + c1.y * sy, ox + c2.x * sx, oy + c2.y * sy, px, py)
                }
                else -> if (pen) path.lineTo(px, py) else { path.moveTo(px, py); pen = true }
            }
            if (dots) g.fill(Ellipse2D.Double(px - 1.5, py - 1.5, 3.0, 3.0))
        }
        if (!dots) g.draw(path)
    }

    /** Fraction of pixels that differ from the flat panel background — a "did anything render" guard. */
    private fun paintedFraction(img: BufferedImage): Double {
        val bg = BG.rgb and 0xFFFFFF
        val panel = PANEL_BG.rgb and 0xFFFFFF
        var painted = 0
        var seen = 0
        var y = 0
        while (y < img.height) {
            var x = 0
            while (x < img.width) {
                val p = img.getRGB(x, y) and 0xFFFFFF
                if (p != bg && p != panel) painted++
                seen++
                x += 2
            }
            y += 2
        }
        return painted.toDouble() / seen.coerceAtLeast(1)
    }

    // ==========================================================================================
    // Entry point
    // ==========================================================================================

    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("java.awt.headless", "true")
        val outDir = File(
            args.getOrNull(0) ?: System.getenv("IMAGING_GALLERY_DIR")
                ?: File(System.getProperty("java.io.tmpdir"), "imaging-gallery").path
        )
        outDir.mkdirs()

        // A real photograph from the repo, if one is available, proves the loader on real data.
        realPhotoSample()?.let { (path, img) ->
            // A dark, softly-lit photograph is exactly where a hand-picked (low, high) pair finds
            // nothing; cannyAuto derives them from this image's own gradient distribution.
            val fixed = img.luma().canny(0.06, 0.16, sigma = 1.4)
            val edges = img.luma().cannyAuto(0.90, 0.4, sigma = 1.4)
            val on = edges.data.count { it > 0.5f }
            val onFixed = fixed.data.count { it > 0.5f }
            check(on > 0) { "cannyAuto found no edges in the real photo $path" }
            check(on > onFixed * 2) { "cannyAuto ($on) should find far more than the fixed pair ($onFixed)" }
            val flines = edges.toContourFLines(minPerimeter = 20, simplifyTolerance = 1.2)
            val fig = Figure(
                listOf(
                    Tile("imgFromFile(\"${File(path).name}\")", img),
                    Tile("luma()", img.luma()),
                    Tile("sobelMagnitude() (inferno)", img.luma().sobelMagnitude().normalizedPercentile(0.0, 0.995).colormap("inferno")),
                    Tile("canny(0.06, 0.16) — too strict here: $onFixed px", fixed),
                    Tile("cannyAuto() -> $on edge px", edges),
                    Tile("-> ${flines.size} FLines over the source", img, listOf(Overlay(flines, CYAN, 1.2f)))
                ),
                "real file: ${File(path).name} (${img.width}x${img.height}, ${img.channels} ch); " +
                    "cannyAuto $on edge px vs $onFixed for a fixed pair; ${flines.size} FLines"
            )
            val png = renderPanel(Panel("00-real-image", "A real PNG from the repo, through the pipeline") {
                fig
            }, fig)
            val f = File(outDir, "00-real-image.png")
            ImageIO.write(png, "png", f)
            println("wrote ${f.absolutePath} (${png.width}x${png.height}) — ${fig.summary}")
        } ?: println("no repo PNG found to sample; skipping the real-image panel")

        for (panel in PANELS) {
            val fig = panel.build()
            check(fig.tiles.isNotEmpty()) { "${panel.name} produced no tiles" }
            for (t in fig.tiles) {
                check(t.img.data.all { it.isFinite() }) { "${panel.name} / ${t.caption} contains non-finite samples" }
                check(t.img.max() > t.img.min() + 1e-6f) {
                    "${panel.name} / ${t.caption} is a flat image (all ${t.img.min()})"
                }
            }
            val png = renderPanel(panel, fig)
            val f = File(outDir, "${panel.name}.png")
            ImageIO.write(png, "png", f)
            val painted = paintedFraction(png)
            // The uniform cell grid leaves real whitespace on panels whose tiles differ wildly in
            // size (the pyramid one is ~24% painted), so this is only a "did anything render at
            // all" floor; the per-tile finite/non-flat checks above are the real guard.
            check(painted > 0.15) { "${panel.name} rendered nearly empty (painted=$painted)" }
            println("wrote ${f.absolutePath} (${png.width}x${png.height}, painted=${"%.3f".format(painted)}) — ${fig.summary}")
        }

        // A bare Img.savePng round-trip, so the file-writing path is exercised too.
        val probe = File(outDir, "_probe.png")
        Img.generate(8, 8) { x, y -> ((x + y) % 2).toFloat() }.savePng(probe.path)
        check(probe.isFile && probe.length() > 8) { "savePng wrote nothing to $probe" }
        val reread = imgFromFile(probe.path, 1)
        check(reread.width == 8 && reread.at(1, 0) == 1f && reread.at(0, 0) == 0f) { "savePng/imgFromFile round-trip failed" }
        probe.delete()

        println("imaging gallery rendered to ${outDir.absolutePath} (${PANELS.size} panels)")
    }

    /** Find a small-enough real PNG in the repo to run through the pipeline; `null` if none. */
    private fun realPhotoSample(): Pair<String, Img>? {
        // A hand-picked rich, photographic capture reads far better than the first PNG on disk
        // (which tends to be a line diagram); fall back to a scan if the repo layout moves.
        val preferred = listOf(
            "public/docs/assets/fabricate-toy-car-donmccurdy-reference-close.png",
            "public/docs/assets/fabricate-shiny-checkerboard.png",
            "public/docs/assets/fabricate-ao.png"
        )
        for (path in preferred) {
            val f = File(path)
            if (!f.isFile) continue
            val img = try { imgFromFile(f.path, 3) } catch (_: Exception) { continue }
            val scaled = if (maxOf(img.width, img.height) > 420) {
                img.scaleBy(420.0 / maxOf(img.width, img.height))
            } else img
            return f.path to scaled
        }
        val roots = listOf(
            File("public/docs/assets"),
            File("dev_docs/assets"),
            File("public/docs"),
            File("src/commonMain/resources")
        )
        for (r in roots) {
            if (!r.isDirectory) continue
            val cand = r.walkTopDown()
                .maxDepth(3)
                .filter { it.isFile && it.extension.equals("png", true) && it.length() in 2_000..3_000_000 }
                .sortedBy { it.path }
                .firstOrNull { f ->
                    try {
                        val bi = ImageIO.read(f) ?: return@firstOrNull false
                        bi.width in 64..4000 && bi.height in 64..4000
                    } catch (_: Exception) { false }
                } ?: continue
            val img = imgFromFile(cand.path, 3)
            // Keep the panel small.
            val scaled = if (maxOf(img.width, img.height) > 480) {
                img.scaleBy(480.0 / maxOf(img.width, img.height))
            } else img
            return cand.path to scaled
        }
        return null
    }
}
