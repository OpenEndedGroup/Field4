package afield.examples

import afield.runtime.kotlin.KotlinInterface2
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.script.experimental.api.ScriptDiagnostic

/*
FIELD_TEST
id: kotlin.repl.provide-delegate
runner: jvm-main
entrypoint: afield.examples.ProvideDelegateReplSmokeTest
tags: [smoke, kotlin, repl, k2, delegate]
timeoutSec: 600
expect:
  exitCode: 0
  stdoutContains:
    - "provideDelegate REPL characterization passed"
*/
/**
 * `operator fun provideDelegate` in Field's K2 REPL, in every position box code can put it — the
 * box idiom `var kc by keyCamera(camera=camera)` (`public/docs/afield_four_graphics.md:787`) among
 * them. This began as a *characterization* of two crashes; both are fixed, and it now asserts that
 * every rung evaluates.
 *
 * ## History
 *
 * The test pinned the crash signatures while they existed, and was built to go red when either was
 * fixed. That alarm fired twice:
 *
 *  - **2026-08-25**, [DEFECT_A] was fixed in the fork: `cross-snippet-provideDelegate`, the
 *    `by keyCamera(...)` shape, started working, and the five rungs that embed [PROVIDER_DECL] moved
 *    from A to [DEFECT_B] (A had only been failing first).
 *  - **2026-10-04**, [DEFECT_B] was fixed in Field's build of the Wither plugin, and the nine rungs
 *    in [PROVIDE_DELEGATE] moved from failing to working.
 *
 * ## The two causes
 *
 * [DEFECT_A] was `IrPropertySymbolImpl is unbound`, thrown inside
 * `org.jetbrains.kotlin.fir.backend.Fir2IrConverter.runSourcesConversion`, with no Field frame and
 * no compiler-plugin frame between `K2ReplCompiler.compileImpl` and the throw. A REPL-level
 * delegated property is resolved through a disposable FIR copy of itself
 * (`replSnippetDelegatedPropertyCopies`, which upstream needs because the Analysis API forbids
 * resolving a sibling member mid-resolution), and the resolved delegate expression is then
 * installed into the real property — but with `provideDelegate` that expression carries a `::x`
 * callable reference resolved against the *copy*, which is not a snippet-class member and never
 * gets IR. The fork records the original on the copy and maps back to it in
 * `Fir2IrDeclarationStorage.prepareProperty`.
 *
 * [DEFECT_B] was `IrGenerationExtensionException: IrFunctionImpl … has no callableId`, thrown from
 * the Wither plugin's `WitherReceiverTransformer`. To find its own `with(...)` calls, that IR pass
 * read the `callableId` of every call standing as a statement, and `callableId` throws for a
 * function that is local or a member of a local or anonymous class. The `v = value` in
 * [PROVIDER_DECL]'s anonymous `ReadWriteProperty` is such a call (the setter of `v`), which is
 * why that interface failed and `ReadOnlyProperty`, with no setter to call, did not. Wither now
 * checks the callee's name and parent before asking. Local functions called as statements failed
 * the same way; `afield.runtime.kotlin.ReplSnippetDeclarationsSmokeTest` cases LF1–LF9 cover those.
 *
 * House style, applied deliberately:
 *  - **asserts its own premise first** — [assertReplItselfWorks] proves plain evaluation and the
 *    non-`provideDelegate` delegates yield `42` before anything else is judged.
 *  - **floored at a number** — every rung must produce its exact value, not merely "not crash".
 *  - **bidirectional ledger** — [assertLedger] checks missing, stale, and set equality across the
 *    declared and observed rung names.
 */
object ProvideDelegateReplSmokeTest {

    /** Signature of the fork-side, top-level failure. See [DEFECT_A]. */
    const val DEFECT_A = "IrPropertySymbolImpl is unbound"

    /**
     * Signature of the Wither-plugin failure, fixed 2026-10-04. Its trigger was not
     * `provideDelegate` and not local-ness: it was a call, as a statement, to a function with no
     * `callableId` — here the setter of a property of an anonymous `ReadWriteProperty`. See
     * `dev_docs/k2_repl_provide_delegate.md`.
     */
    const val DEFECT_B = "has no callableId"

