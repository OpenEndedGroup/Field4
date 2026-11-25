package afield.geometry.kinematics

import afield.geometry.solid.manifold.ManifoldLibrary
import afield.geometry.solid.manifold.Solid
import afield.math.linalg.Vector3d
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SweptClearanceTest {
    private val z = Vector3d(0.0, 0.0, 1.0)
    private val origin = Vector3d()

    // A slab 1000 x 20 x 100 hinged on the z axis at the origin, and a box whose near corner C sits at radius
    // 1100 and 45°. Every box point is at least 1100 from the axis and every slab point within R of it, so the
    // nearest approach is exactly 1100 - R, when the slab's far corner points at C.
    private val w = 1000.0; private val th = 20.0; private val h = 100.0
    private val r = hypot(w, th)
    private val d = 1100.0
    private val c = Vector3d(d * cos(PI / 4), d * sin(PI / 4), 0.0)
    private val slab = ClearanceBody.box(Vector3d(0.0, 0.0, 0.0), Vector3d(w, th, h), "slab")
    private val post = ClearanceBody.box(c, Vector3d(c).add(Vector3d(100.0, 100.0, h)), "post")
    private val swing = RigidMotion.rotation(origin, z, 0.0, 90.0)
    private val angleOfMinimum = 45.0 - Math.toDegrees(atan2(th, w))

    @Test fun aSlabSwingingPastABoxComesClosestWhereTheGeometrySays() {
        val sampled = sweptClearance(slab, swing, listOf(post), maxStep = 1.0)
        assertEquals(91, sampled.steps.size)
        assertEquals("post", sampled.minimum.obstacle)
        assertTrue(sampled.minimum.gap >= d - r - 1e-9)
        assertTrue(sampled.lowerBound <= d - r + 1e-9, "the certified bound ${sampled.lowerBound} must not exceed the true minimum ${d - r}")

        val refined = sweptClearance(slab, swing, listOf(post), maxStep = 1.0, tolerance = 0.01)
        assertEquals(d - r, refined.minimum.gap, 0.01)
        assertTrue(refined.minimum.gap - refined.lowerBound <= 0.01 + 1e-9)
        assertEquals(angleOfMinimum, refined.minimum.value, 0.05)
        val k = refined.minimum.contact!!
        assertEquals(c.x, k.onObstacle.x(), 0.5); assertEquals(c.y, k.onObstacle.y(), 0.5)
        assertEquals(refined.minimum.gap, Vector3d(k.onPart).sub(k.onObstacle).length(), 1e-9)
    }

    @Test fun theFirstAngleUnderAThresholdIsTheCornerReachingIt() {
        // Near the minimum the far corner is nearest to C, so gap(θ) = |corner(θ) − C| = sqrt(R² + D² − 2RD cos(θ* − θ)).
        val threshold = 101.0
        val cosDelta = (r * r + d * d - threshold * threshold) / (2 * r * d)
        val expected = angleOfMinimum - Math.toDegrees(acos(cosDelta))
        val result = sweptClearance(slab, swing, listOf(post), maxStep = 1.0, tolerance = 0.01, threshold = threshold)
        val first = assertNotNull(result.firstBelow(threshold))
        assertTrue(first.gap < threshold)
        // Located to within a step over which nothing moves more than the tolerance: 0.01 / R radians.
        assertEquals(expected, first.value, Math.toDegrees(0.01 / 1000.0) * 2 + 1e-6)
        assertNull(result.firstBelow(d - r - 0.1))
        assertTrue(result.keeps(d - r - 0.02)); assertFalse(result.keeps(d - r + 0.02))
    }

    @Test fun aSlideTowardAWallHasGapDistanceMinusTravel() {
        val part = ClearanceBody.box(Vector3d(0.0, 0.0, 0.0), Vector3d(100.0, 100.0, 100.0), "block")
        val wall = ClearanceBody.box(Vector3d(700.0, -500.0, -500.0), Vector3d(800.0, 500.0, 500.0), "wall")
        val slide = RigidMotion.slide(Vector3d(1.0, 0.0, 0.0), 0.0, 500.0)
        val result = sweptClearance(part, slide, listOf(wall), maxStep = 10.0, tolerance = 0.01, threshold = 250.0)
        for (s in result.steps) assertEquals(600.0 - s.value, s.gap, 1e-9)
        assertEquals(100.0, result.minimum.gap, 1e-9)
        assertEquals(500.0, result.minimum.value)
        assertEquals(350.0, result.firstBelow(250.0)!!.value, 0.02)
        // A probe answers for values of the caller's choosing, the same way.
        val probe = ClearanceProbe(part, slide, listOf(wall))
        assertEquals(600.0 - 123.4, probe.at(123.4).gap, 1e-9)
        assertEquals(30.0, probe.travel(10.0, 40.0))
        assertEquals(36, part.triangles().size)
        // A slide's travel bound is exact, so the certified bound is the true minimum.
        assertEquals(100.0, result.lowerBound, 1e-9)
    }

    @Test fun rotationAboutATiltedAxisKeepsTheAxialGapAndAgreesWithBruteForce() {
        val u = Vector3d(1.0, 2.0, 2.0).normalize()   // a tilted axis
        val motion = RigidMotion.rotation(Vector3d(0.5, -0.25, 0.0), u, -40.0, 220.0)
        val part = ClearanceBody.box(Vector3d(2.0, 0.0, 0.0), Vector3d(3.0, 1.0, 0.5), "cube")
        // A slab across the axis, 4 beyond the part along it: rotating about u keeps every point's u-coordinate,
        // so the gap to it is 4 at every angle.
        val verts = part.vertices()
        val top = (0 until 8).maxOf { verts[it * 3] * u.x + verts[it * 3 + 1] * u.y + verts[it * 3 + 2] * u.z }
        val e1 = Vector3d(2.0, -1.0, 0.0).normalize(); val e2 = Vector3d(u).cross(e1)
        val corners = ArrayList<Double>()
        for (a in listOf(-50.0, 50.0)) for (b in listOf(-50.0, 50.0)) for (t in listOf(0.0, 1.0)) {
            val p = Vector3d(u).mul(top + 4.0 + t).add(Vector3d(e1).mul(a)).add(Vector3d(e2).mul(b))
            corners += listOf(p.x, p.y, p.z)
        }
        val slab = ClearanceBody.ofTriangles(corners.toDoubleArray(), hullTriangles(), "slab")
        val axial = sweptClearance(part, motion, listOf(slab), maxStep = 5.0)
        assertEquals(53, axial.steps.size)
        for (s in axial.steps) assertEquals(4.0, s.gap, 1e-9, "at ${s.value}°")

        // Against an arbitrary box, compare every step with brute force over all triangle pairs.
        val other = ClearanceBody.box(Vector3d(-1.0, 2.5, 1.0), Vector3d(0.5, 4.0, 2.0), "box")
        val result = sweptClearance(part, motion, listOf(other), maxStep = 10.0)
        for (s in result.steps) assertEquals(bruteForce(part, motion.poseAt(s.value), other), s.gap, 1e-9, "at ${s.value}°")
    }

    @Test fun meshDistanceAgreesWithBruteForceOnRandomPoses() {
        val rnd = Random(7)
        val a = ClearanceBody.prism(listOf(0.0 to 0.0, 3.0 to 0.0, 4.0 to 2.0, 1.0 to 3.0), 0.0, 1.5, "a")
        val b = ClearanceBody.box(Vector3d(-1.0, -1.0, -1.0), Vector3d(1.0, 1.0, 1.0), "b")
        repeat(200) {
            val m = RigidMotion.rotation(
                Vector3d(rnd.nextDouble(-3.0, 3.0), rnd.nextDouble(-3.0, 3.0), rnd.nextDouble(-3.0, 3.0)),
                Vector3d(rnd.nextDouble(-1.0, 1.0), rnd.nextDouble(-1.0, 1.0), rnd.nextDouble(0.1, 1.0)), 0.0, 360.0,
            ).poseAt(rnd.nextDouble(0.0, 360.0))
            val got = a.distanceTo(b, m)
            assertEquals(bruteForce(a, m, b), got.gap, 1e-9)
            if (!got.touching) assertEquals(got.gap, Vector3d(got.onPart).sub(got.onObstacle).length(), 1e-9)
        }
    }

    @Test fun nestedClosedBodiesAreAtGapZero() {
        val big = ClearanceBody.box(Vector3d(-10.0, -10.0, -10.0), Vector3d(10.0, 10.0, 10.0), "big")
        val small = ClearanceBody.box(Vector3d(-1.0, -1.0, -1.0), Vector3d(1.0, 1.0, 1.0), "small")
        assertTrue(small.isClosed)
        val inside = small.distanceTo(big)
        assertEquals(0.0, inside.gap); assertTrue(inside.touching)
        val around = big.distanceTo(small)
        assertEquals(0.0, around.gap)
        val apart = small.distanceTo(big, RigidMotion.slide(Vector3d(1.0, 0.0, 0.0), 0.0, 1.0).poseAt(15.0))
        assertEquals(4.0, apart.gap, 1e-12)
    }

    @Test fun manifoldSolidsGoInAndAgreeWithMinGap() {
        org.junit.Assume.assumeTrue("native library not available: ${ManifoldLibrary.describe()}", ManifoldLibrary.isAvailable)
        Solid.scope {
            val door = Solid.cube(w, th, h)
            val box = Solid.cube(100.0, 100.0, h).translated(c.x, c.y, 0.0)
            val result = sweptClearance(door, swing, listOf(box), maxStep = 1.0, tolerance = 0.01)
            assertEquals(d - r, result.minimum.gap, 0.01)
            val at = result.minimum.value
            val pose = swing.poseAt(at)
            val posed = door.transformedBy(pose)
            assertEquals(posed.minGap(box, 500.0), result.minimum.gap, 1e-6)
        }
    }

    @Test fun jsonCarriesTheMotionAndPerStepGaps() {
        val result = sweptClearance(slab, swing.withRange(0.0, 2.0), listOf(post), maxStep = 1.0)
        val json = result.toJson()
        assertTrue(json.startsWith("""{"motion":{"type":"rotation","""), json)
        assertTrue(json.contains(""""obstacle":"post""""), json)
        assertEquals(3, Regex(""""value":""").findAll(json).count())
    }

    private fun hullTriangles(): IntArray {
        // The 8 corners above, indexed (a, b, t) with t fastest: a box's 12 triangles, outward-wound or not (only
        // distance is asked of them).
        fun i(a: Int, b: Int, t: Int) = a * 4 + b * 2 + t
        val faces = listOf(
            listOf(i(0, 0, 0), i(1, 0, 0), i(1, 1, 0), i(0, 1, 0)), listOf(i(0, 0, 1), i(1, 0, 1), i(1, 1, 1), i(0, 1, 1)),
            listOf(i(0, 0, 0), i(1, 0, 0), i(1, 0, 1), i(0, 0, 1)), listOf(i(0, 1, 0), i(1, 1, 0), i(1, 1, 1), i(0, 1, 1)),
            listOf(i(0, 0, 0), i(0, 1, 0), i(0, 1, 1), i(0, 0, 1)), listOf(i(1, 0, 0), i(1, 1, 0), i(1, 1, 1), i(1, 0, 1)),
        )
        return faces.flatMap { f -> listOf(f[0], f[1], f[2], f[0], f[2], f[3]) }.toIntArray()
    }

    private fun bruteForce(a: ClearanceBody, pose: afield.math.linalg.Matrix4dc, b: ClearanceBody): Double {
        val pa = PosedBody(a).also { it.place(pose) }
        val q = TriangleQuery()
        var best = Double.POSITIVE_INFINITY
        for (s in 0 until a.triangleCount) for (t in 0 until b.triangleCount) {
            best = min(best, q.distance2(pa.world, a.indices, s, b.positions, b.indices, t))
        }
        return sqrt(best)
    }
}

class SweptVolumeTest {
    @Test fun aSwingingSlabsSweptVolumeContainsTheSweepAndConverges() {
        org.junit.Assume.assumeTrue("native library not available: ${ManifoldLibrary.describe()}", ManifoldLibrary.isAvailable)
        val w = 1000.0; val t = 20.0; val h = 100.0
        val swing = RigidMotion.rotation(Vector3d(), Vector3d(0.0, 0.0, 1.0), 0.0, 90.0)
        // The plan area swept by [0,w]x[0,t] turning 90° about the origin, by integrating the angle covered at
        // each radius (see the case split in sweptPlanArea).
        val exact = sweptPlanArea(w, t) * h
        Solid.scope {
            val slab = Solid.cube(w, t, h)
            var previous = Double.POSITIVE_INFINITY
            for (step in listOf(10.0, 3.0, 1.0)) {
                val swept = sweptVolume(slab, swing, maxStep = step)
                val v = swept.solid.volume
                assertTrue(v >= exact * (1 - 1e-9), "step $step: swept $v under the exact $exact")
                assertTrue(v <= previous + 1e-6, "finer steps should not grow the volume")
                previous = v
                // Every fine intermediate pose lies inside.
                for (k in 0..90) {
                    val pose = slab.transformedBy(swing.poseAt(k * 1.0 + 0.37 * (k % 2)))
                    assertEquals(0.0, (pose - swept.solid).volume, 1e-3 * h, "pose ${k}° pokes out at step $step")
                }
            }
            assertEquals(exact, previous, exact * 2e-4)
        }
    }

    @Test fun aSlidingBlockSweepsExactly() {
        org.junit.Assume.assumeTrue("native library not available: ${ManifoldLibrary.describe()}", ManifoldLibrary.isAvailable)
        Solid.scope {
            val block = Solid.cube(10.0, 20.0, 30.0)
            val swept = sweptVolume(block, RigidMotion.slide(Vector3d(1.0, 1.0, 0.0), 0.0, sqrt(2.0) * 5.0), maxStep = 100.0)
            // A box slid diagonally by (5, 5): its plan sweeps a hexagon of area 10·20 + 5·20 + 10·5.
            assertEquals((200.0 + 100.0 + 50.0) * 30.0, swept.solid.volume, 1e-6)
            assertEquals(0.0, swept.maxOvershoot)
        }
    }

    @Test fun aNotchedPartSlidingPastAPostInItsNotchKeepsTheNotch() {
        org.junit.Assume.assumeTrue("native library not available: ${ManifoldLibrary.describe()}", ManifoldLibrary.isAvailable)
        Solid.scope {
            // A 100-long block with a 10-wide slot down its length, sliding along the slot over a post in it.
            val part = Solid.cube(100.0, 30.0, 20.0) - Solid.cube(120.0, 10.0, 12.0).translated(-10.0, 10.0, 10.0)
            val post = Solid.cube(4.0, 4.0, 4.0).translated(150.0, 13.0, 14.0)
            val slide = RigidMotion.slide(Vector3d(1.0, 0.0, 0.0), 0.0, 100.0)
            val hulled = minkowskiClearance(part, slide, listOf(post), clearance = 1.0, maxStep = 25.0, shape = SweepShape.HULLS)
            assertFalse(hulled.passes, "hulling the whole part fills the slot")
            val exact = minkowskiClearance(part, slide, listOf(post), clearance = 1.0, maxStep = 25.0)
            assertTrue(exact.passes, "the slot is 3 clear of the post on each side: $exact")
            val tight = minkowskiClearance(part, slide, listOf(post), clearance = 3.5, maxStep = 25.0, names = listOf("post"))
            assertFalse(tight.passes)
            assertEquals("post", tight.worst!!.obstacle)
        }
    }

    @Test fun minkowskiClearancePassesUnderTheGapAndFailsOverItWhereTheSlabComesClose() {
        org.junit.Assume.assumeTrue("native library not available: ${ManifoldLibrary.describe()}", ManifoldLibrary.isAvailable)
        val w = 1000.0; val t = 20.0; val h = 100.0; val d = 1100.0
        val r = hypot(w, t)
        val swing = RigidMotion.rotation(Vector3d(), Vector3d(0.0, 0.0, 1.0), 0.0, 90.0)
        Solid.scope {
            val slab = Solid.cube(w, t, h)
            val box = Solid.cube(100.0, 100.0, h).translated(d * cos(PI / 4), d * sin(PI / 4), 0.0)
            val pass = minkowskiClearance(slab, swing, listOf(box), clearance = 85.0, maxStep = 1.0, names = listOf("post"))
            assertTrue(pass.passes, "$pass")
            assertTrue(85.0 + pass.maxOvershoot < d - r, "the resolution ${pass.maxOvershoot} is fine enough to decide 85")
            val fail = minkowskiClearance(slab, swing, listOf(box), clearance = 110.0, maxStep = 1.0, names = listOf("post"))
            assertFalse(fail.passes)
            val worst = fail.worst!!
            assertEquals("post", worst.obstacle)
            val atMinimum = 45.0 - Math.toDegrees(atan2(t, w))
            assertTrue(worst.worstFrom - 1e-9 <= atMinimum + 1.0 && worst.worstTo + 1e-9 >= atMinimum - 1.0, "$worst")
            assertTrue(worst.from < atMinimum && worst.to > atMinimum)
            assertEquals(d * cos(PI / 4), worst.where.x(), 30.0)
        }
    }

    /** ∫ ρ · (angle covered at radius ρ) dρ for [0,w]x[0,t] turned 0..90° about the origin, by Simpson's rule. */
    private fun sweptPlanArea(w: Double, t: Double): Double {
        val rMax = hypot(w, t)
        fun covered(rho: Double): Double = when {
            rho <= t -> PI                                      // from angle 0 to 90° + 90°
            rho <= w -> PI / 2 + asin(t / rho)                  // 0 .. asin(t/ρ), swept a further 90°
            else -> PI / 2 + asin(t / rho) - acos(w / rho)      // acos(w/ρ) .. asin(t/ρ), plus 90°
        }
        fun simpson(a: Double, b: Double, n: Int): Double {
            val hh = (b - a) / n
            var s = 0.0
            for (i in 0..n) {
                val x = a + i * hh
                val f = x * covered(x)
                s += f * if (i == 0 || i == n) 1.0 else if (i % 2 == 1) 4.0 else 2.0
            }
            return s * hh / 3
        }
        return simpson(0.0, t, 200) + simpson(t, w, 20000) + simpson(w, rMax, 2000)
    }
}

