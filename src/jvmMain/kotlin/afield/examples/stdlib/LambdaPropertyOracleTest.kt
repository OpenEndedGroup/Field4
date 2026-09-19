package afield.examples.stdlib

import afield.stdlib.ActivationOrder
import afield.stdlib.Lambda
import afield.stdlib.LambdaTerms
import afield.stdlib.NodeState
import afield.stdlib.Term
import afield.stdlib.net
import afield.stdlib.vec

/*
FIELD_TEST
id: stdlib.lambda-terms.property
runner: jvm-main
entrypoint: afield.examples.stdlib.LambdaPropertyOracleTest
tags: [smoke, stdlib, interaction-nets, lambda, property]
timeoutSec: 900
expect:
  exitCode: 0
  stdoutContains:
    - "Lambda property oracle passed"
*/

/**
 * The property harness that turns `dev_docs/INTERACTION_NETS_LAMBDA.md`'s "measurement over 25 terms, not a proof"
 * into a number with a population.
 *
 * `stdlib.lambda-terms.smoke` asserts `readback(reduce(compile(t))) ≡α referenceNormalOrder(t)` over a **named**
 * corpus of 25 terms. That corpus was hand-written, so it tests what its author thought of. This one generates its
 * population from a seed ([LambdaTermGenerator]) and asserts the same identity plus the half the page says is
 * unproven:
 *
 * > A term that does could surface either way: as a `⋈`, or as a wrong normal form with no marker at all.
 *
 * So there are **two** assertions per disagreement, not one:
 *
 * 1. the net's normal form is α-equivalent to the oracle's, and
 * 2. **any** disagreement carries a readback marker.
 *
 * (2) is the one worth running. A disagreement that carries `⋈` is a documented failure mode being observed; a
 * disagreement with no marker at all is the quiet wrong answer the page warns about and has no detector but this.
 *
 * ### What keeps this from becoming a tautology
 *
 * Three premises are asserted *before* any generated term is reduced, because a harness that generates zero terms,
 * or whose marker detector cannot see a marker, or whose key comparison cannot see a difference, passes silently and
 * is worse than no harness:
 *
 * - [assertTheCorpusIsStillTwentyFive] — the environment this page's claim was made in.
 * - [assertTheMarkerDetectorSeesAMarker] — a hand-wired net with an unmatched fan must read back as `⋈`, and a clean
 *   reduction must read back with no marks. Both directions, since only one of them can be got wrong by accident.
 * - [assertTheComparisonCanFail] — an *unreduced* compiled term, read back, must disagree with the oracle. If this
 *   passes, the key comparison is not comparing anything.
 *
 * And the population is **floored**: [CHECK_FLOOR] and the per-family fan floors are asserted, so a generator that
 * collapses from thousands of terms to one fails the test rather than printing a smaller number nobody reads.
 */
object LambdaPropertyOracleTest {

    /** Same four as `stdlib.lambda-terms.smoke`: RANDOM is not reproducible, LOWEST_PAIR_ENERGY is run-count. */
    private val ORDERS = listOf(
        ActivationOrder.FIRST_FOUND,
        ActivationOrder.LEFTMOST_BY_ID,
        ActivationOrder.RIGHTMOST_BY_ID,
        ActivationOrder.HIGHEST_PAIR_ENERGY
    )

    // The seeds. Named constants rather than a clock, so every number this test prints is reproducible by anyone
    // who reads the output — "0 disagreements" means nothing without the seed that found them.
    private const val TYPED_SEED = 20260919L
    private const val SOUP_SEED = 20260919_002L

    private const val TYPED_N = 100
    private const val SOUP_N = 50

    // Budgets, per family, because the two families cost wildly different amounts.
    //
    // A simply-typed term is strongly normalising, so the typed budgets bound *cost* and never filter a sample — an
    // overrun there would be a bug. The soup family is untyped, and its blow-up is not in the step count but in the
    // *size of the intermediate terms*: a β-step of normal-order substitution can nearly double the term, so a
    // thousand-step budget is not a memory bound. The soup oracle budget is therefore deliberately small, and the
    // draws that exceed it are a reported column rather than a silent drop.
    private const val TYPED_ORACLE_BUDGET = 40_000
    private const val TYPED_ORACLE_WORK = 20_000_000
    private const val TYPED_NET_BUDGET = 100_000
    private const val SOUP_ORACLE_BUDGET = 2_000
    private const val SOUP_ORACLE_WORK = 4_000_000
    private const val SOUP_NET_BUDGET = 20_000

