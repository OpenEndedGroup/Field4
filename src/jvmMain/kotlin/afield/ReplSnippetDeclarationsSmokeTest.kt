package afield

import afield.macros.standardExecutionPractice
import kotlinx.coroutines.runBlocking
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import kotlin.script.experimental.api.KotlinType
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.system.exitProcess

/*
FIELD_TEST
id: kotlin.repl-snippet-declarations.smoke
runner: jvm-main
entrypoint: afield.ReplSnippetDeclarationsSmokeTest
tags: [smoke, kotlin, k2, repl, regression]
timeoutSec: 900
expect:
  exitCode: 0
  stdoutContains:
    - "REPL snippet declarations smoke passed"
*/
/**
 * What a box may declare at its top level, and still compile.
 *
 * Two K2 REPL defects made ordinary box code fail, both fixed in field's Kotlin fork
 * (`~/temp/kotlin`, branch `field/repl-implicit-receiver-in-named-functions`) and neither fixed
 * upstream as of 2.4.20-RC2 — upstream's REPL codegen suite has no test for either shape:
 *
 *  1. **A named function that touches the implicit `Box` receiver.** `fun foo(a: Double) = a + _qqq`
 *     became a member of the snippet class, which has no `$script_receiver_n` parameter, so the
 *     receiver read reached the JVM backend as a dangling parameter and it failed with
 *     `No mapping for symbol: VALUE_PARAMETER SCRIPT_IMPLICIT_RECEIVER`. The fix stores the
 *     receivers in the snippet class's `$$implicitReceiver_*` fields, as the `.kts` script lowering
 *     already did. Cases A, B.
 *
 *     The `fun Box.foo` workaround had a second-order failure, case L: the *failed* snippet stayed
 *     in the REPL history, so the next run's `foo(12.0)` bound to the dead snippet's plain `foo`
 *     (a member beats an extension) and threw `null cannot be cast to non-null type …__code__N`
 *     from the `ReplState` lookup. With (1) fixed there is no failed snippet, so the sequence is
 *     clean — but see [staleDeclarationShadowing], which is still open.
 *
 *  2. **An expression-bodied function that reaches the receiver.** `fun f(a: Double) = println(a)`
 *     failed at *resolution* — `Candidate 'fun Box._printlnHere(...)' is inapplicable because of a
 *     receiver type mismatch` — while the same body in braces was fine. Only an inferred return type
 *     takes the jumping-return-type path, whose designation looked for a `FirScript` and so never
 *     found a `FirReplSnippet`, leaving the snippet's implicit receivers off the tower. Cases S, T, U.
 *
 *  3. **A delegated property whose provider declares `provideDelegate`** — `var x by keyCamera()`.
 *     Such a property is resolved through a disposable FIR copy of itself; the resolved delegate
 *     expression carries a `::x` reference to that copy, which gets no IR, so fir2ir failed with
 *     `IrPropertySymbolImpl is unbound`. Cases N, Q.
 *
 * Each case drives the real engine: a fresh [KotlinInterface2] with an implicit `Box` receiver, the
 * stdlib `intro`, then [standardExecutionPractice] — the same preamble-then-body snippet sequence a
 * launched box runs through.
 */
object ReplSnippetDeclarationsSmokeTest {

    /** The preamble `@exported var qqq = 10.0` upstream leaves behind for a child box. */
    private const val EXPORT_PREAMBLE = "var Box._qqq by InheritanceFromContext<kotlin.Double>() /**/"

    private val failures = mutableListOf<String>()

    private class Outcome(val verdict: String, val results: List<ResultValue?>, val detail: String)

    private fun engineFor(tag: String, preamble: String?): Pair<KotlinInterface2, Box> {
        val box = Box("repl-decls-$tag")
        // `_qqq` resolves against this box itself, so no topology (and no session) is needed.
        box.setPropertyRaw("qqq", 10.0, doResync = false)
        preamble?.let { box.preamble = it }
        val ki = KotlinInterface2(
            implicitReceivers = listOf(KotlinType(Box::class)),
            filePrefix = "REPLDECLS_${tag}_"
        )
        ki.associatedBox = box
        box.ki = ki
        return ki to box
    }

