package afield.graphics

import afield.stdlib.Vec3
import afield.stdlib.vec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Checks that the cubics [fitHyperbezierCubics] emits actually trace the hyperbezier they claim to,
 * and that the [FLine] helpers behave the way the rest of the geometry API does.
 */
class FLineHyperbezierTest {

    private val chord = 200.0f
    private val p0 = vec(0f, 0f, 0f)
    private val p3 = vec(chord, 0f, 0f)

    /** A control polygon in the XY plane from chord-frame tangent angles and arm fractions. */
    private fun polygon(th0: Double, th1: Double, l0: Double, l1: Double): Pair<Vec3, Vec3> {
        val c1 = vec(
            p0.x + (l0 * chord * cos(th0)).toFloat(),
            p0.y + (l0 * chord * sin(th0)).toFloat(),
            0f,
        )
        val c2 = vec(
            p3.x - (l1 * chord * cos(th1)).toFloat(),
            p3.y - (l1 * chord * sin(th1)).toFloat(),
            0f,
        )
        return c1 to c2
    }

    /** Every emitted cubic, densely flattened, as one polyline. */
    private fun chainOf(fit: HyperbezierFit, perCubic: Int = 2000): List<Vec3> {
        val out = ArrayList<Vec3>(fit.cubics.size * perCubic + 1)
        var start = fit.start
        out.add(Vec3(start))
        for (piece in fit.cubics) {
            for (i in 1..perCubic) {
                val s = i.toDouble() / perCubic
                val om = 1 - s
                val w0 = om * om * om
                val w1 = 3 * s * om * om
                val w2 = 3 * s * s * om
                val w3 = s * s * s
                out.add(
                    vec(
                        start.x * w0 + piece.control1.x * w1 + piece.control2.x * w2 + piece.end.x * w3,
                        start.y * w0 + piece.control1.y * w1 + piece.control2.y * w2 + piece.end.y * w3,
                        start.z * w0 + piece.control1.z * w1 + piece.control2.z * w2 + piece.end.z * w3,
                    )
                )
            }
            start = piece.end
        }
        return out
    }

    private fun distanceToSegment(p: Vec3, a: Vec3, b: Vec3): Double {
        val abx = (b.x - a.x).toDouble()
        val aby = (b.y - a.y).toDouble()
        val abz = (b.z - a.z).toDouble()
        val len2 = abx * abx + aby * aby + abz * abz
        val apx = (p.x - a.x).toDouble()
        val apy = (p.y - a.y).toDouble()
        val apz = (p.z - a.z).toDouble()
        val t = if (len2 < 1e-18) 0.0 else ((apx * abx + apy * aby + apz * abz) / len2).coerceIn(0.0, 1.0)
        return sqrt(
            (apx - t * abx) * (apx - t * abx) +
                (apy - t * aby) * (apy - t * aby) +
                (apz - t * abz) * (apz - t * abz)
        )
    }

    private fun distanceToChain(p: Vec3, chain: List<Vec3>): Double {
        var best = Double.MAX_VALUE
        for (i in 0 until chain.size - 1) best = min(best, distanceToSegment(p, chain[i], chain[i + 1]))
        return best
    }

    @Test
    fun fittedCubicsTraceARealCircleWhenTheArmsSayCircle() {
        // The strongest check available, and it needs no knowledge of the internals: with
        // circle-length arms and symmetric tangents the curve is a circular arc, so every point the
        // emitted cubics produce has to be the same distance from the arc's centre.
        val th = 45.0 * PI / 180.0
        val arm = circleArm(th)
        val (c1, c2) = polygon(th, -th, arm, arm)
        val fit = fitHyperbezierCubics(p0, c1, c2, p3, tolerance = 1e-5)
        assertTrue(fit.solved, "the solver stalled on a plain circular arc")

        val radius = chord / sqrt(2.0)
        val cx = chord / 2.0
        val cy = -chord / 2.0
        var worst = 0.0
        for (p in chainOf(fit)) {
            worst = max(worst, abs(hypot(p.x - cx, p.y - cy) - radius))
            assertTrue(abs(p.z) < 1e-4, "a planar fit left the plane at z=${p.z}")
        }
        assertTrue(worst <= 1e-5 * chord, "worst radial deviation was $worst over ${fit.segmentCount} cubics")
    }

