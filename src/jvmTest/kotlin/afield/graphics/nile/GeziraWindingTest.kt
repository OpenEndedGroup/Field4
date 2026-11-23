package afield.graphics.nile

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The winding rasteriser ([Gezira.rasterizer]) under both fill rules, against exact answers where
 * they can be built from `TriangleCoverage` (convex polygons, their intersections and differences)
 * and against a 64 × 64 supersample where they cannot (curves, self-intersecting outlines). Each
 * check prints its error with the number of pixels it was measured over.
 */
class GeziraWindingTest {

    private fun lines(poly: DoubleArray): List<Bezier> = (0 until poly.size / 2).map { i ->
        val j = (i + 1) % (poly.size / 2)
        Bezier(doubleArrayOf(poly[2 * i], poly[2 * i + 1], poly[2 * j], poly[2 * j + 1]))
    }

    private fun ngon(cx: Double, cy: Double, r: Double, n: Int, phase: Double = 0.1, reverse: Boolean = false) =
        DoubleArray(2 * n) {
            val k = if (reverse) n - 1 - it / 2 else it / 2
            val a = phase + 2 * PI * k / n
            if (it % 2 == 0) cx + r * cos(a) else cy + r * sin(a)
        }

    /** The convex polygon [a] clipped to the convex polygon [clip] (both anticlockwise in y-down). */
    private fun clip(a: DoubleArray, clip: DoubleArray): DoubleArray {
        var out = a.toList().chunked(2).map { it[0] to it[1] }
        val m = clip.size / 2
        val s = Math.signum(area(clip))
        for (e in 0 until m) {
            val (ax, ay) = clip[2 * e] to clip[2 * e + 1]
            val (bx, by) = clip[2 * ((e + 1) % m)] to clip[2 * ((e + 1) % m) + 1]
            fun side(p: Pair<Double, Double>) = s * ((bx - ax) * (p.second - ay) - (by - ay) * (p.first - ax))
            val next = ArrayList<Pair<Double, Double>>()
            for (i in out.indices) {
                val p = out[i]
                val q = out[(i + 1) % out.size]
                val sp = side(p)
                val sq = side(q)
                if (sp >= 0) next.add(p)
                if ((sp >= 0) != (sq >= 0)) {
                    val t = sp / (sp - sq)
                    next.add((p.first + t * (q.first - p.first)) to (p.second + t * (q.second - p.second)))
                }
            }
            out = next
            if (out.isEmpty()) break
        }
        return out.flatMap { listOf(it.first, it.second) }.toDoubleArray()
    }

    private fun area(p: DoubleArray): Double {
        var a = 0.0
        val n = p.size / 2
        for (i in 0 until n) {
            val j = (i + 1) % n
            a += p[2 * i] * p[2 * j + 1] - p[2 * j] * p[2 * i + 1]
        }
        return a / 2
    }

    private fun exact(p: DoubleArray, w: Int, h: Int) =
        if (p.size < 6) DoubleArray(w * h) else Gezira.exactPolygonCoverage(p, w, h)

    private fun report(label: String, got: DoubleArray, want: DoubleArray): Pair<Double, Double> {
        val (worst, mean) = Gezira.error(got, want)
        val n = got.indices.count { got[it] > 0 || want[it] > 0 }
        println("$label: max |diff| %.3g, mean %.3g over $n pixels".format(worst, mean))
        return worst to mean
    }

    @Test
    fun simplePolygonsMatchTriangleCoverage() {
        val w = 48
        val h = 40
        var worst = 0.0
        var pixels = 0
        for (k in 0 until 20) {
            val p = ngon(20.3 + k * 0.37, 19.1 + k * 0.21, 6.0 + k * 0.6, 3 + k % 7, phase = 0.13 * k, reverse = k % 2 == 1)
            val got = Gezira.rasterize(lines(p), w, h)
            val want = exact(p, w, h)
            worst = max(worst, Gezira.error(got, want).first)
            pixels += want.count { it > 0 }
        }
        println("20 convex polygons, both orientations, vs TriangleCoverage: max |diff| %.3g over $pixels pixels".format(worst))
        assertTrue(worst < 1e-9, "simple polygons should be exact, worst $worst")
    }

