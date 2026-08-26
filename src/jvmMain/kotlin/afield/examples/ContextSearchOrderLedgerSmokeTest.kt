package afield.examples

import afield.OperationTranscript
import afield.Box
import afield.SavingContext
import afield.SessionInformation
import afield.thisSessionInfo
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

/*
FIELD_TEST
id: examples.context.search-order-ledger
runner: jvm-main
entrypoint: afield.examples.ContextSearchOrderLedgerSmokeTest
tags: [smoke, context, language, regression]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "ContextSearchOrderLedgerSmokeTest ok"
*/
/**
 * The whole search order of `fromContext` (afield.four.modules, Mesh.kt), staged live, one box per
 * step, asserted in both directions.
 *
 * bafa9ee5f dropped step 3 — this box's own top-level (`ki`) variables — from the default search,
 * to stop `var renderer: Renderer = fromContext()` pinning itself to its own previous answer.
 * [ContextLocalVariableShadowingSmokeTest] demonstrates that fix, and
 * [CrossBoxContextInheritanceSmokeTest] pins the ancestor half. Two facts about the *cost* of the
 * change had no committed artifact of any kind, in tree or in the published transcript corpus, and
 * they are the two that decide its blast radius:
 *
 *  1. **This box's own properties are still searched.** That is the escape route the documented
 *     renderer-box idiom depends on — `public/docs/box_code_pipeline.md` shows a graphics-context
 *     box publishing with `prop<Window>(window)` / `prop<Renderer>(renderer)`, i.e. as *properties*
 *     of itself, not as bare top-level vals. Every `moduleHere { }`, `Mesh()`, `Camera()`,
 *     `KeyEvents()` and `standardRenderer` entry point defaults its renderer to `fromContext()`, so
 *     if step 2 had gone with step 3 the entire graphics surface would resolve from nowhere. It did
 *     not, and this is where that is written down rather than inferred from smokes passing.
 *
 *  2. **A value held only in this box's own top-level variable, with no ancestor holding one, is
 *     now unreachable by default** — and `includeLocalVariables = true` is what reaches it. This is
 *     the price of the fix, stated as a checked fact instead of a caveat. A box in that shape used
 *     to work and now raises `IllegalArgumentException: must pass in a value for context
 *     parameter ...`, and the opt-in is the fix for it.
 *
 * Both need two passes and a real engine, for the same reason the shadowing test does: a top-level
 * `val` only enters this box's variable table once the box has run, so pass 1 cannot see what pass
 * 1 declared. A direct call to the helper cannot stage that at all.
 *
 * The assertions are a **bidirectional ledger**. [LEDGER] names every observation this transcript
 * should produce; the run must produce all of them, and must produce nothing else on the boxes
 * involved. A one-directional check would pass if a box silently stopped printing, which is exactly
 * how a live-engine test becomes a tautology.
 */
object ContextSearchOrderLedgerSmokeTest {

    /**
     * Every line the two boxes must print, and nothing they may print instead.
     *
     * Read as: this box owns the value as a *property*, so it finds it (step 2, kept); this box
     * owns it as a *variable*, so by default it does not (step 3, dropped) and with the opt-in it
     * does (step 3, restored on request).
     */
    private val LEDGER = listOf(
        "own-property pass1 found=11",
        "own-property pass2 found=11",
        "own-variable pass1 default=absent",
        "own-variable pass2 default=absent",
        "own-variable pass2 optin=22",
    )

    /**
     * A box that puts the value in its own property bag and then reads it back through the context.
     * `setPropertyRaw` is the untyped half of the `prop<T>(...)` delegate the renderer-box idiom
     * uses, and it is the same bag `fromContext` step 2 walks.
     */
    private const val OWN_PROPERTY_CODE = """import java.util.concurrent.atomic.AtomicInteger
import afield.four.modules.fromContext
thisBox.setPropertyRaw("owned_by_me", AtomicInteger(11))
val found = runCatching { fromContext<AtomicInteger>() }
println("own-property PASS found=" + (found.getOrNull()?.get()?.toString() ?: "absent"))
"""

    /**
     * A box whose only copy of the value is a top-level `val`, with nothing above it holding one.
     * Pass 1 has not left the variable behind yet; pass 2 has, which is the pass that used to
     * succeed by accident and now needs the opt-in said out loud.
     */
    private const val OWN_VARIABLE_CODE = """import java.util.concurrent.atomic.AtomicInteger
import afield.four.modules.fromContext
val mine_only = AtomicInteger(22)
val byDefault = runCatching { fromContext<AtomicInteger>() }
println("own-variable PASS default=" + (byDefault.getOrNull()?.get()?.toString() ?: "absent"))
val optedIn = runCatching { fromContext<AtomicInteger>(includeLocalVariables = true) }
optedIn.getOrNull()?.let { println("own-variable PASS optin=" + it.get()) }
"""