    @Test
    fun fittedCubicsStayWithinTolerance() {
        // Rebuild the target curve from the public solution and measure how far the emitted cubics
        // stray from it. For these polygons the documented frame is u = +x, v = +y.
        val cases = listOf(
            "circle" to listOf(45.0, -45.0, circleArm(45.0 * PI / 180.0), circleArm(45.0 * PI / 180.0)),
            "corner" to listOf(45.0, -45.0, 0.6248, 0.6248),
            "past" to listOf(45.0, -45.0, 0.9373, 0.9373),
            "sShape" to listOf(30.0, 30.0, 0.3573, 0.3573),
            "asym" to listOf(70.0, -20.0, 0.40, 0.25),
            "euler120" to listOf(120.0, -120.0, 1.3333, 1.3333),
            "shallow" to listOf(8.0, -8.0, 0.30, 0.30),
            "slack" to listOf(45.0, -45.0, 0.15, 0.15),
        )
        val tolerance = 1e-4
        for ((name, spec) in cases) {
            val th0 = spec[0] * PI / 180.0
            val th1 = spec[1] * PI / 180.0
            val (c1, c2) = polygon(th0, th1, spec[2], spec[3])
            val fit = fitHyperbezierCubics(p0, c1, c2, p3, tolerance = tolerance)
            assertTrue(fit.solved, "$name: the solver stalled")
            val curve = assertNotNull(fit.curve)

            assertTrue(fit.toleranceMet, "$name: reported maxError ${fit.maxError} over ${fit.tolerance}")

            val chain = chainOf(fit)
            var worst = 0.0
            for (i in 0..400) {
                val q = curve.pointAt(i / 400.0)
                val target = vec(q[0] * chord, q[1] * chord, 0.0)
                worst = max(worst, distanceToChain(target, chain))
            }
            // Control points land in single-precision Vec3, so allow a few ulps of the coordinate
            // magnitude on top of what the fit itself promised.
            val allowed = tolerance * chord + 8 * chord * 1.2e-7
            assertTrue(
                worst <= allowed,
                "$name: measured deviation $worst exceeded $allowed with ${fit.segmentCount} cubics",
            )
            assertTrue(fit.segmentCount in 1..16, "$name: used ${fit.segmentCount} cubics")
        }
    }

    @Test
    fun fittedCubicsMeetG1AtEveryJoin() {
        val (c1, c2) = polygon(70.0 * PI / 180.0, -20.0 * PI / 180.0, 0.9, 0.55)
        val fit = fitHyperbezierCubics(p0, c1, c2, p3, tolerance = 1e-6)
        assertTrue(fit.segmentCount > 1, "needed a multi-cubic fit to have a join to check")

        var start = fit.start
        var previousOut: Vec3? = null
        for (piece in fit.cubics) {
            val incoming = Vec3(piece.control1).sub(start)
            previousOut?.let { out ->
                val dot = (out.x * incoming.x + out.y * incoming.y + out.z * incoming.z) /
                    (out.length() * incoming.length())
                assertTrue(dot > 1 - 1e-6, "tangent direction broke at a join, cos was $dot")
            }
            previousOut = Vec3(piece.end).sub(piece.control2)
            start = piece.end
        }
    }

    @Test
    fun fitLandsExactlyOnTheRequestedEndpoints() {
        val (c1, c2) = polygon(60.0 * PI / 180.0, -30.0 * PI / 180.0, 0.5, 0.7)
        val fit = fitHyperbezierCubics(p0, c1, c2, p3)
        assertEquals(p0.x, fit.start.x)
        assertEquals(p0.y, fit.start.y)
        val last = fit.cubics.last().end
        assertEquals(p3.x, last.x, "endpoint x drifted")
        assertEquals(p3.y, last.y, "endpoint y drifted")
        assertEquals(p3.z, last.z, "endpoint z drifted")
    }

    @Test
    fun tighterTolerancesSpendMoreCubics() {
        val (c1, c2) = polygon(45.0 * PI / 180.0, -45.0 * PI / 180.0, 0.9373, 0.9373)
        val loose = fitHyperbezierCubics(p0, c1, c2, p3, tolerance = 1e-2)
        val tight = fitHyperbezierCubics(p0, c1, c2, p3, tolerance = 1e-7)
        assertTrue(
            tight.segmentCount > loose.segmentCount,
            "1e-7 used ${tight.segmentCount} cubics, 1e-2 used ${loose.segmentCount}",
        )
        assertTrue(tight.maxError < loose.maxError, "the tighter fit was not more accurate")
    }

