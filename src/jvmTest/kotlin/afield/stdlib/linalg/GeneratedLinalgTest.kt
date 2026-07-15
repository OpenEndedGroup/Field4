package afield.stdlib.linalg

import afield.stdlib.linalg.arena.FrameArena
import afield.stdlib.linalg.arena.closestPointOnSegment
import afield.stdlib.linalg.arena.frameScratch
import afield.stdlib.linalg.arena.intersectRayAabb
import afield.stdlib.linalg.storage.SegmentVec3Buffer
import afield.stdlib.linalg.storage.Vec3StorageLayout
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GeneratedLinalgTest {
    @Test
    fun heapVectorMatchesIndependentReferenceFormulas() {
        val random = Random(0x4f13d)
        repeat(1_000) {
            val av = FloatArray(3) { random.nextFloat() * 20f - 10f }
            val bv = FloatArray(3) { random.nextFloat() * 20f - 10f }
            val t = random.nextFloat()
            val a = Vector3f(av[0], av[1], av[2])
            val b = Vector3f(bv[0], bv[1], bv[2])
            assertVectorClose(av[0]+bv[0],av[1]+bv[1],av[2]+bv[2],Vector3f(a).add(b))
            assertVectorClose(av[0]-bv[0],av[1]-bv[1],av[2]-bv[2],Vector3f(a).sub(b))
            assertVectorClose(av[0]*bv[0],av[1]*bv[1],av[2]*bv[2],Vector3f(a).mul(b))
            assertVectorClose(av[1]*bv[2]-av[2]*bv[1],av[2]*bv[0]-av[0]*bv[2],av[0]*bv[1]-av[1]*bv[0],Vector3f(a).cross(b))
            assertVectorClose(av[0]+(bv[0]-av[0])*t,av[1]+(bv[1]-av[1])*t,av[2]+(bv[2]-av[2])*t,Vector3f(a).lerp(b,t))
            val inv=1f/kotlin.math.sqrt(av[0]*av[0]+av[1]*av[1]+av[2]*av[2])
            assertVectorClose(av[0]*inv,av[1]*inv,av[2]*inv,Vector3f(a).normalize(),2e-6f)
            assertEquals(av[0]*bv[0]+av[1]*bv[1]+av[2]*bv[2],a.dot(b),2e-5f)
            assertEquals((av[0]-bv[0])*(av[0]-bv[0])+(av[1]-bv[1])*(av[1]-bv[1])+(av[2]-bv[2])*(av[2]-bv[2]),a.distanceSquared(b),2e-4f)
        }
    }

    @Test
    fun destinationOperationsPreserveAliasingContract() {
        val a = Vector3f(1f, 2f, 3f)
        val b = Vector3f(4f, 5f, 6f)
        assertSame(a, a.cross(b, a))
        assertVectorEquals(Vector3f(-3f, 6f, -3f), a)

        val c = Vector3f(1f, 2f, 3f)
        val d = Vector3f(4f, 5f, 6f)
        assertSame(d, c.cross(d, d))
        assertVectorEquals(Vector3f(-3f, 6f, -3f), d)
    }

    @Test
    fun denseBufferWritesUseCanonicalLayoutAndDoNotMovePosition() {
        val value = Vector3f(1.25f, -2.5f, 9f)

        val ours = FloatBuffer.allocate(8).also { it.position(2) }
        value.get(ours)
        assertEquals(2, ours.position())
        assertEquals(listOf(0f,0f,1.25f,-2.5f,9f,0f,0f,0f),ours.array().toList())

        val ourBytes = ByteBuffer.allocate(32).order(ByteOrder.nativeOrder()).also { it.position(3) }
        value.get(8, ourBytes)
        assertEquals(3, ourBytes.position())
        assertEquals(1.25f,ourBytes.getFloat(8));assertEquals(-2.5f,ourBytes.getFloat(12));assertEquals(9f,ourBytes.getFloat(16))
    }

    @Test
    fun matrixAndQuaternionConventionsAreStable() {
        val q=Quaternionf().setAngleAxis((Math.PI/2).toFloat(),0f,0f,1f)
        assertVectorClose(0f,1f,0f,q.transform(Vector3f(1f,0f,0f),Vector3f()),2e-6f)
        val m=Matrix4f().translate(3f,4f,5f).rotate(q).scale(2f)
        val p=m.transformPosition(Vector3f(1f,0f,0f),Vector3f())
        assertVectorClose(3f,6f,5f,p,3e-6f)
        val restored=Matrix4f(m).invert().transformPosition(p,Vector3f())
        assertVectorClose(1f,0f,0f,restored,3e-5f)
        val identity=Matrix4f(m).mul(Matrix4f(m).invert())
        assertEquals(1f,identity.m00,2e-5f);assertEquals(1f,identity.m11,2e-5f);assertEquals(1f,identity.m22,2e-5f);assertEquals(1f,identity.m33,2e-5f)
    }

    @Test
    fun arenaMatchesHeapAndCopiesAcrossExplicitBoundary() {
        FrameArena(32).use { arena ->
            arena.scoped {
                val a = vec3(1f, 2f, 3f)
                val b = vec3(4f, 5f, 6f)
                val result = vec3()
                a.cross(b, result)
                assertEquals(-3f, result.x)
                assertEquals(6f, result.y)
                assertEquals(-3f, result.z)

                val heap = Vector3f()
                result.copyTo(heap)
                assertVectorEquals(Vector3f(-3f, 6f, -3f), heap)

                result.set(heap).normalize()
                assertEquals(1f, result.length(), 1e-6f)
            }
            assertTrue(arena.growthCount > 0)
            assertEquals(0, arena.usedBytes)
            assertTrue(arena.highWaterBytes >= 48)
        }
    }

    @Test
    fun resetAndMarkRestoreInvalidateOnlyDeadHandles() {
        FrameArena().use { arena ->
            lateinit var accessAfterRestore: () -> Float
            lateinit var accessBeforeMark: () -> Float
            arena.scoped {
                val permanent = vec3(1f, 2f, 3f)
                accessBeforeMark = { permanent.x }
                val mark = arena.mark()
                val temporary = vec3(4f, 5f, 6f)
                accessAfterRestore = { temporary.x }
                arena.restore(mark)
                assertEquals(1f, permanent.x)
                assertFailsWith<IllegalStateException> { temporary.x }
            }
            assertFailsWith<IllegalStateException> { accessBeforeMark() }
            assertFailsWith<IllegalStateException> { accessAfterRestore() }

            lateinit var staleAfterReset: () -> Float
            val mark = arena.mark()
            arena.scopeForInline().run {
                val value = vec3(7f, 8f, 9f)
                staleAfterReset = { value.x }
            }
            arena.restore(mark)
            arena.reset()
            assertFailsWith<IllegalStateException> { staleAfterReset() }
        }
    }

    @Test
    fun arenaRejectsWrongThread() {
        FrameArena().use { arena ->
            val failure = AtomicReference<Throwable?>()
            arena.scoped {
                val value = vec3(1f, 2f, 3f)
                val other = thread(start = true) {
                    failure.set(runCatching { value.x }.exceptionOrNull())
                }
                other.join()
            }
            assertTrue(failure.get() is IllegalStateException)
            assertTrue(failure.get()!!.message!!.contains("accessed from"))
        }
    }

    @Test
    fun threadLocalScratchIsConvenientAndIsolated() {
        val mainArena = FrameArena.current()
        try {
            frameScratch {
                val a = vec3(1f, 2f, 3f)
                val b = vec3(4f, 5f, 6f)
                assertEquals(32f, a.dot(b))

                val closest = vec3()
                closestPointOnSegment(vec3(2f, 4f, 0f), vec3(0f, 0f, 0f), vec3(2f, 0f, 0f), closest)
                assertEquals(2f, closest.x); assertEquals(0f, closest.y)
                val nearFar = vec2()
                assertTrue(intersectRayAabb(
                    vec3(-3f, 0f, 0f), vec3(1f, 0f, 0f), vec3(-1f, -1f, -1f), vec3(1f, 1f, 1f), nearFar,
                ))
                assertEquals(2f, nearFar.x); assertEquals(4f, nearFar.y)
            }
            assertEquals(0, mainArena.usedBytes)

            val otherArena = AtomicReference<FrameArena>()
            thread(start = true) {
                otherArena.set(FrameArena.current())
                frameScratch { assertEquals(1f, vec3(1f, 0f, 0f).length()) }
                FrameArena.closeCurrent()
            }.join()
            assertNotSame(mainArena, otherArena.get())
        } finally {
            FrameArena.closeCurrent()
        }
    }

    @Test
    fun segmentVec3ViewsSupportPackedAndAlignedMappedLayouts() {
        Arena.ofConfined().use { owner ->
            val packedSegment = owner.allocate(24, 4)
            val packed = SegmentVec3Buffer(packedSegment, 2, Vec3StorageLayout.PACKED_12)
            with(packed) {
                packed[0].set(1f, 2f, 3f)
                packed[1].set(4f, 5f, 6f)
                assertEquals(4f, packedSegment.get(ValueLayout.JAVA_FLOAT, 12))
                assertEquals(32f, packed[0].dot(packed[1]))
            }

            val alignedSegment = owner.allocate(48, 16)
            val aligned = SegmentVec3Buffer(alignedSegment, 3, Vec3StorageLayout.ALIGNED_16)
            with(aligned) {
                aligned[0].set(1f, 0f, 0f)
                aligned[1].set(0f, 1f, 0f)
                aligned[0].cross(aligned[1], aligned[2])
                assertEquals(0f, alignedSegment.get(ValueLayout.JAVA_FLOAT, 12))
                assertVectorEquals(Vector3f(0f, 0f, 1f), aligned[2].copyTo(Vector3f()))
            }

            val another = SegmentVec3Buffer(owner.allocate(16, 16), 1)
            val foreignHandle = aligned[0]
            with(another) {
                assertFailsWith<IllegalStateException> { foreignHandle.x }
            }
        }

        val direct = ByteBuffer.allocateDirect(24)
        val fromBuffer = SegmentVec3Buffer.of(direct, 2, Vec3StorageLayout.PACKED_12)
        with(fromBuffer) {
            fromBuffer[1].set(7f, 8f, 9f)
            assertVectorEquals(Vector3f(7f, 8f, 9f), fromBuffer.get(1, Vector3f()))
        }
    }

    @Test
    fun geometryClosestPointContainmentAndIntersectionsAreStable() {
        val a = Vector3f(0f, 0f, 0f)
        val b = Vector3f(2f, 0f, 0f)
        val c = Vector3f(0f, 2f, 0f)
        assertVectorEquals(Vector3f(1f, 0f, 0f), closestPointOnSegment(Vector3f(1f, 3f, 0f), a, b, Vector3f()))
        assertVectorEquals(Vector3f(1f, 1f, 0f), closestPointOnTriangle(Vector3f(2f, 2f, 4f), a, b, c, Vector3f()))
        assertTrue(containsPointInTriangle(Vector3f(.25f, .25f, 0f), a, b, c))
        assertFalse(containsPointInTriangle(Vector3f(2f, 2f, 0f), a, b, c))
        assertFalse(containsPointInTriangle(Vector3f(.25f, .25f, 1f), a, b, c))

        val barycentric = Vector3f()
        assertTrue(barycentricCoordinates(Vector3f(.5f, .5f, 0f), a, b, c, barycentric))
        assertVectorClose(.5f, .25f, .25f, barycentric)
        assertFalse(barycentricCoordinates(a, a, a, a, barycentric))

        val closestA = Vector3f(); val closestB = Vector3f()
        assertEquals(1f, closestPointsBetweenSegments(
            Vector3f(0f, 0f, 0f), Vector3f(2f, 0f, 0f),
            Vector3f(1f, 1f, -1f), Vector3f(1f, 1f, 1f), closestA, closestB,
        ))
        assertVectorEquals(Vector3f(1f, 0f, 0f), closestA)
        assertVectorEquals(Vector3f(1f, 1f, 0f), closestB)
        val segmentHit = Vector2f()
        assertTrue(intersectSegments(Vector2f(0f, 0f), Vector2f(2f, 2f), Vector2f(0f, 2f), Vector2f(2f, 0f), segmentHit))
        assertEquals(1f, segmentHit.x); assertEquals(1f, segmentHit.y)

        val hit = Vector3f()
        assertTrue(intersectRayTriangle(Vector3f(.25f, .25f, 2f), Vector3f(0f, 0f, -1f), a, b, c, hit))
        assertVectorEquals(Vector3f(.25f, .25f, 0f), hit)
        assertFalse(intersectRayTriangle(Vector3f(3f, 3f, 2f), Vector3f(0f, 0f, -1f), a, b, c, hit))
        assertTrue(intersectRayPlane(Vector3f(0f, 0f, 2f), Vector3f(0f, 0f, -1f), a, Vector3f(0f, 0f, 1f), hit))

        val minimum = Vector3f(-1f)
        val maximum = Vector3f(1f)
        val nearFar = Vector2f()
        assertTrue(intersectRayAabb(Vector3f(-3f, 0f, 0f), Vector3f(1f, 0f, 0f), minimum, maximum, nearFar))
        assertEquals(2f, nearFar.x)
        assertEquals(4f, nearFar.y)
        assertTrue(intersectRaySphere(Vector3f(-3f, 0f, 0f), Vector3f(1f, 0f, 0f), Vector3f(), 1f, nearFar))
        assertEquals(2f, nearFar.x); assertEquals(4f, nearFar.y)
        assertTrue(intersectsAabbSphere(minimum, maximum, Vector3f(2f, 0f, 0f), 1f))
        assertFalse(intersectsAabbSphere(minimum, maximum, Vector3f(3f, 0f, 0f), 1f))
    }

    private fun assertVectorClose(x:Float,y:Float,z:Float,actual:Vector3f,tolerance:Float=1e-5f) {
        assertTrue(abs(x-actual.x)<=tolerance,"x: expected=$x, actual=${actual.x}")
        assertTrue(abs(y-actual.y)<=tolerance,"y: expected=$y, actual=${actual.y}")
        assertTrue(abs(z-actual.z)<=tolerance,"z: expected=$z, actual=${actual.z}")
    }

    private fun assertVectorEquals(expected: Vector3f, actual: Vector3f) {
        assertEquals(expected.x, actual.x)
        assertEquals(expected.y, actual.y)
        assertEquals(expected.z, actual.z)
    }
}
