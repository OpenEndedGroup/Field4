package afield.four.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Transport tests for `PatClock.kt`, which had no coverage at all.
 *
 * The clock reads from `System.nanoTime`, so the timing assertions here sleep for a real
 * interval and then assert a relationship — "the reading went backwards", "the reading kept
 * the time it had accrued" — rather than a number. Each one seeks to a large, distinctive
 * anchor first so that a rewind to `accumulated` is unmistakable and not a small drift.
 */
class PatClockTest {

    /** Long enough that the reading moves well clear of the tolerance, short enough to be free. */
    private val sleepMillis = 80L
    private val sleepSeconds = sleepMillis / 1000.0
    private val tolerance = 0.03

    private fun stoppedClock(): PatClock = PatClock(label = "test")

    // ── reading a running clock ───────────────────────────────────────────────────────────

    @Test
    fun `a stopped clock holds its accumulated reading`() {
        val clock = stoppedClock().seek(100.0)
        assertEquals(100.0, clock.now, 0.0)
        Thread.sleep(sleepMillis)
        assertEquals(100.0, clock.now, 0.0)
        assertTrue(!clock.isRunning)
    }

    @Test
    fun `a running clock advances in real time at its rate`() {
        val clock = stoppedClock().seek(100.0).start()
        Thread.sleep(sleepMillis)
        assertEquals(100.0 + sleepSeconds, clock.now, tolerance)
    }

    @Test
    fun `pause folds the elapsed time into the reading and resume continues from it`() {
        val clock = stoppedClock().seek(100.0).start()
        Thread.sleep(sleepMillis)
        val paused = clock.pause().now
        assertTrue(paused >= 100.0 + sleepSeconds - tolerance, "paused at $paused")

        // Time passes while paused; it must not count.
        Thread.sleep(sleepMillis)
        assertEquals(paused, clock.now, 0.0)

        // Resuming a paused clock is correct: pause() already folded the elapsed time in.
        clock.start()
        Thread.sleep(sleepMillis)
        assertEquals(paused + sleepSeconds, clock.now, tolerance)
    }

    // ── start() on a clock that is already running ─────────────────────────────────────────

    @Test
    fun `start on a running clock keeps the reading it has`() {
        // REGRESSION FENCE, 2026-09-15. Until this date start() re-anchored `sourceAnchor` without
        // folding the elapsed time into `accumulated`, so a clock that was already running lost
        // everything it had accrued since it was last started, sought or reset: the reading here
        // went back to 100.0. A bare start() must not move the reading at all.
        val clock = stoppedClock().seek(100.0).start()
        Thread.sleep(sleepMillis)

        val before = clock.now
        assertTrue(before >= 100.0 + sleepSeconds - tolerance, "before start() the reading was $before")

        clock.start()
        val after = clock.now

        assertTrue(after >= before, "start() rewound the reading from $before to $after")
        assertEquals(before, after, tolerance)

        // And it is still running afterwards, from there.
        Thread.sleep(sleepMillis)
        assertEquals(after + sleepSeconds, clock.now, tolerance)
    }

    @Test
    fun `start with an explicit time re-zeros a running clock`() {
        // Passing `at` is the unambiguous case: it is documented to set the reading, and it does.
        val clock = stoppedClock().seek(100.0).start()
        Thread.sleep(sleepMillis)
        clock.start(at = 7.0)
        assertEquals(7.0, clock.now, tolerance)
    }

    // ── reachability from ordinary box code ───────────────────────────────────────────────

    @Test
    fun `calling musicClock on the running shared clock leaves the reading alone`() {
        // This is the route the bug reached ordinary box code by. `musicClock()` defaults to
        // `started = true`, and `PatClock.shared(started = true)` calls `start()` on the clock it
        // hands back, so before 2026-09-15 a bare `musicClock()` in a second box wound the global
        // transport backwards by however long it had been running, and everything already waiting
        // on a future time through `waitForPatTime` waited that much longer.
        val first = musicClock()
        assertTrue(first.isRunning)
        Thread.sleep(sleepMillis)

        val before = first.now
        val second = musicClock()
        assertTrue(first === second, "musicClock() should hand back the one shared clock")
        val after = second.now

        assertTrue(after >= before, "musicClock() rewound the shared clock from $before to $after")
        assertEquals(before, after, tolerance)
    }

    @Test
    fun `asking for the shared clock without starting it leaves the reading alone`() {
        val clock = musicClock()
        Thread.sleep(sleepMillis)
        val before = clock.now
        assertTrue(PatClock.shared(started = false) === clock)
        assertTrue(clock.now >= before, "shared(started = false) moved the reading to ${clock.now} from $before")
    }

    // ── subdivision ──────────────────────────────────────────────────────────────────────

    @Test
    fun `a child clock tracks the parent scaled by its factor`() {
        val parent = stoppedClock().seek(10.0).start()
        val child = parent.subdivide(4.0, label = "sixteenths")
        assertEquals(parent.now * 4.0, child.now, tolerance * 4.0)
        Thread.sleep(sleepMillis)
        assertEquals(parent.now * 4.0, child.now, tolerance * 4.0)
    }
}
