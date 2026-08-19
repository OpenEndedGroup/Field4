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

    // ---- the rest of the surface the page's ledger lists -------------------------------------

    /** Evaluating a `Frames` without returning it shows this, so the page quotes it. */
    @Test
    fun toStringIsTheStringThePageQuotes() {
        assertEquals("(frames)", frames { }.toString())
    }

    /**
     * `delay(ms)` suspends for real milliseconds and *then* advances one frame.
     *
     * A [Framer] implements no `Delay`, so kotlinx falls back to its default scheduler and
     * re-dispatches the resumption onto the Framer — which means the frame only lands once
     * something pumps. Pumping in a wall-clock loop is the honest way to observe that.
     */
    @Test
    fun delaySuspendsInRealTimeAndThenAdvancesOneFrame() {
        val f = frames { delay(60) }
        val framer = Framer()
        val job = CoroutineScope(framer).launch { runCatching { f.body() } }
        f.job = job

        val deadline = System.nanoTime() + 5_000_000_000L
        while (f.frameNumber < 1 && System.nanoTime() < deadline) {
            framer.frame()
            Thread.sleep(2)
        }
        assertEquals(1, f.frameNumber, "delay(60) should suspend, then advance exactly one frame")
        job.cancel()
    }

    /** `wait()` parks the body, advancing a frame per pump, until the job is cancelled. */
    @Test
    fun waitAdvancesUntilCancelled() {
        val f = frames { wait() }
        val framer = Framer()
        val job = CoroutineScope(framer).launch { runCatching { f.body() } }
        f.job = job

        repeat(5) { framer.frame() }
        val parked = f.frameNumber
        assertTrue(parked >= 4, "wait() advances about one frame per pump, got $parked")
        job.cancel()
        repeat(3) { framer.frame() }
        assertEquals(parked, f.frameNumber, "and stops once the job is cancelled")
    }

    /** `a + b` runs two frame programs under one parent; both advance as the parent advances. */
    @Test
    fun framesPlusRunsBothChildrenInParallel() {
        var left = 0
        var right = 0
        val combined = eachFrame { left++ } + eachFrame { right++ }

        val framer = Framer()
        val job = CoroutineScope(framer).launch { runCatching { combined.body() } }
        combined.job = job
        repeat(6) { framer.frame() }
        job.cancel()

        assertTrue(left > 0, "the left child never ran")
        assertTrue(right > 0, "the right child never ran")
        assertEquals(left, right, "the two children advance in step with each other")
    }

    /** `then` sequences two plain suspend lambdas into one. */
    @Test
    fun thenSequencesTwoSuspendLambdas() {
        val order = mutableListOf<String>()
        val first: suspend () -> Unit = { order += "first" }
        val combined = first.then { order += "second" }
        kotlinx.coroutines.runBlocking { combined() }
        assertEquals(listOf("first", "second"), order)
    }

    /** `Framer.frame()` marks the Runloop thread while it drains, which is what `isMain` is for. */
    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    @Test
    fun framerMarksIsMainWhileItDrains() {
        var insideDrain: Boolean? = null
        val framer = Framer()
        CoroutineScope(framer).launch { insideDrain = Generator.isMain.load() }

        framer.frame()
        assertEquals(true, insideDrain, "isMain is set while the queued block runs")
        assertFalse(Generator.isMain.load(), "and cleared again once the drain finishes")
    }

    /** `gather { }` is the consume-only shape: `yield()` with no argument hands back `true`. */
    @Test
    fun gatherConsumesAndTheBareYieldReturnsTrue() {
        val seen = mutableListOf<Int>()
        val sink = gather<Int> {
            while (true) {
                seen += yield()
            }
        }
        assertEquals(true, sink.start())
        listOf(3, 1, 4).forEach { assertEquals(true, sink.next(it)) }
        assertEquals(listOf(3, 1, 4), seen)
    }

    /** `a + b` on generators is sequential: drain the left, then the right. */
    @Test
    fun generatorPlusIsSequential() {
        val ab = generator<Int> { yield(1); yield(2) } + generator<Int> { yield(3) }
        val out = mutableListOf<Int>()
        var v = ab.start()
        while (v != null) { out += v; v = ab.next(Unit) }
        assertEquals(listOf(1, 2, 3), out)
    }

    /** `a % b` is lockstep: one input to both, a `Pair` out, stopping when either side does. */
    @Test
    fun generatorRemIsLockstepAndStopsWithTheShorterSide() {
        val doubling = generator2<Int, Int> { var x = yield(0); while (true) x = yield(x * 2) }
        val threeOnly = generator2<Int, String> { yield("a"); yield("b"); yield("c") }
        val both = doubling % threeOnly

        val out = mutableListOf<Pair<Int, String>>()
        var p = both.start()
        var i = 1
        while (p != null) { out += p; p = both.next(i); i++ }

        assertEquals(listOf(0 to "a", 2 to "b", 4 to "c"), out)
    }

    /** `pipe` feeds one generator's output in as the next one's input. */
    @Test
    fun pipeFeedsOutputIntoTheNextGeneratorsInput() {
        val source = generator<Int> { yield(1); yield(2); yield(3) }
        val doubling = generator2<Int, Int> { var x = yield(0); while (true) x = yield(x * 2) }

        val out = mutableListOf<Int>()
        val piped = source pipe doubling
        var v = piped.start()
        while (v != null) { out += v; v = piped.next(Unit) }

        assertEquals(listOf(2, 4, 6), out)
    }

    /** `map` transforms the outputs. */
    @Test
    fun mapTransformsTheOutput() {
        val mapped = generator<Int> { yield(1); yield(2) }.map { "n$it" }
        val out = mutableListOf<String>()
        var v = mapped.start()
        while (v != null) { out += v; v = mapped.next(Unit) }
        assertEquals(listOf("n1", "n2"), out)
    }

    /** `EndedException` is declared in `Phased.kt`; nothing in the tree throws or catches it. */
    @Test
    fun endedExceptionIsADeclaredButUnusedThrowable() {
        val e: Throwable = EndedException()
        assertEquals("EndedException", e.javaClass.simpleName)
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
