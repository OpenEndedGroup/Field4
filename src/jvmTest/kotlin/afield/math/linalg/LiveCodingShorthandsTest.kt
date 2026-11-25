package afield.math.linalg

import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Tier 1 spellings from `dev_docs/stdlib/LINALG_OPERATORS_PROPOSAL.md`: angles as values,
 * infix products and measurements, 2D rotation, quaternion fields and the complex additions.
 * Every one returns a new value, so each test also checks that its operands are unchanged.
 */
class LiveCodingShorthandsTest {
    private fun close(expected: Double, actual: Number, tolerance: Double = 1e-5) =
        assertEquals(expected, actual.toDouble(), tolerance)

    @Test
    fun anglesAreRadiansAsFloats() {
        close(PI / 2, 90.deg)
        close(PI / 2, 0.25.turns)
        close(1.5, 1.5.rad)
        close(2 * PI, TAU)
        assertTrue(30.deg.dir near vec(sqrt(3.0) / 2, 0.5))
        close(PI / 6, 30.deg.dir.angle)
        close(-PI / 2, vec(0, -3).angle)
    }

    @Test
    fun productsAndMeasurementsLeaveOperandsAlone() {
        val a = vec(1, 0, 0)
        val b = vec(0, 2, 0)
        assertEquals(0f, a dot b)
        assertEquals(vec(0, 0, 2), a x b)
        assertEquals(vec(1, 0, 0), a)
        val readOnly: Vector3fc = vec(0, 1, 0)
        assertEquals(vec(0, 0, -1), readOnly x a)
        assertEquals(Vector3d(0.0, 0.0, 1.0), Vector3d(1.0, 0.0, 0.0) x Vector3d(0.0, 1.0, 0.0))
        assertEquals(2f, vec(1, 0) x vec(0, 2))
        assertEquals(5f, vec(0, 0) dist vec(3, 4))
        assertEquals(25f, vec(3, 4).sq)
        assertEquals(25.0, Vector2d(3.0, 4.0).sq)
        close(PI / 2, a angleTo b)
        close(PI / 4, vec(1, 0) angleTo vec(1, 1))
        close(-PI / 2, vec(0, 1) signedAngleTo vec(1, 0))
        assertEquals(7f, 2 + (vec(1, 2) dot vec(3, 1)))
        assertEquals(Vector2d(2.0, 4.0), 2 * Vector2d(1.0, 2.0))
    }

    @Test
    fun nearComparesWithinATolerance() {
        val a = vec(0.1 + 0.2, 0.0)
        assertTrue(a near vec(0.3, 0.0))
        assertFalse(vec(1, 0) near vec(1.001, 0.0))
        assertTrue(vec(1, 0).near(vec(1.001, 0.0), 0.01))
        assertTrue(Vector3d(1.0, 2.0, 3.0) near Vector3d(1.0 + 1e-12, 2.0, 3.0))
        assertFalse(Vector3d(1.0, 2.0, 3.0) near Vector3d(1.0 + 1e-6, 2.0, 3.0))
    }

    @Test
    fun planarRotation() {
        assertEquals(vec(-2, 1), vec(1, 2).perp)
        assertTrue(vec(1, 0) rot 90.deg near vec(0, 1))
        val c = vec(1, 1)
        val p = vec(2, 1)
        assertTrue(p about c rot 90.deg near vec(1, 2))
        assertEquals(vec(2, 1), p)
        assertEquals(vec(1, 1), c)
        assertTrue(Vector2d(1.0, 0.0) rot PI near Vector2d(-1.0, 0.0))
    }

    @Test
    fun companionConstantsAreReadOnlyAxes() {
        assertEquals(vec(0, 1, 0), Vec3.Y)
        assertEquals(vec(1, 1), Vec2.ONE)
        assertEquals(Vector4d(0.0, 0.0, 0.0, 1.0), Vector4d.W)
        assertEquals(vec(0, 0, 0), Vec3.ZERO)
        val q = 90.deg about Vec3.Y
        assertTrue(q near quat(PI / 2, vec(0, 1, 0)))
        assertEquals(vec(0, 1, 0), Vec3.Y, "the shared axis is untouched")
    }

