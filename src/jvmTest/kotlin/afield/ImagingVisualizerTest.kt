package afield

import afield.stdlib.FlowField
import afield.stdlib.FlowVector
import afield.stdlib.HoughCircle
import afield.stdlib.HoughLine
import afield.stdlib.Img
import afield.stdlib.connectedComponents
import afield.stdlib.gaussianPyramid
import afield.stdlib.harrisCorners
import afield.stdlib.laplacianPyramid
import afield.stdlib.sobel
import java.awt.image.BufferedImage
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Behavioural test for [ImagingVisualizer] — the inline visualizers for the CPU imaging / CV family.
 *
 * The point of these assertions is not "HTML came out": it is that the *pixels* the visualizer
 * inlines are the right pixels. Every raster claim here is checked by **decoding the emitted
 * base64 PNG back into a `BufferedImage`** and reading actual samples out of it, because the
 * failures that matter in this kind of code are silent and visual — channels swapped so a red
 * square renders blue, a NaN turning the whole thumbnail black, a signed gradient collapsing to
 * flat grey. `assertTrue("<img" in html)` would pass through all three.
 *
 * Nothing here writes into the repository tree (cf. the `sparkline-proof/` directory
 * `ArraySparklineVisualizerTest` leaves behind); the human-facing captures are made by
 * [ImagingVisualizerDocCaptureTest], which defaults to a temp directory too.
 */
class ImagingVisualizerTest {

    private fun viz(value: Any?): String = ValueVisualizer.visualize(value)

    /** Every inlined PNG in [html], decoded. */
    private fun pngs(html: String): List<BufferedImage> =
        Regex("data:image/png;base64,([A-Za-z0-9+/=]+)").findAll(html).map { m ->
            ImageIO.read(Base64.getDecoder().decode(m.groupValues[1]).inputStream())
                ?: error("emitted PNG did not decode")
        }.toList()

    private fun rgbAt(img: BufferedImage, fx: Double, fy: Double): Triple<Int, Int, Int> {
        val argb = img.getRGB((fx * img.width).toInt().coerceIn(0, img.width - 1),
            (fy * img.height).toInt().coerceIn(0, img.height - 1))
        return Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
    }

    private fun count(html: String, needle: String): Int =
        html.split(needle).size - 1