    fun runTests(): Boolean {
        return try {
            println("Running lambda property oracle...")
            assertTheCorpusIsStillTwentyFive()
            assertTheMarkerDetectorSeesAMarker()
            assertTheComparisonCanFail()
            assertTheNamedRegressionsStillHold()

            val typed = LambdaTermGenerator.Population(
                "typed",
                TYPED_SEED,
                LambdaTermGenerator.typedTerms(TYPED_SEED, TYPED_N)
            )
            val soup = LambdaTermGenerator.Population(
                "soup",
                SOUP_SEED,
                LambdaTermGenerator.soupTerms(SOUP_SEED, SOUP_N)
            )
            val results = listOf(
                sweep(typed, TYPED_ORACLE_BUDGET, TYPED_ORACLE_WORK, TYPED_NET_BUDGET),
                sweep(soup, SOUP_ORACLE_BUDGET, SOUP_ORACLE_WORK, SOUP_NET_BUDGET)
            )
            report(results)
            // Findings are printed before any assertion, floors included: a floor that fires first would hide the
            // counterexample the run was for.
            printAllFindings(results)
            assertFloors(typed, soup, results)
            assertNoQuietDisagreement(results)
            println("Lambda property oracle passed")
            true
        } catch (t: Throwable) {
            println("Lambda property oracle failed: ${t.message}")
            t.printStackTrace()
            false
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "Lambda property oracle failed" }
    }

    // =========================
    // Premises
    // =========================

    /** The claim under test is a claim about a 25-term corpus. If that changed, this test is measuring something else. */
    private fun assertTheCorpusIsStillTwentyFive() {
        val n = LambdaTerms.corpus().size
        require(n == 25) { "The page's claim is about a 25-term corpus; LambdaTerms.corpus() now has $n" }
        println("  premise: LambdaTerms.corpus() is still $n named terms")
    }

    /**
     * Both directions of the marker detector, because assertion (2) of this harness is only as good as its ability
     * to *see* a `⋈`.
     *
     * The net is a `ROOT` facing a fan's principal port with nothing on the stack — which is precisely the readback
     * case the page describes — and `λx. x` normalised is the clean side.
     */
    private fun assertTheMarkerDetectorSeesAMarker() {
        val stranded = net {
            val root = node(Lambda.ROOT, id = LambdaTerms.ROOT_ID, at = vec(0.0, -80.0), state = NodeState(energy = 0.6))
            val fan = node(Lambda.DUP, id = "fan", at = vec(0.0, 0.0), state = Lambda.fan(7))
            val a = node(Lambda.VAR, id = "a", at = vec(-60.0, 80.0), state = NodeState(energy = 0.8))
            val b = node(Lambda.VAR, id = "b", at = vec(60.0, 80.0), state = NodeState(energy = 0.8))
            root.principal to fan.principal
            fan.aux(0) to a.principal
            fan.aux(1) to b.principal
        }
        val marks = marksIn(LambdaTerms.readback(stranded))
        require("⋈" in marks) { "A ROOT facing an unmatched fan must read back as ⋈, got $marks" }

        val clean = marksIn(LambdaTerms.normalise("(\\x. x x) (\\y. y)").result)
        require(clean.isEmpty()) { "A clean reduction must carry no marks, got $clean" }
        println("  premise: marker detector sees ⋈ on a stranded fan and nothing on a clean reduction")
    }

    /**
     * The key comparison must be able to say "different".
     *
     * An unreduced compiled term read back is the term itself — redexes and all — so for a term with a redex it
     * cannot equal the oracle's normal form. If this ever passes, every `agrees` below is vacuous.
     */
    private fun assertTheComparisonCanFail() {
        val term = LambdaTerms.parse("(\\x. x x) (\\y. y)")
        val unreduced = LambdaTerms.deBruijnKey(LambdaTerms.readback(LambdaTerms.compile(term)))
        val oracle = LambdaTerms.deBruijnKey(LambdaTerms.referenceNormalOrder(term))
        require(unreduced != oracle) { "Comparison is vacuous: unreduced readback already equals the oracle ($oracle)" }
        println("  premise: comparison distinguishes $unreduced from $oracle")
    }

