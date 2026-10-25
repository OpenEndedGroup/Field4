package afield.runtime.completion

import afield.app.protocol.put
import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.document.graph.box
import afield.runtime.execution.plus
import afield.runtime.kotlin.exported
import afield.runtime.lifecycle.end
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.ui.notation.string
import afield.ui.notation.with
import afield.ui.output.md
import afield.ui.output.tree
import afield.ui.visualization.typed

import afield.audio.synth.*
import afield.document.graph.Box
import afield.document.graph.doResync
import afield.document.graph.preamble
import afield.document.workspace.metadata
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import afield.runtime.kotlin.CompletionResult
import afield.runtime.kotlin.KotlinInterface2
import afield.ui.output.templates.filter
import afield.runtime.macros.gatherPreambleUpwards
import kotlinx.coroutines.runBlocking
import kotlin.script.experimental.api.KotlinType
import kotlin.script.experimental.api.SourceCodeCompletionVariant

/*
FIELD_TEST
id: kotlin.completion-context.probe
runner: jvm-main
entrypoint: afield.runtime.completion.CompletionContextProbe
tags: [probe, kotlin, completion]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "CONTEXT PROBE DONE"
*/
/**
 * PHASE 0 context-dependent completion expectations (see dev_docs/COMPLETION_ENGINE.md).
 * These can't be `(string, cursor)` fixtures — they depend on the box's INHERITED PREAMBLE, i.e.
 * on where the box sits in the tree. Both mechanisms land in the inherited preamble:
 *
 *   - `prop<Foo>(bar)` (a MACRO) expands to `var Box.bar by Inheritance<Foo>()` in the preamble.
 *   - `@exported` republishes members under the `_name` underscore alias (exportedAlias).
 *
 * We build a real parent→child box pair, put both forms in the PARENT's preamble (lines end with
 * `/**/` so gatherPreambleUpwards inherits them downward), associate a KotlinInterface2 with the
 * CHILD, and assert the child's completion surfaces the inherited `bar` (typed) and the `_name`
 * alias. This verifies the load-bearing invariant: completion resolves against the inherited
 * preamble, not just the edited snippet. Report-only (exits 0).
 */
object CompletionContextProbe {

    private typealias Meta = List<SourceCodeCompletionVariant>

    private fun step(label: String, body: () -> Unit) {
        println("STEP: $label"); System.out.flush()
        try { body() } catch (t: Throwable) {
            println("STEP FAILED: $label -> ${t.javaClass.name}: ${t.message}"); t.printStackTrace(System.out); System.out.flush()
            throw t
        }
        println("  ok: $label"); System.out.flush()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        lateinit var parent: Box
        lateinit var child: Box
        step("construct boxes") { parent = Box("ctx-parent"); child = Box("ctx-child") }
        step("wire parent edge") { child.parents.add(parent) }   // inheritance edge; gatherPreambleUpwards walks .parents

        // What `prop<Int>(inheritedCount)` and an @exported `_exportedGreeting` look like once they
        // land in the preamble. `/**/` marks them inheritable to descendants.
        val preamble = """
            var Box.inheritedCount by Inheritance<Int>() /**/
            val Box._exportedGreeting: String get() = "hi from export" /**/
        """.trimIndent()
        step("set parent preamble") { parent.setPropertyRaw("preamble", preamble, doResync = false) }

        lateinit var ki: KotlinInterface2
        step("construct KotlinInterface2") { ki = KotlinInterface2(implicitReceivers = listOf(KotlinType(Box::class)), filePrefix = child.id) }
        step("set associatedBox") { ki.associatedBox = child }
        step("warmCompletion") { runBlocking { ki.warmCompletion() } }

        // Diagnostic: did the child actually inherit the parent's preamble?
        val gathered = gatherPreambleUpwards(child)
        println("── inherited preamble for child (${gathered.length} chars) ──")
        gathered.lines().filter { it.isNotBlank() }.forEach { println("   | $it") }

        var met = 0; var total = 0
        fun check(desc: String, prefix: String, cursor: Int, expect: String) {
            total++
            val meta: Meta = when (val r = runBlocking { runCatching { ki.complete(prefix, cursor) }.getOrNull() }) {
                is CompletionResult.Success -> r.metadata
                else -> emptyList()
            }
            val rank = meta.indexOfFirst { it.text == expect || it.displayText.startsWith(expect) }
            if (rank >= 0) {
                met++
                println("  ✓ $desc  → #${rank + 1}: ${meta[rank].displayText}${meta[rank].tail.let { if (it.isBlank()) "" else "  $it" }}  (of ${meta.size})")
            } else {
                println("  ✗ $desc  ABSENT (of ${meta.size} candidates)")
            }
        }

        println("── expectations ──")
        // prop<Int>(inheritedCount): inherited Box-extension property must surface, typed Int
        check("prop<Int>(inheritedCount) inherited → `inheritedCount`", "inheritedC", 10, "inheritedCount")
        check("prop inherited via short prefix `inh`", "inh", 3, "inheritedCount")
        // @exported underscore alias must surface
        check("@exported `_exportedGreeting` via `_exp`", "_exp", 4, "_exportedGreeting")
        check("@exported underscore surfaces via bare `_`", "_", 1, "_exportedGreeting")

        println("── SUMMARY: $met/$total context expectations met ──")
        println("CONTEXT PROBE DONE")
    }
}
