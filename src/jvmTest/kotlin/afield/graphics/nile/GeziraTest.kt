package afield.graphics.nile

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Gezira kernel pipeline against exact polygon coverage ([Gezira.exactPolygonCoverage], built on
 * `TriangleCoverage`) and against Skia's anti-aliased raster, with the error and the number of pixels
 * it was measured over printed together.
 */
class GeziraTest {

    private fun polygonBeziers(poly: DoubleArray): List<Bezier> = (0 until poly.size / 2).map { i ->
        val j = (i + 1) % (poly.size / 2)
        Bezier(doubleArrayOf(poly[2 * i], poly[2 * i + 1], poly[2 * j], poly[2 * j + 1]))
    }

    private fun star(cx: Double, cy: Double, r0: Double, r1: Double, points: Int, phase: Double) =
        DoubleArray(4 * points) {
            val k = it / 2
            val a = phase + PI * k / points
            val r = if (k % 2 == 0) r1 else r0
            if (it % 2 == 0) cx + r * cos(a) else cy + r * sin(a)
        }

    private fun touched(c: DoubleArray) = c.count { it > 0.0 }

    @Test
    fun aKernelCanPushBackOntoItsOwnQueue() {
        // Halve every number above 1 by pushing both halves back: 8 becomes eight 1s.
        val halve = kernel<Int, Int>("Halve") {
            each { n ->
                if (n > 1) {
                    push(n / 2)
                    push(n - n / 2)
                } else {
                    emit(n)
                }
            }
        }
        val t = (halve then mapKernel("Double") { it * 2 }).trace(listOf(8, 3))
        assertEquals(List(11) { 1 }, t["Halve"])
        assertEquals(List(11) { 2 }, t.output)
        assertEquals(11 + 7 + 2, t.steps["Halve"])
    }

    @Test
    fun polygonsMatchExactCoverage() {
        val rng = Random(3)
        var worst = 0.0
        var sum = 0.0
        var pixels = 0
        val w = 48
        val h = 40
        repeat(40) {
            val poly = if (it % 2 == 0) {
                star(rng.nextDouble(12.0, 36.0), rng.nextDouble(12.0, 28.0), rng.nextDouble(3.0, 8.0), rng.nextDouble(9.0, 16.0), 3 + rng.nextInt(5), rng.nextDouble(6.0))
            } else {
                DoubleArray(6) { k -> if (k % 2 == 0) rng.nextDouble(-4.0, w + 4.0) else rng.nextDouble(-4.0, h + 4.0) }
            }
            val got = Gezira.rasterize(polygonBeziers(poly), w, h)
            val want = Gezira.exactPolygonCoverage(poly, w, h)
            val (wst, mean) = Gezira.error(got, want)
            val n = maxOf(touched(got), touched(want))
            worst = maxOf(worst, wst)
            sum += mean * n
            pixels += n
        }
        println("polygons vs TriangleCoverage: max |diff| $worst, mean ${sum / pixels} over $pixels touched pixels in 40 shapes")
        assertTrue(pixels > 10_000, "population too small: $pixels")
        assertTrue(worst < 1e-6, "kernel pipeline should match exact coverage, worst $worst")
    }

    private fun circle(cx: Double, cy: Double, r: Double): List<Bezier> = Gezira.circle(cx, cy, r)


    /** The same curves flattened to [n] chords each, as a polygon. */
    private fun flatten(beziers: List<Bezier>, n: Int): DoubleArray {
        val out = ArrayList<Double>()
        for (b in beziers) for (i in 0 until n) {
            val t = i.toDouble() / n
            out += b.at(t, 0)
            out += b.at(t, 1)
        }
        return out.toDoubleArray()
    }

    @Test
    fun curvesMatchFinelyFlattenedExactCoverage() {
        val w = 40
        val h = 40
        val shapes = listOf(
            circle(20.0, 20.0, 15.3),
            circle(10.4, 11.7, 4.2),
            listOf(Bezier(doubleArrayOf(3.0, 35.0, 20.0, -10.0, 37.0, 35.0))) + listOf(Bezier(doubleArrayOf(37.0, 35.0, 3.0, 35.0))),
        )
        var worst = 0.0
        var pixels = 0
        for (s in shapes) {
            val got = Gezira.rasterize(s, w, h)
            val want = Gezira.exactPolygonCoverage(flatten(s, 2048), w, h)
            worst = maxOf(worst, Gezira.error(got, want).first)
            pixels += touched(got)
        }
        println("curves vs 2048-chord exact coverage: max |diff| $worst over $pixels touched pixels")
        assertTrue(worst < 1e-4, "curve coverage should match the finely flattened polygon, worst $worst")
    }

    @Test
    fun beside_skia() {
        val w = 48
        val h = 40
        val shapes = listOf(
            "star" to polygonBeziers(star(24.0, 20.0, 7.0, 17.0, 5, 0.3)),
            "circle" to circle(23.6, 20.2, 15.3),
            "small circle" to circle(10.4, 11.7, 3.1),
        )
        var worst = 0.0
        for ((name, s) in shapes) {
            val got = Gezira.rasterize(s, w, h)
            val skia = Gezira.skiaCoverage(s, w, h)
            val (wst, mean) = Gezira.error(got, skia)
            worst = maxOf(worst, wst)
            println("$name vs Skia: max |diff| $wst, mean $mean over ${maxOf(touched(got), touched(skia))} pixels")
        }
        // Skia's own error, on a polygon where the exact answer is known: the difference above is Skia's.
        val starPoly = star(24.0, 20.0, 7.0, 17.0, 5, 0.3)
        val (skiaWorst, skiaMean) = Gezira.error(Gezira.skiaCoverage(polygonBeziers(starPoly), w, h), Gezira.exactPolygonCoverage(starPoly, w, h))
        println("Skia vs TriangleCoverage on the star: max |diff| $skiaWorst, mean $skiaMean")
        // Skia's AA rasteriser supersamples and stores 8 bits, so this is a sanity bound.
        assertTrue(worst < 0.25, "kernel pipeline and Skia disagree by $worst")
    }
}
