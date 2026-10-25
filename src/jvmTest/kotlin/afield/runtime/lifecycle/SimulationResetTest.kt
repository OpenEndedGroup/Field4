package afield.runtime.lifecycle

import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.runtime.execution.plus

import kotlin.test.*

class SimulationResetTest {
    @Test fun coalescesAndDoesNotConsumeRequestsFromInsideReset() {
        val reset = SimulationReset()
        val firstEpoch = reset.epoch
        repeat(10) { reset.request() }
        var count = 0
        assertTrue(reset.atFrame(1) { count++; reset.request() })
        assertEquals(10L, reset.completed)
        assertNotSame(firstEpoch, reset.epoch)
        assertTrue(reset.pending)
        assertFalse(reset.atFrame(1) { error("duplicate") })
        assertTrue(reset.atFrame(2) { count++ })
        assertEquals(2, count)
        assertFalse(reset.pending)
    }
    @Test fun failedResetDoesNotClaimSuccessOrAutomaticallyRetry() {
        val reset = SimulationReset()
        val epoch = reset.epoch
        reset.request()
        assertFailsWith<IllegalStateException> { reset.atFrame(1) { error("failure") } }
        assertEquals("failure", reset.failure)
        assertEquals(0L, reset.completed)
        assertSame(epoch, reset.epoch)
        assertFalse(reset.atFrame(2) {})
        reset.request()
        assertTrue(reset.atFrame(3) {})
        assertNull(reset.failure)
    }
}