    /**
     * Two overlapping convex polygons, exact answers from TriangleCoverage: the union is
     * `A + B - A∩B` (non-zero) and the symmetric difference `A + B - 2 A∩B` (even-odd).
     * The intersection is a polygon clipped exactly, so these are exact references.
     */
    @Test
    fun overlappingPolygonsMatchExactUnionAndXor() {
        val w = 48
        val h = 40
        var worstNz = 0.0
        var worstEo = 0.0
        var worstOld = 0.0
        var pixels = 0
        for (k in 0 until 12) {
            val a = ngon(18.0 + 0.31 * k, 18.0, 11.0, 5 + k % 4, phase = 0.2 * k)
            val b = ngon(26.0, 21.0 + 0.27 * k, 9.5 + 0.2 * k, 3 + k % 5, phase = -0.3 * k, reverse = k % 3 == 0)
            val ab = clip(a, b)
            val ca = exact(a, w, h)
            val cb = exact(b, w, h)
            val cab = exact(ab, w, h)
            val union = DoubleArray(w * h) { ca[it] + cb[it] - cab[it] }
            val xor = DoubleArray(w * h) { ca[it] + cb[it] - 2 * cab[it] }
            val both = lines(a) + lines(b)
            // Opposite orientations wind to 0 in the overlap, so non-zero gives the xor there too.
            val nzWant = if (k % 3 == 0) xor else union
            worstNz = max(worstNz, Gezira.error(Gezira.rasterize(both, w, h, FillRule.NON_ZERO), nzWant).first)
            worstEo = max(worstEo, Gezira.error(Gezira.rasterize(both, w, h, FillRule.EVEN_ODD), xor).first)
            if (k % 3 != 0) worstOld = max(worstOld, Gezira.error(Gezira.coverage(Gezira.edgeSampleRasterizer(both), w, h), union).first)
            pixels += union.count { it > 0 }
        }
        println("12 overlapping polygon pairs over $pixels pixels: non-zero max |diff| %.3g, even-odd %.3g; edge-sample rasteriser (non-zero) %.3g"
            .format(worstNz, worstEo, worstOld))
        assertTrue(worstNz < 1e-9 && worstEo < 1e-9, "overlap should be exact: non-zero $worstNz, even-odd $worstEo")
        assertTrue(worstOld > 0.05, "the edge-sample rasteriser was expected to show its overlap error, got $worstOld")
    }

    /** A square with a square hole: reversed inner contour for non-zero, same direction for even-odd. */
    @Test
    fun holesUnderBothRules() {
        val w = 40
        val h = 40
        val outer = doubleArrayOf(4.3, 4.6, 35.2, 4.6, 35.2, 35.7, 4.3, 35.7)
        val inner = doubleArrayOf(12.25, 13.5, 27.75, 13.5, 27.75, 26.1, 12.25, 26.1)
        val innerRev = inner.toList().chunked(2).reversed().flatten().toDoubleArray()
        val ring = DoubleArray(w * h).also { r ->
            val o = exact(outer, w, h)
            val i = exact(inner, w, h)
            for (k in r.indices) r[k] = o[k] - i[k]
        }
        val (a, _) = report("hole, reversed inner, non-zero", Gezira.rasterize(lines(outer) + lines(innerRev), w, h), ring)
        val (b, _) = report("hole, same-direction inner, even-odd", Gezira.rasterize(lines(outer) + lines(inner), w, h, FillRule.EVEN_ODD), ring)
        val (c, _) = report("hole, reversed inner, even-odd", Gezira.rasterize(lines(outer) + lines(innerRev), w, h, FillRule.EVEN_ODD), ring)
        val solid = exact(outer, w, h)
        val (d, _) = report("same-direction inner, non-zero (no hole)", Gezira.rasterize(lines(outer) + lines(inner), w, h), solid)
        assertTrue(maxOf(a, b, c, d) < 1e-9, "holes should be exact")
    }

