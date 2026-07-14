package afield.stdlib.linalg

import afield.stdlib.linalg.arena.FrameArena
import org.joml.Vector3f as JomlVector3f
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GeneratedLinalgTest {
    @Test
    fun heapVectorMatchesJomlForCoreOperations() {
        val random = Random(0x4f13d)
        repeat(1_000) {
            val av = FloatArray(3) { random.nextFloat() * 20f - 10f }
            val bv = FloatArray(3) { random.nextFloat() * 20f - 10f }
            val t = random.nextFloat()
            val a = Vector3f(av[0], av[1], av[2])
            val b = Vector3f(bv[0], bv[1], bv[2])
            val ja = JomlVector3f(av[0], av[1], av[2])
            val jb = JomlVector3f(bv[0], bv[1], bv[2])

            assertVectorClose(JomlVector3f(ja).add(jb), Vector3f(a).add(b))
            assertVectorClose(JomlVector3f(ja).sub(jb), Vector3f(a).sub(b))
            assertVectorClose(JomlVector3f(ja).mul(jb), Vector3f(a).mul(b))
            assertVectorClose(JomlVector3f(ja).cross(jb), Vector3f(a).cross(b))
            assertVectorClose(JomlVector3f(ja).lerp(jb, t), Vector3f(a).lerp(b, t))
            assertVectorClose(JomlVector3f(ja).normalize(), Vector3f(a).normalize(), 2e-6f)
            assertEquals(ja.dot(jb), a.dot(b), 2e-5f)
            assertEquals(ja.distanceSquared(jb), a.distanceSquared(b), 2e-4f)
        }
    }

    @Test
    fun destinationOperationsPreserveJomlAliasingContract() {
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
    fun denseBufferWritesMatchJomlAndDoNotMovePosition() {
        val value = Vector3f(1.25f, -2.5f, 9f)
        val joml = JomlVector3f(value.x, value.y, value.z)

        val ours = FloatBuffer.allocate(8).also { it.position(2) }
        val theirs = FloatBuffer.allocate(8).also { it.position(2) }
        value.get(ours)
        joml.get(theirs)
        assertEquals(2, ours.position())
        assertEquals(theirs.position(), ours.position())
        assertEquals(theirs.array().toList(), ours.array().toList())

        val ourBytes = ByteBuffer.allocate(32).order(ByteOrder.nativeOrder()).also { it.position(3) }
        val theirBytes = ByteBuffer.allocate(32).order(ByteOrder.nativeOrder()).also { it.position(3) }
        value.get(8, ourBytes)
        joml.get(8, theirBytes)
        assertEquals(3, ourBytes.position())
        assertEquals(theirBytes.array().toList(), ourBytes.array().toList())
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

    private fun assertVectorClose(expected: JomlVector3f, actual: Vector3f, tolerance: Float = 1e-5f) {
        assertTrue(abs(expected.x - actual.x) <= tolerance, "x: expected=${expected.x}, actual=${actual.x}")
        assertTrue(abs(expected.y - actual.y) <= tolerance, "y: expected=${expected.y}, actual=${actual.y}")
        assertTrue(abs(expected.z - actual.z) <= tolerance, "z: expected=${expected.z}, actual=${actual.z}")
    }

    private fun assertVectorEquals(expected: Vector3f, actual: Vector3f) {
        assertEquals(expected.x, actual.x)
        assertEquals(expected.y, actual.y)
        assertEquals(expected.z, actual.z)
    }
}
