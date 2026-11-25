package afield.math.linalg

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Tier 2 spellings from `dev_docs/stdlib/LINALG_OPERATORS_PROPOSAL.md`: interpolation and
 * motion (`mid`, `towards`, `smooth`, `ease`, `damp`, `slerp`), composition with `then`,
 * quaternion powers, and the shaping helpers. Every one returns a new value.
 */
class LiveCodingMotionTest {
    private fun close(expected: Double, actual: Number, tolerance: Double = 1e-5) =
        assertEquals(expected, actual.toDouble(), tolerance)

    @Test
    fun interpolationVariantsFinishWithTo() {
        val a = vec(0, 0)
        val b = vec(10, 20)
        assertEquals(vec(5, 10), a mid b)
        assertTrue(a towards b near vec(1, 2).n)
        assertEquals(Vec2(), a towards Vec2(a), "coincident points have no direction")
        assertEquals(vec(5, 10), a smooth 0.5 to b)
        close(10 * 0.25 * 0.25 * (3 - 2 * 0.25), (a smooth 0.25 to b).x)
        assertEquals(b, a smooth 7 to b, "smooth clamps t")
        assertEquals(vec(2.5, 5), a ease (0.5 to Ease.inQuad) to b)
        assertEquals(vec(5, 10), a ease (0.5 to { t: Double -> t }) to b)
        assertEquals(vec(0, 0), a)
        assertEquals(vec(10, 20), b)
    }

    @Test
    fun dampIsFrameRateIndependent() {
        val target = vec(100, 0)
        var once = vec(0, 0)
        once = once.damp(4, 1.0) to target
        var many = vec(0, 0)
        repeat(60) { many = many.damp(4, 1.0 / 60) to target }
        close(once.x.toDouble(), many.x, 1e-3)
        close(100 * (1 - exp(-4.0)), once.x, 1e-3)
        val live = vec(0, 0) damp 6 to target
        assertTrue(live.x in 0f..100f, "the runloop form moves part of the way: $live")
    }

    @Test
    fun vectorSlerpTurnsAtAConstantRate() {
        assertTrue((vec(1, 0) slerp 0.5 to vec(0, 1)) near vec(sqrt(0.5), sqrt(0.5)))
        assertTrue((vec(1, 0) slerp 0.5 to vec(0, 3)).length.let { kotlin.math.abs(it - 2f) < 1e-5f }, "length interpolates")
        assertTrue((vec(1, 0, 0) slerp 0.5 to vec(0, 0, 1)) near vec(sqrt(0.5), 0.0, sqrt(0.5)))
        val opposite = vec(1, 0, 0) slerp 0.5 to vec(-1, 0, 0)
        close(1.0, opposite.length)
        close(0.0, opposite.x)
        assertTrue((vec(1, 0, 0) slerp 0 to vec(0, 1, 0)) near vec(1, 0, 0))
        assertTrue((vec(1, 0, 0) slerp 1 to vec(0, 1, 0)) near vec(0, 1, 0))
        assertTrue((Vec3() slerp 0.5 to vec(2, 0, 0)) near vec(1, 0, 0), "a zero end falls back to lerp")
    }

    @Test
    fun quaternionInterpolationCompositionAndPowers() {
        val q = 90.deg about Vec3.Z
        val r = 90.deg about Vec3.X
        assertTrue((Quat() slerp 0.5 to q) near (45.deg about Vec3.Z))
        assertTrue(q near (90.deg about Vec3.Z), "slerp … to leaves q alone")
        assertTrue((q then r) near r * q)
        val p = vec(1, 0, 0)
        assertTrue((q then r) * p near r * (q * p), "q then r applies q first")
        assertTrue(q.pow(0.5) near (45.deg about Vec3.Z))
        assertTrue(q.pow(2) near (180.deg about Vec3.Z))
        assertTrue(q.pow(-1) near q.inv)
        assertTrue(q.ln().exp() near q)
        assertTrue(Quat().ln() near Quaternionf(0f, 0f, 0f, 0f))
        val t = dualquat(q, vec(1, 0, 0))
        val u = dualquat(Quat(), vec(0, 5, 0))
        assertTrue(((t then u) * p) near (u * (t * p)))
        val m = Matrix4f().translation(1f, 0f, 0f)
        val n = Matrix4f().scaling(2f, 2f, 2f)
        assertTrue(((m then n) * vec(0, 0, 0)) near vec(2, 0, 0), "translate, then scale")
    }

    @Test
    fun complexThenLerpAndSlerp() {
        val z = Complexd.polar(2, PI / 2)
        val w = Complexd.polar(1, PI / 4)
        assertTrue((z then w) near w * z)
        assertTrue((Complexd(0, 0) lerp 0.5 to Complexd(2, 4)) near Complexd(1, 2))
        assertTrue((Complexd(1, 0) slerp 0.5 to Complexd(0, 3)) near Complexd.polar(2, PI / 4))
        val across = Complexd.polar(1, 3.0) slerp 0.5 to Complexd.polar(1, -3.0)
        close(PI, kotlin.math.abs(across.arg), 1e-9)
    }

    @Test
    fun shapingHelpers() {
        val v = vec(3, 4)
        assertEquals(vec(3, 4), v limit 10)
        assertTrue((v limit 1) near vec(0.6, 0.8))
        assertEquals(vec(10, 0), vec(12, 4) snap 10)
        assertEquals(vec(12, 6), vec(11, 5.4) snap vec(4, 2))
        assertEquals(vec(-2, 1), vec(-1.5, 1.5).floor())
        assertTrue(vec(-1.25, 1.75).fract() near vec(0.75, 0.75))
        assertEquals(vec(1, 2), vec(-1, 2).abs())
        assertEquals(vec(-1, 0, 1), vec(-3, 0, 2).sign())
        assertEquals(4f, v.maxComponent)
        assertEquals(-1.0, Vector4d(1.0, -1.0, 2.0, 0.5).minComponent)
        assertEquals(7f, v.sum)
        assertEquals(vec(1, 1), vec(1, -1) reflect vec(0, 3))
        assertEquals(vec(3, 0), v proj vec(5, 0))
        assertEquals(vec(0, 4), v rej vec(5, 0))
        assertEquals(Vec2(), v proj Vec2())
        assertEquals(vec(3, 4), v, "nothing above mutated v")
    }
}