    /** A pentagram drawn as one self-crossing outline: non-zero fills the centre, even-odd leaves it empty. */
    @Test
    fun selfIntersectingStarAgainstSupersample() {
        val w = 48
        val h = 48
        val star = DoubleArray(10) {
            val a = -PI / 2 + 4 * PI * (it / 2) / 5 + 0.07
            if (it % 2 == 0) 24.2 + 19.0 * cos(a) else 24.6 + 19.0 * sin(a)
        }
        for (rule in FillRule.values()) {
            val got = Gezira.rasterize(lines(star), w, h, rule)
            val ss = Gezira.supersampledCoverage(lines(star), w, h, n = 64, rule = rule)
            val skia = Gezira.skiaCoverage(lines(star), w, h, rule)
            val (worst, mean) = report("pentagram $rule vs 64x64 supersample", got, ss)
            report("  Skia $rule vs the same supersample", skia, ss)
            assertTrue(worst < 0.02 && mean < 0.003, "pentagram $rule off the supersample: worst $worst mean $mean")
        }
        val centre = 24 * w + 24
        assertTrue(Gezira.rasterize(lines(star), w, h)[centre] == 1.0)
        assertTrue(Gezira.rasterize(lines(star), w, h, FillRule.EVEN_ODD)[centre] == 0.0)
    }

    /** Curves: a star overlapping a circle, a figure eight, a glyph line, against the supersample. */
    @Test
    fun curvedOverlapsAgainstSupersample() {
        val w = 48
        val h = 40
        val s = lines(DoubleArray(20) {
            val k = it / 2
            val r = if (k % 2 == 0) 17.0 else 7.0
            val a = PI * k / 5
            if (it % 2 == 0) 24.0 + r * cos(a) else 20.0 + r * sin(a)
        })
        val c = Gezira.circle(30.3, 26.1, 9.4)
        val eight = Gezira.circle(16.0, 20.0, 8.3) + Gezira.circle(25.0, 20.0, 8.3)
        for ((name, shape) in listOf("star over circle" to s + c, "two circles" to eight)) {
            for (rule in FillRule.values()) {
                val got = Gezira.rasterize(shape, w, h, rule)
                val ss = Gezira.supersampledCoverage(shape, w, h, n = 64, rule = rule)
                val (worst, mean) = report("$name $rule vs 64x64 supersample", got, ss)
                assertTrue(worst < 0.02 && mean < 0.003, "$name $rule: worst $worst mean $mean")
            }
        }
        val old = Gezira.coverage(Gezira.edgeSampleRasterizer(s + c), w, h)
        report("star over circle, edge-sample rasteriser vs the same supersample", old, Gezira.supersampledCoverage(s + c, w, h, n = 64))
    }

    /** Where one outline crosses each pixel, the old exact-curve pipeline is the reference: flattening costs ≤ 1e-4. */
    @Test
    fun simpleCurvesMatchTheEdgeSampleRasteriser() {
        val w = 48
        val h = 40
        var worst = 0.0
        var pixels = 0
        for (k in 0 until 10) {
            val c = Gezira.circle(20.0 + 0.71 * k, 19.0 + 0.43 * k, 3.0 + 1.6 * k)
            val got = Gezira.rasterize(c, w, h)
            val want = Gezira.coverage(Gezira.edgeSampleRasterizer(c), w, h)
            worst = max(worst, Gezira.error(got, want).first)
            pixels += want.count { it > 0 }
        }
        println("10 circles vs the exact-curve edge-sample rasteriser: max |diff| %.3g over $pixels pixels".format(worst))
        assertTrue(worst < 2 * Gezira.FLATNESS, "flattened curves should be within the flatness, worst $worst")
    }
}
