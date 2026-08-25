package afield.examples

import afield.Box
import afield.Execute
import afield.KotlinExecution
import afield.Message
import afield.SavingContext
import afield.SessionInformation
import afield.box
import afield.offline
import afield.thisSessionInfo
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

/*
FIELD_TEST
id: examples.launch.batch-sequencing
runner: jvm-main
entrypoint: afield.examples.BatchLaunchSequencingSmokeTest
tags: [smoke, execution, regression]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "BatchLaunchSequencingSmokeTest ok"
*/
/**
 * Regression test: a second chord fired while the first is still running must *extend* the launch
 * sequence, not race it.
 *
 * Reported 2026-08-24: chording one group of boxes sequences them correctly, but chording a second
 * group before the first finished let a quick-compiling box in the second group evaluate ahead of
 * boxes still queued in the first — which, when those boxes are parents of the ones that jumped the
 * queue, reliably errors.
 *
 * Cause: within a batch [KotlinExecution.runPreparedBatchLaunches] serializes boxes, precisely so a
 * later box's macro expansion and compile observe an earlier box's runtime effects. That guarantee
 * stopped at the batch boundary — each `launch-batch` was handed to its own `GlobalScope.launch`, so
 * two batches ran concurrently and only the *within*-batch order was honoured. Batches now queue
 * through a single consumer.
 *
 * Two scenarios, both fired back to back the way the front end does, and both asserting the
 * observed eval order (each box prints at eval time; `reply` messages are recorded in arrival
 * order):
 *
 *  1. chord, then chord — two `launchBatch` calls, as `monacoLaunchBatch` sends for two multi-box
 *     selections.
 *  2. chord, then a single box — a `launchBatch` followed by `launchAll`, which is what the front
 *     end actually sends when the second selection is ONE box: `canvasGestures` calls `launchAll`
 *     for `size == 1` (Main.kt). Reported 2026-08-24 with a screenshot: a row of three boxes
 *     chorded, then a single box below it, and the single box ran before the slow-to-compile second
 *     box of the first row. That path used to go straight to the runloop via `inMain`, never
 *     entering the queue at all, so fixing the batch-to-batch case alone did not fix it.
 *
 * Engines are warmed before either batch is fired, so the ordering being measured is the launch
 * queue's and not a race between four cold `ki` bootstraps.
 */
object BatchLaunchSequencingSmokeTest {

    /** Slow to *compile* rather than slow to run: nothing here blocks the runloop. */
    private fun slowCode(label: String): String {
        val lines = (0 until 140).joinToString("\n") { i ->
            "val v$i = kotlin.math.sqrt($i.0 + 1.0) * kotlin.math.sin($i.0) + kotlin.math.cos($i.0 / 3.0)"
        }
        return "$lines\nprintln(\"EVAL $label sum=\" + (v0 + v139))\n"
    }

    private fun quickCode(label: String): String = "println(\"EVAL $label\")\n"

    private val firstGroup = listOf("seq_a", "seq_b", "seq_c")
    private const val SECOND_GROUP_BOX = "seq_late"

    // The reported shape: a row of three chorded, then a single box below it.
    private val secondRound = listOf("row_a", "row_b", "row_c")
    private const val SINGLE_LAUNCH_BOX = "row_single"