    @Test
    fun fitsAnArcAsWellAsTheTextbookCubicDoes() {
        // With circle-length arms the target is a 90 degree arc, and the classic single-cubic
        // approximation to one is known to sit 2.725e-4 * R away. A fit that quietly settled for
        // arms a fraction of a percent off would still look fine by eye and be several times
        // worse, so pin the number.
        val th = 45.0 * PI / 180.0
        val arm = circleArm(th)
        val (c1, c2) = polygon(th, -th, arm, arm)
        val radius = chord / sqrt(2.0)

        val single = fitHyperbezierCubics(p0, c1, c2, p3, tolerance = 1.0, maxSegments = 1)
        assertEquals(1, single.segmentCount)
        assertTrue(
            single.maxError <= 1.05 * 2.725e-4 * radius,
            "one cubic over a quarter arc was ${single.maxError}, textbook is ${2.725e-4 * radius}",
        )

        // Halving the pieces should buy about four bits per piece. Two cubics over 45 degrees each
        // land at 6.0e-4 absolute; anything near the single-cubic error means subdivision is not
        // actually converging.
        val pair = fitHyperbezierCubics(p0, c1, c2, p3, tolerance = 1e-12, maxSegments = 2)
        assertEquals(2, pair.segmentCount)
        assertTrue(
            pair.maxError < single.maxError / 30,
            "two cubics gave ${pair.maxError} against one cubic's ${single.maxError}",
        )
    }

    @Test
    fun theSegmentBudgetGoesWhereTheErrorIs() {
        // Arms well under the circle length put a tight hook at each end and leave the middle
        // nearly straight. Splitting to a fixed depth wastes half the budget on the easy middle;
        // this shape is the reason the fit refines its worst piece instead.
        val th = 45.0 * PI / 180.0
        val (c1, c2) = polygon(th, -th, 0.08, 0.08)
        val fit = fitHyperbezierCubics(p0, c1, c2, p3, tolerance = 1e-5, maxSegments = 16)
        assertTrue(fit.solved, "the solver stalled on short arms")
        assertTrue(fit.toleranceMet, "missed 1e-5 with ${fit.segmentCount} of 16 cubics: ${fit.maxError}")

        val curve = assertNotNull(fit.curve)
        val chain = chainOf(fit)
        var worst = 0.0
        for (i in 0..1000) {
            val q = curve.pointAt(i / 1000.0)
            worst = max(worst, distanceToChain(vec(q[0] * chord, q[1] * chord, 0.0), chain))
        }
        assertTrue(worst <= 1e-5 * chord + 8 * chord * 1.2e-7, "measured $worst on the hooked shape")
    }

    @Test
    fun maxSegmentsCapsTheFitAndReportsTheMiss() {
        val (c1, c2) = polygon(45.0 * PI / 180.0, -45.0 * PI / 180.0, 0.9373, 0.9373)
        val capped = fitHyperbezierCubics(p0, c1, c2, p3, tolerance = 1e-12, maxSegments = 2)
        assertTrue(capped.segmentCount <= 2, "used ${capped.segmentCount} cubics against a cap of 2")
        assertFalse(capped.toleranceMet, "an impossible tolerance was reported as met")
        assertTrue(capped.solved, "the cap should not look like a stall")
    }

    @Test
    fun theFittedCurveIsNotJustTheInputCubic() {
        // Past the circle-length arms the two families visibly disagree; if they did not, none of
        // this would be worth doing.
        val (c1, c2) = polygon(45.0 * PI / 180.0, -45.0 * PI / 180.0, 0.9373, 0.9373)
        val fit = fitHyperbezierCubics(p0, c1, c2, p3, tolerance = 1e-6)
        val ghostMid = vec(
            0.125f * p0.x + 0.375f * c1.x + 0.375f * c2.x + 0.125f * p3.x,
            0.125f * p0.y + 0.375f * c1.y + 0.375f * c2.y + 0.125f * p3.y,
            0f,
        )
        val separation = distanceToChain(ghostMid, chainOf(fit))
        assertTrue(separation > 0.01 * chord, "hyperbezier and cubic were only $separation apart")
    }

