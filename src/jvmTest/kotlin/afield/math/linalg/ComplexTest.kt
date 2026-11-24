package afield.math.linalg

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.E
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ComplexTest {
    private fun near(expected: Double, actual: Double, eps: Double = 1e-6) =
        assertTrue(abs(expected - actual) < eps, "expected $expected, got $actual")

    @Test
    fun complexArithmeticRotatesAndRoundTrips() {
        val z = Complexd(3, 4)
        near(5.0, z.abs)
        assertEquals(Complexd(-5, 10), z * Complexd(1, 2))
        val q = z / Complexd(1, 2)
        near(3.0, (q * Complexd(1, 2)).re); near(4.0, (q * Complexd(1, 2)).im)
        assertEquals(Complexd(3, -4), z.conj)
        // A unit complex number rotates: i turns (1, 0) a quarter turn.
        val turned = Complexf.I * Complexf(1, 0)
        assertEquals(0f, turned.re); assertEquals(1f, turned.im)
        val e = Complexd(0, PI).exp()
        near(-1.0, e.re); near(0.0, e.im)
        near(PI / 2, Complexd.polar(2, PI / 2).arg)
        val r = Complexd(-4, 0).pow(0.5)
        near(0.0, r.re); near(2.0, r.im)
        assertEquals(Vector2f(1f, 2f), Complexf(1, 2).toVector())
        assertEquals(Complexf(1, 2), Vector2f(1f, 2f).toComplex())
        val literal: Complexf = [1, 2]
        assertEquals(Complexf(1, 2), literal)
        assertEquals(Complexd(3, 4), 2 + Complexd(1, 4))
    }

    @Test
    fun hypercomplexCoversComplexDualAndSplit() {
        val c = Hypercomplexd.complex(0, 1)
        assertEquals(Hypercomplexd(-1, 0, -1), c * c)
        // Dual numbers carry a derivative: (x + ε)³ at x = 2 is 8 + 12ε.
        val x = Hypercomplexd.dual(2, 1)
        assertEquals(Hypercomplexd(8, 12, 0), x * x * x)
        val ex = Hypercomplexd.dual(1, 1).exp()
        near(E, ex.a); near(E, ex.b)
        val j = Hypercomplexd.split(0, 1)
        assertEquals(Hypercomplexd(1, 0, 1), j * j)
        // A boost keeps a² − b².
        val boost = Hypercomplexd.split(0, 0.7).exp()
        val v = Hypercomplexd.split(3, 1)
        near(v.norm, (boost * v).norm)
        val d = Hypercomplexf.complex(3, 4) / Hypercomplexf.complex(1, 2)
        assertTrue(abs(d.a - 2.2f) < 1e-5f && abs(d.b + 0.4f) < 1e-5f)
        assertFailsWith<IllegalArgumentException> { c + x }
    }

    @Test
    fun readOnlySwizzleTargetsAcceptCollectionLiterals() {
        val a = Vector3f(0f, 0f, 9f)
        a.xy = [1, 2]
        assertEquals(Vector3f(1f, 2f, 9f), a)
        val p: Vector4dc = [1, 2, 3, 4]
        assertEquals(4.0, p.w())
    }
}