    private fun pass(code: String, label: String) = code.replace("PASS", label)

    private fun buildTranscript(): String = """
name: context_search_order_ledger
description: Ledger - fromContext still searches this box's properties, and reaches this box's own variables only on request.
operations:
  - create:
      box: own_property
      name: Owns It As A Property
      properties:
        code: |
${pass(OWN_PROPERTY_CODE, "pass1").trimEnd().prependIndent("          ")}
  - create:
      box: own_variable
      name: Owns It As A Variable
      properties:
        code: |
${pass(OWN_VARIABLE_CODE, "pass1").trimEnd().prependIndent("          ")}
  - execute:
      box: own_property
      property: code
      type: fragment
  - execute:
      box: own_variable
      property: code
      type: fragment
  - execute:
      box: own_property
      property: code
      type: fragment
      code: |
${pass(OWN_PROPERTY_CODE, "pass2").trimEnd().prependIndent("        ")}
  - execute:
      box: own_variable
      property: code
      type: fragment
      code: |
${pass(OWN_VARIABLE_CODE, "pass2").trimEnd().prependIndent("        ")}
  - wait:
      millis: 250
""".trimIndent()

    @JvmStatic
    fun main(args: Array<String>) {
        startRunloopDaemon()

        val document = freshSession("search-order-ledger")
        val macrosDir = File(document, "macros").also { it.mkdirs() }
        File(macrosDir, "context_search_order_ledger.yaml").writeText(buildTranscript())

        val failures = mutableListOf<String>()
        try {
            val run = OperationTranscript.runMacro(document.absolutePath, "context_search_order_ledger")

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

            // --- Premise. If the boxes did not run at all, everything below is vacuous. ---
            val printed = run.output
                .map { stripHtml(it.html) }
                .filter { it.startsWith("own-property ") || it.startsWith("own-variable ") }
            if (printed.isEmpty()) {
                failures.add("neither context box printed anything - the transcript did not execute " +
                    "these boxes, so the ledger below would have compared two empty sets")
            }

            // --- Bidirectional ledger. Both directions, so a deleted case fails loudly. ---
            (LEDGER - printed.toSet()).forEach { expected ->
                failures.add("ledger line never printed: \"$expected\" (printed: $printed)")
            }
            (printed.toSet() - LEDGER.toSet()).forEach { unexpected ->
                failures.add("printed a line the ledger does not account for: \"$unexpected\" - " +
                    "either fromContext's search order changed or the ledger is stale")
            }

            // --- The two facts, named, so a failure reads as a diagnosis rather than a diff. ---
            if (!printed.contains("own-property pass2 found=11")) {
                failures.add("this box's own PROPERTIES are no longer searched. Every graphics entry " +
                    "point defaults its renderer to fromContext(), and the documented renderer-box " +
                    "idiom (prop<Renderer>(renderer), box_code_pipeline.md) publishes into exactly " +
                    "this bag - dropping step 2 the way bafa9ee5f dropped step 3 breaks all of it")
            }
            if (printed.contains("own-variable pass2 default=22")) {
                failures.add("fromContext resolved to this box's own leftover variable with no " +
                    "ancestor in play - the self-pinning bug bafa9ee5f fixed is back")
            }
            if (!printed.contains("own-variable pass2 optin=22")) {
                failures.add("includeLocalVariables = true no longer reaches this box's own " +
                    "variable, so there is no longer any way to write the box shape bafa9ee5f " +
                    "took away - the escape hatch is the whole reason that change was safe")
            }
        } catch (t: Throwable) {
            failures.add("harness threw ${t.javaClass.simpleName}: ${t.message}")
            t.printStackTrace(System.err)
        } finally {
            thisSessionInfo.remove()
        }

        if (failures.isNotEmpty()) {
            System.err.println("ContextSearchOrderLedgerSmokeTest FAILED with ${failures.size} problem(s):")
            failures.forEach { System.err.println("  - $it") }
            exitProcess(1)
        }
        println("ContextSearchOrderLedgerSmokeTest ok")
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
        val t = Thread({ afield.Runloop.runLoop.start() }, "context-search-order-harness-runloop")
        t.isDaemon = true
        t.start()
        val deadline = System.currentTimeMillis() + 30_000
        while (afield.Runloop.runLoop.frame < 1L) {
            check(System.currentTimeMillis() < deadline) { "runloop did not start within 30s" }
            Thread.sleep(20)
        }
        println("ContextSearchOrderLedgerSmokeTest: runloop up (frame=${afield.Runloop.runLoop.frame})")
    }

    private fun stripHtml(html: String): String =
        html.replace(Regex("<[^>]+>"), " ").replace("&lt;", "<").replace("&gt;", ">").trim()
}