    private suspend fun run(ki: KotlinInterface2, box: Box, code: String): Outcome {
        val compileErrors = mutableListOf<String>()
        val evalErrors = mutableListOf<String>()
        var thrown: Throwable? = null
        val results = try {
            standardExecutionPractice(
                ki = ki,
                s = code,
                onCompileError = { d -> d.forEach { compileErrors += firstLine(it) } },
                onEvalError = { d -> d.forEach { evalErrors += firstLine(it) } },
                onEvalException = { t -> thrown = t },
                implicits = listOf(box),
                onSetupCompileError = { block, d -> d.forEach { compileErrors += "$block: ${firstLine(it)}" } },
                onSetupEvalError = { block, d -> d.forEach { evalErrors += "$block: ${firstLine(it)}" } },
                onSetupEvalException = { _, t -> thrown = t },
            )
        } catch (t: Throwable) {
            thrown = t
            emptyList()
        }
        val verdict = when {
            compileErrors.isNotEmpty() -> "COMPILE-ERROR"
            thrown != null || evalErrors.isNotEmpty() -> "EVAL-ERROR"
            else -> "ok"
        }
        val detail = (compileErrors + evalErrors + listOfNotNull(thrown?.let { "${it::class.simpleName}: ${firstLine(it)}" }))
            .joinToString(" | ").take(400)
        return Outcome(verdict, results, detail)
    }

    /** Run [code] in a fresh engine and require it to evaluate to [expected]. */
    private fun case(label: String, preamble: String?, code: String, expected: String) = runBlocking {
        val tag = label.filter { it.isLetterOrDigit() }
        val (ki, box) = engineFor(tag, preamble)
        try {
            ki.exec(intro, implicits = listOf(box))
            val outcome = run(ki, box, code)
            val value = outcome.results.lastOrNull()?.toString().orEmpty()
            val ok = outcome.verdict == "ok" && expected in value
            println("  ${if (ok) "ok  " else "FAIL"} $label" + if (ok) "" else "  ${outcome.verdict} ${outcome.detail}")
            if (!ok) {
                failures += "$label: expected a result containing \"$expected\", got ${outcome.verdict} " +
                        (outcome.detail.ifBlank { value })
            }
        } finally {
            ki.dispose()
        }
    }

    /**
     * Require [code] to COMPILE, without evaluating it.
     *
     * For the real `keyCamera(...)` delegate: the defect this file covers was a *compile* failure, and
     * evaluating this line headlessly cannot work for an unrelated reason -- `keyCamera()` defaults its
     * `renderer` to `fromContext()`, so constructing the provider needs a live renderer in the box
     * context, i.e. a GPU. Compiling it is the faithful check of what changed; the delegate mechanism
     * itself is evaluated end-to-end by the provideDelegate cases above, which use the same shape
     * (a compiled provider returning an anonymous `ReadWriteProperty`).
     */
    private fun compilesOnly(label: String, code: String) = runBlocking {
        val tag = label.filter { it.isLetterOrDigit() }
        val (ki, box) = engineFor(tag, null)
        try {
            ki.exec(intro, implicits = listOf(box))
            val compileErrors = mutableListOf<String>()
            ki.compileSnippet(
                code,
                implicits = listOf(box),
                onCompileError = { d -> d.forEach { compileErrors += firstLine(it) } },
            )
            val ok = compileErrors.isEmpty()
            println("  ${if (ok) "ok  " else "FAIL"} $label (compile only)" + if (ok) "" else "  ${compileErrors.first().take(240)}")
            if (!ok) failures += "$label: expected a clean compile, got ${compileErrors.first().take(240)}"
        } finally {
            ki.dispose()
        }
    }

    /** Run several code versions through ONE engine, requiring each to evaluate to its expectation. */
    private fun sequence(label: String, preamble: String?, steps: List<Pair<String, String>>) = runBlocking {
        val tag = label.filter { it.isLetterOrDigit() }
        val (ki, box) = engineFor(tag, preamble)
        try {
            ki.exec(intro, implicits = listOf(box))
            steps.forEachIndexed { index, (code, expected) ->
                val outcome = run(ki, box, code)
                val value = outcome.results.lastOrNull()?.toString().orEmpty()
                val ok = outcome.verdict == "ok" && expected in value
                println("  ${if (ok) "ok  " else "FAIL"} $label [step $index]" + if (ok) "" else "  ${outcome.verdict} ${outcome.detail}")
                if (!ok) {
                    failures += "$label step $index: expected a result containing \"$expected\", got " +
                            "${outcome.verdict} ${outcome.detail.ifBlank { value }}"
                }
            }
        } finally {
            ki.dispose()
        }
    }

