package afield

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/*
FIELD_TEST
id: core.repl-bootstrap-gate.smoke
runner: jvm-main
entrypoint: afield.ReplBootstrapGateTest
tags: [smoke, core, kotlin, repl, regression]
timeoutSec: 60
expect:
  exitCode: 0
  stdoutContains:
    - "ReplBootstrapGate tests passed"
*/
/**
 * Regression tests for the K2 REPL bootstrap-ordering fix.
 *
 * Background: on one [KotlinInterface2] engine the K2 evaluator chains each snippet's
 * classloader onto the previously *evaluated* snippet and emits the shared `ReplState`
 * holder only into the first-evaluated snippet. So snippets must evaluate in the order
 * they compile; a later snippet evaluating before the stdlib intro used to throw
 * `NoClassDefFoundError: ReplState` (mis-reported as a "package declaration" error). The 2.4.20
 * uplift fixed that upstream — see [testOutOfOrderEvalIsNowSafe] — so the gate below is now
 * belt-and-suspenders rather than the thing standing between a box and a broken engine.
 *
 * A box connected under a running renderer used to hit this because makeKI evaluated the
 * intro asynchronously while the launch setup evaluated the inherited preamble — the two
 * raced. The real race is timing-dependent (it never fired naturally on macOS), so these
 * tests do NOT rely on thread timing to reproduce it. Instead they:
 *   1. deterministically produce the out-of-order evaluation and pin what it does (since the
 *      2.4.20 uplift it is simply safe — it evaluates, with the right answer), and
 *   2. assert the engine's bootstrap gate ([KotlinScriptEngine.armBootstrapGate] /
 *      [bootstrap] / [releaseBootstrapGate]) makes ordinary exec/evaluate wait for the
 *      intro — which is what removes the race in production.
 *
 * Follows the repo's lightweight "runnable object" test style (see [CompiledSnippetTest]).
 */
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
