package afield.stdlib

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [sampledField3D], the bridge a field takes when it cannot be handed to a marcher directly.
 *
 * The three properties that matter to a caller: it reproduces the samples exactly at the lattice
 * points, it interpolates linearly between them, and it refuses to extrapolate past the box. The
 * fourth test is the one that says the whole thing is useful: a sphere sampled onto a grid and then
 * marched has the volume a sphere has, to within the sampling error.
 */
class SampledField3DTest {

    private val unit = boundsOf(vec(0.0, 0.0, 0.0), vec(1.0, 1.0, 1.0))

    /** `f(x, y, z) = x + 2y + 4z` on a 2x2x2 lattice over the unit cube: linear, so exactly representable. */
    private fun linear(): ScalarField3D {
        val values = FloatArray(8)
        for (k in 0..1) for (j in 0..1) for (i in 0..1) {
            values[(k * 2 + j) * 2 + i] = (i + 2 * j + 4 * k).toFloat()
        }
        return sampledField3D(values, 2, 2, 2, unit)
    }

    @Test
    fun `it reproduces every lattice sample exactly`() {
        val f = linear()
        for (k in 0..1) for (j in 0..1) for (i in 0..1) {
            assertEquals(
                (i + 2 * j + 4 * k).toFloat(), f(vec(i.toDouble(), j.toDouble(), k.toDouble())),
                "corner ($i, $j, $k)",
            )
        }
    }

    @Test
    fun `a linear field is interpolated exactly between the samples`() {
        val f = linear()
        // Trilinear interpolation is exact for a linear function, so these are equalities.
        assertEquals(3.5f, f(vec(0.5, 0.5, 0.5)), 1e-6f)
        assertEquals(0.25f, f(vec(0.25, 0.0, 0.0)), 1e-6f)
        assertEquals(4.5f, f(vec(0.5, 0.0, 1.0)), 1e-6f)
    }

    @Test
    fun `outside the box it returns the outside value rather than extrapolating`() {
        val values = FloatArray(8) { 1f }
        val f = sampledField3D(values, 2, 2, 2, unit, outside = -7f)
        assertEquals(1f, f(vec(0.5, 0.5, 0.5)))
        assertEquals(-7f, f(vec(-0.01, 0.5, 0.5)))
        assertEquals(-7f, f(vec(0.5, 1.01, 0.5)))
        assertEquals(-7f, f(vec(0.5, 0.5, 2.0)))
    }

    @Test
    fun `it refuses a lattice it cannot interpolate over`() {
        assertFailsWith<IllegalArgumentException> { sampledField3D(FloatArray(4), 1, 2, 2, unit) }
        assertFailsWith<IllegalArgumentException> { sampledField3D(FloatArray(7), 2, 2, 2, unit) }
        assertFailsWith<IllegalArgumentException> {
            sampledField3D(FloatArray(8), 2, 2, 2, boundsOf(vec(0.0, 0.0, 0.0), vec(0.0, 1.0, 1.0)))
        }
    }

    /**
     * The end-to-end property: sample a sphere, march the samples, and the volume is a sphere's.
     *
     * The tolerance is the interesting part. Interpolating a sampled distance is not the distance, so
     * the marched surface sits slightly inside or outside the true one depending on the local
     * curvature, and the error falls with the lattice spacing. 3% at 64 samples across a diameter is
     * what that costs; asserting a tighter number would be asserting this machine's rounding.
     */
    @Test
    fun `a sphere sampled and then marched has a sphere's volume`() {
        val radius = 0.8
        val n = 64
        val box = boundsOf(vec(-1.0, -1.0, -1.0), vec(1.0, 1.0, 1.0))
        val truth = sdfSphere(radius.toFloat())
        val values = FloatArray(n * n * n)
        val probe = Vec3()
        for (k in 0 until n) for (j in 0 until n) for (i in 0 until n) {
            probe.set(
                (-1.0 + 2.0 * i / (n - 1)).toFloat(),
                (-1.0 + 2.0 * j / (n - 1)).toFloat(),
                (-1.0 + 2.0 * k / (n - 1)).toFloat(),
            )
            values[(k * n + j) * n + i] = truth(probe)
        }

        val mesh = marchingCubes(sampledField3D(values, n, n, n, box), box, resolution = 96)
        assertTrue(mesh.isWatertight(), "a sampled sphere should still march watertight")
        assertEquals(0, mesh.genus())
        val expected = 4.0 / 3.0 * PI * radius * radius * radius
        val relative = abs(mesh.signedVolume() - expected) / expected
        assertTrue(
            relative < 0.03,
            "marched volume ${mesh.signedVolume()} against a sphere's $expected, off by ${relative * 100}%",
        )
    }
}
