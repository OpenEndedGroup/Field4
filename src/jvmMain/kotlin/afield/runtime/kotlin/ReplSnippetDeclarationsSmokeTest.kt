package afield.runtime.kotlin

import afield.app.diagnostics.named
import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.document.graph.nested
import afield.runtime.execution.plus
import afield.runtime.lifecycle.end
import afield.runtime.lifecycle.map
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.runtime.lifecycle.then
import afield.ui.notation.with
import afield.ui.output._printlnHere
import afield.ui.output.history
import afield.ui.output.update

import afield.audio.synth.*
import afield.document.graph.Box
import afield.document.graph.box
import afield.document.graph.doResync
import afield.document.graph.intro
import afield.document.graph.preamble
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import afield.ui.output.templates.filter
import afield.runtime.macros.standardExecutionPractice
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
entrypoint: afield.runtime.kotlin.ReplSnippetDeclarationsSmokeTest
tags: [smoke, kotlin, k2, repl, regression]
timeoutSec: 900
expect:
  exitCode: 0
  stdoutContains:
    - "REPL snippet declarations smoke passed"
*/
/**
 * Smoke test of what a box may declare at its top level and still compile under the K2 REPL.
 *
 * Each case is a shape of ordinary box code that the stock 2.4 K2 REPL fails to compile or load,
 * and that Field's Kotlin fork (and, for the last group, Field's build of the Wither plugin) handles:
 *
 * 1. **A named function that touches the implicit `Box` receiver**, such as
 *    `fun foo(a: Double) = a + _qqq`. The fork stores the receivers in the snippet class's
 *    implicit-receiver fields, as the `.kts` script lowering does. Cases A, B, and case L, which
 *    checks that a later run binds `foo(12.0)` to the live declaration.
 * 2. **An expression-bodied function that reaches the receiver**, such as
 *    `fun f(a: Double) = println(a)`. Only an inferred return type takes this resolution path, so
 *    the same body in braces is a separate shape. Cases S, T, U.
 * 3. **A delegated property whose provider declares `provideDelegate`**, such as
 *    `var x by keyCamera()`. Cases N, Q.
 * 4. **A destructuring declaration with a `_` entry**, such as `val (a, _) = p`. The fork builds the
 *    `_` entry as a local of the snippet's evaluation body rather than a class property. Cases V to
 *    Y, with Z1 to Z4 covering the name-based forms that reach the same path.
 * 5. **A statement-level call to a function with no `callableId`**: a local function, or a member
 *    of a local or anonymous class, which Wither's IR pass has to tolerate. Cases LF1 to LF9.
 * 6. **A class or object that reaches the box receiver**, such as
 *    `class C { fun g() { println("hi") } }` -- a bare `println` or `display` is a member of the box.
 *    The REPL lowering makes no class capturing, so the read had no snippet `this` and crashed with
 *    "Unexpected script transformation state"; the fork now reads the snippet's own instance from the
 *    REPL state. Cases CR1 to CR6; CR6 constructs the class from a later snippet.
 *
 * One case is still open: a declaration from an earlier snippet can shadow a later one of the
 * same name, and the test records that behaviour rather than asserting a fix.
 *
 * Each case drives the real engine: a fresh [KotlinInterface2] with an implicit `Box` receiver, the
 * stdlib `intro`, then [standardExecutionPractice], the same preamble-then-body snippet sequence a
 * launched box runs through.
 */

