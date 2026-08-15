package afield.graphics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Checks the [Hyperbezier] core against the reference implementation it was ported from — the
 * JavaScript driving the figures in Raph Levien's hyperbezier post — and against the closed forms
 * the family is supposed to contain exactly.
 *
 * The [Reference] rows are the reference implementation's own output, transcribed at full double
 * precision: the four Cesaro parameters and three points along the curve in the chord frame. They
 * are the load-bearing part of this file. Everything below them is a property the blog claims and
 * this port therefore has to keep.
 */
class HyperbezierTest {

    private class Reference(
        val name: String,
        val th0: Double,
        val th1: Double,
        val l0: Double,
        val l1: Double,
        val a: Double,
        val b: Double,
        val c: Double,
        val d: Double,
        /** x, y at t = 0.25, 0.5, 0.75. */
        val points: DoubleArray,
    )

    private val references = listOf(
        Reference("circle", 0.7853981633974483, -0.7853981633974483, 0.39052429175127, 0.39052429175127,
            0.0, -1.5707963267948966, 2.6645352591003745e-15, -2.6645352591003745e-15,
            doubleArrayOf(0.22940194992690158, 0.15328148243818823, 0.5000000000000002, 0.2071067811865474, 0.7705980500730987, 0.15328148243818823)),
        Reference("euler60", 1.0471975511965976, -1.0471975511965976, 0.4444444444444444, 0.4444444444444444,
            0.0, -2.0943951023931953, 0.0, 0.0,
            doubleArrayOf(0.21132486540518725, 0.21132486540518713, 0.5000000000000001, 0.288675134594813, 0.7886751345948126, 0.21132486540518713)),
        Reference("euler90", 1.5707963267948966, -1.5707963267948966, 0.6666666666666666, 0.6666666666666666,
            0.0, -3.141592653589793, 0.0, 0.0,
            doubleArrayOf(0.14644660940672627, 0.35355339059327373, 0.4999999999999997, 0.4999999999999999, 0.8535533905932736, 0.35355339059327356)),
        Reference("euler120", 2.0943951023931953, -2.0943951023931953, 1.3333333333333328, 1.3333333333333328,
            0.0, -4.1887902047863905, 0.0, 0.0,
            doubleArrayOf(0.0, 0.5773502691896261, 0.5000000000000002, 0.8660254037844388, 1.0000000000000004, 0.577350269189626)),
        Reference("euler135", 2.356194490192345, -2.356194490192345, 2.276142374915396, 2.276142374915396,
            0.0, -4.71238898038469, -1.776356839400251e-15, 1.776356839400251e-15,
            doubleArrayOf(-0.1532814824381886, 0.7705980500730988, 0.49999999999999967, 1.207106781186548, 1.1532814824381883, 0.7705980500730988)),
        Reference("sShape", 0.5235987755982988, 0.5235987755982988, 0.3572655899081636, 0.3572655899081636,
            6.266663452565842, -3.133331726282921, 0.0, 0.0,
            doubleArrayOf(0.24833402011692512, 0.049569920406120876, 0.5000000000000001, -2.931773251951035e-17, 0.7516659798830747, -0.04956992040612097)),
        Reference("corner", 0.7853981633974483, -0.7853981633974483, 0.624838866802032, 0.624838866802032,
            0.0, -0.3283282345945162, 3.163919016122357, -3.163919016122357,
            doubleArrayOf(0.22463583717832333, 0.19675624779497514, 0.5000000000000001, 0.300084425786865, 0.7753641628216769, 0.19675624779497514)),
        Reference("past", 0.7853981633974483, -0.7853981633974483, 0.937258300203048, 0.937258300203048,
            0.0, -0.042711621342409675, 3.891235749374179, -3.891235749374179,
            doubleArrayOf(0.2324432978502539, 0.22768957226177453, 0.4999999999999999, 0.40111482983350416, 0.767556702149746, 0.2276895722617744)),
        Reference("asym1", 1.2217304763960306, -0.3490658503988659, 0.4, 0.25,
            7.992856049048442, -7.208141524282854, -4.448933575910764, 4.264967069259864,
            doubleArrayOf(0.21079593848355202, 0.1574884569055726, 0.4819543546743119, 0.16778512226027487, 0.7459878756971567, 0.1001199119011453)),
        Reference("asym2", 0.3490658503988659, -1.2217304763960306, 0.25, 0.4,
            -10.842764243197099, 1.0645099246168337, -5.451900702815279, 5.677340599768381,
            doubleArrayOf(0.25401212430284326, 0.1001199119011451, 0.5180456453256883, 0.16778512226027487, 0.7892040615164484, 0.1574884569055727)),
        Reference("shallow", 0.13962634015954636, -0.13962634015954636, 0.3, 0.3,
            0.0, -0.3887094340165819, -1.5678525065172755, 1.5678525065172757,
            doubleArrayOf(0.24944377024682796, 0.024519468889209858, 0.5000000000000001, 0.03207009535010012, 0.7505562297531722, 0.02451946888920986)),
        Reference("tiny", 0.017453292519943295, -0.017453292519943295, 0.33, 0.33,
            0.0, -0.03598330093876506, -0.12338255348074208, 0.12338255348074208,
            doubleArrayOf(0.24999055325024955, 0.0032538422823058456, 0.4999999999999999, 0.004330292995517394, 0.7500094467497505, 0.003253842282305848)),
        Reference("slack", 0.7853981633974483, -0.7853981633974483, 0.15, 0.15,
            -1.1766953748318858e-13, -27.714541758006106, -66.5745010610147, 66.5745010610147,
            doubleArrayOf(0.2449607522690987, 0.0629297653374328, 0.4999999974221822, 0.07566242798775184, 0.7550392427785627, 0.06292976543115152)),
        Reference("loose", 0.5235987755982988, -1.0471975511965976, 0.9, 0.55,
            0.17774612862845812, -0.16430248108526493, 1.6304657845719672, -2.5165491176868233,
            doubleArrayOf(0.2641843051782006, 0.14326368148916674, 0.5415722927107096, 0.25807041872652814, 0.8320293798990673, 0.24636399833783088)),
        Reference("sAsym", 0.6981317007977318, 0.2617993877991494, 0.35, 0.45,
            7.87807159341356, -3.766237647692992, 1.7475787266357155, -0.652270001149707,
            doubleArrayOf(0.2407886346034512, 0.07143125310380938, 0.4926318998520948, 0.022082858438813392, 0.7451613254749938, -0.024171267432594484)),
    )

