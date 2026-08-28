package afield

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
     * The three hooks are the whole contract a launcher installs: [Frames.shouldend] is the strand
     * generation fence, [Frames.onAtEndStart] registers the end-phase job with the box through
     * [Box.registerEndPhaseJob], and it re-arms [Frames.shouldCancelAtEnd] against the generation as
     * it stands once the end phase has begun, so a *second* stop can cut a multi-frame end block
     * short. Kept in step with `registerFramesReturnValue`.
     */
    private fun launchAgainst(box: Box, frames: Frames, strand: String = "default"): Pair<Framer, kotlinx.coroutines.Job> {
        val launchEpoch = box.captureLaunchEpoch()
        val generation = box.runGeneration.compute(strand) { _, current -> (current ?: -1) + 1 }!!
        frames.shouldend = { box.runGeneration[strand] != generation }
        frames.onAtEndStart = { endJob ->
            val endingGeneration = box.runGeneration[strand] ?: (generation + 1)
            frames.shouldCancelAtEnd = { box.runGeneration[strand] != endingGeneration }
            box.registerEndPhaseJob(endJob)
        }
        val framer = Framer()
        val job = CoroutineScope(framer).launch { runCatching { frames.body() } }
        frames.job = job
        box.registerLaunchJob(job, launchEpoch)
        return framer to job
    }

    /**
     * **`Box.stop` runs `.end { }`.**
     *
     * It did not until 2026-08-28, and the reason is not visible from `Phased.kt` alone. `frame()`
     * launches the end phase on cancellation, then calls `onAtEndStart`, which registers the end job
     * with the box. That registration used to pass the *launch* epoch — but `stopTrackedJobs`
     * increments `launchEpoch` before it cancels anything, so the epoch was already stale and
     * `registerLaunchJob` *cancelled the job it was handed*, while it was still queued on the
     * Framer. [Box.registerEndPhaseJob] re-captures the epoch instead, because it is stop's own
     * cancellation that creates this job. See `dev_docs/frames_stop_end_phase.md`.
     */
    @Test
    fun boxStopStillRunsTheEndBlock() {
        val box = Box("stop-runs-end")
        var cleaned = false
        val f = eachFrame { }.end { cleaned = true }
        val (framer, _) = launchAgainst(box, f)

        repeat(3) { framer.frame() }
        KotlinExecution().stop(box)
        repeat(8) { framer.frame() }

        assertTrue(cleaned, "the end job registers against the epoch as it stands after the stop")
    }

    /**
     * A `.end { }` that calls `frame()` gets those frames, *after* stop has returned.
     *
     * This is the half of the contract that makes the end phase a place to put a wind-down rather
     * than only a final assignment, and it is the half that stop cannot be a synchronous barrier
     * for: the end block runs on Runloop ticks after `stop` returned. Ten `frame()` calls, so the
     * last value written is the tenth.
     */
    @Test
    fun theEndBlockKeepsItsFramesAfterAStop() {
        val box = Box("stop-multi-frame-end")
        var x = 0
        val f = loop { x++ }.end {
            x = 20
            repeat(10) { i ->
                frame()
                x = 30 + i
            }
        }
        val (framer, _) = launchAgainst(box, f)

        repeat(3) { framer.frame() }
        KotlinExecution().stop(box)
        assertEquals(3, x, "stop is not synchronous for the end phase: nothing has run yet")
        repeat(20) { framer.frame() }

        assertEquals(39, x, "the end block must get all ten of its frames")
    }

    /**
     * A *second* stop, arriving while a multi-frame end block is mid-flight, cuts it short.
     *
     * The end phase is not unkillable — it is registered in `box.jobs` like anything else, and
     * `Frames.shouldCancelAtEnd` re-fences it against the generation as it stood when it began. Both
     * routes point the same way; this pins that a runaway end block cannot ignore the stop button.
     */
    @Test
    fun aSecondStopCutsTheEndBlockShort() {
        val box = Box("stop-twice")
        var reached = 0
        val f = eachFrame { }.end { repeat(100) { frame(); reached++ } }
        val (framer, _) = launchAgainst(box, f)

        repeat(3) { framer.frame() }
        KotlinExecution().stop(box)
        repeat(4) { framer.frame() }
        val partway = reached
        assertTrue(partway in 1..99, "the end block should be mid-flight here, not $partway")

        KotlinExecution().stop(box)
        repeat(20) { framer.frame() }
        assertTrue(
            reached < 100,
            "a second stop must cancel the end phase; it ran to completion ($reached)"
        )
    }

    /**
     * The same thing again, but through the **production** launch path rather than a mirror of it.
     *
     * Every other case here wires the launcher's hooks by hand, which is what makes them readable —
     * and is also how the stop/end-phase defect survived: the hand-wiring agreed with the code it
     * copied. This case returns a `Frames` to [ReturnValueRegistry.launch] exactly as a box does,
     * pumps `Runloop.runLoop.main` (itself a [Framer]) by hand instead of starting the Runloop
     * thread, and presses stop through [KotlinExecution.stop]. It is the whole user-visible claim:
     *
     * ```
     * var x = 0
     * loop { x++ }.end { x = 20; 10 { frame(); x = 30 + it } }
     * ```
     *
     * launch, stop, and `x` is 39.
     */
    @Test
    fun theRealLaunchPathRunsAMultiFrameEndBlockOnStop() {
        val box = Box("real-path-stop-end")
        var x = 0
        val f = loop { x++ }.end {
            x = 20
            repeat(10) { i ->
                frame()
                x = 30 + i
            }
        }
        val runloop = Runloop.runLoop.main

        val handled = ReturnValueRegistry.launch(box, f, "default") { _, _, _ -> }
        assertTrue(handled, "a Frames must be claimed by registerFramesReturnValue")

        repeat(6) { runloop.frame() }
        assertTrue(x > 0, "the body should be running before we stop it, not $x")

        KotlinExecution().stop(box)
        // Stop cleared the running states on its way out; the end phase puts one back, which is how
        // the front end knows the box is winding down rather than idle (`syncExecutionStatus` reads
        // this map, not the launch's notify callback).
        val modesDuringEnd = mutableSetOf<String?>()
        repeat(30) {
            runloop.frame()
            modesDuringEnd += box.currentRunningStates["default"]?.mode
        }

        assertEquals(39, x, "stop must run the end block, frames and all")
        assertTrue("ending" in modesDuringEnd, "the box should report the end phase: $modesDuringEnd")
        assertEquals(null, box.currentRunningStates["default"], "and be idle once it finishes")
    }

    /**
     * The end phase must not report *its* generation as current when a newer launch already holds
     * the strand.
     *
     * Newly reachable: before stop ran the end phase, a stop-then-relaunch — which is what the docs
     * capture's `restartTarget` does — had nothing left of the old launch to report. Now the old end
     * phase starts while the new launch is already on record, and `execution.kt` puts that record in
     * place *synchronously* at launch preparation, before it compiles anything. Overwriting it with
     * the older generation makes the strand read as the previous launch winding down for as long as
     * the compile takes, and a capture gate waiting on `watermark > stopped` waits with it.
     *
     * This is a reporting guard, not a correctness one: the removal at the end of the end phase keys
     * on its own generation and so has always declined to delete a newer entry.
     */
    @Test
    fun theEndPhaseDoesNotReportOverANewerLaunch() {
        val box = Box("end-phase-vs-newer-launch")
        var cleaned = false
        val runloop = Runloop.runLoop.main

        ReturnValueRegistry.launch(box, loop { }.end { cleaned = true }, "default") { _, _, _ -> }
        repeat(3) { runloop.frame() }
        val oldGeneration = box.runGeneration["default"]!!

        KotlinExecution().stop(box)
        // What a relaunch's preparation does, synchronously, before the old end phase gets a tick.
        val newer = RunningState(oldGeneration + 5, "frames")
        box.currentRunningStates["default"] = newer

        repeat(10) { runloop.frame() }

        assertTrue(cleaned, "the end block must still run — the guard is about reporting, not running")
        assertEquals(
            newer.watermark,
            box.currentRunningStates["default"]?.watermark,
            "the end phase must leave a newer launch's record alone"
        )
        assertEquals("frames", box.currentRunningStates["default"]?.mode)
        box.stopTrackedJobs()
    }

    /**
     * Re-running the box — a generation bump with no epoch change — runs `.end { }` too.
     *
     * The contrast with [boxStopStillRunsTheEndBlock] used to be the whole point: the two stops a
     * user reaches for most differed in whether cleanup happened at all. They now agree, and this
     * case is what pins the route that always worked.
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

    /**
     * **The leak `Frames.plus` used to have.** A child's end block must be stoppable.
     *
     * A launcher installs its fences on the `Frames` it is handed — the combined one — and the
     * children are reached only through `body()`. So they kept the defaults, and
     * `onAtEndStart = {}` meant a child's end job was never registered with the box: nothing could
     * cancel it. Measured before the fix, this ticked 18 -> 58 -> 98 and kept going through every
     * stop. `Frames.plus` now forwards all four fences, so the end job lands in `box.jobs` and the
     * second stop reaches it, exactly as it does for a top-level end block.
     */
    @Test
    fun aRunawayChildEndBlockIsKillable() {
        val box = Box("plus-runaway-child")
        var ticks = 0
        val runloop = Runloop.runLoop.main
        val combined = eachFrame { }.end { while (true) { frame(); ticks++ } } + eachFrame { }

        ReturnValueRegistry.launch(box, combined, "default") { _, _, _ -> }
        repeat(6) { runloop.frame() }
        KotlinExecution().stop(box)
        repeat(20) { runloop.frame() }

        assertTrue(ticks > 0, "the child end block should be running after the first stop")
        assertTrue(box.jobs.isNotEmpty(), "and its end job must be registered with the box")

        KotlinExecution().stop(box)
        repeat(20) { runloop.frame() }
        val settled = ticks
        repeat(40) { runloop.frame() }

        assertEquals(settled, ticks, "a second stop must stop a child's end block, not just the parent's")
    }

    /** All three end blocks of `(a.end + b.end).end` run on stop, and every end job drains. */
    @Test
    fun bothChildrenAndTheParentRunTheirEndBlocks() {
        val box = Box("plus-three-ends")
        var left = false
        var right = false
        var parent = false
        val runloop = Runloop.runLoop.main
        val combined = (eachFrame { }.end { left = true } + eachFrame { }.end { right = true })
            .end { parent = true }

        ReturnValueRegistry.launch(box, combined, "default") { _, _, _ -> }
        repeat(6) { runloop.frame() }
        KotlinExecution().stop(box)
        repeat(30) { runloop.frame() }

        assertTrue(left && right && parent, "left=$left right=$right parent=$parent")
        assertTrue(box.jobs.isEmpty(), "every end job must be removed once it finishes: ${box.jobs.size} left")
        assertEquals(null, box.currentRunningStates["default"], "and the strand must not be left reporting")
    }

    /**
     * The forwarding itself, directly: a fence set on `a + b` is visible through both children.
     *
     * This is the discriminating test for `Frames.plus`'s hook forwarding, and it is a flat
     * assertion rather than a behavioural one on purpose. The behavioural cases below and above
     * mostly cannot tell the two versions apart — children were already cancelled *indirectly*, by
     * the parent's `finally`, so "the end block ran" and "the body stopped" look identical either
     * way. Only [aRunawayChildEndBlockIsKillable] and this test fail when the forwarding is removed.
     *
     * The forwarding has to be lazy, and that is what the reassignment here checks: a launcher
     * installs these hooks *after* `plus` has returned, so a child holding a copy would hold the
     * defaults forever.
     */
    @Test
    fun plusForwardsTheParentsFencesToBothChildren() {
        val left = eachFrame { }
        val right = eachFrame { }
        val parent = left + right

        // Installed after `plus` returned, exactly as a launcher does it.
        parent.shouldend = { true }
        parent.shouldCancelAtEnd = { true }
        val registered = mutableListOf<String>()
        parent.onAtEndStart = { registered += "start" }
        parent.onAtEndFinish = { registered += "finish" }

        listOf("left" to left, "right" to right).forEach { (name, child) ->
            assertTrue(child.shouldend(), "$name child does not see the parent's shouldend")
            assertTrue(child.shouldCancelAtEnd(), "$name child does not see the parent's shouldCancelAtEnd")
            child.onAtEndStart(Job())
            child.onAtEndFinish(Job())
        }
        assertEquals(
            listOf("start", "finish", "start", "finish"),
            registered,
            "both children must route their end-phase bracket through the parent, which is what " +
                "registers the end job with the box"
        )
    }

    /**
     * On a re-run, both children of `a + b` stop and both end blocks run, and every job drains.
     *
     * An invariant rather than a discriminating test — see
     * [plusForwardsTheParentsFencesToBothChildren] for why. Worth keeping because it is the case a
     * person actually stages (re-run a box holding a composed animation), and it would catch a
     * regression that left a child running or an end job in `box.jobs`.
     */
    @Test
    fun aReRunStopsBothChildrenOfPlusAndRunsTheirEndBlocks() {
        val box = Box("plus-rerun")
        var leftCalls = 0
        var rightCalls = 0
        var leftEnded = false
        var rightEnded = false
        val runloop = Runloop.runLoop.main
        val combined = eachFrame { leftCalls++ }.end { leftEnded = true } +
            eachFrame { rightCalls++ }.end { rightEnded = true }

        ReturnValueRegistry.launch(box, combined, "default") { _, _, _ -> }
        repeat(6) { runloop.frame() }
        val atReRun = leftCalls to rightCalls
        // What a second launch on the same strand does, and nothing else: no epoch change.
        box.runGeneration.compute("default") { _, current -> (current ?: -1) + 1 }
        repeat(20) { runloop.frame() }

        assertEquals(atReRun.first + 1, leftCalls, "the left child should stop one body call later")
        assertEquals(atReRun.second + 1, rightCalls, "and so should the right")
        assertTrue(leftEnded && rightEnded, "both children run their end block")
        assertTrue(box.jobs.isEmpty(), "and nothing is left registered: ${box.jobs.size}")
        box.stopTrackedJobs()
    }

    /**
     * **`frame()`'s `Boolean` is not a stop signal — it is never `false`.**
     *
     * Every route that stops a frame body cancels the job *before* the `yield()` that `frame()` is
     * about to suspend on, so the yield throws and `frame()` does not return at all. The local `r`
     * that `frame()` computes can only ever be returned as `true`. That is why `loop`'s `while (true)`
     * gives up nothing over `eachFrame`'s `while (frame())`, and why `forever { }`'s own `Boolean` is
     * the only return-based way to end a frame program.
     *
     * This is a characterization of a consequence, not a designed contract: it follows from
     * `job?.cancel()` preceding `yield()`. If that order ever changes, `eachFrame` starts exiting
     * through its loop condition instead of through the `catch` — and the end phase lives in the
     * `catch`, so `.end { }` would stop running. Hence pinning it.
     */
    @Test
    fun frameNeverReturnsFalse() {
        fun outcome(stage: (Frames, kotlinx.coroutines.Job, Framer) -> Unit): String {
            var result = "never finished"
            val f = frames {
                try {
                    while (true) {
                        if (!frame()) { result = "returned false"; break }
                    }
                } catch (t: Throwable) {
                    result = "threw ${t.javaClass.simpleName}"
                    throw t
                }
            }
            val framer = Framer()
            val job = CoroutineScope(framer).launch { runCatching { f.body() } }
            f.job = job
            repeat(3) { framer.frame() }
            stage(f, job, framer)
            repeat(4) { framer.frame() }
            job.cancel()
            return result
        }

        // The three ways a frame body is stopped, per the page's "What stops one" table.
        assertEquals("threw JobCancellationException", outcome { f, _, _ -> f.shouldend = { true } },
            "the generation-fence route")
        assertEquals("threw JobCancellationException", outcome { _, job, _ -> job.cancel() },
            "the direct cancel route, which is what Box.stop does")
        assertEquals("threw JobCancellationException",
            outcome { f, _, _ -> kotlinx.coroutines.runBlocking { f.end() } },
            "end() from inside the body")
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
