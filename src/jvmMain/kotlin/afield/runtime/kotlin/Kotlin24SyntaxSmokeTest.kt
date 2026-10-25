package afield.runtime.kotlin

import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.runtime.execution.plus
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem

import afield.audio.synth.*
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
/*
FIELD_TEST
id: core.kotlin-2-4-syntax.smoke
runner: jvm-main
entrypoint: afield.runtime.kotlin.Kotlin24SyntaxSmokeTest
tags: [smoke, core, kotlin, k2]
timeoutSec: 30
expect:
  exitCode: 0
  stdoutContains:
    - "Kotlin 2.4 syntax smoke passed"
*/
/**
 * Smoke test that a box compiles Kotlin 2.4 syntax: collection literals, positional
 * destructuring with square brackets, and name-based destructuring with renamed entries.
 *
 * One snippet on a fresh [KotlinInterface2] declares a data class, a `[1, 2, 3]` list literal and
 * both destructuring forms, and the test checks the result string. It runs in the smoke catalog
 * as `core.kotlin-2-4-syntax.smoke`.
 */

/** Compiles and evaluates the Kotlin 2.4 syntax snippet; `main` fails if the result differs. */
object Kotlin24SyntaxSmokeTest {

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "Kotlin 2.4 syntax smoke failed" }
    }

    fun runTests(): Boolean {
        val ki = KotlinInterface2(filePrefix = "KOTLIN24_SYNTAX_")
        return try {
            val result = ki.execAndWait(
                """
                data class Person(val name: String, val age: Int)
                val values: List<Int> = [1, 2, 3]
                val [first, second, third] = values
                val person = Person("Ada", 37)
                (val age, val displayName = name) = person
                "kotlin24:${'$'}first,${'$'}second,${'$'}third,${'$'}displayName,${'$'}age,${'$'}{values.sum()}"
                """.trimIndent(),
                onCompileError = { diagnostics ->
                    diagnostics.forEach { println(it.render()) }
                },
                onEvalError = { diagnostics ->
                    diagnostics.forEach { println(it.render()) }
                }
            )

            val text = result.toString()
            println("Kotlin 2.4 syntax result: $text")
            require("kotlin24:1,2,3,Ada,37,6" in text) { "Unexpected result: $text" }
            println("Kotlin 2.4 syntax smoke passed")
            true
        } catch (t: Throwable) {
            println("Kotlin 2.4 syntax smoke failed: ${t.message}")
            t.printStackTrace()
            false
        } finally {
            ki.dispose()
        }
    }
}
