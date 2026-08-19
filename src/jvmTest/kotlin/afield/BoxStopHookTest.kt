package afield

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The stop-time teardown registry that `Box.streamProcess` relies on.
 *
 * These are the properties `stopTrackedJobs` has to have for the fix in
 * `dev_docs/frames_stop_end_phase.md` to be worth anything, tested without spawning a process:
 * hooks run, they run once, one throwing does not strand the others, and unregistering works.
 */
class BoxStopHookTest {

    @Test
    fun stopRunsRegisteredHooks() {
        val box = Box("stop-hook-runs")
        var ran = 0
        box.registerStopHook(Runnable { ran++ })

        box.stopTrackedJobs()

        assertEquals(1, ran, "a registered hook should run exactly once on stop")
    }

    @Test
    fun hooksRunOnceAndTheListIsDrained() {
        val box = Box("stop-hook-drained")
        var ran = 0
        box.registerStopHook(Runnable { ran++ })

        box.stopTrackedJobs()
        box.stopTrackedJobs()

        assertEquals(1, ran, "the second stop must not re-run a hook the first already consumed")
        assertTrue(box.stopHooks.isEmpty(), "stop should leave the hook list empty")
    }

    /**
     * The isolation property. `streamProcess`'s hook blocks for up to 500 ms killing a child, and a
     * box can own several; one of them throwing must not mean the rest never release anything.
     */
    @Test
    fun oneThrowingHookDoesNotStrandTheOthers() {
        val box = Box("stop-hook-isolated")
        val ran = mutableListOf<String>()
        box.registerStopHook(Runnable { ran += "first" })
        box.registerStopHook(Runnable { throw IllegalStateException("deliberate") })
        box.registerStopHook(Runnable { ran += "third" })

        box.stopTrackedJobs()

        assertEquals(
            listOf("first", "third"),
            ran,
            "a hook that throws must be isolated: the ones on either side of it still run"
        )
    }

    @Test
    fun unregisteringRemovesTheHook() {
        val box = Box("stop-hook-unregister")
        var ran = false
        val unregister = box.registerStopHook(Runnable { ran = true })

        unregister.run()
        box.stopTrackedJobs()

        assertFalse(ran, "an unregistered hook must not run")
        assertTrue(box.stopHooks.isEmpty(), "and must not be left on the list")
    }

    /**
     * Ordering, asserted because `streamProcess` depends on it: the producer is cancelled first, so
     * the hook is not racing a reader coroutine that is still pumping the process's output.
     */
    @Test
    fun hooksRunAfterJobsAreCancelled() {
        val box = Box("stop-hook-ordering")
        val job = kotlinx.coroutines.Job()
        box.jobs.add(job)
        var jobWasCancelledFirst = false
        box.registerStopHook(Runnable { jobWasCancelledFirst = job.isCancelled })

        box.stopTrackedJobs()

        assertTrue(
            jobWasCancelledFirst,
            "the drain must come after job cancellation, mirroring the order the end phase used"
        )
    }
}
