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
id: examples.context.resolution-ledger
runner: jvm-main
entrypoint: afield.examples.ContextResolutionLedgerSmokeTest
tags: [smoke, context, exports, language, regression]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "ContextResolutionLedgerSmokeTest ok"
*/
/**
 * The live-engine half of `ContextResolutionCharacterizationTest`: the two resolution behaviours
 * that only exist once a real box has run — a variable it really left behind, and a preamble line
 * `checkPreambleAndExecute` really persisted.
 *
 *  1. **`@exported` self-pinning.** A consumer that writes `val x = _x` leaves a variable called
 *     `x` in its own table, and `InheritanceFromContext` looks for `x` as well as `_x`. Does the
 *     consumer's second run see the exporter's new value, or its own leftover?
 *  2. **A persisted inherited preamble line after a disconnect.** The consumer's preamble gets the
 *     exporter's inherit-marked declaration written into it. After the edge goes, is it still there,
 *     and does the consumer's own child still inherit it?
 *
 * [LEDGER] is read in both directions, like `ContextSearchOrderLedgerSmokeTest`'s. Its lines are
 * dated: each records what the engine did on that date, and a line that was flipped deliberately
 * names the ledger entry in `dev_docs/stdlib_behaviour_changes.md` that explains it.
 */
object ContextResolutionLedgerSmokeTest {

    /**
     * 2026-09-28, before `ContextResolution`: the consumer pinned itself to its first answer
     * (`pass2 x=1`), the stale declaration survived the disconnect in the consumer, and the
     * consumer's child inherited it.
     *
     * FLIPPED 2026-09-29 (stdlib_behaviour_changes.md, "one context walk"): `pass2 x=2` — the
     * consumer's own leftover `x` no longer answers for `_x`.
     *
     * FLIPPED 2026-09-29 (stdlib_behaviour_changes.md, "sourced preamble lines"): the persisted
     * declaration carries its supplier's uid, so after the disconnect it leaves the consumer's
     * preamble and the grandchild does not inherit it.
     */
    private val LEDGER = listOf(
        "ledger: consumer pass1 x=1",
        "ledger: consumer pass2 x=2",
        "ledger: exporter own=2",
        "ledger: consumer after-disconnect stale-line=false",
        "ledger: grandchild after-disconnect inherits=false",
    )

    private const val EXPORTER_V1 = """@exported
var x = 1
"""

    private const val EXPORTER_V2 = """@exported
var x = 2
"""

    private const val EXPORTER_READS_OWN = """println("ledger: exporter own=" + _x)
"""

    private fun consumer(label: String) = """val x = _x
println("ledger: consumer $label x=" + x)
"""

    private const val CONSUMER_AFTER_DISCONNECT =
        """println("ledger: consumer after-disconnect stale-line=" + ((thisBox.getPropertyRaw("preamble") as String?) ?: "").contains("Box._x"))
"""

    private const val GRANDCHILD_AFTER_DISCONNECT =
        """println("ledger: grandchild after-disconnect inherits=" + ((thisBox.getPropertyRaw("preamble") as String?) ?: "").contains("Box._x"))
"""

    private fun execute(box: String, code: String) = """
  - execute:
      box: $box
      property: code
      type: fragment
      code: |
${code.trimEnd().prependIndent("        ")}"""

    private fun buildTranscript(): String = """
name: context_resolution_ledger
description: Ledger - @exported self-pinning and persisted inherited preamble lines, through the real engine.
operations:
  - create:
      box: res_exporter
      name: Exporter
      properties:
        code: |
${EXPORTER_V1.trimEnd().prependIndent("          ")}
  - create:
      box: res_consumer
      name: Consumer
      properties:
        code: |
${consumer("pass1").trimEnd().prependIndent("          ")}
  - create:
      box: res_grandchild
      name: Grandchild
      properties:
        code: |
${GRANDCHILD_AFTER_DISCONNECT.trimEnd().prependIndent("          ")}
  - connect:
      parent: res_exporter
      child: res_consumer
  - connect:
      parent: res_consumer
      child: res_grandchild""" +
        execute("res_exporter", EXPORTER_V1) +
        execute("res_consumer", consumer("pass1")) +
        execute("res_exporter", EXPORTER_V2) +
        execute("res_consumer", consumer("pass2")) +
        execute("res_exporter", EXPORTER_READS_OWN) + """
  - disconnect:
      parent: res_exporter
      child: res_consumer""" +
        execute("res_consumer", CONSUMER_AFTER_DISCONNECT) +
        execute("res_grandchild", GRANDCHILD_AFTER_DISCONNECT) + """
  - wait:
      millis: 250
"""

    @JvmStatic
    fun main(args: Array<String>) {
        startRunloopDaemon()

        val document = freshSession("resolution-ledger")
        val macrosDir = File(document, "macros").also { it.mkdirs() }
        File(macrosDir, "context_resolution_ledger.yaml").writeText(buildTranscript().trimStart())

        val failures = mutableListOf<String>()
        try {
            val run = OperationTranscript.runMacro(document.absolutePath, "context_resolution_ledger")

            println("run.success=${run.success} applied=${run.applied} output lines=${run.output.size}")
            run.output.forEach { println("  [${it.kind}] ${it.uid}: ${stripHtml(it.html)}") }

            if (!run.success) {
                failures.add("transcript run failed: ${run.errors.joinToString("; ").ifBlank { run.message }}")
            }
            val errorLines = run.output.filter { it.kind == "error" || it.kind == "err" }
            if (errorLines.isNotEmpty()) {
                failures.add("box execution emitted ${errorLines.size} error diagnostic(s): " +
                    errorLines.joinToString(" | ") { "${it.uid}:${it.line} ${stripHtml(it.html)}" })
            }

            val printed = run.output.map { stripHtml(it.html) }.filter { it.startsWith("ledger: ") }
            if (printed.isEmpty()) {
                failures.add("no box printed a ledger line - the transcript did not execute them, so the " +
                    "comparison below would be between two empty sets")
            }
            (LEDGER - printed.toSet()).forEach { failures.add("ledger line never printed: \"$it\" (printed: $printed)") }
            (printed.toSet() - LEDGER.toSet()).forEach {
                failures.add("printed a line the ledger does not account for: \"$it\" - either resolution " +
                    "changed or the ledger is stale")
            }
        } catch (t: Throwable) {
            failures.add("harness threw ${t.javaClass.simpleName}: ${t.message}")
            t.printStackTrace(System.err)
        } finally {
            thisSessionInfo.remove()
        }

        if (failures.isNotEmpty()) {
            System.err.println("ContextResolutionLedgerSmokeTest FAILED with ${failures.size} problem(s):")
            failures.forEach { System.err.println("  - $it") }
            exitProcess(1)
        }
        println("ContextResolutionLedgerSmokeTest ok")
    }

    private fun freshSession(name: String): File {
        thisSessionInfo.remove()
        val workspace = Files.createTempDirectory("afield-ctx-$name").toFile()
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
        val t = Thread({ afield.Runloop.runLoop.start() }, "context-resolution-ledger-runloop")
        t.isDaemon = true
        t.start()
        val deadline = System.currentTimeMillis() + 30_000
        while (afield.Runloop.runLoop.frame < 1L) {
            check(System.currentTimeMillis() < deadline) { "runloop did not start within 30s" }
            Thread.sleep(20)
        }
        println("ContextResolutionLedgerSmokeTest: runloop up (frame=${afield.Runloop.runLoop.frame})")
    }

    private fun stripHtml(html: String): String =
        html.replace(Regex("<[^>]+>"), " ").replace("&lt;", "<").replace("&gt;", ">").trim()
}
