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
id: examples.context.cross-box-inheritance
runner: jvm-main
entrypoint: afield.examples.CrossBoxContextInheritanceSmokeTest
tags: [smoke, context, language, regression]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "CrossBoxContextInheritanceSmokeTest ok"
*/
/**
 * Regression test for cross-box context inheritance via `fromContextWithNameOrNull`
 * (afield.four.modules, defined in Mesh.kt).
 *
 * Why this exists: on 2026-07-19 (commit 7cf85f08) the property-inheritance path of
 * `fromContextWithNameOrNull` was found to return the `Map.Entry` instead of `.value`, so a
 * child box reading an *inherited property* set by a parent box got a non-null-but-wrong
 * value coerced to `A` — i.e. cross-box property inheritance via that helper had NEVER worked.
 * (The ki-var path, and the no-name `fromContext`, were always correct.)
 *
 * The existing [DemoTranscriptExecSmokeTest] already *runs* the `two_box_context` demo, but it
 * only asserts the run produced no error-kind output. The buggy path emitted no error — the
 * child simply printed `child inherited: null` (or a garbage entry) — so that test passed even
 * while inheritance was broken. This test closes the gap by asserting the *actual inherited
 * value* arrives, and by adding a negative sibling case (a box NOT childed to the parent must
 * NOT see the value).
 *
 * It drives the REAL engine through the identical path the `two_box_context` child uses:
 * `OperationTranscript.runMacro` -> executeFragment -> the real compile+eval pipeline, then
 * inspects the captured box `println` output. (A unit test that called the helper directly with
 * correct types would not prove the box execution path — see the 2026-07-18 fake-demo lesson.)
 *
 * Matrix exercised:
 *  - property inheritance, String            (the path fixed by 7cf85f08) -> HARD assert
 *  - property inheritance, Int               (type-filtered subclass match) -> HARD assert
 *  - ki-var inheritance, String (top-level val in parent) (line 91-96 path) -> HARD assert
 *  - negative: sibling on root, not childed to parent, must resolve null -> HARD assert
 *  - no error-kind output on any box                                       -> HARD assert
 */
object CrossBoxContextInheritanceSmokeTest {

    // Parent sets two properties (via setPropertyRaw) AND declares a top-level val (a ki-var).
    private const val PARENT_CODE = """thisBox.setPropertyRaw("ctx_string", "value from parent")
thisBox.setPropertyRaw("ctx_number", 42)
val ctx_kivar = "kivar from parent"
println("parent ready kivar=${'$'}ctx_kivar")
"""

    // Child (childed to parent) reads all three via the helper the two_box_context demo uses.
    private const val CHILD_CODE = """val s = fromContextWithNameOrNull<String>("ctx_string")
val n = fromContextWithNameOrNull<Int>("ctx_number")
val kv = fromContextWithNameOrNull<String>("ctx_kivar")
println("child string=${'$'}s number=${'$'}n kivar=${'$'}kv")
"""

    // Sibling lives only on root (never connected to parent) and must NOT inherit.
    private const val SIBLING_CODE = """val s = fromContextWithNameOrNull<String>("ctx_string")
println("sibling string=${'$'}s")
"""

    private fun buildTranscript(): String = """
name: cross_box_context_inheritance
description: Regression - a child box inherits parent properties/ki-vars via fromContextWithNameOrNull; a sibling does not.
operations:
  - create:
      box: ctx_parent
      name: Context Parent
      properties:
        code: |
${PARENT_CODE.trimEnd().prependIndent("          ")}
  - create:
      box: ctx_child
      name: Context Child
      properties:
        code: |
${CHILD_CODE.trimEnd().prependIndent("          ")}
  - create:
      box: ctx_sibling
      name: Context Sibling
      properties:
        code: |
${SIBLING_CODE.trimEnd().prependIndent("          ")}
  - connect:
      parent: ctx_parent
      child: ctx_child
  - execute:
      box: ctx_parent
      property: code
      type: fragment
  - execute:
      box: ctx_child
      property: code
      type: fragment
  - execute:
      box: ctx_sibling
      property: code
      type: fragment
  - wait:
      millis: 250
""".trimIndent()

    @JvmStatic
    fun main(args: Array<String>) {
        startRunloopDaemon()

        val document = freshSession("cross-box-context")
        val macrosDir = File(document, "macros").also { it.mkdirs() }
        File(macrosDir, "cross_box_context_inheritance.yaml").writeText(buildTranscript())

        val failures = mutableListOf<String>()
        try {
            val run = OperationTranscript.runMacro(document.absolutePath, "cross_box_context_inheritance")

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

            // Collapse all captured output into plain text for content assertions.
            val text = run.output.joinToString("\n") { stripHtml(it.html) }

            // --- Positive: child inherits the parent's String property (the 7cf85f08 fix) ---
            assertContains(text,
                "child string=value from parent",
                "child did NOT inherit the parent's String property via fromContextWithNameOrNull " +
                    "(this is the exact regression fixed by 7cf85f08 - the .value bug)",
                failures)

            // --- Positive: child inherits the parent's Int property (type-filtered) ---
            assertContains(text,
                "number=42",
                "child did NOT inherit the parent's Int property (type-filtered isSubclassOf match)",
                failures)

            // --- Positive: child inherits the parent's ki-var (top-level val) ---
            assertContains(text,
                "kivar=kivar from parent",
                "child did NOT inherit the parent's ki-var (the ki-var walk path, always expected to work)",
                failures)

            // --- Negative: a null must never surface for the inherited child properties ---
            if (text.contains("child string=null")) {
                failures.add("child resolved the inherited String property to null (inheritance broken)")
            }

            // --- Negative: sibling (root-only, not childed to parent) must NOT inherit ---
            assertContains(text,
                "sibling string=null",
                "sibling (not childed to the parent) unexpectedly inherited the parent's property - " +
                    "context leaked across an unrelated box",
                failures)
        } catch (t: Throwable) {
            failures.add("harness threw ${t.javaClass.simpleName}: ${t.message}")
            t.printStackTrace(System.err)
        } finally {
            thisSessionInfo.remove()
        }

        if (failures.isNotEmpty()) {
            System.err.println("CrossBoxContextInheritanceSmokeTest FAILED with ${failures.size} problem(s):")
            failures.forEach { System.err.println("  - $it") }
            exitProcess(1)
        }
        println("CrossBoxContextInheritanceSmokeTest ok")
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
        val t = Thread({ afield.Runloop.runLoop.start() }, "cross-box-context-harness-runloop")
        t.isDaemon = true
        t.start()
        val deadline = System.currentTimeMillis() + 30_000
        while (afield.Runloop.runLoop.frame < 1L) {
            check(System.currentTimeMillis() < deadline) { "runloop did not start within 30s" }
            Thread.sleep(20)
        }
        println("CrossBoxContextInheritanceSmokeTest: runloop up (frame=${afield.Runloop.runLoop.frame})")
    }

    private fun stripHtml(html: String): String =
        html.replace(Regex("<[^>]+>"), " ").replace("&lt;", "<").replace("&gt;", ">").trim()
}
