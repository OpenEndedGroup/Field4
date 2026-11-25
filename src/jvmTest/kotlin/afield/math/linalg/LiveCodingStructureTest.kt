package afield.math.linalg

import afield.geometry.path.about
import afield.geometry.path.times
import afield.geometry.spatial.closest
import afield.geometry.spatial.sortedAround
import afield.math.statistics.inBall
import afield.math.statistics.inDisc
import afield.math.statistics.jitter
import afield.math.statistics.onCircle
import afield.math.statistics.onSphere
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Tier 3 spellings from `dev_docs/stdlib/LINALG_OPERATORS_PROPOSAL.md`: scalar shaping,
 * integer grids, list rotations and point helpers, random vectors, destructuring, constant-lane
 * swizzles, and the matrix operators and builders.
 */
class LiveCodingStructureTest {
    private fun close(expected: Double, actual: Number, tolerance: Double = 1e-6) =
        assertEquals(expected, actual.toDouble(), tolerance)

    @Test
    fun scalarShaping() {
        close(0.0, 0.5.remap(0.0..1.0, -1.0..1.0))
        close(3.0, 2.remap(0.0..1.0, 1.0..2.0), 0.0)
        close(0.75, (-1.25).fract())
        close(1.0, 7.clamp01())
        close(0.0, (-3).clamp01())
        close(0.5, 0.5.smoothstep())
        close(1.0, 2.smoothstep())
    }

    @Test
    fun integerGridsComeRowByRow() {
        val g = (0 until 3) x (0 until 2)
        assertEquals(6, g.size)
        assertEquals(listOf(vec(0, 0), vec(1, 0), vec(2, 0), vec(0, 1), vec(1, 1), vec(2, 1)), g)
    }

    @Test
    fun listsRotateAndAnswerQuestions() {
        val square = listOf(vec(1, 0), vec(0, 1), vec(-1, 0), vec(0, -1))
        val q = 90.deg about Vec3.Z
        val turned = square * q
        assertTrue(turned[0] near vec(0, 1))
        val ps3 = listOf(vec(1, 0, 0), vec(0, 1, 0))
        assertTrue((ps3 * q)[0] near vec(0, 1, 0))
        assertTrue((square * Complexf.polar(2, PI / 2))[0] near vec(0, 2))
        val c = vec(1, 1)
        assertTrue((listOf(vec(2, 1)) about c rot 90.deg)[0] near vec(1, 2))
        assertEquals(vec(1, 0), square[0], "the list is untouched")
        assertEquals(vec(0, 1), square.closest(vec(0.2, 2)))
        assertEquals(vec(0, 1, 0), ps3.closest(vec(0, 5, 0)))
        val scattered = listOf(vec(0, 1), vec(0, -1), vec(1, 0), vec(-1, 0))
        assertEquals(listOf(vec(0, -1), vec(1, 0), vec(0, 1), vec(-1, 0)), scattered.sortedAround(vec(0, 0)))
        assertEquals(4, scattered.sortedAround().size)
    }

    @Test
    fun randomVectorsHaveTheRightShape() {
        repeat(200) {
            close(2.0, Vec3.onSphere(2).length, 1e-5)
            assertTrue(Vec3.inBall(2).length <= 2.00001f)
            close(3.0, Vec2.onCircle(3).length, 1e-5)
            assertTrue(Vec2.inDisc().length <= 1.00001f)
            val j = vec(5, 5) jitter 0.5
            assertTrue(j.x in 4.5f..5.5f && j.y in 4.5f..5.5f)
            val j3 = vec(1, 2, 3) jitter 0.1
            assertTrue(j3.near(vec(1, 2, 3), 0.1001), "jitter stays within its amount: $j3")
        }
        val mean = (1..4000).map { Vec3.onSphere() }.fold(Vec3()) { a, b -> a.add(b) }.mul(1f / 4000)
        assertTrue(mean.length < 0.06f, "uniform on the sphere averages to the centre: $mean")
    }

    @Test
    fun destructuringAndConstantLanes() {
        val (x, y, z) = vec(1, 2, 3)
        assertEquals(listOf(1f, 2f, 3f), listOf(x, y, z))
        val (a, b) = Vector2d(4.0, 5.0)
        assertEquals(9.0, a + b)
        val (p, q, r, w) = vec(1, 2, 3, 4)
        assertEquals(10f, p + q + r + w)
        assertEquals(vec(1, 2, 0), vec(1, 2).xy0)
        assertEquals(vec(1, 2, 1), vec(1, 2).xy1)
        assertEquals(vec(1, 0, 2), vec(1, 2).x0y)
        assertEquals(vec(1, 2, 3, 0), vec(1, 2, 3).xyz0)
        assertEquals(vec(1, 2, 3, 1), vec(1, 2, 3).xyz1)
        assertEquals(vec(1, 2, 0), vec(1, 2, 3).xy0)
        assertEquals(vec(1, 0, 3), vec(1, 2, 3).x0z)
        assertEquals(Vector4d(1.0, 2.0, 3.0, 1.0), Vector3d(1.0, 2.0, 3.0).xyz1)
    }

    @Test
    fun matrixOperatorsAndBuilders() {
        val rot2 = Matrix2f(0f, 1f, -1f, 0f)
        assertTrue(rot2 * vec(1, 0) near vec(0, 1))
        assertTrue((rot2 * rot2) * vec(1, 0) near vec(-1, 0))
        assertTrue(Matrix2d(2.0, 0.0, 0.0, 3.0) * Vector2d(1.0, 1.0) near Vector2d(2.0, 3.0))
        val m3 = Matrix3f().rotation(90.deg about Vec3.Z)
        assertTrue(m3 * vec(1, 0, 0) near vec(0, 1, 0))
        assertTrue((m3 then m3) * vec(1, 0, 0) near vec(-1, 0, 0))
        val m3d = Matrix3d().set(0.0, 1.0, 0.0, -1.0, 0.0, 0.0, 0.0, 0.0, 1.0)
        assertTrue(m3d * Vector3d(1.0, 0.0, 0.0) near Vector3d(0.0, 1.0, 0.0))
        assertTrue((m3d * m3d) * Vector3d(1.0, 0.0, 0.0) near Vector3d(-1.0, 0.0, 0.0))
        val t4 = Matrix4d().translation(1.0, 2.0, 3.0)
        val s4 = Matrix4d().scaling(2.0, 2.0, 2.0)
        assertTrue((t4 then s4) * Vector3d(0.0, 0.0, 0.0) near Vector3d(2.0, 4.0, 6.0))
        assertTrue(t4 * Vector4d(0.0, 0.0, 0.0, 0.0) near Vector4d(0.0, 0.0, 0.0, 0.0), "a direction ignores translation")
        val built = Mat4.scale(2) then Mat4.rotate(90.deg about Vec3.Z) then Mat4.translate(vec(10, 0, 0))
        assertTrue(built * vec(1, 0, 0) near vec(10, 2, 0))
        assertTrue(Mat4.rotate(90.deg, Vec3.Z) * vec(1, 0, 0) near vec(0, 1, 0))
        assertTrue(Mat4.translate(1, 2, 3) * vec(0, 0, 0) near vec(1, 2, 3))
        assertTrue(Mat4.scale(vec(1, 2, 3)) * vec(1, 1, 1) near vec(1, 2, 3))
    }
}