    // =========================
    // The sweep
    // =========================

    private enum class Verdict {
        /** Net and oracle agree on the de Bruijn key. */
        AGREE,

        /** They disagree, and the readback left at least one mark — a documented failure mode, observed. */
        DISAGREE_MARKED,

        /** They disagree and the readback looks like a perfectly good term. This is the one the page cannot rule out. */
        DISAGREE_QUIET,

        /**
         * The oracle ran out of β-steps or of work, so there is nothing to compare against. Reported, never dropped
         * — an untyped draw that diverges is part of this population and its count is part of every number below.
         */
        ORACLE_OVER_BUDGET,

        /** The net ran out of reactions. Also not a disagreement — the net simply has not finished. */
        NET_UNFINISHED,

        /** Something threw. Always a finding. */
        THREW
    }

    private class Check(
        val index: Int,
        val order: ActivationOrder,
        val verdict: Verdict,
        val netKey: String,
        val oracleKey: String,
        val marks: Set<String>,
        val steps: Int,
        val detail: String = ""
    )

    private class Sweep(
        val population: LambdaTermGenerator.Population,
        val checks: List<Check>,
        val oracleBudget: Int,
        val oracleWork: Int,
        val netBudget: Int
    ) {
        fun count(v: Verdict): Int = checks.count { it.verdict == v }

        /** Terms whose net key differs between two schedulers — confluence at scale, not just on 25 terms. */
        val confluenceFailures: List<Int> by lazy {
            checks.groupBy { it.index }
                .filterValues { group ->
                    val comparable = group.filter { it.verdict != Verdict.ORACLE_OVER_BUDGET }
                    comparable.size > 1 && comparable.map { it.netKey }.toSet().size != 1
                }
                .keys.sorted()
        }

        val netSteps: List<Int> by lazy { checks.filter { it.steps >= 0 }.map { it.steps } }
    }

    private fun sweep(
        population: LambdaTermGenerator.Population,
        oracleBudget: Int,
        oracleWork: Int,
        netBudget: Int
    ): Sweep {
        val checks = ArrayList<Check>(population.terms.size * ORDERS.size)
        val started = System.nanoTime()
        var slowestMillis = 0L
        var slowestIndex = -1
        for ((i, term) in population.terms.withIndex()) {
            val termStarted = System.nanoTime()
            // The oracle is computed once per term; the schedulers are compared against the same answer.
            //
            // It runs *first* and on a budget because it is the expensive half: the net shares and the oracle does
            // not, so a term the net normalises in a hundred reactions can cost the oracle exponentially more. An
            // over-budget oracle is a column in the report, not a dropped sample.
            val oracleKey = try {
                LambdaTerms.deBruijnKey(
                    LambdaTerms.referenceNormalOrder(term, maxSteps = oracleBudget, maxWorkNodes = oracleWork)
                )
            } catch (e: LambdaTerms.ReductionBudgetExceeded) {
                null
            } catch (e: LambdaTerms.IntermediateTermTooLarge) {
                null
            }
            for (order in ORDERS) checks += checkOne(i, term, order, oracleKey, netBudget)
            val millis = (System.nanoTime() - termStarted) / 1_000_000
            if (millis > slowestMillis) {
                slowestMillis = millis
                slowestIndex = i
            }
            // A heartbeat, because this test has minutes in it and a silent minute is indistinguishable from a hang.
            if ((i + 1) % 100 == 0) {
                println(
                    "    ${population.label} ${i + 1}/${population.terms.size} " +
                        "(${(System.nanoTime() - started) / 1_000_000_000}s, slowest #$slowestIndex ${slowestMillis}ms)"
                )
            }
        }
        println(
            "    ${population.label} swept in ${(System.nanoTime() - started) / 1_000_000_000}s, " +
                "slowest term #$slowestIndex at ${slowestMillis}ms"
        )
        return Sweep(population, checks, oracleBudget, oracleWork, netBudget)
    }

