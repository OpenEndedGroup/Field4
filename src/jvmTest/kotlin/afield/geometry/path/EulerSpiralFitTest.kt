package afield.geometry.path

import afield.math.linalg.Vec3
import afield.math.linalg.vec
import kotlin.math.abs
import kotlin.math.acos
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `eulerSpiralBetween3D` must meet both endpoints and both directions. The previous solver fitted
 * only the length, then sheared the leftover endpoint error across every sample; the docs capture
 * looked plausible while the start tangent was about 15° off. These tests measure the tangents.
 */
class EulerSpiralFitTest {

    private fun points(line: FLine): List<Vec3> = line.nodes.map { Vec3(it.to) }

    private fun angleDeg(a: Vec3, b: Vec3): Double {
        val c = (Vec3(a).normalize().dot(Vec3(b).normalize())).toDouble().coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(c))
    }

    private fun checkFit(start: Vec3, startDir: Vec3, end: Vec3, endDir: Vec3, label: String) {
        val pts = points(eulerSpiralBetween3D(start, startDir, end, endDir, samples = 400))
        val chord = Vec3(end).sub(start).length()

        val startGap = Vec3(pts.first()).sub(start).length()
        assertTrue(startGap < 1e-4f * chord, "$label: starts $startGap away from start")

        // The last sample is snapped to `end`; the one before it shows whether the curve really got there.
        val approach = Vec3(pts[pts.lastIndex - 1]).sub(pts.last()).length()
        val step = Vec3(pts[1]).sub(pts[0]).length()
        assertTrue(approach < 1.5f * step, "$label: second-to-last sample is $approach from end (step $step)")

        val startTangent = Vec3(pts[1]).sub(pts[0])
        val endTangent = Vec3(pts.last()).sub(pts[pts.lastIndex - 1])
        val startErr = angleDeg(startTangent, startDir)
        val endErr = angleDeg(endTangent, endDir)
        assertTrue(startErr < 1.0, "$label: start tangent off by $startErr°")
        assertTrue(endErr < 1.0, "$label: end tangent off by $endErr°")
    }

    @Test
    fun docsExampleMeetsBothTangents() {
        checkFit(vec(60f, 180f, 0f), vec(1f, 0f, 0f), vec(360f, 120f, 0f), vec(-0.2f, -1f, 0f), "docs example")
    }

    @Test
    fun sCurveAndUTurnMeetBothTangents() {
        checkFit(vec(0f, 0f, 0f), vec(0f, 1f, 0f), vec(100f, 0f, 0f), vec(0f, 1f, 0f), "S-ish, parallel directions")
        checkFit(vec(0f, 0f, 0f), vec(1f, 0f, 0f), vec(0f, 50f, 0f), vec(-1f, 0f, 0f), "U-turn")
        checkFit(vec(0f, 0f, 0f), vec(1f, 1f, 0f), vec(100f, 0f, 0f), vec(1f, 1f, 0f), "S-curve")
        checkFit(vec(0f, 0f, 0f), vec(1f, 0f, 0f), vec(100f, 0f, 0f), vec(1f, 0f, 0f), "straight line")
    }

    @Test
    fun curvatureChangesLinearly() {
        val pts = points(eulerSpiralBetween3D(vec(0f, 0f, 0f), vec(1f, 0f, 0f), vec(200f, 80f, 0f), vec(0f, 1f, 0f), samples = 201))
        // Discrete heading change per step is κ·ds; for a clothoid its second difference is constant.
        val headings = (1 until pts.size).map {
            val d = Vec3(pts[it]).sub(pts[it - 1])
            kotlin.math.atan2(d.y.toDouble(), d.x.toDouble())
        }
        val turns = (1 until headings.size).map { headings[it] - headings[it - 1] }
        val accel = (1 until turns.size).map { turns[it] - turns[it - 1] }
        val mean = accel.average()
        val worst = accel.maxOf { abs(it - mean) }
        val scale = turns.maxOf { abs(it) }
        assertTrue(worst < 0.02 * scale, "curvature is not linear in arc length: deviation $worst vs turn scale $scale")
    }

    @Test
    fun outOfPlaneEndIsReachedByALinearLift() {
        val start = vec(0f, 0f, 0f)
        val end = vec(100f, 40f, 30f)
        val pts = points(eulerSpiralBetween3D(start, vec(1f, 0f, 0f), end, vec(0f, 1f, 0f), samples = 300))
        val approach = Vec3(pts[pts.lastIndex - 1]).sub(pts.last()).length()
        val step = Vec3(pts[1]).sub(pts[0]).length()
        assertTrue(approach < 1.5f * step, "lifted curve does not arrive at end: $approach (step $step)")
        val zSteps = (1 until pts.size).map { pts[it].z - pts[it - 1].z }
        assertTrue(zSteps.maxOf { abs(it - zSteps.first()) } < 1e-3f, "lift is not linear")
    }
}
