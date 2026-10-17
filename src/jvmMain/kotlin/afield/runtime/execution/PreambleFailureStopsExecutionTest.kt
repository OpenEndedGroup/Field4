package afield.runtime.execution

import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem

import afield.audio.synth.*
import afield.document.graph.Box
import afield.document.graph.box
import afield.document.graph.preamble
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import afield.runtime.kotlin.KotlinInterface2
import afield.runtime.macros.standardExecutionPractice
import kotlinx.coroutines.runBlocking
import kotlin.script.experimental.api.ScriptDiagnostic

/*
FIELD_TEST
id: kotlin.preamble-failure-stops-execution
runner: jvm-main
entrypoint: afield.runtime.execution.PreambleFailureStopsExecutionTest
tags: [smoke, kotlin, preamble, execution]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "Preamble failure stop test passed"
*/
object PreambleFailureStopsExecutionTest {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val box = Box("preamble-failure-stop").apply {
            preamble = "val obsolete: org.joml.Matrix4f? = null"
        }
        val engine = KotlinInterface2(
            implicitReceivers = listOf(kotlin.script.experimental.api.KotlinType(Box::class)),
            filePrefix = "preamble-failure-stop"
        )
        box.ki = engine

        val setupDiagnostics = mutableListOf<ScriptDiagnostic>()
        var bodyReachedCompiler = false
        var bodyReachedEvaluator = false

        try {
            val results = standardExecutionPractice(
                ki = engine,
                s = "error(\"BODY_EXECUTED\")",
                onCompileError = { bodyReachedCompiler = true },
                onEvalError = { bodyReachedEvaluator = true },
                onEvalException = { bodyReachedEvaluator = true },
                implicits = listOf(box),
                onSetupCompileError = { block, diagnostics ->
                    require(block == "preamble") { "expected preamble failure, got $block" }
                    setupDiagnostics += diagnostics
                }
            )

            require(setupDiagnostics.any { "joml" in it.message.lowercase() || "matrix4f" in it.message.lowercase() }) {
                "expected the missing preamble type diagnostic, got $setupDiagnostics"
            }
            require(results.isEmpty()) { "body should not produce a result after preamble failure: $results" }
            require(!bodyReachedCompiler) { "body compiler callback ran after preamble failure" }
            require(!bodyReachedEvaluator) { "body evaluator ran after preamble failure" }

            require(box.getPropertyRaw("_preamble", null) == null) {
                "failed preamble must not be cached as successfully executed"
            }
            println("Preamble failure stop test passed")
        } finally {
            engine.dispose()
        }
    }
}