    private fun checkOne(index: Int, term: Term, order: ActivationOrder, oracleKey: String?, netBudget: Int): Check {
        if (oracleKey == null) {
            return Check(index, order, Verdict.ORACLE_OVER_BUDGET, "", "", emptySet(), -1)
        }
        val run = try {
            LambdaTerms.normalise(term, order = order, maxSteps = netBudget)
        } catch (t: Throwable) {
            // The top frames, not just the message: a StackOverflowError's message is null, and *where* it
            // overflowed is the whole finding.
            val site = t.stackTrace.take(3).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            return Check(
                index, order, Verdict.THREW, "", oracleKey, emptySet(), -1,
                "${t::class.simpleName}: ${t.message} at $site"
            )
        }
        val marks = marksIn(run.result)
        val netKey = LambdaTerms.deBruijnKey(run.result)
        val verdict = when {
            !run.finished -> Verdict.NET_UNFINISHED
            netKey == oracleKey -> Verdict.AGREE
            marks.isNotEmpty() -> Verdict.DISAGREE_MARKED
            else -> Verdict.DISAGREE_QUIET
        }
        return Check(index, order, verdict, netKey, oracleKey, marks, run.steps, run.printed)
    }

    // =========================
    // Marks
    // =========================

    /** The five marks `LambdaTerms.readback` leaves where the net was not a term. */
    private val MARKS = setOf("⊥", "·", "⋈", "∞", "…")

    /**
     * Every mark in [term], plus the two things a readback emits that are not in the table but are just as much "this
     * is not a term": a `?id` for a binder occurrence with no binder, and an `agent:port` for a node type the walk
     * has no case for.
     */
    fun marksIn(term: Term): Set<String> = when (term) {
        is Term.Var -> when {
            term.name in MARKS -> setOf(term.name)
            term.name.startsWith("?") -> setOf("?binder")
            ":" in term.name -> setOf("agent:port")
            else -> emptySet()
        }
        is Term.Lam -> marksIn(term.body)
        is Term.App -> marksIn(term.fn) + marksIn(term.arg)
    }

    // =========================
    // Reporting — every number with its population
    // =========================

    private fun report(sweeps: List<Sweep>) {
        for (s in sweeps) {
            println("  ${s.population.summary()}")
            println("    fan histogram (fans per term): ${s.population.fanHistogram()}")
            println(
                "    ${s.population.terms.size} terms x ${ORDERS.size} orders = ${s.checks.size} checks, " +
                    "oracle budget ${s.oracleBudget} beta-steps / ${s.oracleWork} work nodes, " +
                    "net budget ${s.netBudget} reactions"
            )
            println(
                "    agree=${s.count(Verdict.AGREE)}" +
                    "  disagree-marked=${s.count(Verdict.DISAGREE_MARKED)}" +
                    "  disagree-QUIET=${s.count(Verdict.DISAGREE_QUIET)}" +
                    "  oracle-over-budget=${s.count(Verdict.ORACLE_OVER_BUDGET)}" +
                    "  net-unfinished=${s.count(Verdict.NET_UNFINISHED)}" +
                    "  threw=${s.count(Verdict.THREW)}"
            )
            if (s.netSteps.isNotEmpty()) {
                println(
                    "    net reactions per run: ${s.netSteps.min()}..${s.netSteps.max()} " +
                        "mean=${"%.1f".format(s.netSteps.average())} total=${s.netSteps.sum()}"
                )
            }
            println("    scheduler disagreements (confluence): ${s.confluenceFailures.size} terms")
            for (c in s.checks.filter { it.verdict != Verdict.AGREE && it.verdict != Verdict.ORACLE_OVER_BUDGET }.take(20)) {
                println(
                    "      [${s.population.label} #${c.index} ${c.order}] ${c.verdict} marks=${c.marks}\n" +
                        "        term   ${clip(LambdaTerms.format(s.population.terms[c.index]))}\n" +
                        "        oracle ${clip(c.oracleKey)}\n" +
                        "        net    ${clip(c.netKey)}  ${clip(c.detail)}"
                )
            }
        }
    }

    // =========================
    // The floors
    // =========================

    // (900 + 400) terms x 4 orders. Asserted, not printed: this project's recorded weakness is a println behind no
    // ratchet, and a generator that silently produces one term would otherwise pass with "0 disagreements".
    private const val CHECK_FLOOR = (TYPED_N + SOUP_N) * 4

    // How many of those checks had a live oracle to compare against. Below the floor, "0 quiet disagreements" is a
    // statement about a population that mostly diverged.
    private const val COMPARED_FLOOR = 3_800

