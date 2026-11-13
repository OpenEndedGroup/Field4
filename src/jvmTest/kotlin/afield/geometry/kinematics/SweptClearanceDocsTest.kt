package afield.geometry.kinematics

import afield.geometry.solid.manifold.ManifoldLibrary
import afield.geometry.solid.manifold.Solid
import afield.math.linalg.Vector3d
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Runs the examples on `public/docs/swept_clearance.md` and holds the numbers the page quotes. */
class SweptClearanceDocsTest {
    @Test fun thePagesNumbersAreWhatTheCodeComputes() {
        if (!ManifoldLibrary.isAvailable) return println("skipped: libmanifoldc is not installed")
        val swing = RigidMotion.rotation(Vector3d(0.0, 0.0, 0.0), Vector3d(0.0, 0.0, 1.0), 0.0, 90.0)
        assertEquals(91, swing.stepsBy(1.0).size)
        assertEquals("""{"type":"rotation","point":[0.0,0.0,0.0],"axis":[0.0,0.0,1.0],"from":0.0,"to":90.0}""", swing.toJson())
        Solid.scope {
            val slab = Solid.cube(1000.0, 20.0, 100.0)
            val corner = 1100.0 / sqrt(2.0)
            val post = Solid.cube(100.0, 100.0, 100.0).translated(corner, corner, 0.0)

            val c = sweptClearance(slab, swing, listOf(post), maxStep = 1.0)
            assertEquals("ClearanceStep(value=44.0000, gap=99.8325 to obstacle 0)", c.minimum.toString())
            assertEquals(91.1878, c.lowerBound, 5e-5)

            val fine = sweptClearance(slab, swing, listOf(post), maxStep = 1.0, tolerance = 0.01)
            assertEquals("ClearanceStep(value=43.8545, gap=99.8000 to obstacle 0)", fine.minimum.toString())
            assertEquals(99.7901, fine.lowerBound, 5e-5)
            assertEquals(459, fine.steps.size)
            val k = fine.minimum.contact!!
            assertEquals(707.25, k.onPart.x(), 0.005); assertEquals(100.0, k.onPart.z(), 1e-9)
            assertEquals(777.82, k.onObstacle.x(), 0.005)

            val r = sweptClearance(slab, swing, listOf(post), maxStep = 1.0, tolerance = 0.01, threshold = 101.0)
            assertTrue(r.keeps(99.0))
            assertTrue(r.firstBelow(101.0)!!.value < 43.8545)

            val swept = sweptVolume(slab, swing, maxStep = 1.0)
            assertEquals(8.05732e7, swept.solid.volume, 50.0)
            assertEquals(8.73, swept.maxOvershoot, 0.005)

            val pass = minkowskiClearance(slab, swing, listOf(post), clearance = 85.0, names = listOf("post"))
            assertEquals("MinkowskiClearance(keeps 85.0, resolution 12.80)", pass.toString())
            val verdict = minkowskiClearance(slab, swing, listOf(post), clearance = 110.0, names = listOf("post"))
            assertFalse(verdict.passes)
            val w = verdict.worst!!
            assertEquals(40.0, w.from); assertEquals(48.0, w.to); assertEquals(44.0, w.worstFrom); assertEquals(45.0, w.worstTo)
            assertEquals(2.360e4, w.overlapVolume, 5.0)
            assertEquals(788.5, w.where.x(), 0.05); assertEquals(788.7, w.where.y(), 0.05)
        }
    }
}
