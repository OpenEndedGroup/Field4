package afield.graphics.nile

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Overlapping outlines, the stroke kernel and the compositing kernels, each against an exact or
 * supersampled reference and against Skia, with error and population printed together.
 */
class GeziraPaintTest {

    private fun star(cx: Double, cy: Double, r0: Double, r1: Double, points: Int, phase: Double): List<Bezier> {
        val pts = (0 until 2 * points).map { k ->
            val a = phase + PI * k / points
            val r = if (k % 2 == 0) r1 else r0
            (cx + r * cos(a)) to (cy + r * sin(a))
        }
        return pts.indices.map { i ->
            val (ax, ay) = pts[i]
            val (bx, by) = pts[(i + 1) % pts.size]
            Bezier(doubleArrayOf(ax, ay, bx, by))
        }
    }

    private fun ngon(cx: Double, cy: Double, r: Double, n: Int, reverse: Boolean = false) = DoubleArray(2 * n) {
        val k = if (reverse) n - 1 - it / 2 else it / 2
        val a = 2 * PI * k / n
        if (it % 2 == 0) cx + r * cos(a) else cy + r * sin(a)
    }

    /**
     * Two overlapping outlines (the demo's star and circle). The edge-sample rasteriser
     * ([Gezira.edgeSampleRasterizer]) sums signed edge areas, so
     * in a pixel where both outlines pass it counts the overlap of their two partial coverages twice
     * (clamped at 1); the union is smaller. Away from such pixels it agrees with a 64 x 64 non-zero
     * supersample; in them its error is bounded by the smaller of the two single-outline coverages.
     */
    @Test
    fun overlappingOutlinesSumAreasWhereBothCrossAPixel() {
        val w = 48
        val h = 40
        val s = star(24.0, 20.0, 7.0, 17.0, 5, 0.0)
        val c = Gezira.circle(30.3, 26.1, 9.4)
        val ours = Gezira.coverage(Gezira.edgeSampleRasterizer(s + c), w, h)
        val ss = Gezira.supersampledCoverage(s + c, w, h, n = 64)
        val winding = Gezira.rasterize(s + c, w, h)
        val skia = Gezira.skiaCoverage(s + c, w, h)
        val os = Gezira.coverage(Gezira.edgeSampleRasterizer(s), w, h)
        val oc = Gezira.coverage(Gezira.edgeSampleRasterizer(c), w, h)
        fun partial(v: Double) = v > 1e-9 && v < 1 - 1e-9
        var singleWorst = 0.0
        var single = 0
        var bothWorst = 0.0
        var bothExcess = 0.0
        var both = 0
        var skiaWorst = 0.0
        for (i in ours.indices) {
            skiaWorst = max(skiaWorst, abs(skia[i] - ss[i]))
            val d = abs(ours[i] - ss[i])
            if (partial(os[i]) && partial(oc[i])) {
                both++
                bothWorst = max(bothWorst, d)
                bothExcess = max(bothExcess, d - min(os[i], oc[i]))
            } else if (ours[i] > 0 || ss[i] > 0) {
                single++
                singleWorst = max(singleWorst, d)
            }
        }
        println("overlap: pixels one outline crosses: max |kernels - supersample| $singleWorst over $single")
        println("overlap: pixels both outlines cross: max |kernels - supersample| $bothWorst over $both; worst excess over min(single coverages) $bothExcess")
        val (ww, wm) = Gezira.error(winding, ss)
        println("overlap: winding rasteriser vs the same supersample, all pixels: max |diff| $ww, mean $wm; Skia max $skiaWorst")
        assertTrue(ww < 0.02, "the winding rasteriser should match the union, worst $ww")
        println("overlap: max |Skia - supersample| $skiaWorst; max |kernels - Skia| ${Gezira.error(ours, skia).first}")
        assertTrue(single > 500 && both > 0, "population too small: $single / $both")
        assertTrue(singleWorst < 0.02, "away from crossings the kernels should match the supersample, worst $singleWorst")
        assertTrue(bothExcess < 0.02, "where outlines cross, error should stay under the smaller coverage, excess $bothExcess")
    }

    @Test
    fun strokingAStraightLineIsAnExactRectangle() {
        val line = listOf(Bezier(doubleArrayOf(4.3, 5.1, 27.7, 19.6)))
        val outline = GeziraStroke.strokeBezierPath(3.0)(line)
        val got = Gezira.rasterize(outline, 32, 24)
        val dx = 27.7 - 4.3
        val dy = 19.6 - 5.1
        val l = Math.hypot(dx, dy)
        val nx = dy / l * 1.5
        val ny = -dx / l * 1.5
        val rect = doubleArrayOf(4.3 + nx, 5.1 + ny, 27.7 + nx, 19.6 + ny, 27.7 - nx, 19.6 - ny, 4.3 - nx, 5.1 - ny)
        val want = Gezira.exactPolygonCoverage(rect, 32, 24)
        val (worst, mean) = Gezira.error(got, want)
        println("stroked line vs exact rectangle: max |diff| $worst, mean $mean over ${got.count { it > 0 }} pixels")
        assertTrue(worst < 1e-9, "a stroked line is a rectangle, worst $worst")
    }

