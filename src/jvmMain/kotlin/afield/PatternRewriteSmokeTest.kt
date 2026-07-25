package afield

import afield.macros.PatternRewrite
import afield.macros.resolvePatternRewriteAi
import afield.macros.standardExecutionPractice
import com.facebook.ktfmt.format.Parser
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.script.experimental.api.KotlinType
import org.jetbrains.kotlin.psi.KtFile

/*
FIELD_TEST
id: kotlin.pattern-rewrite.smoke
runner: jvm-main
entrypoint: afield.PatternRewriteSmokeTest
tags: [smoke, kotlin, macros, rewrite]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "Pattern-rewrite smoke passed"
*/
/**
 * Smoke for the pattern-rewrite layer ([afield.macros.PatternRewrite]) — a structural
 * source-to-source rewriter that lets a live box author its own `rewrite { … }` rules on top of the
 * existing PSI macro pass.
 *
 * Most cases are pure and black-box: they feed a source string (rule + code) through
 * [PatternRewrite.expand] with the standalone ktfmt [Parser] as the PSI environment and assert on
 * the emitted text — no REPL needed. One end-to-end case drives a real [KotlinInterface2] to prove
 * the rule blocks are deleted, the wiring in `applyMacros` fires, and the result actually compiles
 * and evaluates.
 *
 * Covers proposal §9: match/no-match, non-linearity, paren-modulo, the precedence hazard, the quote
 * barrier, declaration-order first-match-wins, block deletion + compile, and non-convergence
 * termination.
 */
object PatternRewriteSmokeTest {