    /**
     * Report — without asserting — that a plain `fun foo` from an earlier run of the same box still
     * wins over a `fun Box.foo` declared by a later run.
     *
     * A member beats an extension in Kotlin's own resolution rules, and both snippets are live in
     * the REPL history, so the later declaration is silently ignored: the second run below returns
     * the *first* function's answer. This is the surviving half of the stale-declaration problem
     * (its loud half, a `null cannot be cast to non-null type …__code__N` from a snippet that failed
     * codegen, is gone now that such snippets no longer exist). Printed rather than asserted so the
     * day it changes is visible in the log instead of being a test failure.
     */
    private fun staleDeclarationShadowing() = runBlocking {
        val (ki, box) = engineFor("staleshadow", EXPORT_PREAMBLE)
        try {
            ki.exec(intro, implicits = listOf(box))
            run(ki, box, "var m = 10\nfun foo(a: Double): Double { return a + 20 + m }\nfoo(12.0)")
            val second = run(
                ki, box,
                "var m = 10\nfun Box.foo(a: Double): Double { return a + 20 + m + _qqq }\nfoo(12.0)"
            )
            val value = second.results.lastOrNull()?.toString().orEmpty()
            val shadowed = "42.0" in value
            println(
                "  note KNOWN OPEN stale-declaration shadowing: a later `fun Box.foo` " +
                        (if (shadowed) "is STILL shadowed by the earlier plain `foo` (got 42.0, wanted 52.0)"
                        else "now wins (got $value) - the shadowing is gone, update this test")
            )
        } finally {
            ki.dispose()
        }
    }

    private fun firstLine(d: ScriptDiagnostic): String =
        d.render().lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: d.message

    private fun firstLine(t: Throwable): String =
        t.message?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: t::class.simpleName ?: "?"