    // ---------------------------------------------------------------------------------------
    // Img: channels, and the range policy
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a red RGB image really renders red — channels are not swapped`() {
        val red = Img.generateRGB(24, 24) { _, _ -> Triple(0.9f, 0.1f, 0.1f) }
        val html = viz(red)
        assertTrue("Img" in html && "rgb" in html, "expected the Img readout: $html")
        val (r, g, b) = rgbAt(pngs(html).first(), 0.5, 0.5)
        assertTrue(r > 200, "red channel should dominate, got ($r, $g, $b)")
        assertTrue(g < 60 && b < 60, "green/blue should be dark, got ($r, $g, $b)")
    }

    @Test
    fun `a green quadrant lands in the right corner — geometry is not flipped`() {
        // Green only in the top-left quadrant; origin is top-left for Img and for PNG.
        val img = Img.generateRGB(40, 40) { x, y ->
            if (x < 20 && y < 20) Triple(0f, 0.95f, 0f) else Triple(0.05f, 0.05f, 0.05f)
        }
        val decoded = pngs(viz(img)).first()
        val tl = rgbAt(decoded, 0.25, 0.25)
        val br = rgbAt(decoded, 0.75, 0.75)
        assertTrue(tl.second > 200 && tl.first < 60, "top-left should be green, got $tl")
        assertTrue(br.second < 60, "bottom-right should be dark, got $br")
    }

    @Test
    fun `an image in 0 to 1 is shown direct and says so`() {
        val ramp = Img.generate(32, 32) { x, _ -> x / 31f }
        val html = viz(ramp)
        assertTrue("mode</span>=<span style='color:#fc8'>direct" in html.replace("\n", ""),
            "expected direct mode in the stats: $html")
        assertTrue("auto-ranged" !in html, "a 0..1 image must not be rescaled")
        val decoded = pngs(html).first()
        assertTrue(rgbAt(decoded, 0.02, 0.5).first < 30, "left edge of a ramp is black")
        assertTrue(rgbAt(decoded, 0.98, 0.5).first > 225, "right edge of a ramp is white")
    }

    @Test
    fun `an unnormalised positive image is auto-ranged, with the true range printed`() {
        val hot = Img.generate(32, 32) { x, _ -> x / 31f * 40f }
        val html = viz(hot)
        assertTrue("auto-ranged" in html, "expected auto-ranged mode: $html")
        assertTrue("range 0&hellip;40" in html, "expected the true 0..40 range in the caption: $html")
        // Auto-ranged, so the top of the range is white rather than clipped-white everywhere.
        val decoded = pngs(html).first()
        assertTrue(rgbAt(decoded, 0.02, 0.5).first < 30, "low end stays dark")
        assertTrue(rgbAt(decoded, 0.98, 0.5).first > 225, "high end reaches white")
        assertTrue(rgbAt(decoded, 0.5, 0.5).first in 90..165, "midpoint is mid-grey, not clipped")
    }

    @Test
    fun `a signed gradient uses a diverging map with zero in the middle`() {
        // A bright vertical bar: dx rises at its left edge and falls at its right edge, so the
        // response is genuinely two-signed — the case a min..max normalisation renders as mush.
        val bar = Img.generate(48, 48) { x, _ -> if (x in 16..31) 1f else 0f }
        val dx = bar.sobel().dx
        assertTrue(dx.min() < -0.4f && dx.max() > 0.4f, "the fixture must produce a two-signed dx")
        val html = viz(dx)
        assertTrue("signed" in html, "expected signed mode: $html")
        assertTrue("coolwarm" in html, "a signed scalar should get the coolwarm diverging ramp: $html")
        val decoded = pngs(html).first()
        // Far from either edge the gradient is 0 -> the pale middle of coolwarm.
        val flat = rgbAt(decoded, 0.04, 0.5)
        assertTrue(flat.first > 170 && flat.third > 170,
            "zero gradient should be the pale centre of coolwarm, got $flat")
        val columns = (0 until decoded.width).map { x ->
            val argb = decoded.getRGB(x, decoded.height / 2)
            Triple(x, (argb shr 16) and 0xFF, argb and 0xFF)
        }
        val warmest = columns.maxBy { it.second - it.third }
        val coolest = columns.maxBy { it.third - it.second }
        assertTrue(warmest.second - warmest.third > 60, "expected a clearly warm column, got $warmest")
        assertTrue(coolest.third - coolest.second > 60, "expected a clearly cool column, got $coolest")
        // Rising edge (positive dx) before the falling edge (negative dx): warm left of cool.
        assertTrue(warmest.first < coolest.first,
            "the rising edge should be warm and left of the cool falling edge: $warmest / $coolest")
    }

    @Test
    fun `NaN and infinity are counted, excluded from the range, and painted magenta`() {
        val img = Img.generate(32, 32) { x, y ->
            when {
                x == 4 && y == 4 -> Float.NaN
                x == 5 && y == 5 -> Float.POSITIVE_INFINITY
                else -> 0.4f
            }
        }
        val html = viz(img)
        assertTrue("non-finite" in html, "the caption must admit the non-finite samples: $html")
        assertTrue("NaN" !in html.substringBefore("<details"), "the range must not read NaN: $html")
        assertTrue("range 0.4&hellip;0.4" in html, "range should come from the finite samples only: $html")
        val decoded = pngs(html).first()
        // The bulk of the image is a mid grey — the old behaviour was an all-black thumbnail.
        val bulk = rgbAt(decoded, 0.7, 0.7)
        assertTrue(bulk.first in 80..130, "the finite bulk should render mid-grey, got $bulk")
        // Somewhere there must be magenta.
        var magenta = 0
        for (y in 0 until decoded.height) for (x in 0 until decoded.width) {
            val argb = decoded.getRGB(x, y)
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            if (r > 200 && g < 60 && b > 180) magenta++
        }
        assertTrue(magenta >= 2, "expected the two non-finite pixels painted magenta, found $magenta")
    }

    @Test
    fun `an all-NaN image degrades to a readout instead of throwing or going black`() {
        val html = viz(Img.filled(16, 16, 1, Float.NaN))
        assertTrue("no finite samples" in html, "expected the honest empty-range readout: $html")
        val decoded = pngs(html).first()
        val (r, g, b) = rgbAt(decoded, 0.5, 0.5)
        assertTrue(r > 200 && b > 180 && g < 60, "an all-NaN image should be all magenta, got ($r, $g, $b)")
    }

    @Test
    fun `an RGBA image gets a checkerboard and keeps its alpha`() {
        val rgba = Img.generateRGB(32, 32) { _, _ -> Triple(1f, 1f, 1f) }
            .let { base ->
                val out = FloatArray(32 * 32 * 4)
                for (p in 0 until 32 * 32) {
                    out[p * 4] = base.data[p * 3]
                    out[p * 4 + 1] = base.data[p * 3 + 1]
                    out[p * 4 + 2] = base.data[p * 3 + 2]
                    out[p * 4 + 3] = if (p % 32 < 16) 1f else 0f
                }
                Img(32, 32, 4, out)
            }
        val html = viz(rgba)
        assertTrue("rgba" in html, "expected the rgba readout")
        assertTrue("linear-gradient" in html, "an alpha image needs the checkerboard backing: $html")
        val decoded = pngs(html).first()
        assertTrue(decoded.colorModel.hasAlpha(), "the emitted PNG must carry alpha")
        assertEquals(255, (decoded.getRGB(4, 4) shr 24) and 0xFF, "left half is opaque")
        assertEquals(0, (decoded.getRGB(decoded.width - 3, 4) shr 24) and 0xFF, "right half is transparent")
    }

    @Test
    fun `a constant image in the unit box still gets a real histogram`() {
        val html = viz(Img.filled(8, 8, 1, 0.5f))
        // The display range is the unit box, so a constant 0.5 is a single spike at the middle.
        assertTrue("peak 64" in html, "expected every pixel in one bin: $html")
        assertEquals(64, pngs(html).first().let { it.width * it.height }, "8x8 is not downsampled")
        val (r, _, _) = rgbAt(pngs(html).first(), 0.5, 0.5)
        assertTrue(r in 120..135, "0.5 should render as mid-grey, got $r")
    }

    @Test
    fun `a constant out-of-range image does not divide by zero`() {
        val html = viz(Img.filled(8, 8, 1, 7f))
        assertTrue("constant image" in html, "with no spread there is nothing to bin: $html")
        assertTrue("range 7&hellip;7" in html, "expected the true constant value: $html")
        val (r, g, b) = rgbAt(pngs(html).first(), 0.5, 0.5)
        assertTrue(r == g && g == b, "a constant scalar stays neutral, got ($r, $g, $b)")
    }

    @Test
    fun `the histogram peak matches the data`() {
        // 3/4 of the pixels at 0.25, 1/4 at 0.75 -> the peak bin holds exactly 768 of 1024.
        val img = Img.generate(32, 32) { _, y -> if (y < 24) 0.25f else 0.75f }
        val html = viz(img)
        assertTrue("peak 768" in html, "expected the true modal count in the histogram: $html")
    }

    @Test
    fun `a scalar image also offers a viridis reading of itself`() {
        val html = viz(Img.generate(24, 24) { x, y -> (x + y) / 46f })
        assertTrue("viridis" in html, "a 1-channel image should offer a colour-mapped variant: $html")
        // value + viridis strip, plus the main thumbnail.
        assertEquals(3, pngs(html).size, "expected main thumbnail plus the two-panel strip")
    }

    @Test
    fun `an RGB image splits into three channel thumbnails`() {
        val html = viz(Img.generateRGB(24, 24) { x, y -> Triple(x / 23f, y / 23f, 0.5f) })
        assertEquals(4, pngs(html).size, "expected the main thumbnail plus R, G, B")
        assertTrue(">R<" in html && ">G<" in html && ">B<" in html, "expected channel labels: $html")
    }

    @Test
    fun `a large image is downsampled to the thumbnail budget`() {
        val big = Img.generate(1600, 400) { x, _ -> (x % 64) / 63f }
        val decoded = pngs(viz(big)).first()
        assertEquals(240, decoded.width, "long edge is capped")
        assertEquals(60, decoded.height, "aspect ratio is preserved")
    }

    // ---------------------------------------------------------------------------------------
    // Pyramids, gradients, components
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a gaussian pyramid draws every rung at true relative scale`() {
        val pyr = Img.generate(128, 96) { x, y -> ((x / 8 + y / 8) % 2).toFloat() }.gaussianPyramid(4)
        val html = viz(pyr)
        assertTrue("GaussianPyramid" in html && "${pyr.size} levels" in html, "expected the header: $html")
        assertTrue("128&times;96" in html && "16&times;12" in html, "expected the level sizes: $html")
        val widths = pngs(html).map { it.width }
        assertEquals(pyr.size, widths.size, "one thumbnail per level")
        // Rungs must be strictly narrower going up the pyramid, in the same ratio as the data.
        assertTrue(widths.zipWithNext().all { (a, b) -> b < a }, "rungs should shrink: $widths")
        assertEquals(widths.first() / 2, widths[1], "the second rung is half the base")
    }

