package afield

/*
FIELD_TEST
id: core.kotlin-2-4-syntax.smoke
runner: jvm-main
entrypoint: afield.Kotlin24SyntaxSmokeTest
tags: [smoke, core, kotlin, k2]
timeoutSec: 30
expect:
  exitCode: 0
  stdoutContains:
    - "Kotlin 2.4 syntax smoke passed"
*/
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