    private const val PROVIDER_DECL = """
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
class Provider {
    operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): ReadWriteProperty<Any?, Int> {
        val n = 42
        return object : ReadWriteProperty<Any?, Int> {
            private var v = n
            override fun getValue(thisRef: Any?, property: KProperty<*>): Int = v
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) { v = value }
        }
    }
}
"""

    /**
     * A provider whose `provideDelegate` returns a **named** holder instead of an anonymous
     * `ReadWriteProperty`. It was the control that kept [DEFECT_A] and [DEFECT_B] apart while both
     * were open, because [PROVIDER_DECL] could not even be *declared* without tripping [DEFECT_B].
     */
    private const val NAMED_PROVIDER_DECL = """
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
class NamedHolder(private var v: Int) : ReadWriteProperty<Any?, Int> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): Int = v
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) { v = value }
}
class NamedProvider {
    operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): ReadWriteProperty<Any?, Int> =
        NamedHolder(42)
}
"""

    /**
     * Rungs that must evaluate to exactly [EXPECTED_VALUE]. Deliberately broad: these are seven
     * different ways to write `by` *without* a delegate provider, and all seven work. That breadth
     * is what licenses the claim that the defect is `provideDelegate` specifically rather than
     * delegation generally.
     */
    private val WORKING: Map<String, String> = linkedMapOf(
        "plain-val" to "val a = 40 + 2\na",
        "by-lazy" to "val a: Int by lazy { 42 }\na",
        "by-readwriteproperty" to """
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
class Holder : ReadWriteProperty<Any?, Int> {
    private var v = 42
    override fun getValue(thisRef: Any?, property: KProperty<*>): Int = v
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) { v = value }
}
val a: Int by Holder()
a
""",
        "by-delegates-observable" to """
import kotlin.properties.Delegates
var a: Int by Delegates.observable(42) { _, _, _ -> }
a
""",
        "by-delegates-notnull" to """
import kotlin.properties.Delegates
var a: Int by Delegates.notNull<Int>()
a = 42
a
""",
        "by-map-delegation" to "val m = mapOf(\"a\" to 42)\nval a: Int by m\na",
        "by-readonlyproperty-sam" to """
import kotlin.properties.ReadOnlyProperty
val a: Int by ReadOnlyProperty<Any?, Int> { _, _ -> 42 }
a
""",
    )

    /**
     * Working rungs that need a **prior snippet**, kept apart from [WORKING] only because they carry
     * one; they are asserted identically.
     *
     * `cross-snippet-provideDelegate` moved here from the failing ledger on 2026-08-25, when DEFECT_A was
     * root-caused and fixed in the vendored fork. It is the `by keyCamera(...)` shape — the provider
     * is AOT-compiled outside the snippet being compiled — so this is the rung that says the
     * user-facing idiom works again. It used [NAMED_PROVIDER_DECL] because [PROVIDER_DECL] tripped
     * [DEFECT_B] until 2026-10-04; it keeps it, so the AOT-style named provider stays covered.
     */
    private val WORKING_WITH_PRIOR: Map<String, Pair<String, String>> = linkedMapOf(
        "cross-snippet-provideDelegate" to
            (NAMED_PROVIDER_DECL to "var abcd: Int by NamedProvider()\nabcd\n"),
    )