    @Test
    fun `a laplacian pyramid tags its residual and renders its bands as signed`() {
        val pyr = Img.generate(128, 128) { x, y -> if ((x / 16 + y / 16) % 2 == 0) 0.9f else 0.1f }
            .laplacianPyramid(3)
        val html = viz(pyr)
        assertTrue("LaplacianPyramid" in html, "expected the header: $html")
        assertTrue("residual" in html, "the residual rung must be labelled: $html")
        assertEquals(pyr.size + 1, pngs(html).size, "bands plus the residual")
    }

    @Test
    fun `a gradient shows dx, dy, magnitude and direction`() {
        val g = Img.generate(64, 64) { x, y -> ((x + y) % 32) / 31f }.sobel()
        val html = viz(g)
        assertTrue("Gradient" in html && "64&times;64" in html, "expected the header: $html")
        assertTrue("dx (signed)" in html && "hue = direction" in html, "expected all four panels: $html")
        assertEquals(4, pngs(html).size, "dx, dy, magnitude, direction")
    }

    @Test
    fun `connected components get one colour each plus a stats disclosure`() {
        // Three separated squares.
        val mask = Img.generate(64, 64) { x, y ->
            val a = x in 4..12 && y in 4..12
            val b = x in 30..40 && y in 8..18
            val c = x in 20..28 && y in 40..52
            if (a || b || c) 1f else 0f
        }
        val cl = mask.connectedComponents()
        assertEquals(3, cl.count, "the fixture should have exactly three blobs")
        val html = viz(cl)
        assertTrue("ComponentLabelling" in html && "3 components" in html, "expected the header: $html")
        assertTrue("centroid" in html, "expected the per-component readout: $html")
        // Three distinct non-background colours in the label raster.
        val decoded = pngs(html).first()
        val colours = HashSet<Int>()
        for (y in 0 until decoded.height) for (x in 0 until decoded.width) {
            colours.add(decoded.getRGB(x, y) and 0xFFFFFF)
        }
        assertTrue(colours.size >= 4, "background plus three blob colours, got ${colours.size}")
    }

