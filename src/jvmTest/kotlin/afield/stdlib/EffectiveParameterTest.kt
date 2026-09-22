package afield.stdlib

import kotlin.test.*

class EffectiveParameterTest {
    @Test fun requestedValueIsPreservedWhenTheEffectiveValueIsClamped() {
        val p = EffectiveParameter("speed", 2.0, 0.0, 10.0, "m/s", "Speed cap")
        p.requested = 100.0
        val read = p.snapshot()
        assertEquals(100.0, read.requested)
        assertEquals(10.0, read.effective)
        assertTrue(p.clamped)
        p.requested = 3.0
        assertEquals(10.0, read.effective) // A snapshot is independent of later edits.
        assertEquals(3.0, p.effective)
        assertFalse(p.clamped)
        assertFailsWith<IllegalArgumentException> { p.requested = Double.NaN }
        assertEquals(3.0, p.requested)
    }
}