    @Test
    fun strokingACircleGivesTheRing() {
        val w = 40
        val h = 40
        val r = 14.2
        val width = 2.5
        val circle = Gezira.circle(20.1, 19.7, r)
        val t = GeziraStroke.strokeBezierPath(width).trace(circle)
        val got = Gezira.rasterize(t.get("StrokeBezierPath"), w, h)
        val outer = Gezira.exactPolygonCoverage(ngon(20.1, 19.7, r + width / 2, 4096), w, h)
        val inner = Gezira.exactPolygonCoverage(ngon(20.1, 19.7, r - width / 2, 4096), w, h)
        val ring = DoubleArray(w * h) { outer[it] - inner[it] }
        val (worst, mean) = Gezira.error(got, ring)
        val skia = GeziraStroke.skiaStrokeCoverage(circle, width, w, h, closed = true)
        val (sw, sm) = Gezira.error(skia, ring)
        println("stroked circle: ${t.steps["StrokeBezierPath"]} kernel steps, ${t.get<Bezier>("StrokeBezierPath").size} outline pieces")
        println("stroked circle vs exact ring: max |diff| $worst, mean $mean over ${got.count { it > 0 }} pixels; Skia vs ring max $sw, mean $sm")
        // The kappa circle itself is off the true circle by up to 2.7e-4 of its radius (0.004 px here).
        assertTrue(worst < 0.02, "stroke of a circle should be the ring, worst $worst")
    }

    /**
     * The stroke as a set: sample points within `width / 2` of the centre line, minus the round ends
     * (butt caps). `n x n` samples per pixel, the centre line flattened to 512 chords per curve.
     */
    private fun trueStroke(path: List<Bezier>, width: Double, w: Int, h: Int, n: Int): DoubleArray {
        val pts = ArrayList<DoubleArray>()
        for (b in path) for (i in 0..512) pts.add(doubleArrayOf(b.at(i / 512.0, 0), b.at(i / 512.0, 1)))
        val first = path.first()
        val last = path.last()
        val (t0x, t0y) = first.d(0.0, 0) to first.d(0.0, 1)
        val (t1x, t1y) = last.d(1.0, 0) to last.d(1.0, 1)
        val r2 = width * width / 4
        val c = DoubleArray(w * h)
        for (py in 0 until h) for (px in 0 until w) for (j in 0 until n) for (i in 0 until n) {
            val x = px + (i + 0.5) / n
            val y = py + (j + 0.5) / n
            // Beyond either butt cap: outside.
            if ((x - first.p[0]) * t0x + (y - first.p[1]) * t0y < 0) continue
            if ((x - last.p[last.p.size - 2]) * t1x + (y - last.p[last.p.size - 1]) * t1y > 0) continue
            var inside = false
            for (k in 0 until pts.size - 1) {
                val ax = pts[k][0]
                val ay = pts[k][1]
                val dx = pts[k + 1][0] - ax
                val dy = pts[k + 1][1] - ay
                val l2 = dx * dx + dy * dy
                val t = if (l2 == 0.0) 0.0 else (((x - ax) * dx + (y - ay) * dy) / l2).coerceIn(0.0, 1.0)
                val ex = ax + t * dx - x
                val ey = ay + t * dy - y
                if (ex * ex + ey * ey <= r2) {
                    inside = true
                    break
                }
            }
            if (inside) c[py * w + px] += 1.0 / (n * n)
        }
        return c
    }

    @Test
    fun strokingAnOpenWaveMatchesTheTrueStroke() {
        val w = 48
        val h = 24
        val wave = listOf(
            Bezier(doubleArrayOf(3.0, 12.0, 10.0, 0.0, 17.0, 24.0, 24.0, 12.0)),
            Bezier(doubleArrayOf(24.0, 12.0, 31.0, 0.0, 38.0, 24.0, 45.0, 12.0)),
        )
        val got = Gezira.rasterize(GeziraStroke.strokeBezierPath(2.0)(wave), w, h)
        val skia = GeziraStroke.skiaStrokeCoverage(wave, 2.0, w, h, closed = false)
        val truth = trueStroke(wave, 2.0, w, h, 32)
        val (worst, mean) = Gezira.error(got, truth)
        val (sw, sm) = Gezira.error(skia, truth)
        val (kw, km) = Gezira.error(got, skia)
        println("stroked wave vs true stroke (32 x 32 samples): max |diff| $worst, mean $mean over ${got.count { it > 0 }} pixels")
        println("stroked wave: Skia vs true stroke max $sw, mean $sm; kernels vs Skia max $kw, mean $km")
        assertTrue(mean < 0.01, "stroke should match the true stroke on average, mean $mean")
    }

