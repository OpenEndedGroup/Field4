package afield.io.mocap

import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BvhTest {
    private val dir = File("public/docs/assets/temporal_memory/cmu_05")

    private fun dist(a: DoubleArray, b: DoubleArray) = sqrt((0 until 3).sumOf { (a[it] - b[it]) * (a[it] - b[it]) })

    @Test
    fun readsTheCmuTakes() {
        val b = Bvh.read(File(dir, "05_02.bvh.gz"))
        assertEquals(31, b.joints.count { !it.isEndSite })
        assertEquals(1124, b.frameCount)
        assertEquals(120.0, b.sampleRate, 0.1)
    }

    @Test
    fun bonesKeepTheirLengthUnderForwardKinematics() {
        // rotations never stretch a bone: every parent–child distance equals the child's offset length
        val b = Bvh.read(File(dir, "05_07.bvh.gz"))
        for (f in listOf(0, 300, 700, 1100)) {
            val p = b.positions(f)
            for ((a, c) in b.bones) {
                val o = b.joints[c].offset
                assertEquals(sqrt(o.sumOf { it * it }), dist(p[a], p[c]), 1e-6, "bone ${b.joints[a].name}->${b.joints[c].name} at frame $f")
            }
        }
    }

    @Test
    fun bodyFrameRemovesTravelAndTurning() {
        val b = Bvh.read(File(dir, "05_02.bvh.gz"))
        val f = 500
        val world = b.positions(f)
        val body = b.positions(f, bodyFrame = true)
        // the root sits over the origin, height kept
        assertEquals(0.0, body[0][0], 1e-9); assertEquals(0.0, body[0][2], 1e-9)
        assertEquals(world[0][1], body[0][1], 1e-9)
        // distances between joints are unchanged (a rigid motion of the whole figure)
        val h = b.joint("Head"); val l = b.joint("LeftHand")
        assertEquals(dist(world[h], world[l]), dist(body[h], body[l]), 1e-9)
        // the dancer turns (pirouette) in world space, but the hips' left–right axis stays put in the body frame
        val lh = b.joint("LeftUpLeg"); val rh = b.joint("RightUpLeg")
        val axes = (0 until b.frameCount step 50).map { val p = b.positions(it, bodyFrame = true); p[lh][2] - p[rh][2] }
        assertTrue(axes.all { abs(it) < 1.0 }, "hip axis should stay across the body frame: $axes")
    }

    @Test
    fun trajectoryAndSkeletonRoundTrip() {
        val b = Bvh.read(File(dir, "05_14.bvh.gz"))
        val t = b.toTrajectory(rate = 30.0)
        assertEquals(b.joints.size * 3, t.width)
        assertTrue(abs(b.duration * 30 - t.length) <= 1.0, "length ${t.length} for ${b.duration} s")
        val lines = b.skeleton(t.frame(10), t.channels)
        assertEquals(b.bones.size, lines.size)
    }

    @Test
    fun skeletonMeshIsColouredTubes() {
        val b = Bvh.read(File(dir, "05_14.bvh.gz"))
        val t = b.toTrajectory(rate = 30.0)
        val m = b.skeletonMesh(t.frame(10), t.channels, radius = 0.5, color = doubleArrayOf(1.0, 0.5, 0.0))
        assertTrue(m.hasColors)
        assertTrue(m.triangleCount > b.bones.size * 8, "one tube per bone: ${m.triangleCount} triangles")
    }
}