    /** Mixed absolute/relative closeness, so a parameter that is legitimately ~0 is comparable. */
    private fun close(actual: Double, expected: Double, tol: Double = 1e-9): Boolean =
        abs(actual - expected) <= tol * max(1.0, abs(expected))

    @Test
    fun matchesTheReferenceImplementation() {
        for (r in references) {
            val hb = assertNotNull(hyperbezierFor(r.th0, r.th1, r.l0, r.l1), "${r.name}: no solution")
            assertTrue(close(hb.a, r.a), "${r.name}: a was ${hb.a}, reference ${r.a}")
            assertTrue(close(hb.b, r.b), "${r.name}: b was ${hb.b}, reference ${r.b}")
            assertTrue(close(hb.c, r.c), "${r.name}: c was ${hb.c}, reference ${r.c}")
            assertTrue(close(hb.d, r.d), "${r.name}: d was ${hb.d}, reference ${r.d}")

            listOf(0.25, 0.5, 0.75).forEachIndexed { i, t ->
                val p = hb.pointAt(t)
                val ex = r.points[2 * i]
                val ey = r.points[2 * i + 1]
                assertTrue(
                    hypot(p[0] - ex, p[1] - ey) <= 1e-9,
                    "${r.name}: point at t=$t was (${p[0]}, ${p[1]}), reference ($ex, $ey)",
                )
            }
        }
    }

    @Test
    fun interpolatesItsEndpointsAndEndTangents() {
        for (r in references) {
            val hb = assertNotNull(hyperbezierFor(r.th0, r.th1, r.l0, r.l1), "${r.name}: no solution")
            val start = hb.pointAt(0.0)
            val end = hb.pointAt(1.0)
            assertTrue(hypot(start[0], start[1]) < 1e-9, "${r.name}: start was (${start[0]}, ${start[1]})")
            assertTrue(hypot(end[0] - 1.0, end[1]) < 1e-9, "${r.name}: end was (${end[0]}, ${end[1]})")
            assertTrue(
                abs(wrapAngle(hb.tangentAngleAt(0.0) - r.th0)) < 1e-8,
                "${r.name}: start tangent was ${hb.tangentAngleAt(0.0)}, wanted ${r.th0}",
            )
            assertTrue(
                abs(wrapAngle(hb.tangentAngleAt(1.0) - r.th1)) < 1e-8,
                "${r.name}: end tangent was ${hb.tangentAngleAt(1.0)}, wanted ${r.th1}",
            )
        }
    }

