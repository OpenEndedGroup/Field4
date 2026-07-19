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
 * they compile; a later snippet evaluating before the stdlib intro throws
 * `NoClassDefFoundError: ReplState` (mis-reported as a "package declaration" error).
 *
 * A box connected under a running renderer used to hit this because makeKI evaluated the
 * intro asynchronously while the launch setup evaluated the inherited preamble — the two
 * raced. The real race is timing-dependent (it never fired naturally on macOS), so these
 * tests do NOT rely on thread timing to reproduce it. Instead they:
 *   1. deterministically produce the out-of-order evaluation and assert it fails, and
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
            testOutOfOrderEvalReproducesReplState()
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
     * The hazard the gate exists to prevent: on one engine, evaluating a later-compiled
     * snippet before an earlier one fails, because the first-evaluated snippet is where the
     * K2 REPL `ReplState` holder is defined. This is deterministic — no threads, we simply
     * evaluate in the wrong order by hand. (If a future Kotlin makes out-of-order eval safe
     * this test will flag it; the gate would then be belt-and-suspenders rather than load-
     * bearing.)
     */
    private fun testOutOfOrderEvalReproducesReplState() {
        println("  Testing out-of-order evaluation is rejected...")
        val ki = KotlinInterface2(filePrefix = "gate-ooo")

        // Compile order fixes snippet identity: `first` is #0 (defines ReplState), `second`
        // is #1 (references it). Both compile fine — `second` sees `a` in REPL history.
        val first = ki.compileSnippetAndWait("val a = 1")
        val second = ki.compileSnippetAndWait("a + 1")
        require(first != null && second != null) { "setup snippets failed to compile" }

        // Evaluate #1 before #0 — the exact inversion the intro/preamble race produced.
        var err: Throwable? = null
        second.evalAndWait(onEvalException = { err = it })
        require(err != null) {
            "expected out-of-order evaluation to fail (ReplState not yet loaded), but it succeeded"
        }
        val msg = (err.message.orEmpty() + " " + err.cause?.message.orEmpty())
        require(msg.contains("ReplState") || msg.contains("REPL snippets")) {
            "expected a ReplState-flavoured failure, got: $err (cause=${err.cause})"
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
