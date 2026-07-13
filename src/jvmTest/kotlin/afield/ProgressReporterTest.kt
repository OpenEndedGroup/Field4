package afield

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The transient progress-bar contract offered to routines: draw into the running box, be a
 * complete no-op outside a box or when globally disabled, keep the finished frame up, then
 * remove itself. Linger is set to 0 here so removal is synchronous and observable.
 */
class ProgressReporterTest {

    @BeforeTest
    fun instantAndImmediate() {
        ProgressBars.lingerMillis = 0L    // remove synchronously so tests can observe it
        ProgressBars.showDelayMillis = 0L // no grace period so the first frame is visible
    }

    @AfterTest
    fun restoreGlobalSwitches() {
        ProgressBars.enabled = true
        ProgressBars.lingerMillis = 1000L
        ProgressBars.showDelayMillis = 2000L
    }

    /** Capture every `display(channel, html)` a box emits, so we can see bar draws. */
    private fun Box.captureDisplays(): MutableList<Pair<String, Any?>> {
        val seen = mutableListOf<Pair<String, Any?>>()
        output.displayProxy = { channel, content -> seen += channel to content }
        return seen
    }

    @Test
    fun inertWithoutABoxContext() {
        // No active Box.context: the reporter is a complete no-op and never throws.
        val reporter = progressReporter("evolve")
        assertFalse(reporter.active, "no box context -> inert reporter")
        reporter.update(0.5, "half")
        reporter.update(3L, 10L)
        reporter.step(10L)
        reporter.finish()
    }

    @Test
    fun drawsInsideABoxThenCompletesAndRemovesItself() {
        val box = Box("progress-test")
        val draws = box.captureDisplays()
        Box.withBoxContextNow(box) {
            val reporter = progressReporter("evolve")
            assertTrue(reporter.active, "inside a box -> live reporter")

            reporter.update(0.4, "working")
            assertEquals(1, draws.size, "first frame draws immediately")
            assertEquals("*evolve", draws[0].first, "inline progress channel")

            // Reaching 1.0 draws the completed frame, then removes the bar (linger 0 -> now).
            reporter.update(1.0, "done")
            assertEquals(2, draws.size, "terminal draws the completed 100% frame")
            assertFalse(reporter.active, "reporter is finished after reaching 1.0")

            // Post-completion calls are inert.
            reporter.update(0.5, "late")
            assertEquals(2, draws.size, "no draws after completion")
        }
    }

    @Test
    fun abortedBarIsRemovedWithoutACompletedFrame() {
        val box = Box("progress-test")
        val draws = box.captureDisplays()
        Box.withBoxContextNow(box) {
            val reporter = progressReporter("evolve")
            reporter.update(0.3, "partway")
            reporter.finish() // early abort — never reached 100%
            assertFalse(reporter.active)
            assertEquals(1, draws.size, "no extra completed frame on abort")
        }
    }

    @Test
    fun suppressesBarsThatFinishInsideTheGracePeriod() {
        val box = Box("progress-test")
        val draws = box.captureDisplays()
        Box.withBoxContextNow(box) {
            // A long grace period the test never waits out: nothing should ever be sent.
            val reporter = progressReporter("evolve", showDelayMillis = 60_000L)
            reporter.update(0.2, "early")
            reporter.update(0.8, "still early")
            reporter.update(1.0, "done before the grace period elapsed")
            assertFalse(reporter.active, "a bar that completes inside the grace period is finished")
        }
        assertTrue(draws.isEmpty(), "quick work never troubles the front end with a bar")
    }

    @Test
    fun pulseShowsIndeterminateActivityAndNeverAutoCompletes() {
        val box = Box("progress-test")
        val draws = box.captureDisplays()
        Box.withBoxContextNow(box) {
            val reporter = progressReporter("reduce")
            repeat(3) { reporter.pulse() }
            assertTrue(reporter.active, "an indeterminate bar never completes on its own")
            assertTrue(draws.isNotEmpty(), "pulse draws activity")
            assertTrue(draws.all { it.first == "*reduce" }, "on its own channel")
            assertTrue(
                draws.last().second.toString().contains("indeterminate"),
                "renders the indeterminate variant",
            )
            reporter.finish() // only finish() removes an indeterminate bar
            assertFalse(reporter.active)
        }
    }

    @Test
    fun pulseIsSuppressedDuringTheGracePeriod() {
        val box = Box("progress-test")
        val draws = box.captureDisplays()
        Box.withBoxContextNow(box) {
            val reporter = progressReporter("reduce", showDelayMillis = 60_000L)
            repeat(5) { reporter.pulse() }
            reporter.finish()
        }
        assertTrue(draws.isEmpty(), "quick indeterminate work never troubles the front end")
    }

    @Test
    fun globallyDisableableIsANoop() {
        val box = Box("progress-test")
        val draws = box.captureDisplays()
        ProgressBars.enabled = false
        Box.withBoxContextNow(box) {
            val reporter = progressReporter("evolve")
            assertFalse(reporter.active, "disabled globally -> inert even inside a box")
            reporter.update(0.5, "nope")
            reporter.update(1.0, "nope")
        }
        assertTrue(draws.isEmpty(), "disabled reporter draws nothing")
    }

    @Test
    fun distinctlyNamedBarsNestOnSeparateChannels() {
        val box = Box("progress-test")
        val draws = box.captureDisplays()
        Box.withBoxContextNow(box) {
            val outer = progressReporter("outer")
            val inner = progressReporter("inner")
            outer.update(0.2)
            inner.update(0.5)
            assertTrue(outer.active && inner.active, "both bars live at once")
            assertEquals(setOf("*outer", "*inner"), draws.map { it.first }.toSet(), "one channel each")

            inner.update(1.0) // completing the inner bar leaves the outer untouched
            assertFalse(inner.active)
            assertTrue(outer.active, "finishing a nested bar doesn't finish its parent")
        }
    }

    @Test
    fun tqdmDrawsWhileIteratingThenClears() {
        val box = Box("progress-test")
        val draws = box.captureDisplays()
        Box.withBoxContextNow(box) {
            val collected = box.tqdm(listOf("a", "b", "c"), name = "loop").toList()
            assertEquals(listOf("a", "b", "c"), collected, "tqdm is transparent to iteration")
        }
        assertTrue(draws.isNotEmpty(), "tqdm draws progress while iterating")
        assertTrue(draws.all { it.first == "*loop" }, "all draws on the inline loop channel")
    }

    @Test
    fun tqdmRespectsGlobalDisable() {
        val box = Box("progress-test")
        val draws = box.captureDisplays()
        ProgressBars.enabled = false
        Box.withBoxContextNow(box) {
            val collected = box.tqdm(listOf(1, 2, 3), name = "loop").toList()
            assertEquals(listOf(1, 2, 3), collected, "iteration is unaffected when disabled")
        }
        assertTrue(draws.isEmpty(), "disabled tqdm draws nothing")
    }
}
