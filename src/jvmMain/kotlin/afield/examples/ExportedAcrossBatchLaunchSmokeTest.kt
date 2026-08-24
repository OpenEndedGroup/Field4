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
id: examples.exports.batch-launch
runner: jvm-main
entrypoint: afield.examples.ExportedAcrossBatchLaunchSmokeTest
tags: [smoke, exports, context, language, regression]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "ExportedAcrossBatchLaunchSmokeTest ok"
*/
/**
 * Regression test: `@exported` must publish when a box is launched as part of a **batch**
 * (a chord — alt-drag over several boxes — or consecutive `launch` ops in a transcript).
 *
 * Reported 2026-08-24: chording an exporting box and a reading box together ran the exporter
 * but left the reader unable to resolve the exported `_a`, and re-launching the reader did not
 * help; only re-running the *exporter* through a non-batch path (its own launch, or alt-0 /
 * alt-enter execute) made `_a` appear.
 *
 * Cause: `@exported` is published as a side effect of `KotlinInterface2.ContextUpdater.update()`,
 * which only runs when a snippet is evaluated with `updateContext = true`. The exec path
 * (`standardExecutionPractice` -> `exec` -> `evaluateInternal(updateContext = true)`) does that;
 * `KotlinExecution.evaluateInterleavedBatchLaunch` called `PreparedSnippet.eval(...)` without the
 * flag, and it defaults to **false** — so a batch launch refreshed neither `context.vars` nor
 * `@exported`. (The next `exec` on that engine flushed the backlog, which is why re-running the
 * exporter once "fixed" it: its second launch re-executed the preamble through `exec`.)
 *
 * The reader box here declares nothing: it resolves `_a` only if the exporter's launch published
 * the export, wrote the `var Box._a by InheritanceFromContext<...>() /**/` line into its own
 * preamble, and the reader inherited that line — the whole chain, through the real engine, in one
 * batch. Before the fix this fails at the reader's *compile* (`Unresolved reference '_a'`); with a
 * saved document whose exporter preamble already carries the line (the shape the report was filed
 * with) the same cause instead surfaces at runtime as a missing inherited property. Both are
 * covered by asserting the reader printed the value and that no box emitted an error.
 */
object ExportedAcrossBatchLaunchSmokeTest {

    private const val SOURCE_CODE = """@exported
var a = 10.0
println("source ready a=${'$'}a")
"""

    private const val READER_CODE = """println("reader a=${'$'}{_a}")
"""

    private fun buildTranscript(): String = """
name: exported_across_batch_launch
description: Regression - a chorded (batch) launch must publish @exported so a sibling box can read it.
operations:
  - create:
      box: exp_source
      name: Export Source
      properties:
        code: |
${SOURCE_CODE.trimEnd().prependIndent("          ")}
  - create:
      box: exp_reader
      name: Export Reader
      properties:
        code: |
${READER_CODE.trimEnd().prependIndent("          ")}
  - connect:
      parent: exp_source
      child: exp_reader
  - launch:
      box: exp_source
      property: code
  - launch:
      box: exp_reader
      property: code
  - wait:
      millis: 500
""".trimIndent()

    @JvmStatic
    fun main(args: Array<String>) {
        startRunloopDaemon()

        val document = freshSession("exports-batch-launch")
        val macrosDir = File(document, "macros").also { it.mkdirs() }
        File(macrosDir, "exported_across_batch_launch.yaml").writeText(buildTranscript())

        val failures = mutableListOf<String>()
        try {
            val run = OperationTranscript.runMacro(document.absolutePath, "exported_across_batch_launch")

            println("run.success=${run.success} applied=${run.applied} output lines=${run.output.size}")
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

            val text = run.output.joinToString("\n") { stripHtml(it.html) }

            // The exporter ran at all (guards against a harness problem being read as the regression).
            assertContains(
                text,
                "source ready a=10.0",
                "the exporting box did not run in the batch launch",
                failures
            )

            // The regression itself: the reader resolves the exported alias in the SAME batch.
            assertContains(
                text,
                "reader a=10.0",
                "the reader box did not see `_a` from the exporting box launched in the same batch — " +
                    "@exported was not published by the batch-launch path (PreparedSnippet.eval " +
                    "defaults to updateContext=false, so ContextUpdater.update() never ran)",
                failures
            )

            // The export's preamble declaration must have been written by the launch, not only by exec.
            val source = thisSessionInfo.get().boxes.values.firstOrNull { it.name == "Export Source" }
            val preamble = source?.getPropertyRaw("preamble", "") as? String ?: ""
            println("source preamble: ${preamble.replace("\n", " | ")}")
            if (!preamble.contains("Box._a by InheritanceFromContext<")) {
                failures.add(
                    "the exporting box's preamble did not gain the `_a` declaration during a batch " +
                        "launch (found: \"${preamble.replace("\n", " | ")}\")"
                )
            }
        } catch (t: Throwable) {
            failures.add("harness threw ${t.javaClass.simpleName}: ${t.message}")
            t.printStackTrace(System.err)
        } finally {
            thisSessionInfo.remove()
        }

        if (failures.isNotEmpty()) {
            System.err.println("ExportedAcrossBatchLaunchSmokeTest FAILED with ${failures.size} problem(s):")
            failures.forEach { System.err.println("  - $it") }
            exitProcess(1)
        }
        println("ExportedAcrossBatchLaunchSmokeTest ok")
    }

    private fun assertContains(haystack: String, needle: String, message: String, failures: MutableList<String>) {
        if (!haystack.contains(needle)) failures.add("$message (expected to find \"$needle\")")
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
        val t = Thread({ afield.Runloop.runLoop.start() }, "exports-batch-launch-harness-runloop")
        t.isDaemon = true
        t.start()
        val deadline = System.currentTimeMillis() + 30_000
        while (afield.Runloop.runLoop.frame < 1L) {
            check(System.currentTimeMillis() < deadline) { "runloop did not start within 30s" }
            Thread.sleep(20)
        }
        println("ExportedAcrossBatchLaunchSmokeTest: runloop up (frame=${afield.Runloop.runLoop.frame})")
    }

    private fun stripHtml(html: String): String =
        html.replace(Regex("<[^>]+>"), " ").replace("&lt;", "<").replace("&gt;", ">").trim()
}
