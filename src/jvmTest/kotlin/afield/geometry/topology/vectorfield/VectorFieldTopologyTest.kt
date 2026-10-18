package afield.geometry.topology.vectorfield

import afield.geometry.topology.morsesmale.CriticalType
import afield.geometry.topology.tracking.FeatureTracker
import afield.media.image.Img
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VectorFieldTopologyTest {
    private fun flow(w: Int = 41, h: Int = 31, f: (Double, Double) -> Pair<Double, Double>): Img {
        val d = FloatArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) { val (u, v) = f(x.toDouble(), y.toDouble()); d[3 * (y * w + x)] = u.toFloat(); d[3 * (y * w + x) + 1] = v.toFloat() }
        return Img(w, h, 3, d)
    }

    @Test fun linearFieldsAreClassified() {
        val cx = 20.3; val cy = 15.6
        fun one(f: (Double, Double) -> Pair<Double, Double>): FlowCriticalPoint {
            val t = VectorFieldTopology.compute(flow { x, y -> f(x - cx, y - cy) })
            assertEquals(1, t.criticalPoints.size, t.toString())
            val c = t.criticalPoints[0]
            assertTrue(abs(c.x - cx) < 1e-6 && abs(c.y - cy) < 1e-6, "at ${c.x},${c.y}")
            return c
        }
        assertEquals(FlowCriticalType.SOURCE, one { x, y -> x to y }.type)
        assertEquals(FlowCriticalType.SINK, one { x, y -> -x to -2 * y }.type)
        assertEquals(FlowCriticalType.SADDLE, one { x, y -> x to -y }.type)
        assertEquals(FlowCriticalType.CENTER, one { x, y -> -y to x }.type)
        assertEquals(FlowCriticalType.ATTRACTING_FOCUS, one { x, y -> (-y - 0.3 * x) to (x - 0.3 * y) }.type)
        assertEquals(FlowCriticalType.REPELLING_FOCUS, one { x, y -> (-y + 0.3 * x) to (x + 0.3 * y) }.type)
        val c = one { x, y -> (x - y) to (2 * x + y) }
        assertTrue(abs(c.divergence - 2.0) < 1e-4 && abs(c.curl - 3.0) < 1e-4 && abs(c.strength - sqrt(3.0)) < 1e-4)
    }

    private fun sqrt(x: Double) = kotlin.math.sqrt(x)

    @Test fun saddleSeparatricesFollowItsAxesToTheEdge() {
        val t = VectorFieldTopology.compute(flow { x, y -> (x - 20.5) to -(y - 15.5) })
        assertEquals(4, t.separatrices.size)
        assertEquals(2, t.separatrices.count { it.unstable })
        t.separatrices.forEach { s ->
            assertNull(s.end)
            val ex = s.points[s.points.size - 2]; val ey = s.points[s.points.size - 1]
            if (s.unstable) assertTrue(abs(ey - 15.5) < 0.5 && (ex < 1 || ex > 39), "unstable ends at $ex,$ey")
            else assertTrue(abs(ex - 20.5) < 0.5 && (ey < 1 || ey > 29), "stable ends at $ex,$ey")
        }
    }

    private fun twoHills(w: Int = 60, h: Int = 40, shift: Double = 0.0) = Img.generate(w, h) { x, y ->
        (exp(-((x - 18.0 - shift) * (x - 18.0 - shift) + (y - 20.0) * (y - 20.0)) / 60.0) +
            0.8 * exp(-((x - 42.0 - shift) * (x - 42.0 - shift) + (y - 20.3) * (y - 20.3)) / 60.0)).toFloat()
    }

    @Test fun gradientSaddleJoinsTheTwoHills() {
        val t = VectorFieldTopology.compute(VectorFieldTopology.gradientOf(twoHills()), minStrength = 1e-3)
        val sinks = t.of(FlowCriticalType.SINK, FlowCriticalType.ATTRACTING_FOCUS)
        assertEquals(2, sinks.size, t.toString())
        val saddle = t.of(FlowCriticalType.SADDLE).minBy { abs(it.x - 30) + abs(it.y - 20) }
        val ends = t.separatrices.filter { it.saddle == saddle.id && it.unstable }.mapNotNull { it.end }.toSet()
        assertEquals(sinks.map { it.id }.toSet(), ends)
    }

    @Test fun rotatedGradientCirclesTheHills() {
        val t = VectorFieldTopology.compute(VectorFieldTopology.gradientOf(twoHills(), rotate = true), minStrength = 1e-3, centerTolerance = 0.2)
        assertEquals(2, t.of(FlowCriticalType.CENTER).size, t.toString())
    }

    @Test fun outputsAndTracking() {
        val tracker = FeatureTracker()
        for (k in 0 until 5) {
            val t = VectorFieldTopology.compute(VectorFieldTopology.gradientOf(twoHills(shift = k * 0.5)), minStrength = 1e-3)
            tracker.add(k.toDouble(), t.features(minStrength = 1e-3).filter { it.type == CriticalType.MINIMUM || it.type == CriticalType.MAXIMUM })
            if (k == 0) {
                assertEquals(t.separatrices.size, t.toFLines().size)
                assertEquals(t.criticalPoints.size, t.criticalPointsFLine().nodes.size)
                assertEquals(t.separatrices.size, t.network().curves.size)
                assertTrue(t.streamlines(spacing = 10.0).isNotEmpty())
                assertTrue(t.streamline(10.0, 10.0).nodes.size > 2)
            }
        }
        assertEquals(tracker.alive.size, tracker.tracks.size, "features keep their tracks as the hills slide")
        val split = VectorFieldTopology.compute(flow { x, y -> (x - 20.5) to -(y - 15.5) })
        assertEquals(1, split.criticalPoints.size)
    }
}