/** Runs every top-level declaration case on a fresh engine; `main` exits non-zero if any case failed. */
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
            import afield.runtime.kotlin.ReplSnippetDeclarationsSmokeTest.provideDelegateProvider
            var x by provideDelegateProvider()
            x
            """.trimIndent(),
            expected = "= 1"
        )
        case(
            "O var by a plain getValue/setValue delegate", null,
            """
            import afield.runtime.kotlin.ReplSnippetDeclarationsSmokeTest.plainDelegate
            var x by plainDelegate()
            x
            """.trimIndent(),
            expected = "= 7"
        )
        case(
            "P local var by a provideDelegate provider, inside a lambda", null,
            """
            import afield.runtime.kotlin.ReplSnippetDeclarationsSmokeTest.provideDelegateProvider
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
            import afield.runtime.kotlin.ReplSnippetDeclarationsSmokeTest.provideDelegateProvider
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

        println("-- destructuring with an underscore at snippet top level --")
        // Found 2026-09-28. A top-level `_` entry became a snippet-class property named
        // `<unused var>`, and loading the class failed with `ClassFormatError: Illegal method name
        // "get<unused var>"` -- an eval-time diagnostic that Field did not report, so the box
        // looked as though it never finished. `(a, b)` was always fine. Fixed in the fork: a `_`
        // entry is a local of `$$eval`, as inside any function body.
        case(
            "V (a, _) = pair", null,
            "val p = 1f to 2f\nval (a, _) = p\na",
            expected = "= 1.0"
        )
        case(
            "W (_, b) and (_, _, c) positional", null,
            "val (_, b) = 1 to 2\nval (_, _, c) = Triple(3, 4, 5)\nb * 10 + c",
            expected = "= 25"
        )
        case(
            "X var (x, _) with a list", null,
            "var (x, _) = listOf(40, 99)\nx += 2\nx",
            expected = "= 42"
        )
        sequence(
            "Y named entries of a `_` destructuring stay visible to the next snippet", null,
            listOf(
                "val (a, _) = 6 to 0\na" to "= 6",
                "a * 7" to "= 42",
            )
        )

        println("-- name-based destructuring (full form and [a, b]; short form off) at snippet top level --")
        // Field enables the full form and square-bracket positional form, not the short form, so a
        // plain `(a, b)` stays positional (case V). These go through the same snippet path as the
        // `_` fix above; Z2's `val _ = second` is a renamed underscore, which must stay a local.
        case(
            "Z1 (val age, val name) = data class, by name and out of order", null,
            "data class P(val name: String, val age: Int)\n(val age, val name) = P(\"Ada\", 36)\n\"\$name \$age\"",
            expected = "= Ada 36"
        )
        case(
            "Z2 (val n = first, val _ = second) renamed with an underscore", null,
            "(val n = first, val _ = second) = \"left\" to \"right\"\nn",
            expected = "= left"
        )
        case(
            "Z3 [_, z] square-bracket positional", null,
            "val [x, y] = listOf(10, 20)\nval [_, z] = Triple(1, 2, 3)\nx + y + z",
            expected = "= 32"
        )
        sequence(
            "Z4 a name-based entry stays visible to the next snippet", null,
            listOf(
                "(val first, val _ = second) = 6 to 0\nfirst" to "= 6",
                "first * 7" to "= 42",
            )
        )

        println("-- calls, as statements, to functions that have no callableId --")
        // Found 2026-09-16 and again 2026-10-04 in box code; fixed in Field's build of the Wither
        // plugin (libs/wither-plugin.jar), not in the fork. Wither's IR pass asked every call that
        // stands as a statement for its callee's `callableId`, which throws for a local function and
        // for a member of a local or anonymous class, a property setter included. The compile then
        // died with `IrGenerationExtensionException: IrFunctionImpl@... has no callableId`, reported
        // against the box's last line. A call whose value is used, as in case I, is never a
        // statement, which is why I passed throughout.
        case(
            "LF1 local fun in run { }, called as a statement", null,
            """
            var out = 0
            run {
                fun bump() { out += 1 }
                bump()
                bump()
            }
            out
            """.trimIndent(),
            expected = "= 2"
        )
        case(
            "LF2 local fun inside a named fun", null,
            """
            var total = 0
            fun outer(): Int {
                fun inner(k: Int) { total += k }
                inner(2)
                inner(3)
                return total
            }
            outer()
            """.trimIndent(),
            expected = "= 5"
        )
        case(
            "LF3 local fun inside a local fun, inside a lambda", null,
            """
            run {
                var n = 0
                fun outer() {
                    fun inner() { n += 10 }
                    inner()
                    inner()
                }
                outer()
                n
            }
            """.trimIndent(),
            expected = "= 20"
        )
        case(
            "LF4 local fun capturing a lambda parameter, in listOf(...).map { }", null,
            """
            var count = 0
            val doubled = listOf(1, 2, 3).map { x ->
                fun add() { count += x }
                add()
                x * 2
            }
            "${'$'}count ${'$'}doubled"
            """.trimIndent(),
            expected = "= 6 [2, 4, 6]"
        )
        case(
            "LF5 local fun capturing enclosing vars and a List, the once.keys shape", null,
            """
            var x = 0.0
            var y = 0.0
            val sizes = listOf(3.0, 4.0, 5.0)
            val placed = mutableListOf<String>()
            listOf("a", "b", "c").forEach { key ->
                fun layout() {
                    x += sizes[placed.size]
                    y = x * 2
                    placed += "${'$'}key@${'$'}x,${'$'}y"
                }
                layout()
            }
            placed.joinToString(" ")
            """.trimIndent(),
            expected = "= a@3.0,6.0 b@7.0,14.0 c@12.0,24.0"
        )
        case(
            "LF6 local fun reading an inherited (preamble) property", EXPORT_PREAMBLE,
            """
            var acc = 0.0
            run {
                fun add() { acc += _qqq }
                add()
                add()
            }
            acc
            """.trimIndent(),
            expected = "= 20.0"
        )
        case(
            "LF7 member of a local class, called as a statement", null,
            """
            run {
                class Counter { var n = 0; fun bump() { n += 1 } }
                val c = Counter()
                c.bump()
                c.bump()
                c.n
            }
            """.trimIndent(),
            expected = "= 2"
        )
        case(
            "LF8 property write inside an anonymous ReadWriteProperty", null,
            """
            import kotlin.properties.ReadWriteProperty
            import kotlin.reflect.KProperty
            fun make(): ReadWriteProperty<Any?, Int> = object : ReadWriteProperty<Any?, Int> {
                private var v = 40
                override fun getValue(thisRef: Any?, property: KProperty<*>): Int = v
                override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) { v = value }
            }
            var a: Int by make()
            a += 2
            a
            """.trimIndent(),
            expected = "= 42"
        )
        case(
            "LF9 property write inside an anonymous ReadOnlyProperty", null,
            """
            import kotlin.properties.ReadOnlyProperty
            import kotlin.reflect.KProperty
            fun make(): ReadOnlyProperty<Any?, Int> = object : ReadOnlyProperty<Any?, Int> {
                private var v = 0
                override fun getValue(thisRef: Any?, property: KProperty<*>): Int { v = 42; return v }
            }
            val a: Int by make()
            a
            """.trimIndent(),
            expected = "= 42"
        )

        println("-- a class or object that reaches the box receiver --")
        case("CR1 println inside a class member", null,
            "class C { fun g(): Int { println(\"from C\"); return 42 } }\nC().g()", expected = "42")
        case("CR2 display inside a class member", null,
            "class C { fun g(): Int { display(\"from C\"); return 42 } }\nC().g()", expected = "42")
        case("CR3 a lambda property of a class", null,
            "class C { val f = { println(\"from f\"); 42 } }\nC().f()", expected = "42")
        case("CR4 an object", null,
            "object O { fun g(): Int { println(\"from O\"); return 42 } }\nO.g()", expected = "42")
        case("CR5 the box's own property, read inside a class", null,
            "class C { fun g() = name == thisBoxName }\nval thisBoxName = name\nC().g()", expected = "true")
        case("CR6 a class declared in one snippet, constructed in the next",
            "class C(val n: Int) { fun g(): Int { println(\"from C\"); return n } }",
            "C(42).g()", expected = "42")

        println("-- the real keyCamera delegate, as five published sites spell it --")
        compilesOnly(
            "R var kc by keyCamera(camera=camera)",
            "import afield.graphics.vulkan.util.keyCamera\nval camera = Camera()\nvar kc by keyCamera(camera=camera)\nkc"
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

/*
FIELD_TEST
id: kotlin.repl-local-fun-statements.box
runner: backdoor-box
tags: [smoke, kotlin, k2, repl, regression, backdoor]
timeoutSec: 120
expect:
  outputContains:
    - "local-fun-statements 2 5 20 6 [2, 4, 6] [a@3.0, b@7.0, c@12.0] 2"
*/
/**
 * Cases LF1–LF5 and LF7 above, run as one real box through the backdoor rather than through a bare
 * engine. Each value printed comes from a call, as a statement, to a function with no `callableId`:
 * before the Wither fix any one of them failed the whole box with
 * `IrGenerationExtensionException: … has no callableId`.
 */
private val LOCAL_FUN_STATEMENTS_SNIPPET = """
var out = 0
run {
    fun bump() { out += 1 }
    bump()
    bump()
}

var total = 0
fun outer(): Int {
    fun inner(k: Int) { total += k }
    inner(2)
    inner(3)
    return total
}

var nested = 0
run {
    fun outerLocal() {
        fun innerLocal() { nested += 10 }
        innerLocal()
        innerLocal()
    }
    outerLocal()
}

var count = 0
val doubled = listOf(1, 2, 3).map { x ->
    fun add() { count += x }
    add()
    x * 2
}

var px = 0.0
val sizes = listOf(3.0, 4.0, 5.0)
val placed = mutableListOf<String>()
listOf("a", "b", "c").forEach { key ->
    fun layout() {
        px += sizes[placed.size]
        placed += key + "@" + px
    }
    layout()
}

val bumps = run {
    class Counter { var n = 0; fun bump() { n += 1 } }
    val c = Counter()
    c.bump()
    c.bump()
    c.n
}

println(listOf("local-fun-statements", out, outer(), nested, count, doubled, placed, bumps).joinToString(" "))
""".trimIndent()