    @JvmStatic
    fun main(args: Array<String>) {

        println("-- a named function that touches the implicit Box receiver --")
        case(
            "A plain fun reads an inherited (preamble) property", EXPORT_PREAMBLE,
            """
            var m = 10
            fun foo(a: Double): Double { return a + 20 + m + _qqq }
            foo(12.0)
            """.trimIndent(),
            expected = "= 52.0"
        )
        case(
            "B plain fun reads a Box member", null,
            """
            fun foo(a: Double): String { return a.toString() + name }
            foo(12.0)
            """.trimIndent(),
            expected = "= 12.0"
        )
        case(
            "C plain fun reads only snippet-local state", null,
            """
            var m = 10
            fun foo(a: Double): Double { return a + 20 + m }
            foo(12.0)
            """.trimIndent(),
            expected = "= 42.0"
        )

        println("-- the same, written as an extension on Box (the pre-fix workaround) --")
        case(
            "D fun Box.foo called at top level", EXPORT_PREAMBLE,
            """
            var m = 10
            fun Box.foo(a: Double): Double { return a + 20 + m + _qqq }
            foo(12.0)
            """.trimIndent(),
            expected = "= 52.0"
        )
        case(
            "E fun Box.foo called inside a lambda", EXPORT_PREAMBLE,
            """
            var m = 10
            fun Box.foo(a: Double): Double { return a + 20 + m + _qqq }
            run { foo(12.0) }
            """.trimIndent(),
            expected = "= 52.0"
        )
        case(
            "F fun Box.foo called via thisBox inside a lambda", EXPORT_PREAMBLE,
            """
            var m = 10
            fun Box.foo(a: Double): Double { return a + 20 + m + _qqq }
            run { thisBox.foo(12.0) }
            """.trimIndent(),
            expected = "= 52.0"
        )

        println("-- reading the receiver from other positions --")
        case(
            "G preamble property at snippet top level", EXPORT_PREAMBLE,
            "_qqq + 1.0", expected = "= 11.0"
        )
        case(
            "H preamble property inside a lambda", EXPORT_PREAMBLE,
            "run { _qqq + 1.0 }", expected = "= 11.0"
        )
        case(
            "I local fun inside a lambda", EXPORT_PREAMBLE,
            """
            run {
                fun foo(a: Double): Double { return a + 20 + _qqq }
                foo(12.0)
            }
            """.trimIndent(),
            expected = "= 42.0"
        )
        case(
            "J val holding a lambda", EXPORT_PREAMBLE,
            """
            var m = 10
            val foo = { a: Double -> a + 20 + m + _qqq }
            foo(12.0)
            """.trimIndent(),
            expected = "= 52.0"
        )

        println("-- the edit sequence that used to leave a dead snippet behind --")
        sequence(
            "L plain fun, then the fun Box.foo version, then thisBox", EXPORT_PREAMBLE,
            listOf(
                """
                var m = 10
                fun foo(a: Double): Double { return a + 20 + m + _qqq }
                foo(12.0)
                """.trimIndent() to "= 52.0",
                """
                var m = 10
                fun Box.foo(a: Double): Double { return a + 20 + m + _qqq }
                thisBox.foo(12.0)
                """.trimIndent() to "= 52.0",
            )
        )

        println("-- delegated properties at snippet top level --")
        case(
            "N var by a provideDelegate provider", null,
            """
            import afield.ReplSnippetDeclarationsSmokeTest.provideDelegateProvider
            var x by provideDelegateProvider()
            x
            """.trimIndent(),
            expected = "= 1"
        )
        case(
            "O var by a plain getValue/setValue delegate", null,
            """
            import afield.ReplSnippetDeclarationsSmokeTest.plainDelegate
            var x by plainDelegate()
            x
            """.trimIndent(),
            expected = "= 7"
        )
        case(
            "P local var by a provideDelegate provider, inside a lambda", null,
            """
            import afield.ReplSnippetDeclarationsSmokeTest.provideDelegateProvider
            run {
                var x by provideDelegateProvider()
                x
            }
            """.trimIndent(),
            expected = "= 1"
        )
        case(
            "Q val by a provideDelegate provider", null,
            """
            import afield.ReplSnippetDeclarationsSmokeTest.provideDelegateProvider
            val x by provideDelegateProvider()
            x
            """.trimIndent(),
            expected = "= 1"
        )

        println("-- EXPRESSION-bodied functions that reach the receiver --")
        // A third defect of the same family, fixed in the fork on 2026-08-25 and pre-dating the two
        // above. `FirImplicitBodyResolve.resolveDeclaration` builds the designation for an INFERRED
        // return type as file + script + outerClasses, finding the script with
        // `firstIsInstanceOrNull<FirScript>()` -- which can never match a `FirReplSnippet`. Without
        // the snippet in that designation the designated transformer never runs `withReplSnippet`,
        // so the snippet's implicit receivers are absent from the tower and the box's own members
        // come back "inapplicable because of a receiver type mismatch". Only inferred return types
        // take that jump, which is why every case above (block bodies, all of them) resolved fine.
        case(
            "S expr body calling println", null,
            "fun f(a : Double) = println(\"\"\" \${a} \"\"\")\nf(10.0)",
            expected = "Unit"
        )
        case(
            "T expr body reading a Box member", null,
            "fun f(a : Double) = name + a\nf(10.0)",
            expected = "= 10.0"
        )
        case(
            "U expr body calling _printlnHere directly", null,
            "fun f(a : Double) = _printlnHere(1, a)\nf(10.0)",
            expected = "Unit"
        )

        println("-- the real keyCamera delegate, as five published sites spell it --")
        compilesOnly(
            "R var kc by keyCamera(camera=camera)",
            "import afield.four.util.keyCamera\nval camera = Camera()\nvar kc by keyCamera(camera=camera)\nkc"
        )

        println("-- known-open behaviour, reported not asserted --")
        staleDeclarationShadowing()

        if (failures.isNotEmpty()) {
            System.err.println("REPL snippet declarations smoke FAILED with ${failures.size} problem(s):")
            failures.forEach { System.err.println("  - $it") }
            exitProcess(1)
        }
        println("REPL snippet declarations smoke passed")
    }

    // --- delegates the cases above declare in box code, in the two shapes that differ ---

    /** Needs `provideDelegate`, the shape `keyCamera()` / `orbitControls()` have. */
    class ProvideDelegateProvider {
        operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): ReadWriteProperty<Any?, Int> =
            object : ReadWriteProperty<Any?, Int> {
                private var v = property.name.length
                override fun getValue(thisRef: Any?, property: KProperty<*>) = v
                override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) { v = value }
            }
    }

    /** The same value through a plain delegate — no `provideDelegate`. */
    class PlainDelegate(private var v: Int = 7) {
        operator fun getValue(thisRef: Any?, property: KProperty<*>) = v
        operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) { v = value }
    }

    fun provideDelegateProvider() = ProvideDelegateProvider()

    fun plainDelegate() = PlainDelegate()
}
