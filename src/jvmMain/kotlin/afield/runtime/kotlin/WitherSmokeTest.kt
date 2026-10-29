package afield.runtime.kotlin

import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.document.graph.box
import afield.runtime.execution.plus
import afield.runtime.lifecycle.end
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.ui.notation.with

import afield.audio.synth.*
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/*
FIELD_TEST
id: kotlin.wither-plugin.smoke
runner: jvm-main
entrypoint: afield.runtime.kotlin.WitherSmokeTest
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
 * `testReceiverInjection` is the assertion that decides the run: a bare `greet()` with no explicit
 * receiver only resolves if Wither injected the `Greeter` receiver. Without the plugin the
 * snippet fails to compile (`unresolved reference 'greet'`), so a green run shows that the plugin
 * is registered into the REPL's compilation config and working end to end.
 *
 * `testPlainEvalStillWorks` is the regression half: turning the plugin on must not disturb
 * ordinary snippet evaluation.
 *
 * Runnable-object test style, matching [ReplBootstrapGateTest].
 *
 * ### Red from 2026-08-25 to 2026-10-04: Wither's IR pass never recognised its own `with`
 *
 * `testReceiverInjection` failed with an `IrErrorCallExpression` reaching codegen:
 *
 *     Unresolved reference: this@R|<local>/<with holder>|
 *     AssertionError: Unexpected IR element found during code generation ... or it should have been lowered
 *
 * fir2ir emitting that error call is expected. Wither's FIR half binds the injected receiver to a
 * synthetic holder that fir2ir cannot translate, and its IR half (`WitherReceiverTransformer`) exists
 * to patch it: it removes each `with(...)` statement, stores the argument in a temporary, and
 * replaces each `<with holder>` error call with a read of that temporary. The bug was that the pass
 * never matched the `with(...)` call. The IR dump in the failure still held
 *
 *     CALL 'public final fun with (vararg values: kotlin.Any?): kotlin.Unit [companion] declared in io.github.kyay10.wither.WithKt'
 *
 * The callee comes from `libs/wither-annotations.jar`, and in IR its parent is the JVM file facade
 * class, not the package fragment. A temporary probe in the pass printed `parent=IrClassImpl
 * parentOrigin=FILE_CLASS isTopLevel=true kotlinFqName=io.github.kyay10.wither`. Wither matched on
 * `callableId` against the package-level `CallableId(io.github.kyay10.wither, with)`; under a
 * facade class that id is `io/github/kyay10/wither/WithKt.with`, so the two were never equal. (Since
 * 5052517 the pass refused any parent that was not a package fragment, which fails the same way.)
 * `~/temp/wither` `field/kotlin-2.5.0-beta1` @ face220 accepts both top-level shapes and compares
 * package plus name.
 *
 * Measured 2026-10-04 on Kotlin 2.5.0-Beta1, same worktree and harness, changing only
 * `libs/wither-plugin.jar`:
 *
 *  - the jar on `main` before the swap (sha1 `2869851…`): XFAIL, `with` CALL still in the IR, `got: null`;
 *  - 5052517 (`1e6f4c6…`): XFAIL, same IR, same `got: null`;
 *  - face220 (`be020c9…`): passes; `demo()` returns `hi-W`.
 *
 * With face220, `kotlin.repl-snippet-declarations.smoke`, `kotlin.repl.provide-delegate` and
 * `kotlin.repl-local-fun-statements.box` also pass. Those runs used the versions of those tests that
 * expect 5052517's `has no callableId` fix.
 *
 * Not measured: why the jar set from before the 2.4.20 uplift passed. The earlier diagnosis in this
 * KDoc blamed `Fir2IrVisitor.visitThisReceiverExpression` for producing the error call. That is true,
 * but fir2ir is supposed to produce it, as described above. Nobody checked how the older compiler
 * parented the callee.
 *
 * When rebuilding the jar: `JAVA_HOME=<jdk-22> ./gradlew --configure-on-demand :compiler-plugin:jar`
 * in `~/temp/wither` (class file 66; the compiler host is a JDK 22). The build is reproducible, so
 * compare `shasum` with `libs/`.
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
