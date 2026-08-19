package afield

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Executable statements of the frame-scheduling contract documented on
 * `public/docs/animation_time_model.md`.
 *
 * Every case here drives a [Frames] the way the Runloop does — a [Framer] is a
 * [kotlinx.coroutines.CoroutineDispatcher] whose queue is drained once per
 * [Framer.frame] call — so "one pump" below is exactly one Runloop tick, with no
 * window, no server and no wall-clock timing.
 */
class FramesSemanticsTest {

    /** Runs [frames] on a private [Framer] and pumps it [pumps] times, recording state per pump. */
    private fun pump(frames: Frames, pumps: Int, onPump: (Int) -> Unit = {}): List<Int> {
        val framer = Framer()
        val scope = CoroutineScope(framer)
        val job = scope.launch { runCatching { frames.body() } }
        frames.job = job
        val trace = mutableListOf<Int>()
        repeat(pumps) { i ->
            framer.frame()
            onPump(i + 1)
            trace += frames.frameNumber
        }
        job.cancel()
        return trace
    }

    @Test
    fun eachFrameAdvancesBeforeItRunsTheBody() {
        var calls = 0
        val f = eachFrame { calls++ }
        val bodyCallsPerPump = mutableListOf<Int>()
        val frameNumbers = pump(f, 5) { bodyCallsPerPump += calls }

        // Pump 1 only starts the body, which suspends inside its first frame().
        assertEquals(listOf(0, 1, 2, 3, 4), frameNumbers)
        assertEquals(listOf(0, 1, 2, 3, 4), bodyCallsPerPump)
    }

    @Test
    fun loopRunsTheBodyBeforeItAdvances() {
        var calls = 0
        val f = loop { calls++ }
        val bodyCallsPerPump = mutableListOf<Int>()
        val frameNumbers = pump(f, 5) { bodyCallsPerPump += calls }

        // The body runs on pump 1, then frame() suspends: one body call per pump, one ahead of eachFrame.
        assertEquals(listOf(0, 1, 2, 3, 4), frameNumbers)
        assertEquals(listOf(1, 2, 3, 4, 5), bodyCallsPerPump)
    }

    @Test
    fun loopDoesNotDoubleAdvanceWhenTheBodyAdvancedItself() {
        val f = loop { frame() }
        val frameNumbers = pump(f, 4)
        // If loop advanced unconditionally this would be 0,2,4,6.
        assertEquals(listOf(0, 1, 2, 3), frameNumbers)
    }

    @Test
    fun framesGivesTheBodyFullControlOfAdvancement() {
        var calls = 0
        // Two units of work per frame boundary: the body decides when to yield.
        val f = frames {
            while (true) {
                calls++
                calls++
                frame()
            }
        }
        val bodyCallsPerPump = mutableListOf<Int>()
        pump(f, 3) { bodyCallsPerPump += calls }
        assertEquals(listOf(2, 4, 6), bodyCallsPerPump)
    }

    @Test
    fun foreverStopsWhenTheBodyReturnsFalse() {
        var calls = 0
        val f = forever { calls++; calls < 3 }
        pump(f, 6)
        assertEquals(3, calls)
    }

    @Test
    fun endStopsAtTheNextFrameBoundaryAndStillRunsTheEndBlock() {
        var calls = 0
        var cleanedUp = false
        val f = eachFrame {
            calls++
            if (calls == 3) end()
        }.end { cleanedUp = true }

        pump(f, 8)
        assertEquals(3, calls, "the body must not run again after end()")
        assertTrue(cleanedUp, "the .end { } block must still run after cancellation")
    }

    /**
     * `shouldend` is read at the *top* of [Frames.frame], not at the moment the flag flips.
     *
     * A body suspended inside `frame()` is halfway through a call that has already decided
     * it will return `true`; it resumes, returns `true`, and the loop runs the body once
     * more before the *next* `frame()` sees the flag. So a stop requested this way costs
     * exactly one more body call. `Box.stop` does not behave this way — it cancels the Job
     * directly, so the pending `yield()` throws and the body never resumes.
     */
    @Test
    fun shouldendCostsExactlyOneMoreBodyCall() {
        var calls = 0
        var stop = false
        val f = eachFrame { calls++ }
        f.shouldend = { stop }

        val framer = Framer()
        val scope = CoroutineScope(framer)
        val job = scope.launch { runCatching { f.body() } }
        f.job = job
        repeat(3) { framer.frame() }
        val before = calls
        stop = true
        repeat(3) { framer.frame() }

        assertEquals(before + 1, calls, "exactly one more body call after shouldend() flips")
        assertTrue(job.isCancelled)
    }

    /** Cancelling the Job directly — what `Box.stop` does — costs no extra body call. */
    @Test
    fun cancellingTheJobDirectlyCostsNoExtraBodyCall() {
        var calls = 0
        val f = eachFrame { calls++ }

        val framer = Framer()
        val scope = CoroutineScope(framer)
        val job = scope.launch { runCatching { f.body() } }
        f.job = job
        repeat(3) { framer.frame() }
        val before = calls
        job.cancel()
        repeat(3) { framer.frame() }

        assertEquals(before, calls, "no further body calls once the Job itself is cancelled")
    }

    @Test
    fun aGeneratorKeepsItsLocalsAcrossSteps() {
        // The pull-based counterpart: state lives in ordinary locals, no coroutine dispatcher.
        val leaky = generator2<Double, Double> {
            var acc = 0.0
            while (true) {
                val x = yield(acc)
                acc = acc * 0.5 + x
            }
        }
        val out = mutableListOf<Double>()
        out += leaky.start()!!
        repeat(4) { out += leaky.next(1.0)!! }
        assertEquals(listOf(0.0, 1.0, 1.5, 1.75, 1.875), out)
    }

    @Test
    fun aGeneratorReportsCompletionWithNull() {
        val two = generator<String> {
            yield("a")
            yield("b")
        }
        assertEquals("a", two.start())
        assertEquals("b", two.next(Unit))
        assertEquals(null, two.next(Unit))
        assertTrue(two.isCompleted)
    }
}
