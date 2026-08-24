package afield.examples

import afield.Box
import afield.OperationTranscript
import afield.SavingContext
import afield.SessionInformation
import afield.thisSessionInfo
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

/*
FIELD_TEST
id: examples.launch.batch-annotation
runner: jvm-main
entrypoint: afield.examples.BatchLaunchAnnotationSmokeTest
tags: [smoke, execution, ui, regression]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "BatchLaunchAnnotationSmokeTest ok"
*/
/**
 * Regression test: a box launched as part of a **batch** (a chord, or consecutive `launch` ops)
 * must keep its "launching" run state continuously until its "launch" state takes over — it must
 * never look idle in between.
 *
 * Reported 2026-08-24: chording several boxes made the FIRST box flash the stripy "compiling and
 * launching" annotation and then go blank for the whole of its compile, only becoming "running"
 * much later — which reads as "that box died". `prepareBatchLaunchRequests` publishes the pending
 * state for every box in the batch up front, but `evaluateInterleavedBatchLaunch` used to clear it
 * at the *start* of each box's own work: the first box, whose queued wait is ~0, therefore showed
 * the marker for a few milliseconds and then nothing, while the boxes still waiting their turn
 * kept theirs and looked fine.
 *
 * `Box.currentRunningStates` is the server-side mirror of that annotation — every write to it is
 * accompanied by the `is-running` message the front end turns into the stripes (see
 * `clearPendingLaunch` and `launchPreparedReturnValue`) — so sampling it while the batch runs
 * tests the sequence the user actually sees. Both boxes here return `loop { }`, so both are
 * expected to reach a real "launch" state.
 *
 * Asserted per box: "launching" is observed, "launch" is observed after it, and there is no
 * idle (state-absent) sample in between. Before the fix the first box's timeline reads
 * `launching -> absent -> launch`, with the absent stretch as long as its compile.
 */
object BatchLaunchAnnotationSmokeTest {

    private const val FIRST_CODE = """var first = 0
loop {
    first += 1
}
"""

    private const val SECOND_CODE = """var second = 0
loop {
    second += 1
}
"""

    private fun buildTranscript(): String = """
name: batch_launch_annotation
description: Regression - a chorded launch keeps each box's launching annotation until it is running.
operations:
  - create:
      box: chord_first
      name: Chord First
      properties:
        code: |
${FIRST_CODE.trimEnd().prependIndent("          ")}
  - create:
      box: chord_second
      name: Chord Second
      properties:
        code: |
${SECOND_CODE.trimEnd().prependIndent("          ")}
  - launch:
      box: chord_first
      property: code
  - launch:
      box: chord_second
      property: code
  - wait:
      millis: 500
""".trimIndent()

    /** One observed change in a box's run state for the default strand; `mode` null means absent. */
    private data class Sample(val atMs: Long, val mode: String?)