    /**
     * Every `provideDelegate` position probed, as (prior snippet, source, expected value). These
     * were the failing ledger until 2026-10-04, all with [DEFECT_B]: `val` as well as `var`, an
     * extension `provideDelegate` as well as a member one, a generic provider, a factory function,
     * a local, and a class member, plus two rungs with no delegate machinery at all.
     */
    private val PROVIDE_DELEGATE: Map<String, Triple<String?, String, String>> = linkedMapOf(
        "toplevel-provideDelegate" to
            Triple(null, PROVIDER_DECL + "var abcd: Int by Provider()\nabcd\n", EXPECTED_VALUE),
        "factoryfn-provideDelegate" to
            Triple(null, PROVIDER_DECL + "fun provider(): Provider = Provider()\nvar abcd: Int by provider()\nabcd\n", EXPECTED_VALUE),
        "toplevel-val-provideDelegate" to
            Triple(null, PROVIDER_DECL + "val abcd: Int by Provider()\nabcd\n", EXPECTED_VALUE),
        "generic-provideDelegate" to Triple(
            null,
            """
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
class GProvider<T>(val initial: T) {
    operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): ReadWriteProperty<Any?, T> {
        val n = initial
        return object : ReadWriteProperty<Any?, T> {
            private var v = n
            override fun getValue(thisRef: Any?, property: KProperty<*>): T = v
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) { v = value }
        }
    }
}
var abcd: Int by GProvider(42)
abcd
""",
            EXPECTED_VALUE,
        ),
        "extension-provideDelegate" to Triple(
            null,
            """
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
class Plain
operator fun Plain.provideDelegate(thisRef: Any?, property: KProperty<*>): ReadWriteProperty<Any?, Int> {
    val n = 42
    return object : ReadWriteProperty<Any?, Int> {
        private var v = n
        override fun getValue(thisRef: Any?, property: KProperty<*>): Int = v
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) { v = value }
    }
}
var abcd: Int by Plain()
abcd
""",
            EXPECTED_VALUE,
        ),
        // --- The DEFECT_B trigger with no delegate machinery in it at all: an anonymous object
        // implementing kotlin.properties.ReadWriteProperty, created inside a function body. ---
        "anon-readwriteproperty-in-fun" to Triple(
            null,
            """
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
fun make(): ReadWriteProperty<Any?, Int> = object : ReadWriteProperty<Any?, Int> {
    private var v = 0
    override fun getValue(thisRef: Any?, property: KProperty<*>): Int = v
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) { v = value }
}
val p = make()
p.setValue(null, String::length, 42)
p.getValue(null, String::length)
""",
            EXPECTED_VALUE,
        ),
        // Declaring the idiomatic provider used to be enough — it is never used with `by` here.
        "declare-provideDelegate-only" to Triple(null, PROVIDER_DECL + "42\n", EXPECTED_VALUE),
        "local-provideDelegate" to
            Triple(null, PROVIDER_DECL + "fun demo(): Int {\n    var abcd: Int by Provider()\n    return abcd\n}\ndemo()\n", EXPECTED_VALUE),
        "classmember-provideDelegate" to
            Triple(null, PROVIDER_DECL + "class Owner { var abcd: Int by Provider() }\nOwner().abcd\n", EXPECTED_VALUE),
    )

    private const val EXPECTED_VALUE = "42"

    private class Run(val value: String?, val diagnostics: List<String>)

    private fun evaluate(label: String, src: String, priorSnippet: String? = null): Run = runBlocking {
        val diags = mutableListOf<String>()
        val ki = KotlinInterface2(filePrefix = "pd-${label.filter { it.isLetterOrDigit() }}")
        try {
            if (priorSnippet != null) {
                val priorDiags = mutableListOf<String>()
                withTimeout(240_000) {
                    ki.exec(priorSnippet, onCompileError = { ds -> ds.forEach { priorDiags += it.toString() } })
                }
                // The prior snippet is setup, not the thing under test. If it cannot compile, the
                // rung would "fail" for the wrong reason and quietly look like the defect.
                require(priorDiags.isEmpty()) {
                    "rung '$label': its PRIOR snippet failed to compile, so this rung proves nothing: $priorDiags"
                }
            }
            val result = withTimeout(240_000) {
                ki.exec(
                    src,
                    onCompileError = { ds: List<ScriptDiagnostic> -> ds.forEach { diags += it.toString() } },
                    onEvalError = { ds: List<ScriptDiagnostic> -> ds.forEach { diags += it.toString() } },
                    onEvalException = { t: Throwable -> diags += "THROWN ${t::class.qualifiedName}: ${t.message}" },
                )
            }
            Run(result?.toString(), diags)
        } finally {
            runCatching { ki.dispose() }
        }
    }

    /**
     * Premise gate. If the REPL cannot evaluate `40 + 2`, or cannot handle the *non*
     * `provideDelegate` delegate forms, the `provideDelegate` rungs below are not the problem. Fail
     * here, loudly, instead.
     */
    private fun assertReplItselfWorks(observedWorking: MutableMap<String, String>) {
        println("  premise: the REPL and non-provideDelegate delegation still work...")
        (WORKING.mapValues { (_, s) -> null to s } + WORKING_WITH_PRIOR).forEach { (label, spec) ->
            val (prior, src) = spec
            val run = evaluate(label, src, prior)
            require(run.diagnostics.isEmpty()) {
                "PREMISE FAILED: rung '$label' should compile cleanly but reported " +
                    "${run.diagnostics.size} diagnostic(s): ${run.diagnostics}. " +
                    "The REPL is broken more broadly than this test characterizes; fix that first."
            }
            val value = run.value
            require(value != null && value.contains(EXPECTED_VALUE)) {
                "PREMISE FAILED: rung '$label' should evaluate to $EXPECTED_VALUE, got: $value. " +
                    "A working rung that stops producing its value makes this test a tautology."
            }
            observedWorking[label] = value
            println("    ok  $label -> $value")
        }
    }