    @Test
    fun hyperbezierToAppendsCubicNodes() {
        val (c1, c2) = polygon(45.0 * PI / 180.0, -45.0 * PI / 180.0, 0.9373, 0.9373)
        val line = FLine().moveTo(p0).hyperbezierTo(c1, c2, p3, tolerance = 1e-6)
        assertTrue(line.nodes.size > 2, "appended only ${line.nodes.size - 1} segments")
        for (node in line.nodes.drop(1)) {
            assertTrue(node is FLine.CubicTo, "expected only cubics, found ${node::class.simpleName}")
        }
        val end = line.nodes.last().to
        assertEquals(p3.x, end.x)
        assertEquals(p3.y, end.y)
    }

    @Test
    fun byHyperbezierToLeavesTheOriginalAlone() {
        val (c1, c2) = polygon(45.0 * PI / 180.0, -45.0 * PI / 180.0, 0.6248, 0.6248)
        val original = FLine().moveTo(p0)
        val before = original.nodes.size
        val derived = original.byHyperbezierTo(c1, c2, p3)
        assertEquals(before, original.nodes.size, "byHyperbezierTo mutated its receiver")
        assertTrue(derived.nodes.size > before, "byHyperbezierTo returned nothing new")
    }

    @Test
    fun aDegeneratePolygonFallsBackToTheInputCubic() {
        // Start and end coincide, so there is no chord to build a frame on.
        val c1 = vec(40f, 60f, 0f)
        val c2 = vec(-40f, 60f, 0f)
        val fit = fitHyperbezierCubics(p0, c1, c2, Vec3(p0))
        assertFalse(fit.solved, "a degenerate polygon reported a solution")
        assertEquals(1, fit.segmentCount)
        assertEquals(c1.x, fit.cubics[0].control1.x, "the fallback altered the input cubic")
        assertEquals(c2.x, fit.cubics[0].control2.x, "the fallback altered the input cubic")
    }

    @Test
    fun aNonPlanarPolygonKeepsItsOutOfPlaneProfile() {
        val c1 = vec(50f, 80f, 40f)
        val c2 = vec(150f, 80f, -40f)
        val fit = fitHyperbezierCubics(p0, c1, c2, p3, tolerance = 1e-4)
        assertTrue(fit.solved, "the solver stalled on a non-planar polygon")

        val chain = chainOf(fit, perCubic = 200)
        for (p in chain) {
            assertTrue(p.x.isFinite() && p.y.isFinite() && p.z.isFinite(), "fit produced a non-finite point")
        }
        assertTrue(chain.maxOf { abs(it.z) } > 5f, "the fit flattened the out-of-plane profile away")
        val last = fit.cubics.last().end
        assertEquals(p3.x, last.x)
        assertEquals(p3.z, last.z)
    }

    @Test
    fun hyperbezierThroughPassesThroughEveryPoint() {
        val points = listOf(
            vec(0f, 0f, 0f),
            vec(100f, 120f, 0f),
            vec(230f, 40f, 0f),
            vec(320f, 180f, 0f),
            vec(450f, 60f, 0f),
        )
        val line = hyperbezierThrough(points)
        assertTrue(line.nodes.size > points.size, "spline used fewer nodes than it had points")

        val visited = line.nodes.mapNotNull { it.toOrNull }
        for (p in points) {
            val hit = visited.any { hypot((it.x - p.x).toDouble(), (it.y - p.y).toDouble()) < 1e-3 }
            assertTrue(hit, "the spline missed the point (${p.x}, ${p.y})")
        }
    }

    @Test
    fun hyperbezierThroughClosesWhenAsked() {
        val points = listOf(
            vec(0f, 0f, 0f),
            vec(150f, 30f, 0f),
            vec(180f, 170f, 0f),
            vec(20f, 150f, 0f),
        )
        val open = hyperbezierThrough(points, closed = false)
        val closed = hyperbezierThrough(points, closed = true)
        assertTrue(closed.nodes.size > open.nodes.size, "closing added no span")
        assertTrue(closed.nodes.last() is FLine.Close, "closed spline did not end in a Close")
    }
}