    // Terms whose compilation builds a fan at all, and terms that build four or more — the nested duplication this
    // hunt is actually about. Measured, then floored about 20% under.
    private const val TYPED_WITH_FANS_FLOOR = 700
    private const val TYPED_DEEP_FANS_FLOOR = 250
    private const val SOUP_DEEP_FANS_FLOOR = 300

    // Distinct de Bruijn keys, so a generator that emits the same term N times fails.
    private const val TYPED_DISTINCT_FLOOR = 800
    private const val SOUP_DISTINCT_FLOOR = 350

    private fun assertFloors(
        typed: LambdaTermGenerator.Population,
        soup: LambdaTermGenerator.Population,
        sweeps: List<Sweep>
    ) {
        val checks = sweeps.sumOf { it.checks.size }
        require(checks == CHECK_FLOOR) {
            "Population collapsed: $checks checks, expected exactly $CHECK_FLOOR " +
                "(${TYPED_N} typed + ${SOUP_N} soup terms x ${ORDERS.size} orders)"
        }
        val compared = sweeps.sumOf { it.checks.count { c -> c.verdict != Verdict.ORACLE_OVER_BUDGET } }
        require(compared >= COMPARED_FLOOR) {
            "Only $compared of $checks checks had a live oracle, floor is $COMPARED_FLOOR — the rest diverged, " +
                "so any headline about disagreements is about a smaller population than it claims"
        }
        require(typed.distinctKeys >= TYPED_DISTINCT_FLOOR) {
            "Typed family collapsed to ${typed.distinctKeys} distinct terms of ${typed.terms.size}, " +
                "floor $TYPED_DISTINCT_FLOOR"
        }
        require(soup.distinctKeys >= SOUP_DISTINCT_FLOOR) {
            "Soup family collapsed to ${soup.distinctKeys} distinct terms of ${soup.terms.size}, " +
                "floor $SOUP_DISTINCT_FLOOR"
        }
        require(typed.fans.count { it > 0 } >= TYPED_WITH_FANS_FLOOR) {
            "Typed family built fans for only ${typed.fans.count { it > 0 }} of ${typed.terms.size} terms, " +
                "floor $TYPED_WITH_FANS_FLOOR — a population without sharing cannot exhibit a sharing bug"
        }
        require(typed.fans.count { it >= 4 } >= TYPED_DEEP_FANS_FLOOR) {
            "Typed family reached 4+ fans on only ${typed.fans.count { it >= 4 }} terms, floor $TYPED_DEEP_FANS_FLOOR"
        }
        require(soup.fans.count { it >= 4 } >= SOUP_DEEP_FANS_FLOOR) {
            "Soup family reached 4+ fans on only ${soup.fans.count { it >= 4 }} terms, floor $SOUP_DEEP_FANS_FLOOR"
        }
        println(
            "  floors: $checks checks (==$CHECK_FLOOR), $compared compared (>=$COMPARED_FLOOR), " +
                "typed distinct ${typed.distinctKeys} (>=$TYPED_DISTINCT_FLOOR), " +
                "soup distinct ${soup.distinctKeys} (>=$SOUP_DISTINCT_FLOOR), " +
                "typed 4+fans ${typed.fans.count { it >= 4 }} (>=$TYPED_DEEP_FANS_FLOOR), " +
                "soup 4+fans ${soup.fans.count { it >= 4 }} (>=$SOUP_DEEP_FANS_FLOOR)"
        )
    }

    // =========================
    // The two assertions
    // =========================

    private fun of(sweeps: List<Sweep>, v: Verdict): List<Pair<Sweep, Check>> =
        sweeps.flatMap { s -> s.checks.filter { it.verdict == v }.map { s to it } }

    /**
     * Every finding, shrunk and printed. A counterexample that exists only inside a failure message is one nobody
     * can paste into a regression test.
     *
     * One shrink per distinct term rather than per (term, scheduler) — the four orders report the same term four
     * times.
     */
    private fun printAllFindings(sweeps: List<Sweep>) {
        printFindings("QUIET DISAGREEMENT (no readback mark at all)", of(sweeps, Verdict.DISAGREE_QUIET))
        printFindings("MARKED DISAGREEMENT", of(sweeps, Verdict.DISAGREE_MARKED))
        printFindings("THREW", of(sweeps, Verdict.THREW))
    }