    fun runTests(): Boolean {
        return try {
            println("Running pattern-rewrite smoke...")
            testMatchAndNoMatch()
            testLeafOperatorMismatch()
            testArityMismatch()
            testNonLinearity()
            testParenModulo()
            testPrecedenceHazard()
            testAtomicCapturesNotOverParenthesized()
            testQuoteBarrier()
            testQualifiedRewriteIsNotARule()
            testDeclarationOrderFirstMatchWins()
            testNestedRewriteDoesNotSelfApply()
            testBareMetavarPatternRejected()
            testMalformedOperatorPatternRejected()
            testGuardDistinct()
            testGuardKindPredicates()
            testGuardBooleanCombinators()
            testCoercionIdiomTerminates()
            testVariadicHoleWholeArgList()
            testVariadicHoleWithFixedArgs()
            testVariadicEmptyRun()
            testUnquoteWiringWithFakeEvaluator()
            testUnquotePreviewPlaceholder()
            testUnquoteEndToEnd()
            testUnquoteViaFullExecutePath()
            testAmbientRuleApplies()
            testLocalRuleOverridesAmbient()
            testLocalRuleSourcesCaptured()
            testAmbientRuleInheritedByChildBox()
            testOwnRuleAppliesToFragmentOfSameBox()
            testGensymHygiene()
            testProvideProducesKeyedPreamble()
            testProvideDedupAndDistinctKeys()
            testAiRequestProducedAndForwarded()
            testAtticCacheHelpers()
            testProvideEndToEndIdempotent()
            testAiCacheHitIsNoModelCall()
            testTerminationCap()
            // ---- strategy sublanguage (named rules + strategy {} combinators) ----
            testNamedRuleNoStrategyIsGlobalFixpoint()
            testStrategyOnceFiresSingleSite()
            testStrategyLeafSubsetDiffersFromDefault()
            testStrategyTopDownSingleOutermost()
            testStrategyBottomUpSingleInnermost()
            testStrategySeqThenOrderingAndFailFast()
            testStrategyChoiceFirstApplicable()
            testStrategyRepeatBoundedVsUnbounded()
            testStrategyAttemptIsIdentityOnFailure()
            testStrategyAnonymousRulesDoNotFire()
            testStrategyUnknownRuleFailsClosed()
            testStrategyMalformedFailsClosed()
            testStrategyProvideStillContributes()
            testEndToEndThroughEngine()
            testStrategyEndToEndThroughEngine()
            println("✅ Pattern-rewrite smoke passed")
            true
        } catch (t: Throwable) {
            println("❌ Pattern-rewrite smoke failed: ${t.message}")
            t.printStackTrace()
            false
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "Pattern-rewrite smoke failed" }
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun parse(code: String): KtFile = Parser.parse(code)

    private data class Result(
        val text: String,
        val preambleBlocks: List<String>,
        val aiRequests: List<PatternRewrite.AiRequest>,
        val localRuleSources: List<String>,
        val diagnostic: String?,
    )

    private fun expand(
        src: String,
        maxIterations: Int = 64,
        evalUnquote: ((String) -> String?)? = null,
        ambientRuleSources: List<String> = emptyList(),
    ): Result {
        var diag: String? = null
        val out = PatternRewrite.expand(src, ::parse, maxIterations, evalUnquote, ambientRuleSources) { diag = it }
        return Result(out.text, out.preambleBlocks, out.aiRequests, out.localRuleSources, diag)
    }

    private fun require(cond: Boolean, lazyMessage: () -> String) {
        if (!cond) throw AssertionError(lazyMessage())
    }

    // ---- cases ----------------------------------------------------------------------------------

    /** A hole matches any expression; a literal must match exactly; wrong callee does not match. */
    private fun testMatchAndNoMatch() {
        val rule = """
            rewrite { x: Expr -> from { neg(x) }; to { -x } }
        """.trimIndent()

        val hit = expand("$rule\nneg(a)").text
        require("-a" in hit) { "expected neg(a) -> -a, got: $hit" }

        val miss = expand("$rule\npos(a)").text
        require("pos(a)" in miss && "-a" !in miss) { "pos(a) must not match neg(x): $miss" }
    }

    /** Leaf tokens (the operator) must match: `x + y` does not match `a - b`. */
    private fun testLeafOperatorMismatch() {
        val rule = "rewrite { x: Expr, y: Expr -> from { x + y }; to { plus(x, y) } }"
        val out = expand("$rule\na + b\nc - d").text
        require("plus(a, b)" in out) { "x + y should match a + b: $out" }
        require("c - d" in out) { "x + y must NOT match c - d (operator mismatch): $out" }
    }

    /** Fixed arity: a 2-hole pattern matches only a 2-argument call. */
    private fun testArityMismatch() {
        val rule = "rewrite { x: Expr, y: Expr -> from { f(x, y) }; to { h(x, y) } }"
        val out = expand("$rule\nf(a)\nf(a, b)\nf(a, b, c)").text
        require("f(a)" in out) { "arity 1 must not match f(x, y): $out" }
        require("h(a, b)" in out) { "arity 2 must match: $out" }
        require("f(a, b, c)" in out) { "arity 3 must not match: $out" }
    }

    /** A repeated metavar must bind structurally equal subtrees. */
    private fun testNonLinearity() {
        val rule = "rewrite { x: Expr -> from { f(x, x) }; to { g(x) } }"
        val out = expand("$rule\nf(a, a)\nf(a, b)").text
        require("g(a)" in out) { "f(a, a) should collapse via non-linear x: $out" }
        require("f(a, b)" in out) { "f(a, b) must reject the repeated hole: $out" }
    }

    /** Matching is modulo redundant parentheses: `x + y` matches `(a) + (b)`. */
    private fun testParenModulo() {
        val rule = "rewrite { x: Expr, y: Expr -> from { x + y }; to { plus(x, y) } }"
        val out = expand("$rule\n(a) + (b)").text
        require("plus(a, b)" in out) { "x + y should match (a) + (b) modulo parens: $out" }
    }

    /** THE important one: text splicing must not silently change precedence. */
    private fun testPrecedenceHazard() {
        val rule = "rewrite { x: Expr -> from { twice(x) }; to { x * 2 } }"
        val out = expand("$rule\ntwice(a + b)").text
        require("(a + b) * 2" in out) { "non-atomic capture must be paren-guarded: $out" }
        require("a + b * 2" !in out) { "must NOT emit the mis-precedenced a + b * 2: $out" }
    }

    /** ...but atomic captures must NOT be over-parenthesized. */
    private fun testAtomicCapturesNotOverParenthesized() {
        val rule = "rewrite { x: Expr -> from { twice(x) }; to { x * 2 } }"
        val out = expand("$rule\ntwice(a)\ntwice(f())\ntwice(a.b)\ntwice(a[0])\ntwice(3)").text
        listOf("a * 2", "f() * 2", "a.b * 2", "a[0] * 2", "3 * 2").forEach {
            require(it in out) { "atomic capture should NOT be wrapped, expected `$it`: $out" }
        }
        require("(a) * 2" !in out) { "a name must not be parenthesized: $out" }
    }

    /** The pattern body itself is quasiquoted: it is blanked, never independently rewritten. */
    private fun testQuoteBarrier() {
        val rule = "rewrite { x: Expr, y: Expr -> from { max(x, y) }; to { BIG } }"
        val out = expand("$rule\nmax(1, 2)").text
        require("BIG" in out) { "real max(1, 2) should be rewritten: $out" }
        require("rewrite {" !in out && "from {" !in out) { "rule block must be blanked: $out" }
        require("max(x, y)" !in out) { "the pattern body max(x, y) must not survive: $out" }
    }

    /**
     * A *qualified* `.rewrite { … }` is an unrelated method, not a rule declaration, and must
     * survive untouched.
     *
     * The collector used to match on the callee name alone, so any `receiver.rewrite { … }` in
     * user code — `ParamSet.rewrite { normalize(...) }` is one the synth cookbook actively
     * recommends — was blanked out before compilation. The following line was then parsed
     * against the wrong receiver, so the reported error pointed nowhere near the cause.
     */
    private fun testQualifiedRewriteIsNotARule() {
        val out = expand(
            """
            val gains = rig.params("x")
            gains.rewrite { normalize(0f..1f) }
            rig.other().rewrite { jitter(2f) }
            """.trimIndent()
        )

        require("gains.rewrite { normalize(0f..1f) }" in out.text) {
            "a qualified .rewrite must survive verbatim: ${out.text}"
        }
        require("rig.other().rewrite { jitter(2f) }" in out.text) {
            "a qualified .rewrite on a call receiver must survive too: ${out.text}"
        }
        require(out.localRuleSources.isEmpty()) {
            "a qualified .rewrite must not be collected as a rule: ${out.localRuleSources}"
        }

        // …while a genuine unqualified rule in the same source is still collected and blanked.
        val mixed = expand(
            """
            rewrite { x: Expr -> from { neg(x) }; to { -x } }
            gains.rewrite { normalize(0f..1f) }
            neg(a)
            """.trimIndent()
        )
        require("-a" in mixed.text) { "the real rule must still fire: ${mixed.text}" }
        require("gains.rewrite { normalize(0f..1f) }" in mixed.text) {
            "the qualified call must still survive alongside a real rule: ${mixed.text}"
        }
        require(mixed.localRuleSources.size == 1) {
            "exactly one rule should be collected: ${mixed.localRuleSources}"
        }
    }

    /** Declaration order wins when two rules share a pattern. */
    private fun testDeclarationOrderFirstMatchWins() {
        val rules = """
            rewrite { x: Expr, y: Expr -> from { max(x, y) }; to { FIRST } }
            rewrite { p: Expr, q: Expr -> from { max(p, q) }; to { SECOND } }
        """.trimIndent()
        val out = expand("$rules\nmax(1, 2)").text
        require("FIRST" in out) { "first declared rule should win: $out" }
        require("SECOND" !in out) { "second rule should not also fire: $out" }
    }

    /** A `rewrite` nested inside another rule's quasiquote is quoted data, not a live rule. */
    private fun testNestedRewriteDoesNotSelfApply() {
        val src = """
            rewrite { a: Expr ->
                from { host(a) }
                to { rewrite { b: Expr -> from { inner(b) }; to { REPLACED } } }
            }
            inner(untouched)
        """.trimIndent()
        val out = expand(src).text
        // The inner rule was never collected, so `inner(untouched)` is left alone.
        require("inner(untouched)" in out) { "nested rule must not self-apply: $out" }
        require("REPLACED" !in out) { "nested rule must not have fired: $out" }
    }

    /** A `where { distinct(x, y) }` guard rejects matches whose captures are structurally equal. */
    private fun testGuardDistinct() {
        val rule = """
            rewrite { x: Expr, y: Expr ->
                from { swap(x, y) }
                where { distinct(x, y) }
                to { swapped(y, x) }
            }
        """.trimIndent()
        val out = expand("$rule\nswap(a, b)\nswap(c, c)").text
        require("swapped(b, a)" in out) { "distinct(a, b) holds, should fire: $out" }
        require("swap(c, c)" in out) { "distinct(c, c) fails, should NOT fire: $out" }
    }

    /** Kind predicates gate on the shape of the capture. */
    private fun testGuardKindPredicates() {
        val litRule = "rewrite { x: Expr -> from { f(x) }; where { isLiteral(x) }; to { LIT } }"
        val litOut = expand("$litRule\nf(42)\nf(v)").text
        require("LIT" in litOut) { "isLiteral(42) should hold: $litOut" }
        require("f(v)" in litOut) { "isLiteral(v) should fail: $litOut" }

        val idRule = "rewrite { x: Expr -> from { g(x) }; where { isIdentifier(x) }; to { ID } }"
        val idOut = expand("$idRule\ng(name)\ng(1 + 2)").text
        require("ID" in idOut) { "isIdentifier(name) should hold: $idOut" }
        require("g(1 + 2)" in idOut) { "isIdentifier(1 + 2) should fail: $idOut" }
    }

    /** Guards compose with `&&`, `||`, `!`. */
    private fun testGuardBooleanCombinators() {
        val rule = """
            rewrite { x: Expr, y: Expr ->
                from { h(x, y) }
                where { distinct(x, y) && !isLiteral(x) }
                to { OK }
            }
        """.trimIndent()
        val out = expand("$rule\nh(a, b)\nh(1, b)\nh(a, a)").text
        require("OK" in out) { "distinct && !isLiteral should hold for h(a, b): $out" }
        require("h(1, b)" in out) { "!isLiteral(1) fails: $out" }
        require("h(a, a)" in out) { "distinct(a, a) fails: $out" }
    }

    /**
     * The type-directed coercion idiom: wrap every `f(x)` so a runtime adapter can coerce the
     * argument, guarding against re-wrapping the `coerce(...)` we just introduced so the fixpoint
     * terminates. (Purely syntactic — the actual type check happens at runtime inside `coerce`.)
     */
    private fun testCoercionIdiomTerminates() {
        val rule = """
            rewrite { x: Expr ->
                from { f(x) }
                where { !isCallTo(x, "coerce") }
                to { f(coerce(::f, SomeType::class, x)) }
            }
        """.trimIndent()
        val res = expand("$rule\nf(a)\nf(b + 1)")
        require(res.diagnostic == null) { "coercion rule must terminate, not hit the cap: ${res.diagnostic}" }
        require("f(coerce(::f, SomeType::class, a))" in res.text) { "atomic arg wrapped once: ${res.text}" }
        require("f(coerce(::f, SomeType::class, (b + 1)))" in res.text) { "non-atomic arg paren-guarded: ${res.text}" }
        require("coerce(::f, SomeType::class, coerce(" !in res.text) { "must not double-wrap: ${res.text}" }
    }

    /** A lone-metavar pattern (`from { x }`) matches everything → runaway; it must be rejected. */
    private fun testBareMetavarPatternRejected() {
        val res = expand("rewrite { x: Expr -> from { x }; to { wrap(x) } }\nhello", maxIterations = 8)
        require(res.diagnostic != null) { "a bare-metavar pattern should be reported" }
        require("wrap(" !in res.text) { "a bare-metavar rule must be ignored, not applied: ${res.text}" }
    }

    /**
     * A pattern using an operator Kotlin doesn't accept in expressions (`|`) parses leniently to a
     * degenerate/error tree. It must be rejected (not run away). `||` is a real operator and works;
     * this guards the `|` case the hard way — through the engine, whose lenient PSI parser is what
     * actually mis-parses `|` (the ktfmt test parser would just throw).
     */
    private fun testMalformedOperatorPatternRejected() {
        val ki = KotlinInterface2(filePrefix = "malformed-op")
        val box = Box("malformed-op")
        val src = "fun piped(a: Any, b: Any): Double = 10.0\n" +
            "rewrite { x: Expr, y: Expr -> from { x | y }; to { piped(x, y) } }\n" +
            "val z = 4 | 3\n"
        val out = ki.applyMacros(box, src)
        require("piped(piped" !in out && "piped((" !in out) { "malformed `|` pattern must not run away: ${out.take(160)}" }
        require("piped(z" !in out && "piped(4" !in out) { "the malformed rule must be ignored, not applied: ${out.take(160)}" }
    }

    // ---- variadic holes -------------------------------------------------------------------------

    /** A `Vararg` hole binds the whole argument run and expands it comma-separated in `to`. */
    private fun testVariadicHoleWholeArgList() {
        val rule = "rewrite { args: Vararg -> from { log(args) }; to { sink(args) } }"
        val out = expand("$rule\nlog(a, b + 1, c)").text
        require("sink(a, b + 1, c)" in out) { "variadic run must be spliced verbatim, comma-separated: $out" }
    }

    /** A variadic hole composes with fixed holes on either side. */
    private fun testVariadicHoleWithFixedArgs() {
        val rule = "rewrite { first: Expr, rest: Vararg -> from { log(first, rest) }; to { logger(first, rest) } }"
        val out = expand("$rule\nlog(tag, a, b, c)").text
        require("logger(tag, a, b, c)" in out) { "fixed prefix + variadic run: $out" }
    }

    /** A variadic run may be empty. */
    private fun testVariadicEmptyRun() {
        val rule = "rewrite { first: Expr, rest: Vararg -> from { log(first, rest) }; to { logger(first, rest) } }"
        val out = expand("$rule\nlog(tag)").text
        // Empty run ⇒ trailing metavar expands to nothing.
        require("logger(tag, )" in out || "logger(tag,)" in out || "logger(tag )" in out || "logger(tag)" in out) {
            "empty variadic run should leave just the fixed arg: $out"
        }
    }

    // ---- procedural unquote ---------------------------------------------------------------------

    /** `unquote {}` builds an eval snippet (metavars as String vals) and splices the evaluator's result. */
    private fun testUnquoteWiringWithFakeEvaluator() {
        val rule = "rewrite { x: Ident -> from { rev(x) }; to { unquote { x.reversed() } } }"
        var seenSnippet: String? = null
        val res = expand("$rule\nrev(hello)", evalUnquote = { snippet -> seenSnippet = snippet; "SPLICED" })
        require("SPLICED" in res.text) { "unquote region must be replaced by evaluator output: ${res.text}" }
        require("unquote" !in res.text) { "the unquote call itself must be gone: ${res.text}" }
        val snippet = seenSnippet ?: throw AssertionError("evaluator was never invoked")
        require("val x: String = \"hello\"" in snippet) { "snippet must bind the metavar as its captured text: $snippet" }
        require("x.reversed()" in snippet) { "snippet must contain the block body: $snippet" }
    }

    /** Editor-preview (no evaluator): `unquote {}` becomes a well-typed `TODO()`, not a raw call. */
    private fun testUnquotePreviewPlaceholder() {
        val rule = "rewrite { x: Ident -> from { rev(x) }; to { unquote { x.reversed() } } }"
        val res = expand("$rule\nrev(hello)")   // no evalUnquote => preview semantics
        require("TODO()" in res.text) { "preview should splice a TODO() placeholder: ${res.text}" }
        require("unquote" !in res.text) { "the erased unquote call must not survive into preview code: ${res.text}" }
    }

    /** End-to-end: `unquote {}` runs Kotlin at expansion time; its result is spliced as source. */
    private fun testUnquoteEndToEnd() = runBlocking {
        // Faithful box engine: one Box implicit receiver, matching makeKI — so the unquote eval
        // (which runs with `implicits = listOf(box)`) sees the receiver arity it expects.
        val ki = KotlinInterface2(implicitReceivers = listOf(KotlinType(Box::class)), filePrefix = "unquote-e2e")
        val box = Box("unquote-e2e")
        withTimeout(120_000) { ki.exec("1 + 1", implicits = listOf(box)) }   // warm the REPL
        // The block produces a quoted string literal so the spliced code is valid Kotlin.
        val src = """
            rewrite { x: Ident ->
                from { rev(x) }
                to { unquote { "\"" + x.reversed() + "\"" } }
            }
            rev(hello)
        """.trimIndent()
        val expanded = withTimeout(120_000) { ki.applyMacros(box, src) }
        require("\"olleh\"" in expanded) { "unquote must eval to a quoted literal at expansion time: $expanded" }
        require("unquote" !in expanded && "rev(" !in expanded) { "the rule + unquote call must be gone: $expanded" }
    }

    /** Reproduces the live-box path: `unquote {}` must expand AND the result must compile + evaluate. */
    private fun testUnquoteViaFullExecutePath() = runBlocking {
        // Construct the engine as real boxes do — with a Box implicit receiver — so the box code
        // executes with `implicits = listOf(box)` (matching standardExecutionPractice below).
        val ki = KotlinInterface2(implicitReceivers = listOf(KotlinType(Box::class)), filePrefix = "unquote-exec")
        val box = Box("unquote-exec")
        withTimeout(120_000) { ki.exec("1 + 1", implicits = listOf(box)) }
        val src = "import afield.macros.unquote\n" +
            "rewrite { x: Ident -> from { rev(x) }; to { unquote { \"\\\"\" + x.reversed() + \"\\\"\" } } }\n" +
            "rev(hello)"
        var compileErr = ""
        var evalErr = ""
        var evalException: Throwable? = null
        val results = withTimeout(120_000) {
            standardExecutionPractice(
                ki, src,
                onCompileError = { ds -> compileErr = ds.joinToString { it.message } },
                onEvalError = { ds -> evalErr = ds.joinToString { it.message } },
                onEvalException = { t -> evalException = t },
                implicits = listOf(box),
                runSetupBlocks = false,
            )
        }
        println("  [exec-path] compileErr='$compileErr' evalErr='$evalErr' exc=${evalException?.message} results=$results")
        // The bug this guards: the un-expanded `unquote {}` reaching the compiler ("cannot infer T",
        // or a bare identifier "unresolved reference"). A clean compile + eval proves it expanded.
        require(compileErr.isBlank()) { "unquote via the execute path should compile, got: $compileErr" }
        require(evalErr.isBlank() && evalException == null) {
            "unquote via the execute path should evaluate cleanly, got: $evalErr / ${evalException?.message}"
        }
        require(results.isNotEmpty()) { "execute path should produce a result" }
    }

    // ---- ambient (inherited) rules --------------------------------------------------------------

    /** A rule supplied as an ambient (ancestor) source applies to code with no local rule. */
    private fun testAmbientRuleApplies() {
        val ancestor = "rewrite { x: Expr -> from { boom(x) }; to { BANG(x) } }"
        val out = expand("boom(a + 1)", ambientRuleSources = listOf(ancestor))
        require("BANG((a + 1))" in out.text) { "ambient rule should apply (paren-guarded): ${out.text}" }
    }

    /** A local rule wins over an ambient rule with the same pattern (declaration-order). */
    private fun testLocalRuleOverridesAmbient() {
        val ancestor = "rewrite { x: Expr -> from { f(x) }; to { ANCESTOR(x) } }"
        val local = "rewrite { x: Expr -> from { f(x) }; to { LOCAL(x) } }"
        val out = expand("$local\nf(a)", ambientRuleSources = listOf(ancestor))
        require("LOCAL(a)" in out.text) { "local rule should win: ${out.text}" }
        require("ANCESTOR" !in out.text) { "ambient must not override a local rule: ${out.text}" }
    }

    /** Local rule blocks are surfaced verbatim for the caller to persist for descendants. */
    private fun testLocalRuleSourcesCaptured() {
        val out = expand("rewrite { x: Expr -> from { f(x) }; to { g(x) } }\nf(a)")
        require(out.localRuleSources.size == 1) { "one local rule block expected: ${out.localRuleSources}" }
        require("from { f(x) }" in out.localRuleSources[0]) { "rule source must be verbatim: ${out.localRuleSources}" }
    }

    /**
     * A box's OWN rule must apply to a *fragment* run from that box — not only to a whole-box run.
     * The fragment text doesn't contain the `rewrite {}` block, so the rule has to be picked up from
     * the box's stored `code`. (Regression: previously the ambient gather skipped the box itself, so
     * `thisBox.fff(1)` run alone compiled without the coercion the rule inserts.)
     */
    private fun testOwnRuleAppliesToFragmentOfSameBox() {
        val ki = KotlinInterface2(filePrefix = "frag-own-rule")
        val box = Box("frag-own-rule")
        val fullCode = "rewrite { x: Expr -> from { fff(x) }; where { !isCallTo(x, \"wrap\") }; to { fff(wrap(x)) } }\nfff(1)"
        box.setPropertyRaw("code", fullCode, doResync = false)
        // Run only a fragment that does NOT contain the rewrite block.
        val fragOut = ki.applyMacros(box, "fff(2)")
        require("fff(wrap(2))" in fragOut) { "a box's own rule must apply to a fragment run from it: $fragOut" }
    }

    /** End-to-end: a rule *written* in a parent box rewrites a child box's code — no parent run needed. */
    private fun testAmbientRuleInheritedByChildBox() = runBlocking {
        val ki = KotlinInterface2(filePrefix = "ambient-e2e")
        val parent = Box("ambient-parent")
        val child = Box("ambient-child")
        child.parents.add(parent)   // establish the hierarchy edge searchUpwards walks

        // The rule lives in the parent's source `code` (as if typed into the parent box). The parent
        // is never executed — the child should still inherit the rule from the parent's code.
        parent.setPropertyRaw("code", "rewrite { x: Expr -> from { boom(x) }; to { BANG(x) } }", doResync = false)
        val childOut = ki.applyMacros(child, "boom(hello)")
        require("BANG(hello)" in childOut) { "child must inherit the parent's ambient rule: $childOut" }
        require("boom(" !in childOut) { "the inherited rule should have rewritten boom(): $childOut" }
    }

    /** Names the `to {}` side introduces are gensym'd so they cannot capture a spliced metavar. */
    private fun testGensymHygiene() {
        val rule = """
            rewrite { x: Expr ->
                from { doubled(x) }
                to { run { val acc = x; acc + acc } }
            }
        """.trimIndent()
        // The metavar captures the user's own `acc`; without hygiene the introduced local would
        // capture it. With gensym, the local is renamed and the user's `acc` survives on the RHS.
        val out = expand("$rule\ndoubled(acc)\ndoubled(seed)").text
        require("val acc__hyg0 = acc" in out) { "introduced local must be renamed, user acc preserved: $out" }
        require("acc__hyg0 + acc__hyg0" in out) { "references to the local must be renamed too: $out" }
        require("acc__hyg1" in out) { "a second expansion must get a distinct fresh name: $out" }
        require("val acc =" !in out) { "the original introduced name must not survive: $out" }
    }

    // ---- provide {} (stable-id keyed preamble) --------------------------------------------------

    private val provideRule = """
        rewrite { n: Ident ->
            from { counter(n) }
            provide { var Box.n by Counting() }
            to { n }
        }
    """.trimIndent()

    /** A `provide {}` clause yields a metavar-substituted, stably-keyed preamble segment. */
    private fun testProvideProducesKeyedPreamble() {
        val res = expand("$provideRule\ncounter(hits)")
        require(res.preambleBlocks.size == 1) { "expected one preamble block, got ${res.preambleBlocks}" }
        val block = res.preambleBlocks.single()
        require("var Box.hits by Counting()" in block) { "metavar must be substituted in preamble: $block" }
        require("/*start*/" in block && "/*rw-" in block && "/*end*/" in block) { "must be a keyed block: $block" }
        require("hits" in res.text) { "to output should splice the capture: ${res.text}" }
    }

    /** Identical contributions dedup by key; distinct captures get distinct keys. */
    private fun testProvideDedupAndDistinctKeys() {
        val res = expand("$provideRule\ncounter(a)\ncounter(a)\ncounter(b)")
        require(res.preambleBlocks.size == 2) { "counter(a) twice dedups; b distinct ⇒ 2 blocks: ${res.preambleBlocks}" }
        require(res.preambleBlocks.any { "Box.a" in it } && res.preambleBlocks.any { "Box.b" in it }) {
            "expected one block per distinct capture: ${res.preambleBlocks}"
        }
    }

    // ---- ai("…") (cached attic) -----------------------------------------------------------------

    private val aiRule = """
        rewrite { x: Expr ->
            from { explain(x) }
            ai("summarize the value ${'$'}{x}")
            to { __explain(x) }
        }
    """.trimIndent()

    /** An `ai("…")` clause produces one AiRequest with the substituted prompt + stable key; `to` forwards. */
    private fun testAiRequestProducedAndForwarded() {
        val res = expand("$aiRule\nexplain(myval)")
        val req = res.aiRequests.single()
        require(req.prompt == "summarize the value myval") { "prompt metavar must be substituted: ${req.prompt}" }
        require(req.key == PatternRewrite.stableKey("summarize the value myval")) { "stable key mismatch: ${req.key}" }
        require("__explain(myval)" in res.text) { "to should forward with the capture: ${res.text}" }
    }

    /** The pure attic cache helpers detect and append `@rw-ai` blocks. */
    private fun testAtticCacheHelpers() {
        val withBlock = PatternRewrite.appendAiBlock("", "rw-abc", "do a thing", "fun __f() = 1")
        require(PatternRewrite.atticHasAiBlock(withBlock, "rw-abc")) { "should detect the appended block" }
        require(!PatternRewrite.atticHasAiBlock(withBlock, "rw-nope")) { "should not detect a foreign key" }
        require("fun __f() = 1" in withBlock) { "generated body must be present: $withBlock" }
    }

    /** End-to-end: `provide {}` reaches box.preamble through applyMacros and dedups across re-runs. */
    private fun testProvideEndToEndIdempotent() = runBlocking {
        val ki = KotlinInterface2(filePrefix = "provide-e2e")
        val box = Box("provide-e2e")
        val src = "$provideRule\ncounter(hits)"

        ki.applyMacros(box, src)
        val first = box.getPropertyRaw("preamble", "") as String
        require("var Box.hits by Counting()" in first) { "provide must reach box.preamble: $first" }

        ki.applyMacros(box, src)   // re-run
        val second = box.getPropertyRaw("preamble", "") as String
        val occurrences = Regex(Regex.escape("var Box.hits by Counting()")).findAll(second).count()
        require(occurrences == 1) { "keyed preamble must dedup across runs, got $occurrences: $second" }
    }

    /** A cached `ai(...)` request (block already in attic) is a no-op — no model call, attic untouched. */
    private fun testAiCacheHitIsNoModelCall() {
        val box = Box("ai-cache")
        val req = expand("$aiRule\nexplain(myval)").aiRequests.single()
        // Pre-seed the attic with a matching cached block — this is the cache-HIT precondition.
        val seeded = PatternRewrite.appendAiBlock("", req.key, req.prompt, "fun __explain(v: Any?) = v.toString()")
        box.setPropertyRaw("attic", seeded, doResync = false)

        val before = box.getPropertyRaw("attic", "") as String
        box.resolvePatternRewriteAi(req)   // must early-return on cache hit (never touches a model)
        val after = box.getPropertyRaw("attic", "") as String
        require(before == after) { "cache hit must be a no-op on the attic" }
        require(PatternRewrite.atticHasAiBlock(after, req.key)) { "cached block must remain: $after" }
    }

    /** A non-confluent rule set (a→b, b→a) hits the cap with a diagnostic instead of hanging. */
    private fun testTerminationCap() {
        val src = """
            rewrite { -> from { a }; to { b } }
            rewrite { -> from { b }; to { a } }
            a
        """.trimIndent()
        val result = expand(src, maxIterations = 8)
        require(result.diagnostic != null) { "non-convergence should emit a diagnostic" }
        require("did not converge" in result.diagnostic!!) { "diagnostic should explain: ${result.diagnostic}" }
    }

    /** End-to-end: through the real engine, the rule block is deleted, wired, compiles, and runs. */
    private fun testEndToEndThroughEngine() = runBlocking {
        val ki = KotlinInterface2(filePrefix = "pattern-rewrite-e2e")
        val src = """
            rewrite { x: Expr, y: Expr ->
                from { mymax(x, y) }
                to { if (x >= y) x else y }
            }
            val r = mymax(3, 7)
            r
        """.trimIndent()

        val expanded = ki.applyMacros(Box("pattern-rewrite-e2e"), src)
        require("rewrite {" !in expanded && "mymax(" !in expanded) {
            "engine applyMacros must delete the rule block and expand mymax: $expanded"
        }
        require("if (3 >= 7) 3 else 7" in expanded) { "expected expanded conditional: $expanded" }

        val result = withTimeout(120_000) { ki.exec(expanded) }
        require(result.toString().contains("7")) { "expanded source should compile and evaluate to 7, got: $result" }
    }

    // ---- strategy sublanguage --------------------------------------------------------------------

    /**
     * Backward-compat: a `rule("name") { … }` with NO `strategy {}` block behaves EXACTLY like the
     * anonymous `rewrite {}` global fixpoint — every match everywhere is rewritten to convergence.
     */
    private fun testNamedRuleNoStrategyIsGlobalFixpoint() {
        val rule = """rule("neg") { x: Expr -> from { neg(x) }; to { -x } }"""
        val out = expand("$rule\nneg(a)\nneg(b)").text
        require("-a" in out && "-b" in out) { "named rule with no strategy must fire at every site: $out" }
        require("neg(" !in out) { "no match should survive the global fixpoint: $out" }
    }

    /**
     * `once(r)` fires at a single (outermost, document-first) site — leaving the other match
     * untouched, which is the observable difference from the default global fixpoint (both sites).
     */
    private fun testStrategyOnceFiresSingleSite() {
        val src = """
            rule("neg") { x: Expr -> from { neg(x) }; to { -x } }
            strategy { once("neg") }
            neg(a)
            neg(b)
        """.trimIndent()
        val out = expand(src).text
        require("-a" in out) { "once should fire the first site: $out" }
        require("neg(b)" in out && "-b" !in out) { "once must leave the second site untouched: $out" }
    }

    /** A strategy that names only one of two rules fires ONLY that rule (default fires both). */
    private fun testStrategyLeafSubsetDiffersFromDefault() {
        val src = """
            rule("p2a") { x: Expr -> from { p(x) }; to { A(x) } }
            rule("q2b") { x: Expr -> from { q(x) }; to { B(x) } }
            strategy { "p2a" }
            p(1)
            q(2)
        """.trimIndent()
        val out = expand(src).text
        require("A(1)" in out) { "the named rule must fire: $out" }
        require("q(2)" in out && "B(2)" !in out) { "the unnamed rule must NOT fire under a strategy: $out" }
    }

    /** `topdown(r)` rewrites the single OUTERMOST match of a nested form. */
    private fun testStrategyTopDownSingleOutermost() {
        val src = """
            rule("fg") { x: Expr -> from { f(x) }; to { g(x) } }
            strategy { topdown("fg") }
            f(f(a))
        """.trimIndent()
        val out = expand(src).text
        require("g(f(a))" in out) { "topdown must rewrite the outer call only: $out" }
        require("g(g(a))" !in out) { "topdown is a single application, not a fixpoint: $out" }
    }

    /** `bottomup(r)` rewrites the single INNERMOST match — the mirror of `topdown` on `f(f(a))`. */
    private fun testStrategyBottomUpSingleInnermost() {
        val src = """
            rule("fg") { x: Expr -> from { f(x) }; to { g(x) } }
            strategy { bottomup("fg") }
            f(f(a))
        """.trimIndent()
        val out = expand(src).text
        require("f(g(a))" in out) { "bottomup must rewrite the inner call only: $out" }
        require("g(f(a))" !in out && "g(g(a))" !in out) { "bottomup must not touch the outer call: $out" }
    }

    /** `a then b` sequences (result of a feeds b); a leading failure fails the whole sequence. */
    private fun testStrategySeqThenOrderingAndFailFast() {
        val rules = """
            rule("p2q") { x: Expr -> from { p(x) }; to { q(x) } }
            rule("q2r") { x: Expr -> from { q(x) }; to { r(x) } }
        """.trimIndent()

        val ordered = expand("$rules\nstrategy { \"p2q\" then \"q2r\" }\np(1)").text
        require("r(1)" in ordered) { "p→q→r when sequenced in the right order: $ordered" }

        // Reversed: q2r fails first (no q yet) ⇒ fail-fast ⇒ nothing is rewritten.
        val reversed = expand("$rules\nstrategy { \"q2r\" then \"p2q\" }\np(1)").text
        require("p(1)" in reversed && "r(1)" !in reversed && "q(1)" !in reversed) {
            "a failing first step must abort the whole sequence: $reversed"
        }
    }

    /**
     * `choice(a, b)` runs the first applicable alternative. When the alternative order differs from
     * declaration order, the result differs from the default (declaration-order first-match-wins).
     */
    private fun testStrategyChoiceFirstApplicable() {
        val rules = """
            rule("toX") { x: Expr -> from { pick(x) }; to { X(x) } }
            rule("toY") { x: Expr -> from { pick(x) }; to { Y(x) } }
        """.trimIndent()
        // choice prefers toY though toX is declared first ⇒ Y, not the default X.
        val pref = expand("$rules\nstrategy { choice(\"toY\", \"toX\") }\npick(1)").text
        require("Y(1)" in pref && "X(1)" !in pref) { "choice must prefer its first alternative: $pref" }

        // Fallback: a non-matching first alternative falls through to the second.
        val fall = expand(
            """
            rule("zzz") { x: Expr -> from { zzz(x) }; to { Z(x) } }
            rule("toX") { x: Expr -> from { pick(x) }; to { X(x) } }
            strategy { choice("zzz", "toX") }
            pick(1)
            """.trimIndent()
        ).text
        require("X(1)" in fall) { "choice must fall through a non-matching alternative: $fall" }
    }

    /** `repeat(s, n)` stops after n applications; unbounded `repeat(s)` runs to a fixpoint. */
    private fun testStrategyRepeatBoundedVsUnbounded() {
        val rule = """rule("fg") { x: Expr -> from { f(x) }; to { g(x) } }"""
        val bounded = expand("$rule\nstrategy { repeat(topdown(\"fg\"), 1) }\nf(f(f(a)))").text
        require("g(f(f(a)))" in bounded) { "repeat(…, 1) applies exactly one outermost step: $bounded" }

        val unbounded = expand("$rule\nstrategy { repeat(topdown(\"fg\")) }\nf(f(a))").text
        require("g(g(a))" in unbounded) { "unbounded repeat drives topdown to a fixpoint: $unbounded" }
    }

    /** `attempt(s)` rescues a failing step into identity, so a following step can still run. */
    private fun testStrategyAttemptIsIdentityOnFailure() {
        val src = """
            rule("zzz") { x: Expr -> from { zzz(x) }; to { Z(x) } }
            rule("fg") { x: Expr -> from { f(x) }; to { g(x) } }
            strategy { attempt("zzz") then "fg" }
            f(a)
        """.trimIndent()
        val out = expand(src).text
        require("g(a)" in out) { "attempt over a non-matching rule must not abort the sequence: $out" }
    }

    /** Anonymous `rewrite {}` rules carry no name, so a `strategy {}` can never fire them. */
    private fun testStrategyAnonymousRulesDoNotFire() {
        val src = """
            rewrite { x: Expr -> from { anon(x) }; to { A(x) } }
            rule("fg") { x: Expr -> from { f(x) }; to { g(x) } }
            strategy { "fg" }
            anon(1)
            f(2)
        """.trimIndent()
        val out = expand(src).text
        require("g(2)" in out) { "the named rule must fire: $out" }
        require("anon(1)" in out && "A(1)" !in out) { "an anonymous rule must not fire under a strategy: $out" }
    }

    /** Fail-closed: a strategy referencing an unknown rule name rewrites NOTHING, with a diagnostic. */
    private fun testStrategyUnknownRuleFailsClosed() {
        val res = expand("rule(\"fg\") { x: Expr -> from { f(x) }; to { g(x) } }\nstrategy { once(\"nope\") }\nf(a)")
        require(res.diagnostic != null && "unknown rule name" in res.diagnostic!!) {
            "an unknown rule name must be reported: ${res.diagnostic}"
        }
        require("f(a)" in res.text && "g(a)" !in res.text) { "fail-closed must not rewrite anything: ${res.text}" }
    }

    /** Fail-closed: a malformed strategy expression rewrites NOTHING, with a diagnostic. */
    private fun testStrategyMalformedFailsClosed() {
        val res = expand("rule(\"fg\") { x: Expr -> from { f(x) }; to { g(x) } }\nstrategy { 1 + 2 }\nf(a)")
        require(res.diagnostic != null) { "a malformed strategy must be reported" }
        require("f(a)" in res.text && "g(a)" !in res.text) { "fail-closed must not rewrite anything: ${res.text}" }
    }

    /** Side contributions (`provide {}`) still accrue when a rule fires under a strategy. */
    private fun testStrategyProvideStillContributes() {
        val src = """
            rule("cnt") { n: Ident -> from { counter(n) }; provide { var Box.n by Counting() }; to { n } }
            strategy { "cnt" }
            counter(hits)
        """.trimIndent()
        val res = expand(src)
        require(res.preambleBlocks.size == 1) { "provide must still contribute under a strategy: ${res.preambleBlocks}" }
        require("var Box.hits by Counting()" in res.preambleBlocks.single()) {
            "metavar must be substituted in the strategy-driven preamble: ${res.preambleBlocks}"
        }
        require("hits" in res.text) { "to output should splice the capture: ${res.text}" }
    }

    /** End-to-end through the real engine: a named rule + strategy is deleted, wired, compiles, runs. */
    private fun testStrategyEndToEndThroughEngine() = runBlocking {
        val ki = KotlinInterface2(filePrefix = "pattern-rewrite-strategy-e2e")
        val src = """
            rule("dbl") { x: Expr -> from { dbl(x) }; to { x * 2 } }
            strategy { once("dbl") }
            val r = dbl(3) + dbl(4)
            r
        """.trimIndent()

        val expanded = ki.applyMacros(Box("pattern-rewrite-strategy-e2e"), src)
        require("rule(" !in expanded && "strategy {" !in expanded) {
            "engine applyMacros must delete the rule + strategy blocks: $expanded"
        }
        // once ⇒ only the first dbl(3) is rewritten; dbl(4) is left as a literal call to the (real,
        // spliced-in) helper below so the whole thing still compiles + evaluates.
        require("3 * 2" in expanded && "dbl(4)" in expanded) { "once must rewrite only the first call: $expanded" }

        // Provide a real `dbl` for the un-rewritten call so the expanded source compiles + runs.
        val full = "fun dbl(v: Int) = v + v\n$expanded"
        val result = withTimeout(120_000) { ki.exec(full) }
        require(result.toString().contains("14")) { "3*2 + dbl(4)=8 ⇒ 14, got: $result" }
    }
}