    @Test
    fun circleArmsWithSymmetricTangentsGiveAnExactArc() {
        for (degrees in listOf(15.0, 30.0, 45.0, 60.0, 90.0, 120.0)) {
            val th = degrees * PI / 180.0
            val arm = circleArm(th)
            val hb = assertNotNull(hyperbezierFor(th, -th, arm, arm), "$degrees: no solution")

            // Chord 1 subtending a turn of 2*th gives radius 1/(2 sin th), so curvature 2 sin th.
            val expected = 2 * sin(th)
            val curvatures = (0..20).map { abs(hb.curvatureAt(it / 20.0)) }
            for (k in curvatures) {
                assertTrue(
                    abs(k - expected) <= 1e-7 * expected,
                    "$degrees deg: curvature $k, wanted a constant $expected",
                )
            }

            // ...and the apex sits exactly at the circular sagitta.
            val radius = 1.0 / (2 * sin(th))
            val mid = hb.pointAt(0.5)
            assertTrue(abs(mid[0] - 0.5) < 1e-9, "$degrees deg: apex x was ${mid[0]}")
            assertTrue(
                abs(abs(mid[1]) - (radius - radius * cos(th))) < 1e-9,
                "$degrees deg: apex y was ${mid[1]}, wanted ${radius - radius * cos(th)}",
            )
        }
    }

    @Test
    fun circleArmsWithAsymmetricTangentsGiveAnExactEulerSpiral() {
        // The blog's claim is stronger than the arc case: u = v = 1 gives q == 1 at *every* angle
        // pair, and q == 1 with a != 0 is an Euler spiral -- curvature linear in arc length.
        for ((d0, d1) in listOf(50.0 to -20.0, 30.0 to 30.0, 80.0 to 10.0, -25.0 to -70.0)) {
            val th0 = d0 * PI / 180.0
            val th1 = d1 * PI / 180.0
            val hb = assertNotNull(
                hyperbezierFor(th0, th1, circleArm(th0), circleArm(th1)),
                "$d0/$d1: no solution",
            )
            assertTrue(hb.isEulerFamily, "$d0/$d1: q was not constant, got c=${hb.c} d=${hb.d}")

            val k0 = hb.curvatureAt(0.0)
            val k1 = hb.curvatureAt(1.0)
            for (i in 0..20) {
                val t = i / 20.0
                val expected = k0 + (k1 - k0) * t
                assertTrue(
                    abs(hb.curvatureAt(t) - expected) <= 1e-7 * max(1.0, abs(expected)),
                    "$d0/$d1: curvature at $t was ${hb.curvatureAt(t)}, wanted $expected",
                )
            }
        }
    }

    @Test
    fun armsPastTheCircleLengthTightenIntoACorner() {
        val th = 45.0 * PI / 180.0
        val baseline = circleArm(th)
        var previousPeak = 0.0
        for (stretch in listOf(1.0, 1.4, 1.8, 2.4)) {
            val arm = baseline * stretch
            val hb = assertNotNull(hyperbezierFor(th, -th, arm, arm), "$stretch: no solution")
            val peak = (0..200).maxOf { abs(hb.curvatureAt(it / 200.0)) }
            assertTrue(
                peak > previousPeak,
                "stretch $stretch: peak curvature $peak did not exceed the previous $previousPeak",
            )
            previousPeak = peak
            if (stretch > 1.0) {
                // A corner is q dipping below 1 somewhere inside, not just a bigger circle.
                val qMin = (0..200).minOf { hb.q(it / 200.0) }
                assertTrue(qMin < 0.999, "stretch $stretch: q never dipped, min was $qMin")
            }
        }
    }

    @Test
    fun shortArmsStayInTheFamilyAndKeepTheirTangents() {
        // The far field: arms well under the circle length. The clamp in the map is what keeps
        // these solvable at all, and the endpoint conditions still have to hold.
        for (fraction in listOf(0.6, 0.4, 0.25, 0.1)) {
            val th = 60.0 * PI / 180.0
            val arm = circleArm(th) * fraction
            val hb = assertNotNull(hyperbezierFor(th, -th, arm, arm), "$fraction: no solution")
            val end = hb.pointAt(1.0)
            assertTrue(hypot(end[0] - 1.0, end[1]) < 1e-8, "$fraction: end was (${end[0]}, ${end[1]})")
            assertTrue(abs(wrapAngle(hb.tangentAngleAt(0.0) - th)) < 1e-7, "$fraction: start tangent drifted")
        }
    }
}