    @Test
    fun compositeOverMatchesSkiaSourceOver() {
        val w = 48
        val h = 40
        val red = Rgba.of(0.9, 0.2, 0.1, 0.7)
        val blue = Rgba.of(0.1, 0.4, 1.0, 0.6)
        val s = star(22.0, 20.0, 7.0, 16.0, 5, 0.2)
        val c = Gezira.circle(30.0, 24.0, 10.0)
        val canvas = Canvas(w, h)
        canvas.draw(s, GeziraComposite.color(red))
        val t = canvas.draw(c, GeziraComposite.color(blue))
        val skia = Gezira.skiaRaster(w, h) { cv ->
            for ((shape, col) in listOf(s to red, c to blue)) {
                val p = Gezira.skiaPath(shape)
                val a = col.a
                cv.drawPath(
                    p,
                    org.jetbrains.skia.Paint().apply {
                        isAntiAlias = true
                        color = org.jetbrains.skia.Color.makeARGB(
                            (a * 255).toInt(), (col.r / a * 255).toInt(), (col.g / a * 255).toInt(), (col.b / a * 255).toInt(),
                        )
                    },
                )
                p.close()
            }
        }
        val (worst, mean) = canvas.error(skia)
        println("CompositeOver vs Skia srcOver: max channel |diff| $worst, mean $mean; ${t.get<Fragment>("Composite").size} fragments composited")
        assertTrue(mean < 0.01, "composited image should agree with Skia on average, mean $mean")
    }

    @Test
    fun blendsAndTexturesDoWhatTheySay() {
        val sq = listOf(
            Bezier(doubleArrayOf(0.0, 0.0, 8.0, 0.0)), Bezier(doubleArrayOf(8.0, 0.0, 8.0, 8.0)),
            Bezier(doubleArrayOf(8.0, 8.0, 0.0, 8.0)), Bezier(doubleArrayOf(0.0, 8.0, 0.0, 0.0)),
        )
        val grad = GeziraComposite.linearGradient(0.0, 0.0, 8.0, 0.0, Rgba.of(1.0, 0.0, 0.0), Rgba.of(0.0, 0.0, 1.0))
        val canvas = Canvas(8, 8)
        canvas.draw(sq, GeziraComposite.texture(grad))
        for (x in 0 until 8) {
            val want = grad(x + 0.5, 3.5)
            assertEquals(want, canvas[x, 3], "a fully covered pixel takes the texture at its centre")
        }
        // Multiply with white leaves the destination; over with opaque replaces it.
        val dst = Rgba.of(0.3, 0.6, 0.9)
        val m = GeziraComposite.MULTIPLY.blend(Rgba.of(1.0, 1.0, 1.0), dst)
        assertEquals(dst.r, m.r, 1e-6f)
        assertEquals(dst.g, m.g, 1e-6f)
        val o = GeziraComposite.OVER.blend(Rgba.of(0.1, 0.2, 0.3), dst)
        assertEquals(0.1f, o.r, 1e-6f)
    }

    @Test
    fun aLineOfTextMatchesSkia() {
        val w = 360
        val h = 64
        val text = Gezira.glyphs("Pixels on a screen", 6.0, 46.0, size = 40.0)
        val quads = text.count { it.degree == 2 }
        val start = System.nanoTime()
        val t = Gezira.edgeSampleRasterizer.trace(text)
        val ms = (System.nanoTime() - start) / 1e6
        val ours = Gezira.coverage(t.output.filterIsInstance<Span>(), w, h)
        val skia = Gezira.skiaCoverage(text, w, h)
        val (worst, mean) = Gezira.error(ours, skia)
        println("text: ${text.size} segments ($quads quadratics) -> ${t.get<Bezier>("DecomposeBeziers").size} pieces, ${t.output.size} spans in %.1f ms".format(ms))
        val ss = Gezira.supersampledCoverage(text, w, h, n = 32, chords = 64)
        val (tw, tm) = Gezira.error(ours, ss)
        val (sw, sm) = Gezira.error(skia, ss)
        println("text vs Skia: max |diff| $worst, mean $mean over ${ours.count { it > 0 }} pixels")
        val touched = ss.indices.filter { ss[it] > 0 || skia[it] > 0 }
        println("text: Skia minus supersample, signed mean ${touched.sumOf { skia[it] - ss[it] } / touched.size}, total ink Skia ${skia.sum()} vs ${ss.sum()}")
        println("text vs 32 x 32 supersample: kernels max $tw mean $tm; Skia max $sw mean $sm")
        assertTrue(quads > 50 && ours.count { it > 0 } > 2000, "population too small")
        assertTrue(tm < 0.01, "text should match the supersample on average, mean $tm")
    }
}
