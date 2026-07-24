package afield

import afield.macros.PatternRewrite
import afield.macros.resolvePatternRewriteAi
import com.facebook.ktfmt.format.Parser
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
            testDeclarationOrderFirstMatchWins()
            testNestedRewriteDoesNotSelfApply()
            testGuardDistinct()
            testGuardKindPredicates()
            testGuardBooleanCombinators()
            testCoercionIdiomTerminates()
            testGensymHygiene()
            testProvideProducesKeyedPreamble()
            testProvideDedupAndDistinctKeys()
            testAiRequestProducedAndForwarded()
            testAtticCacheHelpers()
            testProvideEndToEndIdempotent()
            testAiCacheHitIsNoModelCall()
            testTerminationCap()
            testEndToEndThroughEngine()
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
        val diagnostic: String?,
    )

    private fun expand(src: String, maxIterations: Int = 64): Result {
        var diag: String? = null
        val out = PatternRewrite.expand(src, ::parse, maxIterations) { diag = it }
        return Result(out.text, out.preambleBlocks, out.aiRequests, diag)
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
}
