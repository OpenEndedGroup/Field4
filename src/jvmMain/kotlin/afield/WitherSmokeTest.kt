package afield

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/*
FIELD_TEST
id: kotlin.wither-plugin.smoke
runner: jvm-main
entrypoint: afield.WitherSmokeTest
tags: [smoke, kotlin, repl, wither]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "Wither smoke passed"
*/
/**
 * Smoke for the experiment that wires the Wither compiler plugin (kyay10/wither) into the
 * K2 REPL ([KotlinInterface2]). Wither is a FIR + IR plugin: a call to
 * `io.github.kyay10.wither.with(x)` turns `x` into an implicit receiver for the rest of the
 * enclosing block, and its IR pass rewrites the receiver-less member accesses that follow.
 *
 * The load-bearing assertion is [testReceiverInjection]: a bare `greet()` with no explicit
 * receiver only resolves if Wither injected the `Greeter` receiver. Without the plugin the
 * snippet fails to compile ("unresolved reference 'greet'"), so a green run proves the plugin
 * is actually registered into the REPL's compilation config and doing work end-to-end.
 *
 * [testPlainEvalStillWorks] is the regression half: turning the plugin on must not disturb
 * ordinary snippet evaluation.
 *
 * Runnable-object test style, matching [ReplBootstrapGateTest].
 */
object WitherSmokeTest {

    fun runTests(): Boolean {
        return try {
            println("Running Wither smoke...")
            testPlainEvalStillWorks()
            testReceiverInjection()
            println("✅ Wither smoke passed")
            true
        } catch (t: Throwable) {
            println("❌ Wither smoke failed: ${t.message}")
            t.printStackTrace()
            false
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "Wither smoke failed" }
    }

    /** Regression: a fresh engine still evaluates ordinary snippets with the plugin enabled. */
    private fun testPlainEvalStillWorks() = runBlocking {
        println("  Testing ordinary eval still works with the plugin on...")
        val ki = KotlinInterface2(filePrefix = "wither-plain")
        val result = withTimeout(120_000) { ki.exec("40 + 2") }
        require(result.toString().contains("42")) {
            "ordinary eval should yield 42, got: $result"
        }
    }

    /**
     * The plugin actually fires: `greet()` has no explicit receiver and resolves only because
     * Wither made `Greeter("W")` an implicit receiver via `with(...)`. The whole thing lives in
     * one function body (one compilation unit) so Wither's expression-resolution + IR passes
     * operate on it directly.
     */
    private fun testReceiverInjection() = runBlocking {
        println("  Testing Wither receiver injection (bare greet() resolves)...")
        val ki = KotlinInterface2(filePrefix = "wither-inject")
        val src = """
            class Greeter(val name: String) { fun greet(): String = "hi-" + name }
            fun demo(): String {
                io.github.kyay10.wither.with(Greeter("W"))
                return greet()
            }
            demo()
        """.trimIndent()
        val result = withTimeout(120_000) { ki.exec(src) }
        require(result.toString().contains("hi-W")) {
            "expected Wither-injected receiver to make greet() resolve to \"hi-W\", got: $result"
        }
    }
}