    private fun assertNoQuietDisagreement(sweeps: List<Sweep>) {
        val threw = of(sweeps, Verdict.THREW)
        val quiet = of(sweeps, Verdict.DISAGREE_QUIET)
        val marked = of(sweeps, Verdict.DISAGREE_MARKED)
        val confluence = sweeps.sumOf { it.confluenceFailures.size }
        require(threw.isEmpty()) {
            "Reduction threw on ${threw.size} checks, first: " +
                threw.first().let { (s, c) -> "${s.population.label} #${c.index} ${c.order} ${c.detail}" }
        }
        require(confluence == 0) {
            "Schedulers disagree on $confluence terms: " +
                sweeps.flatMap { s -> s.confluenceFailures.take(3).map { "${s.population.label} #$it" } }
        }
        require(marked.isEmpty()) {
            "Net and oracle disagree with a readback marker on ${marked.size} checks — a documented failure mode, " +
                "now observed: " + marked.take(3).joinToString { (s, c) -> "${s.population.label} #${c.index} ${c.marks}" }
        }
        require(quiet.isEmpty()) {
            "Net and oracle disagree with NO readback marker on ${quiet.size} checks — the quiet wrong answer " +
                "dev_docs/INTERACTION_NETS_LAMBDA.md says it cannot rule out: " +
                quiet.take(3).joinToString { (s, c) -> "${s.population.label} #${c.index} ${c.order}" }
        }
    }

    private fun printFindings(headline: String, found: List<Pair<Sweep, Check>>) {
        val seen = HashSet<String>()
        for ((s, c) in found) {
            if (!seen.add("${s.population.label}#${c.index}")) continue
            if (seen.size > 6) {
                println("  $headline: ${found.size} checks in total, first 6 terms shown")
                return
            }
            val term = s.population.terms[c.index]
            println("  $headline, ${s.population.label} #${c.index} seed=${s.population.seed} ${c.order}")
            println("    as generated: ${clip(LambdaTerms.format(term))}")
            println("    marks:        ${c.marks}  ${clip(c.detail)}")
            val small = shrink(term) { candidate -> disagrees(candidate, c.order) }
            println("    shrunk to:    ${LambdaTerms.format(small)}")
            describe(small, c.order)
        }
    }

    /** What the two sides say about one term, printed so that a reader can retype it into a box. */
    private fun describe(term: Term, order: ActivationOrder) {
        val oracle = try {
            LambdaTerms.format(
                LambdaTerms.referenceNormalOrder(term, maxSteps = SOUP_ORACLE_BUDGET, maxWorkNodes = SOUP_ORACLE_WORK)
            )
        } catch (t: Throwable) {
            "over budget: ${t::class.simpleName}"
        }
        val net = try {
            val run = LambdaTerms.normalise(term, order = order, maxSteps = SOUP_NET_BUDGET)
            "${run.printed}   (${run.steps} reactions, finished=${run.finished}, marks=${marksIn(run.result)})"
        } catch (t: Throwable) {
            "threw ${t::class.simpleName}"
        }
        println("      oracle: ${clip(oracle)}")
        println("      net:    ${clip(net)}")
        println("      free in net answer: ${LambdaTerms.freeVariablesOf(safeNet(term, order)) - marksAsNames()}")
    }

    private fun safeNet(term: Term, order: ActivationOrder): Term = try {
        LambdaTerms.normalise(term, order = order, maxSteps = SOUP_NET_BUDGET).result
    } catch (t: Throwable) {
        Term.Var("·")
    }

    private fun marksAsNames(): Set<String> = MARKS

    /**
     * The predicate the shrinker minimises against: both sides finish and their answers differ, *or* the net throws
     * where the oracle does not.
     *
     * Mark-agnostic on purpose. Shrinking against "disagrees **and** has no mark" would let the shrinker walk out of
     * the failure it was given and into a different one, and the marks of the shrunk term are printed anyway.
     */
    private fun disagrees(term: Term, order: ActivationOrder): Boolean {
        val oracle = try {
            LambdaTerms.deBruijnKey(
                LambdaTerms.referenceNormalOrder(term, maxSteps = SOUP_ORACLE_BUDGET, maxWorkNodes = SOUP_ORACLE_WORK)
            )
        } catch (t: Throwable) {
            return false // no oracle, no verdict
        }
        return try {
            val run = LambdaTerms.normalise(term, order = order, maxSteps = SOUP_NET_BUDGET)
            !run.finished || LambdaTerms.deBruijnKey(run.result) != oracle
        } catch (t: Throwable) {
            true // the net threw where the oracle answered: still a disagreement, and still worth minimising
        }
    }

