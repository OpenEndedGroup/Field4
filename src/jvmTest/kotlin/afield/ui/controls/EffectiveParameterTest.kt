package afield.ui.controls

import afield.data.collections.get
import afield.data.collections.invoke
import afield.data.collections.m
import afield.data.collections.s
import afield.data.collections.times
import afield.document.graph.minusAssign
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
import afield.graphics.render.rangeTo
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.times
import afield.math.linalg.invoke
import afield.math.linalg.remAssign
import afield.math.linalg.set
import afield.math.linalg.times
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times

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
