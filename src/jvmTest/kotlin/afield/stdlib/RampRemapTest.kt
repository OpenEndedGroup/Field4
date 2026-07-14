package afield.stdlib

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `ramp` vs `remap` — the two linear primitives that used to be conflated under one
 * misleading name.
 *
 *  - `ramp(start, end)` is a **value line**: its value travels start→end as x sweeps
 *    the domain.
 *  - `remap(from, to)` is a **domain normalizer**: it reads out the position of x
 *    within `[from, to]`, i.e. `(x - from)/(to - from)`.
 *
 * Until 2026-07-14 the normalizer was named `ramp`, so every caller that reached for a
 * value line (the chebyshev harmonic sweep, and numerous doc examples) silently got the
 * normalizer instead. This test pins both contracts and the point where they diverge.
 */
class RampRemapTest {

    @Test
    fun rampIsAValueLineAcrossTheDomain() = with(Functions) {
        val order = ramp(1.0, 6.0) over (0.0..1.0)
        assertEquals(1.0, order.eval(0.0), 1e-12, "ramp(1,6) at x=0")
        assertEquals(3.5, order.eval(0.5), 1e-12, "ramp(1,6) at x=0.5")
        assertEquals(6.0, order.eval(1.0), 1e-12, "ramp(1,6) at x=1")

        // The value spans start→end across whatever window the domain is, not just 0..1.
        val overlap = ramp(0.02, 0.18, 0.0..10.0)
        assertEquals(0.02, overlap.eval(0.0), 1e-12, "ramp start at domain min")
        assertEquals(0.10, overlap.eval(5.0), 1e-12, "ramp midpoint")
        assertEquals(0.18, overlap.eval(10.0), 1e-12, "ramp end at domain max")
    }

    @Test
    fun remapNormalizesAnXWindowToUnit() = with(Functions) {
        val alpha = remap(1.0, 6.0, 0.0..6.0)
        assertEquals(0.0, alpha.eval(1.0), 1e-12, "remap: x at window start → 0")
        assertEquals(0.5, alpha.eval(3.5), 1e-12, "remap: window midpoint → 0.5")
        assertEquals(1.0, alpha.eval(6.0), 1e-12, "remap: x at window end → 1")

        // remap(0,1) is the identity — the case that coincidentally worked before the
        // rename and hid the bug for everyone using the 0..1 default.
        val identity = remap(0.0, 1.0)
        for (i in 0..10) assertEquals(i / 10.0, identity.eval(i / 10.0), 1e-12)
    }

    @Test
    fun rampAndRemapDivergeOffTheUnitWindow() = with(Functions) {
        // This is exactly the confusion that broke the chebyshev harmonic sweep:
        // someone wanted a 1..6 value line but wrote ramp(1,6) expecting the old
        // normalizer semantics. Over x∈[0,1] the two could not be more different.
        val valueLine = ramp(1.0, 6.0) over (0.0..1.0)
        val normalizer = remap(1.0, 6.0) over (0.0..1.0)
        assertEquals(1.0, valueLine.eval(0.0), 1e-12)
        assertEquals(-0.2, normalizer.eval(0.0), 1e-12, "remap(1,6) at x=0 is (0-1)/5")
        assertTrue(valueLine.eval(0.0) != normalizer.eval(0.0))
    }

    @Test
    fun graphRoundTripPreservesBothOps() = with(Functions) {
        val r = ramp(1.0, 6.0)
        assertEquals("ramp", r.graphNode().op)
        val rCopy = r.deepCopy()
        assertEquals("ramp", rCopy.graphNode().op)
        assertNotNull(rCopy.graphNode().child("start"), "ramp start child preserved")
        assertNotNull(rCopy.graphNode().child("end"), "ramp end child preserved")
        for (i in 0..10) assertEquals(r.eval(i / 10.0), rCopy.eval(i / 10.0), 1e-12)

        val m = remap(1.0, 6.0)
        assertEquals("remap", m.graphNode().op)
        val mCopy = m.deepCopy()
        assertEquals("remap", mCopy.graphNode().op)
        assertNotNull(mCopy.graphNode().child("from"), "remap from child preserved")
        assertNotNull(mCopy.graphNode().child("to"), "remap to child preserved")
        for (i in 0..10) assertEquals(m.eval(i / 10.0), mCopy.eval(i / 10.0), 1e-12)
    }

    @Test
    fun bothAreTransmutationTargetsInTheRegistry() = with(Functions) {
        assertTrue(OpRegistry.isTransmutable("ramp"), "ramp must be transmutable")
        assertTrue(OpRegistry.isTransmutable("remap"), "remap must be transmutable")
        assertEquals("shape", OpRegistry.family("ramp"))
        assertEquals("shape", OpRegistry.family("remap"))
    }
}