    @JvmStatic
    fun main(args: Array<String>) {
        startRunloopDaemon()

        val document = freshSession("batch-launch-annotation")
        val macrosDir = File(document, "macros").also { it.mkdirs() }
        File(macrosDir, "batch_launch_annotation.yaml").writeText(buildTranscript())

        val session = thisSessionInfo.get()
        val watched = listOf("chord_first", "chord_second")
        val timelines = ConcurrentHashMap<String, MutableList<Sample>>()
        watched.forEach { timelines[it] = java.util.Collections.synchronizedList(mutableListOf<Sample>()) }

        val sampling = java.util.concurrent.atomic.AtomicBoolean(true)
        val startedAt = System.currentTimeMillis()
        val sampler = Thread({
            val last = mutableMapOf<String, String?>()
            while (sampling.get()) {
                watched.forEach { id ->
                    val box = session.boxes[id] ?: return@forEach
                    val mode = box.currentRunningStates["default"]?.mode
                    if (!last.containsKey(id) || last[id] != mode) {
                        last[id] = mode
                        timelines[id]!!.add(Sample(System.currentTimeMillis() - startedAt, mode))
                    }
                }
                Thread.sleep(2)
            }
        }, "batch-launch-annotation-sampler")
        sampler.isDaemon = true
        sampler.start()

        val failures = mutableListOf<String>()
        try {
            val run = OperationTranscript.runMacro(document.absolutePath, "batch_launch_annotation")
            Thread.sleep(500)
            sampling.set(false)
            sampler.join(2_000)

            println("run.success=${run.success} applied=${run.applied}")
            run.output.forEach { println("  [${it.kind}] ${it.uid}: ${stripHtml(it.html)}") }

            if (!run.success) {
                failures.add("transcript run failed: ${run.errors.joinToString("; ").ifBlank { run.message }}")
            }

            val errorLines = run.output.filter { it.kind == "error" || it.kind == "err" }
            if (errorLines.isNotEmpty()) {
                failures.add(
                    "box execution emitted ${errorLines.size} error diagnostic(s): " +
                        errorLines.joinToString(" | ") { "${it.uid}:${it.line} ${stripHtml(it.html)}" }
                )
            }

            watched.forEach { id ->
                val timeline = timelines[id]!!.toList()
                println("$id run-state timeline: ${timeline.joinToString(" -> ") { "${it.atMs}ms:${it.mode ?: "absent"}" }}")
                checkTimeline(id, timeline, failures)
            }
        } catch (t: Throwable) {
            failures.add("harness threw ${t.javaClass.simpleName}: ${t.message}")
            t.printStackTrace(System.err)
        } finally {
            sampling.set(false)
            thisSessionInfo.remove()
        }

        if (failures.isNotEmpty()) {
            System.err.println("BatchLaunchAnnotationSmokeTest FAILED with ${failures.size} problem(s):")
            failures.forEach { System.err.println("  - $it") }
            exitProcess(1)
        }
        println("BatchLaunchAnnotationSmokeTest ok")
    }

    private fun checkTimeline(id: String, timeline: List<Sample>, failures: MutableList<String>) {
        val launchingAt = timeline.indexOfFirst { it.mode == "launching" }
        if (launchingAt < 0) {
            failures.add("$id never showed the pending 'launching' state, so the harness did not observe the launch at all")
            return
        }
        val launchAt = timeline.drop(launchingAt).indexOfFirst { it.mode == "launch" }
        if (launchAt < 0) {
            failures.add(
                "$id never reached the 'launch' state after 'launching' (timeline: " +
                    timeline.joinToString(" -> ") { it.mode ?: "absent" } + ")"
            )
            return
        }

        val between = timeline.subList(launchingAt, launchingAt + launchAt)
        val idle = between.filter { it.mode == null }
        if (idle.isNotEmpty()) {
            val firstIdle = idle.first().atMs
            val becameRunning = timeline[launchingAt + launchAt].atMs
            failures.add(
                "$id went back to looking idle for ~${becameRunning - firstIdle}ms between 'launching' and " +
                    "'launch' — its annotation disappears mid-launch, which reads as the box having died " +
                    "(timeline: " + timeline.joinToString(" -> ") { "${it.atMs}ms:${it.mode ?: "absent"}" } + ")"
            )
        }
    }

    private fun freshSession(name: String): File {
        thisSessionInfo.remove()
        val workspace = Files.createTempDirectory("afield-$name").toFile()
        workspace.deleteOnExit()
        val document = File(workspace, "doc").also { it.mkdirs() }
        File(document, SavingContext.METADATA_EDN).writeText("{:version 2 :boxes []}\n")
        val root = Box("root")
        val session = SessionInformation(
            filename = document.absolutePath,
            documentName = document.name,
            boxes = ConcurrentHashMap(),
            root = root,
            ephemeral = true,
        )
        root.inside = session
        session.boxes[root.id] = root
        thisSessionInfo.set(session)
        return document
    }

    private fun startRunloopDaemon() {
        val t = Thread({ afield.Runloop.runLoop.start() }, "batch-launch-annotation-harness-runloop")
        t.isDaemon = true
        t.start()
        val deadline = System.currentTimeMillis() + 30_000
        while (afield.Runloop.runLoop.frame < 1L) {
            check(System.currentTimeMillis() < deadline) { "runloop did not start within 30s" }
            Thread.sleep(20)
        }
        println("BatchLaunchAnnotationSmokeTest: runloop up (frame=${afield.Runloop.runLoop.frame})")
    }

    private fun stripHtml(html: String): String =
        html.replace(Regex("<[^>]+>"), " ").replace("&lt;", "<").replace("&gt;", ">").trim()
}
