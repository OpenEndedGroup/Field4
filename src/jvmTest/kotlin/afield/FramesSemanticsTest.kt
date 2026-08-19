package afield

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /**
     * `.end { }` is **not** a `finally`. [Frames.atEnd] is invoked from exactly one place —
     * [Frames.frame]'s `catch`, reached when the pending `yield()` throws a cancellation. A body
     * that throws its own exception never goes through that path.
     */
    @Test
    fun theEndBlockDoesNotRunWhenTheBodyThrows() {
        var cleanedUp = false
        var calls = 0
        val f = eachFrame {
            calls++
            if (calls == 3) error("boom")
        }.end { cleanedUp = true }

        pump(f, 8)
        assertEquals(3, calls)
        assertFalse(cleanedUp, ".end { } must not run when the body throws — it is not a finally")
    }

    /** Nor does it run when the body simply returns: there is no cancellation to catch. */
    @Test
    fun theEndBlockDoesNotRunWhenTheBodyReturnsNormally() {
        var cleanedUp = false
        var calls = 0
        val f = frames {
            repeat(3) { calls++; frame() }
        }.end { cleanedUp = true }

        pump(f, 8)
        assertEquals(3, calls)
        assertFalse(cleanedUp, ".end { } runs on cancellation, not on completion")
    }

    /**
     * On cancellation, `.end { }` runs **before** a `finally` inside the body.
     *
     * [Frames.frame] runs `atEnd` from inside its own `catch` and only then rethrows, so the
     * cancellation has not yet unwound the body when the end block runs. Worth pinning: the
     * intuitive reading — cleanup blocks running outermost-last — gives the opposite order.
     */
    @Test
    fun theEndBlockRunsBeforeTheBodysOwnFinally() {
        val order = mutableListOf<String>()
        val f = frames {
            try {
                while (true) frame()
            } finally {
                order += "body finally"
            }
        }.end { order += "end block" }

        val framer = Framer()
        val job = CoroutineScope(framer).launch { runCatching { f.body() } }
        f.job = job
        repeat(3) { framer.frame() }
        job.cancel()
        repeat(3) { framer.frame() }

        assertEquals(listOf("end block", "body finally"), order)
    }

    /**
     * Wires a [Frames] to a real [Box] the way both launchers do, so the tests below exercise the
     * actual epoch/generation fences rather than a bare `Job()`.
     *
     * The two hooks are the whole contract a launcher installs: [Frames.shouldend] is the strand
     * generation fence, and [Frames.onAtEndStart] registers the end-phase job with the box.
     */
    private fun launchAgainst(box: Box, frames: Frames, strand: String = "default"): Pair<Framer, kotlinx.coroutines.Job> {
        val launchEpoch = box.captureLaunchEpoch()
        val generation = box.runGeneration.compute(strand) { _, current -> (current ?: -1) + 1 }!!
        frames.shouldend = { box.runGeneration[strand] != generation }
        frames.onAtEndStart = { endJob -> box.registerLaunchJob(endJob, launchEpoch) }
        val framer = Framer()
        val job = CoroutineScope(framer).launch { runCatching { frames.body() } }
        frames.job = job
        box.registerLaunchJob(job, launchEpoch)
        return framer to job
    }

    /**
     * **`Box.stop` does not run `.end { }`.**
     *
     * This is the one that surprised me, and it is not visible from `Phased.kt` alone.
     * `frame()` does launch the end phase on cancellation — but it then calls `onAtEndStart`, which
     * registers the end job with the box via [Box.registerLaunchJob]. `stopTrackedJobs` has already
     * incremented `launchEpoch`, so that registration fails, and `registerLaunchJob` *cancels the
     * job it was handed*. The end block is queued on the Framer and killed before it is dispatched.
     *
     * So `.end { }` is narrower than "on cancellation": it is "on cancellation that did not come
     * from a full stop".
     */
    @Test
    fun boxStopCancelsTheEndBlockBeforeItCanRun() {
        val box = Box("stop-kills-end")
        var cleaned = false
        val f = eachFrame { }.end { cleaned = true }
        val (framer, _) = launchAgainst(box, f)

        repeat(3) { framer.frame() }
        KotlinExecution().stop(box)
        repeat(8) { framer.frame() }

        assertFalse(cleaned, "Box.stop bumps launchEpoch, so the end job is cancelled at registration")
    }

    /**
     * Re-running the box — a generation bump with no epoch change — *does* run `.end { }`.
     *
     * The contrast with [boxStopCancelsTheEndBlockBeforeItCanRun] is the whole point: the two stops
     * a user reaches for most differ in whether cleanup happens at all.
     */
    @Test
    fun reRunningTheBoxStillRunsTheEndBlock() {
        val box = Box("rerun-keeps-end")
        var cleaned = false
        val f = eachFrame { }.end { cleaned = true }
        val (framer, _) = launchAgainst(box, f)

        repeat(3) { framer.frame() }
        // What a second launch on the same strand does, and nothing else.
        box.runGeneration.compute("default") { _, current -> (current ?: -1) + 1 }
        repeat(8) { framer.frame() }

        assertTrue(cleaned, "a generation bump leaves launchEpoch alone, so the end job registers")
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
