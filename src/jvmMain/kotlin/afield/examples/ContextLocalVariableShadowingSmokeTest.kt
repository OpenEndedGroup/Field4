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
id: examples.context.local-variable-shadowing
runner: jvm-main
entrypoint: afield.examples.ContextLocalVariableShadowingSmokeTest
tags: [smoke, context, language, regression]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "ContextLocalVariableShadowingSmokeTest ok"
*/
/**
 * Regression test for the self-pinning bug in `fromContext` (afield.four.modules, Mesh.kt).
 *
 * The canonical idiom is
 *
 *     var renderer: Renderer = fromContext()
 *
 * in a box childed to the box that owns the value. `fromContext` used to search *this* box's own
 * top-level (`ki`) variables before walking up to the parents — so the first execution resolved
 * from the parent correctly, and every execution after that found the variable the first one had
 * left behind. The value could then never track the parent again, and re-pointing it by hand did
 * not survive either: the box was pinned to its own stale answer forever.
 *
 * The fix drops the current box's variables from the default search (properties of this box, and
 * both properties and variables of every ancestor, are still searched), with
 * `includeLocalVariables = true` to opt back in.
 *
 * This drives the REAL engine — `OperationTranscript.runMacro` -> executeFragment -> the actual
 * compile+eval pipeline — and inspects captured box output, because the bug only exists once a box
 * has run *twice* and left variables behind; a direct call to the helper cannot show it.
 *
 * Matrix exercised:
 *  - default: second pass sees the parent's NEW value, not the local left by the first  -> HARD assert
 *  - opt-in (`includeLocalVariables = true`): second pass still sees its own local      -> HARD assert
 *  - first pass of both boxes resolves the parent's original value                      -> HARD assert
 *  - no error-kind output on any box                                                    -> HARD assert
 */
object ContextLocalVariableShadowingSmokeTest {

    private const val PARENT_V1 = """thisBox.setPropertyRaw("ctx_holder", java.util.concurrent.atomic.AtomicInteger(1))
println("parent set 1")
"""

    private const val PARENT_V2 = """thisBox.setPropertyRaw("ctx_holder", java.util.concurrent.atomic.AtomicInteger(2))
println("parent set 2")
"""

    // `holder` is left behind in this box's variable table by pass 1; pass 2 must NOT find it.
    private fun childCode(label: String, optIn: Boolean): String {
        val call = if (optIn) "fromContext<AtomicInteger>(includeLocalVariables = true)" else "fromContext<AtomicInteger>()"
        return """import java.util.concurrent.atomic.AtomicInteger
import afield.four.modules.fromContext
val holder = $call
println("$label holder=" + holder.get())
"""
    }

    private fun buildTranscript(): String = """
name: context_local_variable_shadowing
description: Regression - fromContext must not resolve to a variable this box left behind on an earlier run.
operations:
  - create:
      box: shadow_parent
      name: Shadow Parent
      properties:
        code: |
${PARENT_V1.trimEnd().prependIndent("          ")}
  - create:
      box: shadow_child
      name: Shadow Child
      properties:
        code: |
${childCode("child pass1", optIn = false).trimEnd().prependIndent("          ")}
  - create:
      box: shadow_optin
      name: Shadow Opt-in
      properties:
        code: |
${childCode("optin pass1", optIn = true).trimEnd().prependIndent("          ")}
  - connect:
      parent: shadow_parent
      child: shadow_child
  - connect:
      parent: shadow_parent
      child: shadow_optin
  - execute:
      box: shadow_parent
      property: code
      type: fragment
  - execute:
      box: shadow_child
      property: code
      type: fragment
  - execute:
      box: shadow_optin
      property: code
      type: fragment
  - execute:
      box: shadow_parent
      property: code
      type: fragment
      code: |
${PARENT_V2.trimEnd().prependIndent("        ")}
  - execute:
      box: shadow_child
      property: code
      type: fragment
      code: |
${childCode("child pass2", optIn = false).trimEnd().prependIndent("        ")}
  - execute:
      box: shadow_optin
      property: code
      type: fragment
      code: |
${childCode("optin pass2", optIn = true).trimEnd().prependIndent("        ")}
  - wait:
      millis: 250
""".trimIndent()

    @JvmStatic
    fun main(args: Array<String>) {
        startRunloopDaemon()

        val document = freshSession("local-shadowing")
        val macrosDir = File(document, "macros").also { it.mkdirs() }
        File(macrosDir, "context_local_variable_shadowing.yaml").writeText(buildTranscript())

        val failures = mutableListOf<String>()
        try {
            val run = OperationTranscript.runMacro(document.absolutePath, "context_local_variable_shadowing")

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

            val text = run.output.joinToString("\n") { stripHtml(it.html) }

            // --- Control: both children resolve the parent's original value on their first pass ---
            assertContains(text, "child pass1 holder=1",
                "child did not inherit the parent's property at all (context walk broken, not just shadowing)",
                failures)
            assertContains(text, "optin pass1 holder=1",
                "opt-in child did not inherit the parent's property on its first pass",
                failures)

            // --- The regression: pass 2 must see the parent's NEW value, not its own leftover local ---
            assertContains(text, "child pass2 holder=2",
                "fromContext resolved to the variable THIS box left behind on pass 1 instead of the " +
                    "parent's current value - the self-pinning bug is back",
                failures)
            if (text.contains("child pass2 holder=1")) {
                failures.add("child pass2 saw its own stale local (holder=1) rather than the parent's holder=2")
            }

            // --- The opt-in escape hatch still behaves the old way ---
            assertContains(text, "optin pass2 holder=1",
                "includeLocalVariables = true no longer finds this box's own variable - the opt-in path is broken",
                failures)
        } catch (t: Throwable) {
            failures.add("harness threw ${t.javaClass.simpleName}: ${t.message}")
            t.printStackTrace(System.err)
        } finally {
            thisSessionInfo.remove()
        }

        if (failures.isNotEmpty()) {
            System.err.println("ContextLocalVariableShadowingSmokeTest FAILED with ${failures.size} problem(s):")
            failures.forEach { System.err.println("  - $it") }
            exitProcess(1)
        }
        println("ContextLocalVariableShadowingSmokeTest ok")
    }

    private fun assertContains(haystack: String, needle: String, message: String, failures: MutableList<String>) {
        if (!haystack.contains(needle)) failures.add("$message (expected to find \"$needle\")")
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
        val t = Thread({ afield.Runloop.runLoop.start() }, "context-shadowing-harness-runloop")
        t.isDaemon = true
        t.start()
        val deadline = System.currentTimeMillis() + 30_000
        while (afield.Runloop.runLoop.frame < 1L) {
            check(System.currentTimeMillis() < deadline) { "runloop did not start within 30s" }
            Thread.sleep(20)
        }
        println("ContextLocalVariableShadowingSmokeTest: runloop up (frame=${afield.Runloop.runLoop.frame})")
    }

    private fun stripHtml(html: String): String =
        html.replace(Regex("<[^>]+>"), " ").replace("&lt;", "<").replace("&gt;", ">").trim()
}
