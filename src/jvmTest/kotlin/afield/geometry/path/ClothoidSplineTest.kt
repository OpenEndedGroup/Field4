package afield.geometry.path

import afield.math.linalg.Vec3
import afield.math.linalg.vec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `clothoidSplineFrom` is the G2 Euler-spiral spline. These measure the drawn polyline: curvature
 * from circles through consecutive samples, compared just before and just after each knot.
 */
class ClothoidSplineTest {

    private val perSegment = 200

    /** The drawn points: a closed line ends in a close node, which is not a point of its own. */
    private fun points(line: FLine, closed: Boolean): List<Vec3> =
        (if (closed) line.nodes.dropLast(1) else line.nodes).map { Vec3(it.to) }

    private fun curvatures(line: FLine, closed: Boolean = false): DoubleArray {
        val p = points(line, closed)
        // Points are floats; a circle through neighbours 8 samples apart keeps rounding out of the answer.
        val w = 8
        return DoubleArray(p.size) { i ->
            if (i < w || i > p.lastIndex - w) 0.0 else {
                val a = p[i - w]; val b = p[i]; val c = p[i + w]
                val cross = (b.x - a.x).toDouble() * (c.y - b.y) - (b.y - a.y).toDouble() * (c.x - b.x)
                2 * cross / (hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) *
                    hypot((c.x - b.x).toDouble(), (c.y - b.y).toDouble()) *
                    hypot((c.x - a.x).toDouble(), (c.y - a.y).toDouble()))
            }
        }
    }

    private val wave = (0..7).map { i -> vec(40.0 + i * 50, 130 + 70 * sin(i * 0.9), 0.0) }

    @Test
    fun passesThroughEveryKnot() {
        val line = clothoidSplineFrom(wave, samplesPerSegment = perSegment)
        for ((i, k) in wave.withIndex()) {
            val node = line.nodes[i * perSegment].to
            assertTrue(Vec3(node).sub(k).length() < 1e-3f, "knot $i missed: $node vs $k")
        }
    }

    @Test
    fun curvatureIsContinuousAtKnots() {
        val line = clothoidSplineFrom(wave, samplesPerSegment = perSegment)
        val k = curvatures(line)
        val kMax = k.maxOf { abs(it) }
        for (i in 1 until wave.size - 1) {
            val at = i * perSegment
            // curvature is linear inside a piece: extrapolate each side to the knot, then compare
            val left = 2 * k[at - 10] - k[at - 20]
            val right = 2 * k[at + 10] - k[at + 20]
            val jump = abs(left - right)
            assertTrue(jump < 0.01 * kMax, "knot $i curvature jump $jump (max $kMax)")
        }
    }

    @Test
    fun knotsOnACircleGiveTheCircle() {
        val r = 100.0
        val knots = (0 until 7).map { i -> val a = 2 * PI * i / 7 + 0.3 * sin(i * 1.7); vec(r * cos(a), r * sin(a), 0.0) }
        val line = clothoidSplineFrom(knots, closed = true, samplesPerSegment = perSegment)
        for (p in points(line, closed = true)) {
            val d = p.length()
            assertTrue(abs(d - r) < 1e-3 * r, "point $d from centre, expected $r")
        }
        val k = curvatures(line, closed = true).drop(8).dropLast(8)
        val spread = k.maxOf { it } - k.minOf { it }
        assertTrue(spread < 0.01 / r, "curvature varies by $spread on a circle")
    }

    @Test
    fun atMostOneInflectionPerSegment() {
        val shapes = listOf(
            wave to false,
            listOf(vec(0, 0, 0), vec(100, 40, 0), vec(160, 140, 0), vec(60, 200, 0), vec(-40, 120, 0)) to true,
            listOf(vec(0, 0, 0), vec(80, 0, 0), vec(80, 80, 0), vec(160, 80, 0), vec(160, 0, 0)) to false,
        )
        for ((knots, closed) in shapes) {
            val k = curvatures(clothoidSplineFrom(knots, closed = closed, samplesPerSegment = perSegment), closed)
            val segs = if (closed) knots.size else knots.size - 1
            for (s in 0 until segs) {
                val part = k.slice(s * perSegment + 10 until (s + 1) * perSegment - 10)
                // below this the sign is rounding in the measurement, not the curve
                val eps = 1e-3 * k.maxOf { abs(it) }
                val signs = part.filter { abs(it) > eps }.map { it > 0 }
                val changes = signs.zipWithNext().count { (a, b) -> a != b }
                assertTrue(changes <= 1, "segment $s of ${knots.size}-knot shape has $changes inflections")
                assertTrue(part.isNotEmpty())
            }
        }
    }

    @Test
    fun combAndPlotHaveOneToothPerSample() {
        val line = clothoidSplineFrom(wave)
        val comb = line.curvatureComb(samples = 50)
        assertTrue(comb.nodes.size >= 100, "comb has ${comb.nodes.size} nodes")
        val plot = line.curvaturePlot(width = 400.0, height = 100.0, samples = 80)
        assertTrue(plot.nodes.size == 80)
        assertTrue(plot.nodes.all { it.to.y in -0.01f..100.01f })
    }

    @Test
    fun plotOfACircleIsFlat() {
        // The spline carries exact curvature on its nodes, so the plot has no sampling ripple.
        val knots = (0 until 6).map { i -> val a = 2 * PI * i / 6 + 0.2 * sin(i * 2.3); vec(80 * cos(a), 80 * sin(a), 0.0) }
        val ys = clothoidSplineFrom(knots, closed = true).curvaturePlot(height = 100.0, samples = 200, maxCurvature = 0.02)
            .nodes.map { it.to.y.toDouble() }
        val spread = ys.maxOf { it } - ys.minOf { it }
        assertTrue(spread < 0.05, "plot of a circle varies by $spread")
    }

    @Test
    fun cubicCombFallsBackToMeasuredCurvature() {
        val comb = cubicSplineFrom(wave).curvatureComb(samples = 40)
        assertTrue(comb.nodes.size >= 80, "comb has ${comb.nodes.size} nodes")
    }
}
