package afield

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.lang.ProcessHandle
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Measures what `Box.stop` does to the one production user of [Frames.atEnd].
 *
 * `Box.streamProcess` (outputs.kt) documents itself as *"ending the Frames will kill the process"*,
 * and it keeps that promise by installing `runner.atEnd = { proc.destroy() … }`. That is the only
 * assignment to `Frames.atEnd` anywhere in `src/` outside `Phased.kt` itself.
 *
 * [FramesSemanticsTest.boxStopCancelsTheEndBlockBeforeItCanRun] shows in the abstract that a full
 * stop cancels the end job before it is dispatched — **and that is still true**. These tests ask the
 * question that actually matters: **does a real external process outlive a stop?** It used to. It
 * does not now, because the kill moved off the end phase and onto `Box.stopHooks`, which
 * `stopTrackedJobs` drains.
 *
 * The pairing is the point: [stopKillsTheProcess] and [reRunningTheBoxKillsTheProcess] reach the
 * same outcome by two different routes — a stop hook and the end phase respectively — so a
 * regression in either route fails one test and not the other.
 */
class StreamProcessStopLeakTest {

    private val spawned = mutableListOf<ProcessHandle>()

    @AfterTest
    fun reapAnythingThisTestLeaked() {
        // These tests exist because a process can outlive its owner. Do not let that escape the
        // test run: whatever is still alive gets killed here, leak or no leak.
        spawned.forEach { handle ->
            if (handle.isAlive) {
                handle.destroy()
                if (handle.isAlive) handle.destroyForcibly()
            }
        }
        spawned.clear()
    }

    private fun descendantPids(): Set<Long> =
        ProcessHandle.current().descendants().map { it.pid() }.toList().toSet()

    /**
     * Launches a `sleep` through [Box.streamProcess], pumps until the child actually exists, and
     * returns its handle alongside the harness so the caller can stage a stop.
     */
    private fun startSleeper(box: Box): Triple<Framer, Job, ProcessHandle> {
        val before = descendantPids()
        val frames = box.streamProcess("sleeper", listOf("/bin/sleep", "45"), channel = "sleeper-out")

        val launchEpoch = box.captureLaunchEpoch()
        val generation = box.runGeneration.compute("default") { _, current -> (current ?: -1) + 1 }!!
        frames.shouldend = { box.runGeneration["default"] != generation }
        frames.onAtEndStart = { endJob -> box.registerLaunchJob(endJob, launchEpoch) }
        val framer = Framer()
        val job = CoroutineScope(framer).launch { runCatching { frames.body() } }
        frames.job = job
        box.registerLaunchJob(job, launchEpoch)

        // Pump until the child shows up. Fail loudly rather than silently measuring nothing:
        // if `display` or anything else blew up inside the body, there is no process and the
        // leak assertion below would be vacuous.
        var handle: ProcessHandle? = null
        for (attempt in 0 until 400) {
            framer.frame()
            val fresh = descendantPids() - before
            if (fresh.isNotEmpty()) {
                handle = ProcessHandle.of(fresh.first()).orElse(null)
                if (handle != null) break
            }
            Thread.sleep(5)
        }
        val started = requireNotNull(handle) {
            "streamProcess never produced a child process, so this test measured nothing"
        }
        spawned += started
        assertTrue(started.isAlive, "the sleeper should be alive before we stop anything")
        return Triple(framer, job, started)
    }

    /**
     * **The leak, fixed.** `Box.stop` still bumps `launchEpoch` and `registerLaunchJob` still
     * cancels the end job — that fence is deliberate — so `runner.atEnd` still does not run. The
     * process dies anyway, because `streamProcess` registers a `Box.stopHooks` entry that
     * `stopTrackedJobs` drains synchronously.
     *
     * This asserted the opposite until 2026-08-19; see `dev_docs/frames_stop_end_phase.md` for the
     * measurement that produced the fix and for why the end phase itself was left alone.
     */
    @Test
    fun stopKillsTheProcess() {
        val box = Box("stream-stop-kills")
        val (framer, _, sleeper) = startSleeper(box)

        KotlinExecution().stop(box)
        // The hook runs synchronously inside stop, so the kill has already happened here; the pump
        // only gives the OS time to reap, so `isAlive` reflects it.
        for (attempt in 0 until 60) {
            if (!sleeper.isAlive) break
            framer.frame()
            Thread.sleep(5)
        }

        assertTrue(
            !sleeper.isAlive,
            "stop must kill the child: the end phase is still cancelled by the epoch fence, so the " +
                "Box.stopHooks drain in stopTrackedJobs is the only thing that can do it"
        )
    }

    /**
     * The hook must not outlive the launch that installed it. A process that ends on its own runs
     * the end phase, which unregisters — otherwise every `streamProcess` call would leave a stale
     * entry on the box, and a later stop would walk a list of dead processes forever.
     */
    @Test
    fun theStopHookIsUnregisteredWhenTheProcessEndsOnItsOwn() {
        val box = Box("stream-hook-unregister")
        val (framer, _, quick) = startSleeper(box)
        assertEquals(1, box.stopHooks.size, "streamProcess should have installed exactly one hook")

        // Let it exit on its own terms rather than through stop, then pump so the end phase runs.
        quick.destroy()
        for (attempt in 0 until 400) {
            framer.frame()
            if (box.stopHooks.isEmpty()) break
            Thread.sleep(5)
        }

        assertEquals(
            0,
            box.stopHooks.size,
            "the end phase ran, so its stop hook is stale and must have been unregistered"
        )
    }

    /**
     * The control, and the contrast: re-running the box bumps only the strand generation, the end
     * job registers, `atEnd` runs, and the very same process is killed.
     */
    @Test
    fun reRunningTheBoxKillsTheProcess() {
        val box = Box("stream-rerun-kills")
        val (framer, _, sleeper) = startSleeper(box)

        // Exactly what a second launch on the same strand does, and nothing else.
        box.runGeneration.compute("default") { _, current -> (current ?: -1) + 1 }
        for (attempt in 0 until 200) {
            framer.frame()
            if (!sleeper.isAlive) break
            Thread.sleep(5)
        }

        assertTrue(
            !sleeper.isAlive,
            "a generation bump leaves launchEpoch alone, so atEnd runs and the process is destroyed"
        )
    }

    /** Guards the premise: `streamProcess` is still the thing that installs the killing `atEnd`. */
    @Test
    fun streamProcessIsStillTheOnlyProductionUserOfAtEnd() {
        val source = java.nio.file.Path.of("src/jvmMain/kotlin/afield/outputs.kt").toFile().readText()
        assertTrue(
            "runner.atEnd = suspend {" in source,
            "outputs.kt no longer installs Frames.atEnd; re-run the sweep in " +
                "dev_docs/frames_stop_end_phase.md before trusting its caller count"
        )
        assertTrue("proc.destroy()" in source, "outputs.kt no longer destroys the process")
        assertTrue(
            "registerStopHook" in source,
            "outputs.kt no longer registers a stop hook, so a stop would orphan the child again — " +
                "this is the fix dev_docs/frames_stop_end_phase.md describes"
        )
        assertEquals(
            1,
            Regex("""\batEnd\s*=\s*suspend\s*\{""").findAll(source).count(),
            "outputs.kt gained another Frames.atEnd installation; the sweep needs redoing"
        )
    }
}