    @JvmStatic
    fun main(args: Array<String>) {
        startRunloopDaemon()

        val document = freshSession("batch-launch-sequencing")
        val session = thisSessionInfo.get()

        val quick = setOf(SECOND_GROUP_BOX, SINGLE_LAUNCH_BOX)
        val boxes = (firstGroup + SECOND_GROUP_BOX + secondRound + SINGLE_LAUNCH_BOX).map { id ->
            val b = box(id, createIfMissing = true, initIfCreated = true, root = session)
            b.setPropertyRaw("name", id, doResync = false)
            b.setPropertyRaw("code", if (id in quick) quickCode(id) else slowCode(id), doResync = false)
            session.root.connect(b)
            b
        }

        warmEngines(boxes)

        // Every message the launches reply with, in arrival order.
        val transcript: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
        val reply: (Message) -> Unit = { message ->
            transcript.add(runCatching { Json.encodeToString(Message.serializer(), message) }.getOrDefault(""))
        }

        val failures = mutableListOf<String>()
        try {
            // --- 1) chord, then chord: two multi-box selections -------------------------------
            KotlinExecution.launchBatch(firstGroup.map { id -> session.boxes[id]!! to execFor(id) }, reply)
            KotlinExecution.launchBatch(listOf(session.boxes[SECOND_GROUP_BOX]!! to execFor(SECOND_GROUP_BOX)), reply)
            checkOrder(
                "chord then chord",
                firstGroup + SECOND_GROUP_BOX,
                SECOND_GROUP_BOX,
                transcript,
                failures
            )

            // --- 2) chord, then ONE box: `launchAll`, the path a size-1 selection takes -------
            transcript.clear()
            KotlinExecution.launchBatch(secondRound.map { id -> session.boxes[id]!! to execFor(id) }, reply)
            val singleBox = session.boxes[SINGLE_LAUNCH_BOX]!!
            val singleExec = execFor(SINGLE_LAUNCH_BOX)
            singleBox.ke.launchAll(singleBox, singleExec, singleExec.code, "default", reply)
            checkOrder(
                "chord then single-box launch",
                secondRound + SINGLE_LAUNCH_BOX,
                SINGLE_LAUNCH_BOX,
                transcript,
                failures
            )

            val errors = transcript.filter { it.contains("\"kind\":\"error\"") || it.contains("\"kind\":\"err\"") }
            if (errors.isNotEmpty()) {
                failures.add("launches emitted ${errors.size} error message(s): ${errors.take(3)}")
            }
        } catch (t: Throwable) {
            failures.add("harness threw ${t.javaClass.simpleName}: ${t.message}")
            t.printStackTrace(System.err)
        } finally {
            thisSessionInfo.remove()
        }

        if (failures.isNotEmpty()) {
            System.err.println("BatchLaunchSequencingSmokeTest FAILED with ${failures.size} problem(s):")
            failures.forEach { System.err.println("  - $it") }
            exitProcess(1)
        }
        println("BatchLaunchSequencingSmokeTest ok")
    }

    /** Wait for every box in [expected] to report, then assert they reported in that order. */
    private fun checkOrder(
        scenario: String,
        expected: List<String>,
        lateBox: String,
        transcript: MutableList<String>,
        failures: MutableList<String>
    ) {
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            if (expected.all { label -> transcript.toList().any { it.contains("EVAL $label") } }) break
            Thread.sleep(20)
        }

        val observed = observedEvalOrder(transcript.toList(), expected)
        println("[$scenario] observed eval order: $observed")
        println("[$scenario] expected eval order: $expected")

        val missing = expected - observed.toSet()
        if (missing.isNotEmpty()) {
            failures.add("[$scenario] these boxes never reported evaluating within 120s: $missing")
            return
        }
        if (observed != expected) {
            val at = observed.indexOf(lateBox) + 1
            failures.add(
                "[$scenario] the second launch did not extend the first one's sequence: observed " +
                    "$observed but expected $expected — '$lateBox' evaluated at position $at of " +
                    "${observed.size}, ahead of boxes still queued ahead of it, which is exactly the " +
                    "race a child box cannot survive"
            )
        }
    }

    /** First appearance of each box's eval print, in the order the replies arrived. */
    private fun observedEvalOrder(transcript: List<String>, labels: List<String>): List<String> {
        val seen = mutableListOf<String>()
        transcript.forEach { message ->
            labels.forEach { label ->
                if (label !in seen && message.contains("EVAL $label")) seen.add(label)
            }
        }
        return seen
    }

    private fun execFor(id: String): Execute = Execute(
        uid = id,
        type = "launch",
        line = 0,
        code = thisSessionInfo.get().boxes[id]!!.getPropertyRaw("code", "") as String,
        singleLine = false,
        property = "code",
        strand = "default"
    )

    /**
     * Force each box's Kotlin engine up and wait for its stdlib intro, so the batches are not
     * measuring four concurrent cold bootstraps instead of the queue.
     */
    private fun warmEngines(boxes: List<Box>) {
        boxes.forEach { it.ki }
        val deadline = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < deadline) {
            if (boxes.none { it.offline }) {
                println("warmed ${boxes.size} engine(s)")
                return
            }
            Thread.sleep(50)
        }
        System.err.println("warning: engines still offline after 180s: ${boxes.filter { it.offline }.map { it.id }}")
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
        val t = Thread({ afield.Runloop.runLoop.start() }, "batch-launch-sequencing-harness-runloop")
        t.isDaemon = true
        t.start()
        val deadline = System.currentTimeMillis() + 30_000
        while (afield.Runloop.runLoop.frame < 1L) {
            check(System.currentTimeMillis() < deadline) { "runloop did not start within 30s" }
            Thread.sleep(20)
        }
        println("BatchLaunchSequencingSmokeTest: runloop up (frame=${afield.Runloop.runLoop.frame})")
    }
}
