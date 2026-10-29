package afield.runtime.kotlin

import afield.document.graph.Box
import afield.document.graph.intro
import afield.runtime.macros.standardExecutionPractice
import kotlinx.coroutines.runBlocking
import kotlin.script.experimental.api.KotlinType
import kotlin.system.exitProcess

/*
FIELD_TEST
id: kotlin.shadowed-snippet-name.smoke
runner: jvm-main
entrypoint: afield.runtime.kotlin.ShadowedSnippetNameSmokeTest
tags: [smoke, kotlin, k2, repl, regression]
timeoutSec: 600
expect:
  exitCode: 0
  stdoutContains:
    - "shadowed snippet name smoke passed"
*/
/**
 * Smoke test of [ShadowedSnippetNameDiagnostics]: a name the box declares that a lambda's receiver
 * shadows gets one warning on the line where the receiver's member was used, and the uses that do
 * mean the box's declaration, or say which receiver they want, get none.
 *
 * Each case runs on a fresh [KotlinInterface2] with an implicit `Box` receiver, through
 * [standardExecutionPractice] as a launched box does, and collects warnings from the box's
 * [afield.ui.output.DefaultOutput.warningProxy]. A second run of the same code checks the rerun
 * compile cache shows the warning again.
 */
object ShadowedSnippetNameSmokeTest {
    private val failures = mutableListOf<String>()

    private fun warningsFor(label: String, code: String, runs: Int = 1): List<List<Pair<Int, String>>> = runBlocking {
        val box = Box("shadowed-${label.filter { it.isLetterOrDigit() }}")
        val ki = KotlinInterface2(implicitReceivers = listOf(KotlinType(Box::class)), filePrefix = "SHADOWED_${label.filter { it.isLetterOrDigit() }}_")
        ki.associatedBox = box
        box.ki = ki
        try {
            ki.exec(intro, implicits = listOf(box))
            (1..runs).map {
                val warnings = mutableListOf<Pair<Int, String>>()
                box.output.warningProxy = { message, line, _, _, _ -> warnings += line to message }
                val errors = mutableListOf<String>()
                standardExecutionPractice(
                    ki = ki,
                    s = code,
                    onCompileError = { d -> d.forEach { errors += it.message } },
                    onEvalError = { d -> d.forEach { errors += it.message } },
                    onEvalException = { t -> errors += t.toString() },
                    implicits = listOf(box),
                    onSetupCompileError = { _, d -> d.forEach { errors += it.message } },
                    onSetupEvalError = { _, d -> d.forEach { errors += it.message } },
                    onSetupEvalException = { _, t -> errors += t.toString() },
                )
                if (errors.isNotEmpty()) failures += "$label: did not run: ${errors.first().take(240)}"
                warnings
            }
        } finally {
            ki.dispose()
        }
    }

    private fun expect(label: String, code: String, lines: List<Int>, runs: Int = 1) {
        warningsFor(label, code, runs).forEachIndexed { run, warnings ->
            val got = warnings.map { it.first }
            val ok = got == lines && warnings.all { it.second.contains(ShadowedSnippetNameDiagnostics.MESSAGE_MARK) }
            println("  ${if (ok) "ok  " else "FAIL"} $label [run $run] warned on lines $got" + if (warnings.isEmpty()) "" else ": ${warnings.first().second}")
            if (!ok) failures += "$label run $run: expected warnings on lines $lines, got $warnings"
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val shadowed = """
            val h = 40
            class R(val h: Int)
            with(R(3)) { h }
        """.trimIndent()
        expect("a receiver's member hides the box's val, on both runs", shadowed, listOf(3), runs = 2)
        expect("a box function hidden by a receiver's function", "fun size() = 7\nwith(listOf(1, 2)) { size }", listOf(2))
        expect("this.h names the receiver on purpose", "val h = 40\nclass R(val h: Int)\nwith(R(3)) { this.h }", emptyList())
        expect("a lambda with no receiver of its own sees the box's val", "val h = 40\nlistOf(1).map { h + it }", emptyList())
        expect("the box's top level sees its own val", "val h = 40\nh", emptyList())
        expect("a renamed val has nothing to hide", "val outH = 40\nclass R(val h: Int)\nwith(R(3)) { h + outH }", emptyList())

        if (failures.isNotEmpty()) {
            System.err.println("shadowed snippet name smoke FAILED with ${failures.size} problem(s):")
            failures.forEach { System.err.println("  - $it") }
            exitProcess(1)
        }
        println("shadowed snippet name smoke passed")
        exitProcess(0)
    }
}
