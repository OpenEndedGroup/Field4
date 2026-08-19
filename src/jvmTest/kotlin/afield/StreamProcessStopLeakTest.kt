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
 * stop cancels the end job before it is dispatched. These two tests ask the question that actually
 * matters: **does a real external process outlive a stop?**
 *
 * They assert current behaviour, so they are a regression fence in both directions. If someone
 * decides `stop` should run the end phase, [stopLeavesTheProcessRunning] fails and names the file
 * to update.
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
     * **The leak, measured.** `Box.stop` bumps `launchEpoch`; `registerLaunchJob` then cancels the
     * end job it is handed; `runner.atEnd` — which is the `proc.destroy()` — never runs. The
     * external process outlives the box that owns it.
     *
     * `streamProcess`'s own KDoc says "ending the Frames will kill the process". Pressing stop does
     * end the Frames. The process is still there.
     */
    @Test
    fun stopLeavesTheProcessRunning() {
        val box = Box("stream-stop-leak")
        val (framer, _, sleeper) = startSleeper(box)

        KotlinExecution().stop(box)
        repeat(60) { framer.frame(); Thread.sleep(5) }

        assertTrue(
            sleeper.isAlive,
            "current behaviour is that stop orphans the process; if this now fails, stop has been " +
                "taught to run the end phase and dev_docs/frames_stop_end_phase.md needs closing out"
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
        assertTrue("proc.destroy()" in source, "outputs.kt's atEnd no longer destroys the process")
        assertEquals(
            1,
            Regex("""\batEnd\s*=\s*suspend\s*\{""").findAll(source).count(),
            "outputs.kt gained another Frames.atEnd installation; the sweep needs redoing"
        )
    }
}