    /** Long de Bruijn keys are unreadable and drown the log; the shape at the front is what identifies them. */
    private fun clip(s: String, at: Int = 220): String = if (s.length <= at) s else s.take(at) + "…[${s.length} chars]"

    // =========================
    // Shrinking
    // =========================

    /**
     * The smallest closed term this can reach that still satisfies [stillFails], by greedy descent.
     *
     * Candidates are the ways of making a term structurally smaller without opening it: replace any node by a
     * variable already in scope there, drop an abstraction whose binder is unused, or keep either half of an
     * application when that half is closed on its own. Greedy and bounded — a minimal counterexample under this
     * neighbourhood, which is what a regression test wants, and not a claim of global minimality.
     */
    fun shrink(term: Term, rounds: Int = 400, stillFails: (Term) -> Boolean): Term {
        var best = term
        repeat(rounds) {
            val next = candidates(best, emptyList())
                .asSequence()
                .filter { LambdaTermGenerator.nodeCount(it) < LambdaTermGenerator.nodeCount(best) }
                .filter { LambdaTerms.freeVariablesOf(it).isEmpty() }
                .sortedBy { LambdaTermGenerator.nodeCount(it) }
                .firstOrNull(stillFails)
                ?: return best
            best = next
        }
        return best
    }

    private fun candidates(t: Term, scope: List<String>): List<Term> {
        val out = ArrayList<Term>()
        if (t !is Term.Var) for (v in scope) out += Term.Var(v)
        when (t) {
            is Term.Var -> {}
            is Term.Lam -> {
                if (t.param !in LambdaTerms.freeVariablesOf(t.body)) out += t.body
                for (c in candidates(t.body, scope + t.param)) out += Term.Lam(t.param, c)
            }
            is Term.App -> {
                out += t.fn
                out += t.arg
                for (c in candidates(t.fn, scope)) out += Term.App(c, t.arg)
                for (c in candidates(t.arg, scope)) out += Term.App(t.fn, c)
            }
        }
        return out
    }

    // =========================
    // Named regressions
    // =========================

    /**
     * Terms this hunt found and shrank, pinned by name so that a future change to the rule book or the readback has
     * to keep answering them correctly.
     *
     * Empty is a legitimate state and says something: over the populations printed above, no generated closed term
     * made the net and the oracle disagree. It is **not** a proof, and the sentence in
     * `dev_docs/INTERACTION_NETS_LAMBDA.md` is written to match.
     */
    private fun namedRegressions(): Map<String, String> = linkedMapOf(
        // Not a counterexample — a shrink-target shape kept as a smoke check that the shrinker's neighbourhood and
        // the whole per-term path (oracle, four schedulers, marker scan) run on a term from outside the corpus.
        "nested-duplicator" to "(\\w. w (w (\\a b. a b b)) (\\c. c)) (\\x y. x y y)"
    )

    private fun assertTheNamedRegressionsStillHold() {
        for ((name, source) in namedRegressions()) {
            val term = LambdaTerms.parse(source)
            require(LambdaTerms.freeVariablesOf(term).isEmpty()) { "Regression '$name' is not closed" }
            val oracle = LambdaTerms.deBruijnKey(LambdaTerms.referenceNormalOrder(term))
            for (order in ORDERS) {
                val run = LambdaTerms.normalise(term, order = order, maxSteps = TYPED_NET_BUDGET)
                val marks = marksIn(run.result)
                require(run.finished) { "Regression '$name' did not finish under $order" }
                require(marks.isEmpty()) { "Regression '$name' read back with marks $marks under $order" }
                require(LambdaTerms.deBruijnKey(run.result) == oracle) {
                    "Regression '$name' under $order: net ${LambdaTerms.deBruijnKey(run.result)} vs oracle $oracle"
                }
            }
        }
        println("  premise: ${namedRegressions().size} named regression term(s) x ${ORDERS.size} orders still agree")
    }
}
