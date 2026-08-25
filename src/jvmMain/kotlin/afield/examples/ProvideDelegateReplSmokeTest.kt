package afield.examples

import afield.KotlinInterface2
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
 * Characterization of a real, user-facing defect: **`operator fun provideDelegate` does not
 * compile in Field's K2 REPL**, so the box idiom `var kc by keyCamera(camera=camera)` — which
 * `public/docs/afield_four_graphics.md:787` documents — cannot be run.
 *
 * ## Why this test is GREEN while the feature is BROKEN
 *
 * This is a *characterization* test: it pins the behaviour that exists today, including the exact
 * crash signatures, so the finding cannot be lost again. It is deliberately green on a broken
 * REPL. **If someone fixes the underlying compiler, this test goes red** — that is the intended
 * alarm, not a regression. When it does, read [DEFECT_A] / [DEFECT_B] below, delete the rung from
 * the failing ledger, and move it to the working one.
 *
 * ## The boundary, which is the actual finding
 *
 * `by` delegation is *not* broken in general. What breaks is precisely `provideDelegate`:
 *
 *  - `val a: Int by lazy { 42 }` — works.
 *  - `val a: Int by Holder()` where `Holder : ReadWriteProperty` — works.
 *  - `var a: Int by Provider()` where `Provider` has `operator fun provideDelegate` — **crashes**,
 *    in all four positions tested (script top level, local in a function, class member, and
 *    reached through a factory function).
 *
 * ## Two independent causes
 *
 * [DEFECT_A] **top-level** `by provideDelegate` — `IrPropertySymbolImpl is unbound` thrown inside
 * `org.jetbrains.kotlin.fir.backend.Fir2IrConverter.runSourcesConversion`, with **no Field frame
 * and no compiler-plugin frame** between `K2ReplCompiler.compileImpl` and the throw. This is in
 * the vendored Kotlin compiler fork. It is the one that breaks `by keyCamera(...)`, and it is
 * *not* fixable from Field's own source — see `dev_docs/k2_repl_provide_delegate.md` for the
 * isolation runs.
 *
 * [DEFECT_B] **local / class-member** `by provideDelegate` — `IrGenerationExtensionException:
 * IrFunctionImpl … has no callableId`, thrown out of the Wither plugin's
 * `WitherReceiverTransformer`. Wither reaches the compiler by ServiceLoader because
 * `libs/wither-plugin.jar` is an `implementation` dependency (`build.gradle.kts:943`); with that
 * one line removed both of these rungs compile and evaluate correctly. So this half *is* Field's
 * to fix, and the fix is a build-graph change with a cost (it would disable the Wither
 * experiment and `WitherSmokeTest`), which is why it is written up rather than applied here.
 *
 * House style, applied deliberately:
 *  - **asserts its own premise first** — [assertReplItselfWorks] proves plain evaluation and the
 *    non-`provideDelegate` delegates still yield `42`. Without it, a REPL that failed *everything*
 *    would make the crash assertions below vacuously true and this test a tautology.
 *  - **counterfactual floored at a number** — the working rungs must equal exactly `42`, not
 *    merely "not crash"; the failing rungs must each produce at least one diagnostic AND carry
 *    their specific signature, so a *different* crash cannot silently satisfy them.
 *  - **bidirectional ledger** — [assertLedger] checks missing, stale, and set equality across the
 *    expected-working and expected-failing rung names.
 */
object ProvideDelegateReplSmokeTest {

    /** Signature of the fork-side, top-level failure. See [DEFECT_A]. */
    const val DEFECT_A = "IrPropertySymbolImpl is unbound"

