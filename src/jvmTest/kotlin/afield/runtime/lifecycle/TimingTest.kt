package afield.runtime.lifecycle

import kotlin.test.*

class TimingTest {
    @Test fun measuresOnlyRequestedCallsAndSelectsTheMinimum() {
        val clock = listOf(0L, 4_000_000L, 10_000_000L, 12_000_000L, 20_000_000L, 23_000_000L).iterator()
        var calls = 0
        val ms = bestTimeMsWithClock(3, 2, { clock.next() }) { calls++ }
        assertEquals(5, calls)
        assertEquals(2.0, ms)
        assertFalse(clock.hasNext())
    }

    @Test fun invalidCountsDoNotRunTheBlock() {
        assertFailsWith<IllegalArgumentException> { bestTimeMs(0) { fail("ran") } }
        assertFailsWith<IllegalArgumentException> { bestTimeMs(warmup = -1) { fail("ran") } }
    }

    @Test fun aThrownWorkloadStopsImmediately() {
        var calls = 0
        val failure = IllegalStateException("workload")
        assertSame(failure, assertFailsWith<IllegalStateException> {
            bestTimeMsWithClock(3, 0, { 0L }) { calls++; throw failure }
        })
        assertEquals(1, calls)
    }
}
