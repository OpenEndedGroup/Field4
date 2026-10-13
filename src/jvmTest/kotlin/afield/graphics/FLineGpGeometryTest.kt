package afield.graphics

import afield.math.gp.*
import afield.stdlib.*
import kotlin.math.*
import kotlin.test.*

class FLineGpGeometryTest {
    private fun circle(radius: Double, n: Int = 24): FLineGaussianProcess {
        val t = DoubleArray(n) { it.toDouble() / n }
        return gpCurve(t, t.map { vec(radius * cos(2 * PI * it), radius * sin(2 * PI * it), 0.0) },
            noiseStd = DoubleArray(n) { 1e-3 }, kernel = GpKernels.periodic(1.0, 1.0, radius * radius), dimensions = 2, mean = vec(0, 0, 0))
    }

    @Test fun `a sampled circle has the curvature and frames of a circle`() {
        val r = 3.0
        val curve = circle(r)
        val ts = DoubleArray(9) { 0.05 + it * 0.1 }
        val k = curve.curvature(ts, samples = 64, seed = 2)
        for (i in ts.indices) {
            assertEquals(1 / r, k.ofMean[i], 1e-3)
            assertEquals(1 / r, k.mean[i], 2e-3)
            // 1e-3 position noise leaves about 1% uncertainty in curvature (measured: ~3.4e-3,
            // independent of step from 1e-4 to 1e-2, and ten times larger at ten times the noise).
            assertTrue(k.std[i] in 1e-3..1e-2, "curvature std ${k.std[i]}")
        }
        for (f in curve.frames(ts)) {
            val p = f.position
            assertEquals(0.0, (p.x * f.tangent.x + p.y * f.tangent.y).toDouble(), 1e-3)      // tangent ⟂ radius
            assertEquals(-r, (p.x * f.normal!!.x + p.y * f.normal.y).toDouble(), 1e-3)  // left normal points inward
            assertEquals(2 * PI * r, f.speed, 1e-2)
            assertTrue(f.tangentAngleStd < 1e-2)
        }
        // The delta-method angle spread agrees with the spread of directions of drawn velocities.
        val v = curve.velocities(ts)
        val frames = curve.frames(ts)
        val rng = java.util.Random(8)
        val sum2 = DoubleArray(ts.size)
        repeat(4000) {
            val draw = v.sample(rng)
            for (i in ts.indices) {
                val m = v.mean[i]
                val d = atan2(m[0] * draw[i][1] - m[1] * draw[i][0], m[0] * draw[i][0] + m[1] * draw[i][1])
                sum2[i] += d * d
            }
        }
        for (i in ts.indices) assertEquals(frames[i].tangentAngleStd, sqrt(sum2[i] / 4000), 0.06 * frames[i].tangentAngleStd)
    }

    @Test fun `uncertainty about the tangent grows between sparse pins`() {
        val curve = gpCurve(doubleArrayOf(0.0, 1.0), listOf(vec(0, 0, 0), vec(1, 0, 0)), GpKernels.rbf(0.4), dimensions = 2)
        val frames = curve.frames(doubleArrayOf(0.5))
        assertTrue(frames[0].tangentAngleStd > 0.1)
        val k = curve.curvature(doubleArrayOf(0.5), samples = 128, seed = 5)
        assertEquals(0.0, k.ofMean[0], 1e-9)        // the mean is a straight line
        assertTrue(k.std[0] > 0.1)                    // its draws are not
    }

    @Test fun `velocity constraints set the direction a curve leaves its endpoints`() {
        val pins = listOf(vec(0, 0, 0), vec(1, 0, 0))
        val curve = gpCurve(doubleArrayOf(0.0, 1.0), pins, GpKernels.rbf(0.4), dimensions = 2,
            velocities = listOf(0.0 to vec(0, 3, 0), 1.0 to vec(0, -3, 0)))
        val v0 = curve.velocity(0.0)
        assertEquals(0.0, v0.x.toDouble(), 1e-6); assertEquals(3.0, v0.y.toDouble(), 1e-6)
        assertTrue(curve.mean(0.5).y > 0.2)           // it bulges upward to meet both
        assertEquals(0.0, curve.frames(doubleArrayOf(0.0))[0].tangentAngleStd, 1e-4)
    }

    @Test fun `spatial frames are orthonormal and spread rescales a drawn line`() {
        val t = DoubleArray(16) { it / 16.0 }
        val helix = gpCurve(t, t.map { vec(cos(2 * PI * it), sin(2 * PI * it), 0.5 * it) }, GpKernels.rbf(0.2))
        for (f in helix.frames(doubleArrayOf(0.3, 0.5, 0.7))) {
            val n = f.normal!!; val b = f.binormal!!
            assertEquals(0.0, (f.tangent.x * n.x + f.tangent.y * n.y + f.tangent.z * n.z).toDouble(), 1e-6)
            assertEquals(1.0, (b.x * b.x + b.y * b.y + b.z * b.z).toDouble(), 1e-6)
        }
        val joint = helix.posterior(doubleArrayOf(0.03, 0.5))
        val mean = joint.meanPoints
        val flat = joint.sampleLine(9, 0.0).nodes.map { it.storage[0] }
        for (i in mean.indices) assertEquals(mean[i].x.toDouble(), flat[i].x.toDouble(), 1e-6)
    }
}
