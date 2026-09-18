package afield.examples.stdlib

import afield.stdlib.ActivationOrder
import afield.stdlib.Lambda
import afield.stdlib.LambdaTerms
import afield.stdlib.NodeState
import afield.stdlib.Reducer
import afield.stdlib.Term
import afield.stdlib.net
import afield.stdlib.vec

/*
FIELD_TEST
id: stdlib.lambda-terms.smoke
runner: jvm-main
entrypoint: afield.examples.stdlib.LambdaTermsSmokeTest
tags: [smoke, stdlib, interaction-nets, lambda]
timeoutSec: 240
expect:
  exitCode: 0
  stdoutContains:
    - "Lambda terms smoke tests passed"
*/

/**
 * The oracle harness for `afield.stdlib.LambdaTerms`.
 *
 * The load-bearing assertion is [testCorpusAgreesWithTheOracle]: for every named term in
 * `LambdaTerms.corpus()`, `readback(reduce(compile(t)))` is α-equivalent to `referenceNormalOrder(t)` — a net
 * reduction compared against an ordinary capture-avoiding substitution evaluator that shares nothing. It is asserted
 * as **set equality on the corpus names**, so a corpus that quietly loses an entry fails rather than passing more
 * easily, and the disagreements are printed by name before the failure rather than hidden behind it.
 *
 * [testSchedulersAgree] is the other half. The [ActivationOrder] is a free choice and the answer must not depend on
 * it, so four of the six orders are run over the whole corpus and their de Bruijn keys compared.
 */
object LambdaTermsSmokeTest {

    /** Every order except RANDOM (not reproducible in a failure message) and LOWEST_PAIR_ENERGY (see below). */
    private val ORDERS = listOf(
        ActivationOrder.FIRST_FOUND,
        ActivationOrder.LEFTMOST_BY_ID,
        ActivationOrder.RIGHTMOST_BY_ID,
        ActivationOrder.HIGHEST_PAIR_ENERGY
    )

    private const val MAX_STEPS = 200_000

    fun runTests(): Boolean {
        return try {
            println("Running lambda terms smoke tests...")
            testCorpusIsNamedAndClosed()
            testParserRoundTrips()
            testAlphaEquivalence()
            testSubstitutionAvoidsCapture()
            testIdentityUnderDuplicationReducesAtAll()
            testCorpusAgreesWithTheOracle()
            testSchedulersAgree()
            testStepCountTable()
            println("Lambda terms smoke tests passed")
            true
        } catch (t: Throwable) {
            println("Lambda terms smoke tests failed: ${t.message}")
            t.printStackTrace()
            false
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "Lambda terms smoke tests failed" }
    }

    // =========================
    // The corpus itself
    // =========================

    /**
     * The corpus is asserted by the **set of its names**, so it cannot shrink without this failing, and every entry
     * is asserted closed — an open term is outside what the readback promises.
     */
    private fun testCorpusIsNamedAndClosed() {
        val expected = setOf(
            "identity",
            "k-applied",
            "skk",
            "skk-applied",
            "sks-applied",
            "church-zero",
            "church-succ-zero",
            "church-succ-two",
            "church-add-1-2",
            "church-add-2-3",
            "church-mul-2-3",
            "church-mul-3-3",
            "church-pred-one",
            "church-pred-three",
            "church-exp-two-cubed",
            "bool-true",
            "bool-if-true",
            "bool-if-false",
            "bool-and-applied",
            "self-apply-identity",
            "self-apply-redex",
            "duplicate-a-redex",
            "share-under-lambda",
            "erase-argument",
            "erase-nested"
        )
        val actual = LambdaTerms.corpus().keys
        require(actual == expected) {
            "Corpus names changed. Missing: ${expected - actual}; unexpected: ${actual - expected}"
        }
        require(actual.size == 25) { "Expected a corpus of 25 named terms, found ${actual.size}" }
        for ((name, source) in LambdaTerms.corpus()) {
            val free = LambdaTerms.freeVariablesOf(LambdaTerms.parse(source))
            require(free.isEmpty()) { "Corpus entry '$name' is not closed: free variables $free" }
        }
        println("  corpus: ${actual.size} named closed terms")
    }

    /** `parse(format(t)) == t` exactly — not merely α-equivalent — over all 25 corpus entries. */
    private fun testParserRoundTrips() {
        for ((name, source) in LambdaTerms.corpus()) {
            val once = LambdaTerms.parse(source)
            val printed = LambdaTerms.format(once)
            val twice = LambdaTerms.parse(printed)
            require(once == twice) { "Round-trip changed '$name': $source -> $printed" }
        }
        // The two spellings of a binder, and the multi-binder sugar, are one term.
        require(LambdaTerms.parse("\\x y. y x") == LambdaTerms.parse("λx. λy. y x")) {
            "Multi-binder sugar and nested λ should parse identically"
        }
        require(LambdaTerms.format(LambdaTerms.parse("(\\x. x) (\\y. y) z")) == "(λx. x) (λy. y) z") {
            "Printer parenthesised wrongly: ${LambdaTerms.format(LambdaTerms.parse("(\\x. x) (\\y. y) z"))}"
        }
        println("  parser round-trips over 25 corpus entries plus 2 hand cases")
    }