    /**
     * Every [PROVIDE_DELEGATE] rung must compile cleanly and produce its value. A rung that fails is
     * classified by signature, so a returning [DEFECT_A] or [DEFECT_B] reads as that regression
     * rather than as an unknown crash.
     */
    private fun assertProvideDelegateWorks(observedWorking: MutableMap<String, String>) {
        println("  provideDelegate in every position...")
        // Measure every rung, print the table, and only then judge. A compiler change moves rungs in
        // GROUPS -- fixing DEFECT_A on 2026-08-25 moved one rung to working and re-pointed five more
        // at DEFECT_B in a single step -- and failing on the first mismatch reports one rung while
        // hiding the shape of the move.
        val measured = PROVIDE_DELEGATE.mapValues { (_, spec) -> evaluate("measure", spec.second, spec.first) }
        println("  measured ledger (what this compiler actually does):")
        measured.forEach { (label, run) ->
            val expected = PROVIDE_DELEGATE.getValue(label).third
            val joined = run.diagnostics.joinToString("\n")
            println(
                "    %-34s %s".format(
                    label,
                    when {
                        run.diagnostics.isEmpty() && run.value?.contains(expected) == true -> "works -> ${run.value}"
                        run.diagnostics.isEmpty() -> "WRONG VALUE: ${run.value}, expected $expected"
                        joined.contains(DEFECT_A) -> "REGRESSED to DEFECT_A ($DEFECT_A)"
                        joined.contains(DEFECT_B) -> "REGRESSED to DEFECT_B ($DEFECT_B)"
                        else -> "FAILS: ${run.diagnostics.first().take(140)}"
                    }
                )
            )
        }
        PROVIDE_DELEGATE.forEach { (label, spec) ->
            val expected = spec.third
            val run = measured.getValue(label)
            require(run.diagnostics.isEmpty()) {
                "rung '$label' should compile and evaluate cleanly but reported " +
                    "${run.diagnostics.size} diagnostic(s): ${run.diagnostics}"
            }
            val value = run.value
            require(value != null && value.contains(expected)) {
                "rung '$label' should evaluate to $expected, got: $value"
            }
            observedWorking[label] = value
        }
    }

    /** Bidirectional: missing, stale, and set equality, in both directions. */
    private fun assertLedger(observedWorking: Map<String, String>) {
        println("  ledger: missing / stale / set equality...")
        val expectedWorking = WORKING.keys + WORKING_WITH_PRIOR.keys + PROVIDE_DELEGATE.keys

        val missingWorking = expectedWorking - observedWorking.keys
        val staleWorking = observedWorking.keys - expectedWorking

        require(missingWorking.isEmpty()) { "ledger: working rungs declared but never observed: $missingWorking" }
        require(staleWorking.isEmpty()) { "ledger: working rungs observed but not declared: $staleWorking" }
        require(observedWorking.keys == expectedWorking) { "ledger: working set mismatch" }

        // A floor, not an equality: rungs may be added, but a silently-shrinking matrix fails. 17 =
        // seven non-provideDelegate spellings of `by`, the cross-snippet rung, and the nine
        // provideDelegate positions that were the failing ledger until 2026-10-04.
        require(observedWorking.size >= 17) { "expected at least 17 working rungs, got ${observedWorking.size}" }
        println("    working=${observedWorking.size}")
    }

    fun runTests(): Boolean {
        return try {
            println("Running provideDelegate REPL characterization...")
            val observedWorking = linkedMapOf<String, String>()
            assertReplItselfWorks(observedWorking)
            assertProvideDelegateWorks(observedWorking)
            assertLedger(observedWorking)
            println("✅ provideDelegate REPL characterization passed")
            true
        } catch (t: Throwable) {
            println("❌ provideDelegate REPL characterization failed: ${t.message}")
            t.printStackTrace()
            false
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "provideDelegate REPL characterization failed" }
    }
}
