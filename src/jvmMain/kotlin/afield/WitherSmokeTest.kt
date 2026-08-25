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
 *
 * ### RED since 2026-08-25, deliberately: an upstream 2.4.20 REPL regression
 *
 * [testReceiverInjection] fails with an `IrErrorCallExpression` reaching codegen:
 *
 *     Unresolved reference: this@R|<local>/<with holder>|
 *     AssertionError: Unexpected IR element found during code generation ... or it should have been lowered
 *
 * It is left failing on purpose. What is known, so nobody re-derives it:
 *
 *  - **Not our fork patches.** Control matrix: the whole pre-uplift jar set passes; the uplifted set
 *    with our patched files reverted to `origin/2.4.20` still fails; the uplifted+patched set fails.
 *    See the memory note `kotlin-fork-uplift-recipe`.
 *  - **Not plugin-jar drift.** `libs/wither-plugin.jar` was rebuilt from `~/temp/wither` at the same
 *    commit that produced the working jar, against the uplifted fork: 18 classes, identical surface,
 *    same failure. (When rebuilding: `-jvm-target 22`, not 24 — the compiler host is a JDK 22 and
 *    class file 68 will not load — plus `-opt-in=…ExperimentalCompilerApi` and
 *    `-opt-in=…UnsafeDuringIrConstructionAPI` from its build file.)
 *  - **REPL-only, and shape-independent.** The same program in a plain `.kt` file compiles and prints
 *    `hi-W` on the uplifted compiler. Inside a snippet, all six shapes tried fail identically: named
 *    fun at snippet top level, bare snippet body, local fun, lambda, member fun of a snippet class,
 *    property initializer. So there is no box-code spelling that avoids it.
 *  - **Where it dies.** `Fir2IrVisitor.visitThisReceiverExpression` dispatches on
 *    `calleeReference.referencedMemberSymbol` over four symbol kinds (class / script / REPL-snippet /
 *    callable) and falls to `else -> null` for anything else, then to `visitQualifiedAccessExpression`,
 *    which emits the error call above at `CallAndReferenceGenerator.kt:1285`. Wither binds its
 *    rewritten `this` to a synthetic `FirReceiverParameter` (`fakeLegacyReceiver`, built in
 *    `WitherContextArgumentCleaner`), which is none of those four. The REPL branch that does exist,
 *    `generateThisReceiverAccessForReplSnippet`, only maps receivers already in
 *    `firSnippet.receivers` — the snippet's *declared* implicit receivers, i.e. `Box` — matched by
 *    index, and a plugin cannot add itself to that list.
 *  - **Not yet proven:** why the plain-file path succeeds. Either the `FirCallableSymbol` branch finds
 *    an `IrParameterKind.ExtensionReceiver` on the parents stack (`Fir2IrVisitor.kt:906`, which
 *    `?: return null`s when absent), or `injectGetValueCall` short-circuits first. Which one it is
 *    decides whether a fix teaches the REPL branch about plugin-injected receivers or fixes the
 *    callable branch for snippet-class members.
 *
 * The repro, for whoever picks this up — five lines through any [KotlinInterface2]:
 *
 *     class Greeter(val name: String) { fun greet(): String = "hi-" + name }
 *     fun demo(): String { io.github.kyay10.wither.with(Greeter("W")); return greet() }
 *     demo()
 *
 * and the plain-file control, which passes: compile the same code with
 * `K2JVMCompiler -Xplugin=libs/wither-plugin.jar -jvm-target 22` and run it.
 *
 * Wither is an experiment, not load-bearing, so red is acceptable here. The fix belongs upstream (or
 * as a third patch in our fork, since it is in a file we already patch) — not in Wither, whose
 * approach is legitimate and works everywhere except a REPL snippet.
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