    private fun testAlphaEquivalence() {
        require(LambdaTerms.alphaEquivalent(LambdaTerms.parse("\\x. x"), LambdaTerms.parse("\\y. y")))
        require(!LambdaTerms.alphaEquivalent(LambdaTerms.parse("\\x y. x"), LambdaTerms.parse("\\x y. y")))
        require(LambdaTerms.deBruijnKey(LambdaTerms.parse("\\f x. f (f x)")) == "λλ(#1 (#1 #0))") {
            "de Bruijn key of Church two was ${LambdaTerms.deBruijnKey(LambdaTerms.parse("\\f x. f (f x)"))}"
        }
        // A free variable is keyed by name, so two different free variables are not α-equivalent.
        require(!LambdaTerms.alphaEquivalent(LambdaTerms.parse("\\x. y"), LambdaTerms.parse("\\x. z")))
        println("  alpha-equivalence: 4 cases")
    }

    /** The one place a substitution evaluator is ever wrong. */
    private fun testSubstitutionAvoidsCapture() {
        val substituted = LambdaTerms.substitute(LambdaTerms.parse("\\y. x y"), "x", LambdaTerms.parse("y"))
        // The binder must have been renamed, so the `y` that arrived is still free.
        require("y" in LambdaTerms.freeVariablesOf(substituted)) {
            "Substitution captured a free variable: $substituted"
        }
        require(substituted is Term.Lam && substituted.param != "y") {
            "Expected the capturing binder to be renamed, got $substituted"
        }
        println("  substitution avoids capture: $substituted")
    }

    // =========================
    // The substrate fix
    // =========================

    /**
     * The net that used to throw. `λx. x` compiled has a wire straight from the abstraction's binder to its own body,
     * so duplicating it makes `dup-lam` reconnect to a port of a node the reducer has already removed — unless
     * `Reducer.apply` chases the wire. Built by hand rather than through [LambdaTerms.compile] so the failure is
     * pinned to the substrate and not to the compiler.
     */
    private fun testIdentityUnderDuplicationReducesAtAll() {
        val reducer = Reducer(Lambda.rules(), relaxIterations = 0)
        val subject = net {
            val dup = node(Lambda.DUP, id = "dup", at = vec(-80.0, 0.0), state = Lambda.fan(1))
            val lam = node(Lambda.LAM, id = "lam", at = vec(80.0, 0.0), state = NodeState(energy = 1.0))
            val left = node(Lambda.ROOT, id = "left", at = vec(-160.0, -80.0))
            val right = node(Lambda.ROOT, id = "right", at = vec(-160.0, 80.0))
            dup.principal to lam.principal
            dup.aux(0) to left.principal
            dup.aux(1) to right.principal
            lam.aux(0) to lam.aux(1) // λx. x — the binder *is* the body
        }
        val steps = reducer.reduce(subject, maxSteps = 64)
        require(steps >= 2) { "Expected dup-lam then fan-fan, got $steps step(s)" }
        require(subject.nodes.none { it.type == Lambda.DUP }) {
            "Fans survived: ${subject.nodes.filter { it.type == Lambda.DUP }.map { it.id }}"
        }
        val lams = subject.nodes.filter { it.type == Lambda.LAM }
        require(lams.size == 2) { "Expected two copies of the abstraction, found ${lams.size}" }
        for (lam in lams) {
            require(subject.connected(lam.aux(0)) == lam.aux(1)) {
                "Copy ${lam.id} is not the identity: binder faces ${subject.connected(lam.aux(0))}"
            }
        }
        println("  dup-lam on the identity: $steps steps, 2 identity copies")
    }

    // =========================
    // The oracle
    // =========================

    private class Outcome(
        val name: String,
        val netKey: String,
        val oracleKey: String,
        val steps: Int,
        val finished: Boolean,
        val printed: String,
        val fans: Int,
        val remaining: Int
    ) {
        val agrees: Boolean get() = finished && netKey == oracleKey
    }

    private fun reduceAndCompare(name: String, source: String, order: ActivationOrder): Outcome {
        val term = LambdaTerms.parse(source)
        val oracleKey = LambdaTerms.deBruijnKey(LambdaTerms.referenceNormalOrder(term))
        // A term that throws is a finding with a name, not a table that never prints.
        return try {
            val run = LambdaTerms.normalise(term, order = order, maxSteps = MAX_STEPS)
            Outcome(
                name = name,
                netKey = LambdaTerms.deBruijnKey(run.result),
                oracleKey = oracleKey,
                steps = run.steps,
                finished = run.finished,
                printed = run.printed,
                fans = run.fans,
                remaining = run.remainingNodes
            )
        } catch (t: Throwable) {
            Outcome(
                name = name,
                netKey = "threw ${t::class.simpleName}: ${t.message}",
                oracleKey = oracleKey,
                steps = -1,
                finished = false,
                printed = "threw ${t::class.simpleName}: ${t.message}",
                fans = -1,
                remaining = -1
            )
        }
    }

