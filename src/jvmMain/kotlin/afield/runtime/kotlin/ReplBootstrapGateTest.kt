package afield.runtime.kotlin

import afield.app.server.flag
import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.document.graph.box
import afield.document.graph.intro
import afield.document.graph.makeKI
import afield.document.graph.preamble
import afield.runtime.execution.plus
import afield.runtime.lifecycle.end
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.runtime.lifecycle.then
import afield.ui.notation.with
import afield.ui.output.history

import afield.audio.synth.*
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/*
FIELD_TEST
id: core.repl-bootstrap-gate.smoke
runner: jvm-main
entrypoint: afield.runtime.kotlin.ReplBootstrapGateTest
tags: [smoke, core, kotlin, repl, regression]
timeoutSec: 60
expect:
  exitCode: 0
  stdoutContains:
    - "ReplBootstrapGate tests passed"
*/
/**
 * Smoke test of the bootstrap ordering of a [KotlinInterface2] engine: the stdlib intro is
 * evaluated before any ordinary snippet.
 *
 * On one engine the K2 evaluator chains each snippet's classloader onto the previously evaluated
 * snippet and emits the shared `ReplState` holder only into the first-evaluated one, so snippets
 * have to evaluate in the order they compile. A box launched under a running renderer evaluates
 * the intro asynchronously while its launch setup evaluates the inherited preamble, and the two
 * can race.
 *
 * The race depends on timing, so the test does not rely on threads to reproduce it. It runs three
 * cases:
 *
 * 1. It forces an out-of-order evaluation and checks that it evaluates with the right answer, which
 *    it does since the Kotlin 2.4.20 uplift.
 * 2. It checks that the engine's bootstrap gate ([KotlinScriptEngine.armBootstrapGate] and
 *    [KotlinScriptEngine.bootstrap]) holds an ordinary exec until the gate is released.
 * 3. It checks that a concurrent exec waits for the bootstrap to finish.
 *
 * Like [CompiledSnippetTest] it is a runnable object rather than a JUnit class.
 */

/** Runs the three bootstrap-ordering cases; `main` fails if any of them does. */
object ReplBootstrapGateTest {

    fun runTests(): Boolean {
        return try {
            println("Running ReplBootstrapGate tests...")
            testOutOfOrderEvalIsNowSafe()
            testGateBlocksUntilReleased()
            testConcurrentExecWaitsForBootstrap()
            println("✅ ReplBootstrapGate tests passed")
            true
        } catch (t: Throwable) {
            println("❌ ReplBootstrapGate tests failed: ${t.message}")
            t.printStackTrace()
            false
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "ReplBootstrapGate tests failed" }
    }

    /**
     * Out-of-order evaluation on one engine — which upstream fixed under us.
     *
     * It used to *fail*: the first-evaluated snippet is where the K2 REPL `ReplState` holder is
     * defined, so evaluating snippet #1 before #0 threw `NoClassDefFoundError: ReplState`. This
     * test's original KDoc predicted that "if a future Kotlin makes out-of-order eval safe this
     * test will flag it, and the gate would then be belt-and-suspenders rather than load-bearing" —
     * and the 2.4.20 uplift is that future. With upstream's synthetic-intermediate-snippet support
     * (`prependSyntheticSnippets`), evaluating #1 first both works AND gets the right answer: `a`
     * is initialised, so `a + 1` is 2, not the 1 an uninitialised field would have given.
     *
     * Pinned rather than deleted, because "out-of-order eval is correct" is now a property field's
     * engine leans on. If it ever throws again, the engine's bootstrap gate goes back to being
     * load-bearing — and this is the case that will say so.
     */
    private fun testOutOfOrderEvalIsNowSafe() {
        println("  Testing out-of-order evaluation is safe (fixed upstream in 2.4.20)...")
        val ki = KotlinInterface2(filePrefix = "gate-ooo")

        // Compile order fixes snippet identity: `first` is #0 (defines ReplState), `second`
        // is #1 (references it). Both compile fine — `second` sees `a` in REPL history.
        val first = ki.compileSnippetAndWait("val a = 1")
        val second = ki.compileSnippetAndWait("a + 1")
        require(first != null && second != null) { "setup snippets failed to compile" }

        // Evaluate #1 before #0 — the exact inversion the intro/preamble race produced.
        var err: Throwable? = null
        val outOfOrder = second.evalAndWait(onEvalException = { err = it })
        println("    out-of-order eval: result=$outOfOrder error=${err?.let { "${it::class.simpleName}: ${it.message}" }}")
        require(err == null) {
            "out-of-order evaluation threw $err. It threw before the 2.4.20 uplift and stopped " +
                    "throwing after it; if it is failing again, the engine's bootstrap gate is " +
                    "load-bearing once more and this case should assert the ReplState failure again."
        }
        require(outOfOrder.toString().contains("= 2")) {
            "out-of-order eval should see an initialised `a` and yield 2, got: $outOfOrder"
        }

        // Sanity: the *correct* order works on a clean engine.
        val ki2 = KotlinInterface2(filePrefix = "gate-ooo-ok")
        val a0 = ki2.compileSnippetAndWait("val b = 10")
        val b1 = ki2.compileSnippetAndWait("b + 5")
        require(a0 != null && b1 != null) { "sanity snippets failed to compile" }
        a0.evalAndWait()
        val ok = b1.evalAndWait()
        require(ok.toString().contains("15")) { "in-order eval should yield 15, got: $ok" }
    }

    /**
     * The fix: while the gate is armed and unopened, an ordinary [KotlinInterface2.exec]
     * must not run; once opened it proceeds. Deterministic for the working gate — the gate
     * is never opened during the wait, so exec cannot complete. If the gate were removed,
     * exec would compile+evaluate "1 + 1" (self-contained) and return within the timeout,
     * failing the first assertion.
     */
    private fun testGateBlocksUntilReleased() = runBlocking {
        println("  Testing armed gate blocks exec until released...")
        val ki = KotlinInterface2(filePrefix = "gate-block")
        ki.armBootstrapGate()

        val whileArmed = withTimeoutOrNull(8_000) { ki.exec("1 + 1") }
        require(whileArmed == null) {
            "exec must not complete while the bootstrap gate is armed and unopened; got: $whileArmed"
        }

        ki.releaseBootstrapGate()
        val afterOpen = withTimeout(30_000) { ki.exec("2 + 2") }
        require(afterOpen.toString().contains("4")) {
            "exec must proceed once the gate is open; got: $afterOpen"
        }
    }

    /**
     * End-to-end ordering: a concurrent exec started while the gate is armed observes the
     * declarations the bootstrap makes — i.e. it evaluates *after* the intro, never before.
     * With the working gate this is deterministic (the marker resolves to 41 -> 42). Without
     * the gate the concurrent exec would race the bootstrap and typically fail to resolve
     * the marker.
     */
    private fun testConcurrentExecWaitsForBootstrap() = runBlocking {
        println("  Testing concurrent exec waits for bootstrap...")
        val ki = KotlinInterface2(filePrefix = "gate-order")
        ki.armBootstrapGate()

        val gated = async(Dispatchers.Default) { ki.exec("introMarker + 1") }
        // bootstrap runs the "intro" (defining introMarker) past the gate and opens it.
        ki.bootstrap("val introMarker = 41")

        val result = withTimeout(30_000) { gated.await() }
        require(result.toString().contains("42")) {
            "gated exec should observe the bootstrap's declaration (introMarker=41 -> 42); got: $result"
        }
    }
}