    @Test
    fun quaternionFields() {
        val q = 90.deg about Vec3.Z
        assertTrue(q.inv * q near Quat())
        assertTrue(q.conj near q.inv)
        assertTrue(q.axis near Vec3.Z)
        close(PI / 2, q.angle)
        assertTrue(Quat().axis near Vec3.Z)
        close(0.0, Quat().angle)
        assertTrue(q near q * -1)
        assertFalse(q near Quat())
        assertTrue(vec(1, 0, 0) rot q near vec(0, 1, 0))
        assertTrue((vec(1, 0, 0) rot q) near q * vec(1, 0, 0))
        assertTrue(Quat().forward near vec(0, 0, -1))
        assertTrue(Quat().up near Vec3.Y)
        assertTrue(Quat().right near Vec3.X)
        assertTrue(q.right near Vec3.Y)
        assertTrue(q near (90.deg about Vec3.Z), "the fields do not change q")
    }

    @Test
    fun facingPointsForwardAlongTheDirection() {
        for (d in listOf(vec(1, 0, 0), vec(0, 0, 1), vec(1, 2, 3), vec(0, 0, -1), vec(0, 5, 0), vec(0, -1, 0))) {
            val q = facing(d)
            assertTrue(q.forward.near(d.n, 1e-5), "forward of facing($d) is ${q.forward}")
            close(1.0, q.forward.length)
            if ((d.n x Vec3.Y).length > 1e-3f) assertTrue(q.up.y > 0f, "up leans towards +y for $d")
            close(0.0, q.up dot q.forward)
        }
        val tilted = facing(vec(0, 0, -1), vec(1, 1, 0))
        assertTrue(tilted.up.near(vec(1, 1, 0).n, 1e-5))
        assertTrue(facing(vec(0, 0, -1)) near Quat(), "looking down −z with +y up is no rotation")
    }

    @Test
    fun complexAdditions() {
        val z = 2 + 3.im
        assertEquals(Complexd(2, 3), z)
        assertTrue((1 cis 90.deg).near(Complexd.I, 1e-7), ".deg is a Float, so the angle carries float precision")
        assertTrue((1 cis PI / 2) near Complexd.I)
        assertTrue(Complexd(3, 4).n near Complexd(0.6, 0.8))
        assertEquals(25.0, Complexd(3, 4).sq)
        assertTrue(Complexd(1, 0) rot PI / 2 near Complexd.I)
        val p = vec(1, 0)
        assertTrue(p * Complexf.polar(2, PI / 2) near vec(0, 2))
        assertTrue(Complexf.polar(2, PI / 2) * p near vec(0, 2))
        assertEquals(vec(1, 0), p)
        assertTrue(Complexd(-4, 0).sqrt() near Complexd(0, 2))
        assertTrue(Complexd(3, 4).sqrt().let { it * it } near Complexd(3, 4))
        assertTrue(Complexd(0, 1).sin() near Complexd(0.0, kotlin.math.sinh(1.0)))
        assertTrue(Complexd(0, 1).cos() near Complexd(kotlin.math.cosh(1.0), 0.0))
        assertTrue(Complexd(PI / 2, 0).sin() near Complexd.ONE)
        assertTrue(Complexd.I.pow(Complexd.I) near Complexd(kotlin.math.exp(-PI / 2), 0.0), "i^i = e^(-π/2)")
        assertTrue(Complexd(1, 1).exp() near Complexd(kotlin.math.E * kotlin.math.cos(1.0), kotlin.math.E * kotlin.math.sin(1.0)))
    }

    @Test
    fun hypercomplexTakesNumbersOnTheLeft() {
        val x = Hypercomplexd.dual(3, 1)
        assertEquals(Hypercomplexd.dual(6, 2), 2 * x)
        assertEquals(Hypercomplexd.dual(-2, -1), 1 - x)
        assertEquals(Hypercomplexd.dual(4, 1), 1 + x)
        assertTrue((1 / x) near Hypercomplexd.dual(1.0 / 3, -1.0 / 9), "d(1/x) = -1/x²")
        assertTrue(Hypercomplexd.split(1, 2) near Hypercomplexd.split(1, 2 + 1e-12))
        assertFalse(Hypercomplexd.split(1, 2) near Hypercomplexd.dual(1, 2))
        assertFailsWith<IllegalArgumentException> { Hypercomplexd.split(1, 2) + Hypercomplexd.dual(1, 2) }
    }
}