    /**
     * `readback(reduce(compile(t))) ≡α referenceNormalOrder(t)` over the whole corpus, asserted as set equality on
     * the names so the corpus cannot shrink into a pass.
     */
    private fun testCorpusAgreesWithTheOracle() {
        val corpus = LambdaTerms.corpus()
        println("  oracle identity over ${corpus.size} named terms, order=FIRST_FOUND:")
        val outcomes = ArrayList<Outcome>()
        for ((name, source) in corpus) {
            // Printed as it is computed, so a term that hangs or throws still names itself in the log.
            val o = reduceAndCompare(name, source, ActivationOrder.FIRST_FOUND)
            outcomes += o
            val mark = if (o.agrees) "ok  " else "DIFF"
            println(
                "    $mark ${o.name.padEnd(22)} steps=${o.steps.toString().padStart(6)} " +
                    "fans=${o.fans.toString().padStart(3)} left=${o.remaining.toString().padStart(4)} " +
                    "-> ${o.printed}"
            )
            if (!o.agrees) println("         oracle ${o.oracleKey}\n         net    ${o.netKey}")
        }
        val agreed = outcomes.filter { it.agrees }.map { it.name }.toSet()
        require(agreed == corpus.keys) {
            "Net and oracle disagree on ${(corpus.keys - agreed).size} of ${corpus.size}: ${corpus.keys - agreed}"
        }
    }

    /** The scheduler is a free choice; the normal form is not. */
    private fun testSchedulersAgree() {
        val corpus = LambdaTerms.corpus()
        val disagreements = ArrayList<String>()
        for ((name, source) in corpus) {
            val keys = LinkedHashMap<ActivationOrder, String>()
            for (order in ORDERS) keys[order] = reduceAndCompare(name, source, order).netKey
            if (keys.values.toSet().size != 1) disagreements += "$name: $keys"
        }
        require(disagreements.isEmpty()) {
            "Confluence failed for ${disagreements.size} of ${corpus.size} terms across ${ORDERS.size} orders: " +
                disagreements.joinToString("; ")
        }
        println("  confluence: ${corpus.size} terms x ${ORDERS.size} orders agree on the normal form")
    }

    /**
     * The step-count table, with its population stated and a band asserted around the total.
     *
     * **Two columns, in two different units.** A net reaction is any rule firing — a β, a duplication, an erasure or
     * a fan meeting — while an oracle step is a β-reduction and nothing else. They are not comparable as a ratio and
     * neither number says "optimal". What the pair is good for is noticing a change: over the 25-term corpus at
     * `FIRST_FOUND`, 228 net reactions against 117 oracle β-steps as measured on 2026-09-18.
     *
     * A **floor** as well as a ceiling, because a step count that suddenly drops is a reducer that has stopped doing
     * the work rather than one that got clever, and the ceiling is what a rule firing forever trips. The oracle total
     * is asserted exactly: it is pure term arithmetic with no scheduler in it, so it has no business drifting.
     */
    private fun testStepCountTable() {
        val corpus = LambdaTerms.corpus()
        var total = 0
        var oracleTotal = 0
        var remaining = 0
        for ((_, source) in corpus) {
            val term = LambdaTerms.parse(source)
            val run = LambdaTerms.normalise(term, maxSteps = MAX_STEPS)
            total += run.steps
            remaining += run.remainingNodes
            oracleTotal += countOracleBetas(term)
        }
        println(
            "  step counts over ${corpus.size} terms, order=FIRST_FOUND: " +
                "$total net reactions against $oracleTotal oracle beta-steps, $remaining agents left standing"
        )
        require(total in STEP_FLOOR..STEP_CEILING) {
            "Corpus step total $total left the asserted band $STEP_FLOOR..$STEP_CEILING over ${corpus.size} terms"
        }
        require(oracleTotal == ORACLE_BETA_TOTAL) {
            "Oracle beta total moved from $ORACLE_BETA_TOTAL to $oracleTotal over ${corpus.size} terms"
        }
    }

    /** β-steps the oracle takes, counted by instrumenting nothing — it just re-reduces and counts by bisection. */
    private fun countOracleBetas(term: Term): Int {
        // `referenceNormalOrder` throws once the budget is short, so the smallest budget that succeeds is the count.
        var lo = 0
        var hi = 1
        while (hi < 1_000_000 && !oracleFitsIn(term, hi)) hi *= 2
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (oracleFitsIn(term, mid)) hi = mid else lo = mid + 1
        }
        return lo
    }

    private fun oracleFitsIn(term: Term, budget: Int): Boolean = try {
        LambdaTerms.referenceNormalOrder(term, maxSteps = budget)
        true
    } catch (e: LambdaTerms.ReductionBudgetExceeded) {
        false
    }

    // Measured 228 at FIRST_FOUND on 2026-09-18 over the 25-term corpus; widened by about 10% each way so that a
    // re-layout or an id change does not churn the fence, and so that a collapse or an explosion still trips it.
    private const val STEP_FLOOR = 205
    private const val STEP_CEILING = 255

    // 117 oracle beta-steps over the same 25 terms. Exact: no scheduler enters into it.
    private const val ORACLE_BETA_TOTAL = 117
}