    // ---------------------------------------------------------------------------------------
    // Detector lists
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a corner list becomes a scatter, one dot per corner`() {
        val square = Img.generate(80, 80) { x, y -> if (x in 20..60 && y in 20..60) 1f else 0f }
        val corners = square.harrisCorners(minDistance = 5, maxCount = 8)
        assertTrue(corners.size >= 4, "the fixture should find at least the four square corners")
        val html = viz(corners)
        assertTrue("List&lt;Corner&gt;" in html, "expected the header: $html")
        assertTrue("${corners.size} corners" in html, "expected the count: $html")
        assertTrue("inferred extent" in html, "the inferred canvas must be stated: $html")
        assertEquals(corners.size, count(html, "<circle"), "one dot per corner")
        assertTrue("response" in html, "expected the strength readout: $html")
    }

    @Test
    fun `hough lines are drawn in an inferred square and also in parameter space`() {
        // houghLines votes on any 1-channel mask, so plant the edge pixels directly rather than
        // going through canny (whose blur erases a one-pixel-wide synthetic line).
        val edges = Img.generate(100, 100) { x, y -> if (x == 30 || y == 70) 1f else 0f }
        val lines = afield.stdlib.houghLines(edges, maxLines = 4)
        assertTrue(lines.isNotEmpty(), "the fixture should find the planted lines")
        val html = viz(lines)
        assertTrue("List&lt;HoughLine&gt;" in html, "expected the header: $html")
        assertTrue("no image extent known" in html, "the extent assumption must be stated: $html")
        assertTrue("inferred" in html, "the inferred square must be named: $html")
        assertTrue(count(html, "<line") >= 1, "expected drawn line segments: $html")
        assertEquals(lines.size, count(html, "<circle"), "one accumulator dot per line")
        assertTrue("linesToFLines" in html, "expected the pointer to the real, in-place path")
    }

    @Test
    fun `hough circles are drawn as circles over their own extent`() {
        val circles = listOf(
            HoughCircle(40.0, 30.0, 12.0, 90),
            HoughCircle(90.0, 70.0, 20.0, 140)
        )
        val html = viz(circles)
        assertTrue("List&lt;HoughCircle&gt;" in html, "expected the header: $html")
        assertTrue("2 circles" in html, "expected the count: $html")
        // Extent covers (90 + 20) = 110 across and (70 + 20) = 90 down.
        assertTrue("extent 113" in html || "extent 112" in html || "extent 114" in html,
            "expected an extent derived from the circle bounds: $html")
        assertEquals(4, count(html, "<circle"), "an outline plus a centre dot per circle")
    }

    @Test
    fun `a flow field draws arrows for tracks and crosses for failures`() {
        val vectors = listOf(
            FlowVector(10.0, 10.0, 22.0, 14.0, ok = true, error = 0.01),
            FlowVector(40.0, 20.0, 52.0, 24.0, ok = true, error = 0.01),
            FlowVector(70.0, 60.0, 82.0, 64.0, ok = true, error = 0.02),
            FlowVector(20.0, 50.0, 20.0, 50.0, ok = false, error = 0.5)
        )
        val html = viz(FlowField(vectors))
        assertTrue("FlowField" in html && "3/4 tracked" in html, "expected the tracked ratio: $html")
        assertTrue("failed track" in html, "failures must be shown, not hidden: $html")
        // Each arrow is one <path>; each failure cross is one <path> too.
        assertEquals(4, count(html, "<path"), "three arrows plus one failure cross")
        assertEquals(3, count(html, "<circle"), "a tail dot per tracked arrow")
        val expected = hypot(12.0, 4.0)
        assertTrue("median |d| ${"%.4f".format(expected).trimEnd('0').trimEnd('.')}" in html,
            "expected the true median magnitude: $html")
    }

    @Test
    fun `a tiny flow field is exaggerated and says by how much`() {
        val vectors = (0 until 6).map { i ->
            FlowVector(10.0 + i * 20, 40.0, 10.0 + i * 20 + 0.4, 40.2, ok = true, error = 0.0)
        }
        val html = viz(FlowField(vectors))
        assertTrue("arrows &times;" in html, "sub-pixel motion should be exaggerated and named: $html")
    }

    @Test
    fun `an image list becomes a filmstrip`() {
        val frames = (0 until 4).map { i -> Img.filled(32, 24, 1, i / 4f) }
        val html = viz(frames)
        assertTrue("List&lt;Img&gt;" in html && "4 images" in html, "expected the header: $html")
        assertEquals(4, pngs(html).size, "one frame per image")
    }

    // ---------------------------------------------------------------------------------------
    // Non-ownership: the hooks must not swallow other values
    // ---------------------------------------------------------------------------------------

    @Test
    fun `mixed and unrelated lists are left to the ordinary list renderer`() {
        assertTrue("<img" !in viz(listOf(1, 2, 3)), "a numeric list is not an imaging value")
        val mixed = listOf(HoughCircle(1.0, 1.0, 1.0, 1), "not a circle")
        assertTrue("HoughCircle" in viz(mixed), "a mixed list falls back to the reflective renderer")
        assertTrue("<svg" !in viz(mixed), "a mixed list must not be drawn as geometry")
    }

    @Test
    fun `a single corner is still a readable record, not a one-point scatter`() {
        val html = viz(afield.stdlib.Corner(3.0, 4.0, 0.5))
        assertTrue("response" in html, "expected the ordinary data-class tree: $html")
        assertTrue("<svg" !in html, "one corner is not worth a panel: $html")
    }
}