    /**
     * Signature of the Wither-plugin failure. Its trigger is **not** `provideDelegate` and not
     * local-ness: it is an anonymous object implementing `kotlin.properties.ReadWriteProperty`
     * created inside a function body. Isolated by falsifying, in order, anonymous objects in
     * general, properties inside anonymous objects, `operator fun` enclosures, snippet-declared
     * operator interfaces, and precompiled interfaces in general (`Runnable`, `Comparator`,
     * `Iterator`, `CharSequence` and even `ReadOnlyProperty` are all fine). `ReadWriteProperty` is
     * the only one that fails. See `dev_docs/k2_repl_provide_delegate.md`.
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
     * `ReadWriteProperty`. This one declares and compiles fine, and it is the control that keeps
     * [DEFECT_A] and [DEFECT_B] apart — because [PROVIDER_DECL] cannot even be *declared* without
     * tripping [DEFECT_B].
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
     * Rungs that must fail to compile, each with the signature it must fail with, and each with an
     * optional prior snippet evaluated first.
     *
     * At script top level `provideDelegate` fails *universally* — the variants below cover `val` as
     * well as `var`, an extension `provideDelegate` as well as a member one, a generic provider, and
     * a provider declared in an **earlier snippet** rather than the same one. That last is the shape
     * closest to `by keyCamera(...)`, whose provider is AOT-compiled in `afield.four.util` and so is
     * never part of the snippet being compiled.
     */
    private val FAILING: Map<String, Triple<String?, String, String>> = linkedMapOf(
        "toplevel-provideDelegate" to
            Triple(null, PROVIDER_DECL + "var abcd: Int by Provider()\nabcd\n", DEFECT_A),
        "factoryfn-provideDelegate" to
            Triple(null, PROVIDER_DECL + "fun provider(): Provider = Provider()\nvar abcd: Int by provider()\nabcd\n", DEFECT_A),
        "toplevel-val-provideDelegate" to
            Triple(null, PROVIDER_DECL + "val abcd: Int by Provider()\nabcd\n", DEFECT_A),
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
            DEFECT_A,
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
            DEFECT_A,
        ),
        // Provider from an EARLIER snippet — the `by keyCamera(...)` shape. It must use the NAMED
        // provider, because PROVIDER_DECL on its own already trips DEFECT_B (see below), which
        // would make this rung pass for the wrong reason.
        "cross-snippet-provideDelegate" to
            Triple(NAMED_PROVIDER_DECL, "var abcd: Int by NamedProvider()\nabcd\n", DEFECT_A),
        // --- DEFECT_B rungs. Note the trigger is NOT `provideDelegate` and NOT local-ness. ---
        // The minimal trigger, with no delegate machinery in it at all: an anonymous object
        // implementing kotlin.properties.ReadWriteProperty, created inside a function body.
        "anon-readwriteproperty-in-fun" to Triple(
            null,
            """
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
fun make(): ReadWriteProperty<Any?, Int> = object : ReadWriteProperty<Any?, Int> {
    private var v = 42
    override fun getValue(thisRef: Any?, property: KProperty<*>): Int = v
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) { v = value }
}
make().javaClass.simpleName.isNotEmpty()
""",
            DEFECT_B,
        ),
        // Declaring the idiomatic provider is already enough — it is never used with `by` here.
        "declare-provideDelegate-only" to Triple(null, PROVIDER_DECL + "42\n", DEFECT_B),
        "local-provideDelegate" to
            Triple(null, PROVIDER_DECL + "fun demo(): Int {\n    var abcd: Int by Provider()\n    return abcd\n}\ndemo()\n", DEFECT_B),
        "classmember-provideDelegate" to
            Triple(null, PROVIDER_DECL + "class Owner { var abcd: Int by Provider() }\nOwner().abcd\n", DEFECT_B),
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
     * Premise gate. If the REPL cannot evaluate `40 + 2`, or cannot handle the two *non*
     * `provideDelegate` delegate forms, then every crash assertion further down would pass for the
     * wrong reason. Fail here, loudly, instead.
     */
    private fun assertReplItselfWorks(observedWorking: MutableMap<String, String>) {
        println("  premise: the REPL and non-provideDelegate delegation still work...")
        WORKING.forEach { (label, src) ->
            val run = evaluate(label, src)
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
     * Each failing rung must fail, must produce at least one diagnostic, and must carry *its own*
     * signature — so that a different compiler crash cannot quietly keep this test green.
     */
    private fun assertProvideDelegateStillBroken(observedFailing: MutableMap<String, String>) {
        println("  characterizing the provideDelegate failures...")
        FAILING.forEach { (label, spec) ->
            val (prior, src, signature) = spec
            val run = evaluate(label, src, prior)
            require(run.value == null) {
                "rung '$label' now EVALUATES (got ${run.value}). If provideDelegate has been " +
                    "fixed, that is good news: move '$label' from FAILING to WORKING and update " +
                    "dev_docs/k2_repl_provide_delegate.md."
            }
            require(run.diagnostics.isNotEmpty()) {
                "rung '$label' produced no value AND no diagnostic — that is a third, unknown " +
                    "failure mode and must be investigated, not absorbed."
            }
            val joined = run.diagnostics.joinToString("\n")
            require(joined.contains(signature)) {
                "rung '$label' failed, but NOT with its characterized signature '$signature'. " +
                    "Observed instead:\n$joined"
            }
            observedFailing[label] = signature
            println("    broken-as-characterized  $label -> $signature")
        }
    }

    /** Bidirectional: missing, stale, and set equality, in both directions. */
    private fun assertLedger(
        observedWorking: Map<String, String>,
        observedFailing: Map<String, String>,
    ) {
        println("  ledger: missing / stale / set equality...")
        val expectedWorking = WORKING.keys
        val expectedFailing = FAILING.keys

        val missingWorking = expectedWorking - observedWorking.keys
        val staleWorking = observedWorking.keys - expectedWorking
        val missingFailing = expectedFailing - observedFailing.keys
        val staleFailing = observedFailing.keys - expectedFailing

        require(missingWorking.isEmpty()) { "ledger: working rungs declared but never observed: $missingWorking" }
        require(staleWorking.isEmpty()) { "ledger: working rungs observed but not declared: $staleWorking" }
        require(missingFailing.isEmpty()) { "ledger: failing rungs declared but never observed: $missingFailing" }
        require(staleFailing.isEmpty()) { "ledger: failing rungs observed but not declared: $staleFailing" }
        require(observedWorking.keys == expectedWorking) { "ledger: working set mismatch" }
        require(observedFailing.keys == expectedFailing) { "ledger: failing set mismatch" }
        require(expectedWorking.intersect(expectedFailing).isEmpty()) {
            "ledger: a rung cannot be both expected-working and expected-failing"
        }

        // Floors near the numbers this test is proud of, so a silently-shrinking matrix fails.
        // 7 working = seven distinct non-provideDelegate spellings of `by`, which is the breadth
        // that licenses "the defect is provideDelegate, not delegation". 8 failing = every
        // provideDelegate variant probed (val/var, member/extension, generic, cross-snippet,
        // local, class member).
        require(observedWorking.size >= 7) { "expected at least 7 working rungs, got ${observedWorking.size}" }
        require(observedFailing.size >= 10) { "expected at least 10 failing rungs, got ${observedFailing.size}" }

        // Exactly two distinct failure signatures = the two independent defects.
        val signatures = observedFailing.values.toSet()
        require(signatures.size == 2) {
            "expected exactly 2 distinct failure signatures (the two independent defects), got " +
                "${signatures.size}: $signatures"
        }
        require(signatures == setOf(DEFECT_A, DEFECT_B)) {
            "failure signatures drifted from the characterized pair: $signatures"
        }
        println("    working=${observedWorking.size} failing=${observedFailing.size} signatures=${signatures.size}")
    }

    fun runTests(): Boolean {
        return try {
            println("Running provideDelegate REPL characterization...")
            val observedWorking = linkedMapOf<String, String>()
            val observedFailing = linkedMapOf<String, String>()
            assertReplItselfWorks(observedWorking)
            assertProvideDelegateStillBroken(observedFailing)
            assertLedger(observedWorking, observedFailing)
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
