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
 * **The second half was true, and is no longer.** Over 4_000 simply-typed terms and 300 untyped ones at the seeds
 * below — 17_200 (term, scheduler) checks — the net still disagrees with the oracle on closed terms that have normal
 * forms, 84 times. **Every one of those 84 now carries a mark.** On 2026-09-19 four did not, and the four were one
 * term under four schedulers:
 *
 * ```
 * (\m n f x. f (f x)) (\f. f) (\x. x) ((\n f x. f (n f x)) (\n f x. n f x))
 * ```
 *
 * It reduced in 26 reactions to `λa. λb. a (λc. a (λd. a (λe. a b e) d) e)` where the answer is
 * `λa. λb. a (λc. a (λd. a (λe. a b e) d) c)` — one variable occurrence wrong, and out of scope. The cause was not
 * the fan labels: `readback` kept one map of binder names for the whole walk and did not restore an entry on the way
 * out of an abstraction, so the inner copy of a shared abstraction renamed the outer one behind its back. That term
 * now agrees under all four schedulers, and [knownCases] pins both the right answer and the wrong one it used to
 * give.
 *
 * What scoping those names also bought is a *reported* closedness invariant. `binders` now holds exactly the binders
 * open on the walk's own path, so an occurrence that misses reads back as `LambdaTerms.readback`'s `?` mark instead
 * of as an ordinary name. The 24 checks that used to read a closed term back open with a bare free variable in it
 * say `?` now — [SCOPE_MARKED_CHECKS] — which is the same defect, made loud.
 *
 * The split that *did* survive is by family, and it is the most useful thing here: **no typed term disagrees**.
 * Not one of the 4_000 simply-typed terms disagrees, under any scheduler. Note the direction: that is what was
 * measured. The converse, "every counterexample is untypable", is an inference — there is no type checker here,
 * the untyped soup family is not guaranteed untypable, and only the four pinned terms have been checked by
 * inspection (all four self-apply). The two shapes that break the
 * fan bookkeeping — self-application and an abstraction that erases its own argument under duplication — are exactly
 * the shapes simple types forbid.
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

    private const val TYPED_N = 4_000
    // 300 and not 400: soup draw #289 of this seed spends 94 seconds in the reducer on its own, and the findings
    // are all at indices below 300. A seeded generator takes its first N terms, so shortening the family does not
    // reshuffle it.
    private const val SOUP_N = 300

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
    // 4_000 and not more: `Reducer` calls `Net.incrementAges` once per reaction, which is O(nodes), so a reaction
    // budget on a net that grows is a quadratic time budget. One soup draw spent 15 seconds inside it at 20_000.
    private const val SOUP_NET_BUDGET = 4_000

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
            assertTheLedger(results)
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
        // On its own thread, with a 512 MB stack.
        //
        // `LambdaTerms.readback` documents a `…` mark for a walk that runs "past maxDepth frames", and its default
        // maxDepth is 3000. A default JVM stack cannot hold 3000 frames of `walk`, so on a deep net the promised
        // mark arrives as a StackOverflowError instead — measured 2026-09-19, four of fifty soup terms. With room
        // to run, the documented budget is the thing that fires and the classification below means what it says.
        // The trap is written up in dev_docs/INTERACTION_NETS_LAMBDA.md; it is not fixed here.
        var ok = false
        val worker = Thread(null, { ok = runTests() }, "lambda-property-oracle", 512L * 1024 * 1024)
        worker.start()
        worker.join()
        check(ok) { "Lambda property oracle failed" }
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

        // And the closedness mark, in both directions too, because it is the one this branch added and an
        // undetectable detector is how the quiet class stayed quiet.
        //
        // `λ_. x` where the `x` is wired to a *second* abstraction's binder port: a well-formed net cannot contain
        // that occurrence, because the binder it names encloses nothing here. `readback` must say `?` and must not
        // invent the other abstraction's name.
        val outOfScope = net {
            val root = node(Lambda.ROOT, id = LambdaTerms.ROOT_ID, at = vec(0.0, -120.0), state = NodeState(energy = 0.6))
            val outer = node(Lambda.LAM, id = "outer", at = vec(0.0, -40.0), state = NodeState(energy = 1.2))
            val elsewhere = node(Lambda.LAM, id = "elsewhere", at = vec(120.0, 40.0), state = NodeState(energy = 1.2))
            val gone = node(Lambda.ERASE, id = "gone", at = vec(-80.0, 40.0), state = NodeState(energy = 0.4))
            val loop = node(Lambda.ERASE, id = "loop", at = vec(200.0, 40.0), state = NodeState(energy = 0.4))
            root.principal to outer.principal
            outer.aux(0) to gone.principal
            // The body of `outer` is an occurrence of a binder that is not `outer`.
            outer.aux(1) to elsewhere.aux(0)
            elsewhere.principal to loop.principal
        }
        val leaked = marksIn(LambdaTerms.readback(outOfScope))
        require("?" in leaked) {
            "An occurrence wired to a binder that is not open must read back as ?, got $leaked. Without this the " +
                "closedness invariant is unobservable and the quiet class is back."
        }
        require(freeNonMarkNames(LambdaTerms.readback(outOfScope)).isEmpty()) {
            "The ? mark must not also count as a free variable: got ${freeNonMarkNames(LambdaTerms.readback(outOfScope))}"
        }
        println("  premise: marker detector sees ⋈ on a stranded fan, ? on an out-of-scope occurrence, nothing on a clean reduction")
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
        val detail: String = "",
        /**
         * Names occurring free in the net's answer, marks excluded.
         *
         * A readback of a **closed** term must be closed. `readback` does not check this and has no mark for it, so
         * it is the one cheap detector for a wrong answer that carries no mark at all — and it is how the quiet
         * disagreements below were recognised as wrong rather than merely different.
         */
        val freeInNet: Set<String> = emptySet()
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
        return Check(
            index, order, verdict, netKey, oracleKey, marks, run.steps, run.printed,
            freeNonMarkNames(run.result)
        )
    }

    // =========================
    // Marks
    // =========================

    /** The single-glyph marks `LambdaTerms.readback` leaves where the net was not a term. One vocabulary, defined once. */
    private val MARKS = LambdaTerms.MARKS

    /**
     * Every mark in [term], which is [LambdaTerms.marksOf] and no longer a second copy of it.
     *
     * It used to be a copy, and the copy is why this harness could see the quiet class when nothing else could: the
     * library had no way to ask. Now it has one, and a detector that lives only in a test is a detector box code
     * cannot run.
     */
    fun marksIn(term: Term): Set<String> = LambdaTerms.marksOf(term)

    /**
     * Names occurring free in a readback, marks excluded — a `?`*id* is a mark and not a free variable.
     *
     * Subtracting [MARKS] as a *set* is not enough, because the marks that carry an agent id with them spell a
     * different name every time. Asking [LambdaTerms.marksOf] about one name at a time is the same question the
     * readback itself answers, so the two cannot drift.
     */
    private fun freeNonMarkNames(term: Term): Set<String> =
        LambdaTerms.freeVariablesOf(term).filterTo(HashSet()) { LambdaTerms.marksOf(Term.Var(it)).isEmpty() }

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
                    "  threw=${s.count(Verdict.THREW)}" +
                    "  not-closed=${s.checks.count { it.freeInNet.isNotEmpty() }}" +
                    "  scope-marked=${s.checks.count { "?" in it.marks }}"
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

    // (TYPED_N + SOUP_N) terms x 4 orders, which is 4_000 + 300 today. Asserted, not printed: this project's
    // recorded weakness is a println behind no ratchet, and a generator that silently produces one term would
    // otherwise pass with "0 disagreements".
    private const val CHECK_FLOOR = (TYPED_N + SOUP_N) * 4

    // How many of those checks had a live oracle to compare against. Below the floor, "0 quiet disagreements" is a
    // statement about a population that mostly diverged.
    private const val COMPARED_FLOOR = 16_000

    // Terms whose compilation builds a fan at all, and terms that build four or more — the nested duplication this
    // hunt is actually about. Measured, then floored about 20% under.
    private const val TYPED_WITH_FANS_FLOOR = 2_000
    private const val TYPED_DEEP_FANS_FLOOR = 1_200
    private const val SOUP_DEEP_FANS_FLOOR = 160

    // The ledger, re-measured 2026-09-22 at the seeds and populations above. Pinned exactly, in both directions:
    // see assertTheLedger for why a count that *falls* has to fail too.
    //
    // **The headline, and it is now zero.** On 2026-09-19 four of these 17_200 checks were a disagreement carrying
    // no readback mark of any kind — the "wrong normal form with no marker at all" that
    // dev_docs/INTERACTION_NETS_LAMBDA.md said it could not rule out. All four were one term under four schedulers,
    // and the mechanism was `readback`'s own binder-name map rather than the fan labels the page blamed. With binder
    // names scoped, that term agrees with the oracle and the class is empty.
    //
    // Zero pinned in both directions is a claim, and on its own it is a claim a broken detector would also satisfy:
    // `marksIn` returning every term's marks as "something" would drive this to zero too. SCOPE_MARKED_CHECKS below
    // is the counterfactual that rules that out.
    private const val QUIET_DISAGREEMENTS = 0
    private const val MARKED_DISAGREEMENTS = 84
    private const val THREW_CHECKS = 0
    private const val UNFINISHED_CHECKS = 24
    private const val CONFLUENCE_FAILURES = 9

    // Readbacks of a *closed* term that come back open, marks excluded. Also zero now, and for two reasons worth
    // keeping apart: four of the old 28 became *correct*, and the remaining 24 still have the defect but report it
    // as a `?` mark instead of spelling the out-of-scope occurrence as an ordinary name.
    private const val NOT_CLOSED_CHECKS = 0

    // Checks whose answer carries the `?` mark: an occurrence whose binder is not open on the walk's own path.
    //
    // This is the one to read next to NOT_CLOSED_CHECKS = 0, and it is why that zero means something. It is a
    // **counterfactual floored at a number**: 24 of 17_200 checks still contain a scope defect, and they are
    // reported rather than named. If a future change drove this to zero as well, either the defect was genuinely
    // fixed — in which case say so on the page — or the detector stopped detecting, which is how this failure class
    // stayed quiet for the three days before it was found.
    private const val SCOPE_MARKED_CHECKS = 24

    // Distinct de Bruijn keys, so a generator that emits the same term N times fails.
    private const val TYPED_DISTINCT_FLOOR = 2_000
    private const val SOUP_DISTINCT_FLOOR = 250

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
        // The quiet class shrinks against a *quiet-preserving* predicate. Shrinking it mark-agnostically walks
        // straight out of the finding — measured: the first quiet counterexample shrank into a `…`-marked one,
        // which is a different bug and not the headline.
        printFindings("QUIET DISAGREEMENT (no readback mark at all)", of(sweeps, Verdict.DISAGREE_QUIET)) { t, o ->
            quietlyDisagrees(t, o)
        }
        printFindings("MARKED DISAGREEMENT", of(sweeps, Verdict.DISAGREE_MARKED)) { t, o -> disagrees(t, o) }
        printFindings("THREW", of(sweeps, Verdict.THREW)) { t, o -> disagrees(t, o) }
    }

    /**
     * The ledger: the counts this hunt measured, pinned exactly and in **both** directions.
     *
     * Every number here is a characterization and not a correctness claim, because the correctness claim is false —
     * the net disagrees with the oracle on closed terms that have normal forms, and four of those disagreements
     * carry no readback mark at all. Pinning in both directions is deliberate: a count that *falls* is a fix nobody
     * wrote down, and the page quoting these numbers would silently become wrong. A count that rises is a
     * regression. Either way a person has to read this file and then the page.
     */
    private fun assertTheLedger(sweeps: List<Sweep>) {
        val threw = of(sweeps, Verdict.THREW).size
        val quiet = of(sweeps, Verdict.DISAGREE_QUIET).size
        val marked = of(sweeps, Verdict.DISAGREE_MARKED).size
        val unfinished = of(sweeps, Verdict.NET_UNFINISHED).size
        val confluence = sweeps.sumOf { it.confluenceFailures.size }
        val notClosed = sweeps.sumOf { s -> s.checks.count { it.freeInNet.isNotEmpty() } }
        val quietAndNotClosed = sweeps.sumOf { s ->
            s.checks.count { it.verdict == Verdict.DISAGREE_QUIET && it.freeInNet.isNotEmpty() }
        }
        val scopeMarked = sweeps.sumOf { s -> s.checks.count { "?" in it.marks } }
        println(
            "  ledger: not-closed=$notClosed (pinned $NOT_CLOSED_CHECKS), of which quiet=$quietAndNotClosed; " +
                "scope-marked=$scopeMarked (pinned $SCOPE_MARKED_CHECKS)"
        )
        println(
            "  ledger: quiet=$quiet (pinned $QUIET_DISAGREEMENTS, THE HEADLINE)  marked=$marked (pinned $MARKED_DISAGREEMENTS)  " +
                "threw=$threw (pinned $THREW_CHECKS)  net-unfinished=$unfinished (pinned $UNFINISHED_CHECKS)  " +
                "confluence-failures=$confluence terms (pinned $CONFLUENCE_FAILURES)"
        )

        // Collected rather than thrown one at a time. Six counts over a three-minute sweep, and a fail-fast ledger
        // reports the first of them — which costs a run per number when several have moved together, as they do
        // whenever a defect is actually fixed.
        val moved = ArrayList<String>()
        if (quiet != QUIET_DISAGREEMENTS) {
            moved += "Disagreements carrying NO readback marker moved from $QUIET_DISAGREEMENTS to $quiet. This is " +
                "the count dev_docs/INTERACTION_NETS_LAMBDA.md's 'What to be careful of' section quotes. If it " +
                "fell, something was fixed and the page has to say so; if it rose, something got worse. Either way " +
                "a person reads this line. Counterexamples, shrunk, are printed above."
        }
        if (marked != MARKED_DISAGREEMENTS) {
            moved += "Marked disagreements moved from $MARKED_DISAGREEMENTS to $marked over this population. If " +
                "that is a fix, update the count here, the count in dev_docs/INTERACTION_NETS_LAMBDA.md and the " +
                "claim in public/docs/interaction_nets_examples.md together."
        }
        if (threw != THREW_CHECKS) moved += "Checks that threw moved from $THREW_CHECKS to $threw"
        if (notClosed != NOT_CLOSED_CHECKS) {
            moved += "Readbacks of a closed term that are not closed moved from $NOT_CLOSED_CHECKS to $notClosed"
        }
        if (scopeMarked != SCOPE_MARKED_CHECKS) {
            moved += "Checks carrying the ? mark moved from $SCOPE_MARKED_CHECKS to $scopeMarked. Read this one " +
                "against not-closed=$notClosed: a zero there with a zero here is a detector that stopped " +
                "detecting, not a reducer that stopped leaking."
        }
        if (unfinished != UNFINISHED_CHECKS) {
            moved += "Checks where the net ran out of reactions moved from $UNFINISHED_CHECKS to $unfinished"
        }
        if (confluence != CONFLUENCE_FAILURES) {
            moved += "Terms on which the four schedulers disagree moved from $CONFLUENCE_FAILURES to $confluence. " +
                "Confluence holds on the 25-term corpus; it does not hold in general, and this is the count."
        }
        require(moved.isEmpty()) {
            "The ledger moved, ${moved.size} count(s):\n" + moved.joinToString("\n") { "    - $it" }
        }
    }

    private fun printFindings(
        headline: String,
        found: List<Pair<Sweep, Check>>,
        stillFails: (Term, ActivationOrder) -> Boolean
    ) {
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
            val small = shrink(term) { candidate -> stillFails(candidate, c.order) }
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
        println("      free in net answer: ${freeNonMarkNames(safeNet(term, order))}")
    }

    private fun safeNet(term: Term, order: ActivationOrder): Term = try {
        LambdaTerms.normalise(term, order = order, maxSteps = SOUP_NET_BUDGET).result
    } catch (t: Throwable) {
        Term.Var("·")
    }


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

    /**
     * Quiet-preserving: still a disagreement, still with no readback mark.
     *
     * Note what is deliberately *not* here — closedness. A shrunk quiet counterexample is allowed to stop leaking a
     * free variable, because the claim under test is about the readback's marks and not about this harness's own
     * extra detector.
     */
    private fun quietlyDisagrees(term: Term, order: ActivationOrder): Boolean {
        val oracle = try {
            LambdaTerms.deBruijnKey(
                LambdaTerms.referenceNormalOrder(term, maxSteps = SOUP_ORACLE_BUDGET, maxWorkNodes = SOUP_ORACLE_WORK)
            )
        } catch (t: Throwable) {
            return false
        }
        return try {
            val run = LambdaTerms.normalise(term, order = order, maxSteps = SOUP_NET_BUDGET)
            run.finished &&
                marksIn(run.result).isEmpty() &&
                LambdaTerms.deBruijnKey(run.result) != oracle
        } catch (t: Throwable) {
            false
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
     * Terms this hunt found and shrank, pinned by name — both the ones the net still gets wrong and the ones it now
     * gets right.
     *
     * A case marked [Outcome.DISAGREES] is a **closed term with a normal form on which the net is wrong**, so it is
     * a characterization test and not a correctness test: it asserts the answer the net gives *today*, so that a fix
     * trips this test and forces `dev_docs/INTERACTION_NETS_LAMBDA.md` to be rewritten rather than quietly becoming
     * out of date. Pinning the wrong answer is the only way to notice it changing.
     *
     * A case marked [Outcome.AGREES] is one that *was* pinned wrong and has been fixed, and it stays here rather
     * than moving to `stdlib.lambda-terms.smoke` for one reason: [KnownCase.wasBefore] keeps the wrong answer it
     * used to give, next to the right one it gives now. A fix whose old behaviour is not written down anywhere is a
     * fix nobody can tell has come undone.
     *
     * The oracle side of each is asserted too, and it is plain arithmetic: `(λx. λz. x z x) (λx. λy. y y)` really
     * does reduce to `λz. λy. y y`, by hand, in four β-steps.
     */
    private enum class Outcome { AGREES, DISAGREES }

    private class KnownCase(
        val name: String,
        val source: String,
        /** Whether the net gets this right today. */
        val outcome: Outcome,
        /** The right answer, as `format` prints it. */
        val oracle: String,
        /** The answer the net gives today, as `Run.printed` gives it — pinned so that it cannot drift. */
        val netToday: String,
        /** The readback marks in that answer. Empty on an [Outcome.AGREES] case, and empty here on a
         * [Outcome.DISAGREES] one would be the "quiet wrong answer" class this branch set out to close. */
        val marks: Set<String>,
        val note: String,
        /**
         * For an [Outcome.AGREES] case: the exact wrong answer this term gave before the fix.
         *
         * Asserted as a counterfactual, not decoration — the net must no longer print this string. A test that only
         * checked "agrees with the oracle" would keep passing if the fix were reverted *and* the oracle broke the
         * same way, which is exactly the shape of failure the oracle exists to rule out.
         */
        val wasBefore: String = ""
    )

    private fun knownCases(): List<KnownCase> = listOf(
        // ** The one the page said it could not rule out, and it is FIXED. ** Shrunk from soup #156 of seed
        // 20260919002; 26 reactions, both sides finished, and no mark anywhere in the answer.
        //
        // The net's answer differed from the right one in exactly ONE variable occurrence: correct is
        //   λa. λb. a (λc. a (λd. a (λe. a b e) d) c)
        // and the net put `e` in the last position where `c` belongs. The diagnosis was not the fan labels the page
        // blamed. It was `readback`'s own `binders` map: one map for the whole walk, written on the way *in* to an
        // abstraction and never put back on the way out. A shared abstraction is legitimately walked once per copy,
        // each copy taking a fresh name, so the inner copy's entry outlived it and every occurrence read afterwards
        // resolved to the inner binder — the `λe` three levels down, whose body had already closed. Restoring the
        // shadowed entry, the way `compile` already did with its own scope map, is the fix.
        KnownCase(
            name = "quiet-wrong-branch",
            source = "(\\m n f x. f (f x)) (\\f. f) (\\x. x) ((\\n f x. f (n f x)) (\\n f x. n f x))",
            outcome = Outcome.AGREES,
            oracle = "λx. λx'. x (λx'1. x (λx''. x (λx''. x x' x'') x'') x'1)",
            netToday = "λa. λb. a (λc. a (λd. a (λe. a b e) d) c)",
            marks = emptySet(),
            wasBefore = "λa. λb. a (λc. a (λd. a (λe. a b e) d) e)",
            note = "was the quiet class's only term: a stale binder name from `readback`, not a fan-label defect"
        ),
        // Shrunk from soup #22 of seed 20260919002. Twelve nodes, four β-steps, no budget anywhere near it. An
        // abstraction that *erases* its argument is duplicated by a fan; one of the two copies of the answer comes
        // back as the ERASE itself. This is the erase side of the bookkeeping, not the fan-label side.
        KnownCase(
            name = "dup-of-an-erasing-abstraction",
            source = "(\\x z. x z x) (\\x y. y y)",
            outcome = Outcome.DISAGREES,
            oracle = "λz. λy. y y",
            netToday = "λa. λb. b ⊥",
            marks = setOf("⊥"),
            note = "a duplicated abstraction that erases its argument loses one copy to the ERASE — loud, with a ⊥"
        ),
        // Shrunk from soup #4 and #19. `two two` is Church four and the net reaches a normal form in 19 reactions,
        // but the readback cannot walk it: the fan stack never closes, so the walk runs past its node budget.
        KnownCase(
            name = "two-applied-to-two-unwalkable",
            source = "(\\x y. y y) (\\x. x) (\\f x. f (f x))",
            outcome = Outcome.DISAGREES,
            oracle = "λx. λx'. x (x (x (x x')))",
            netToday = "λa. λb. …",
            marks = setOf("…"),
            note = "net reports finished in 19 reactions; readback exhausts its budget rather than closing the fans"
        ),
        // Shrunk from soup #48. The net's answer is not merely wrong, it is **not closed** — and since binder names
        // are now scoped, what used to be a bare free name is the `?` mark instead. Same defect, reported.
        KnownCase(
            name = "compose-self-apply-scope-leak",
            source = "(\\m n f. m (n f)) (\\f. f f) (\\n f. f (n f))",
            outcome = Outcome.DISAGREES,
            oracle = "λf. f (λf'. f' (f f')) (f (f (λf'. f' (f f'))))",
            netToday = "",
            marks = setOf("…", "?"),
            note = "an occurrence outside its binder's body; reported as ? rather than silently named, and the " +
                "printed form is thousands of characters so only the mark set is pinned"
        )
    )

    /**
     * The set of names [knownCases] must contain, pinned separately from the list itself.
     *
     * A **bidirectional ledger**: set equality, so deleting a case fails as loudly as changing one. A list asserted
     * only by "every entry still behaves as pinned" is vacuously satisfied by the empty list, and a counterexample
     * quietly deleted is exactly how a closed failure class reopens.
     */
    private val NAMED_CASES = setOf(
        "quiet-wrong-branch",
        "dup-of-an-erasing-abstraction",
        "two-applied-to-two-unwalkable",
        "compose-self-apply-scope-leak"
    )

    /**
     * The named cases, re-run under all four schedulers.
     *
     * Asserted as a *premise*, before anything is generated, for the same reason the marker detector is: if these
     * silently changed behaviour, every count below would be describing a different reducer.
     *
     * Every mismatch is collected and reported together rather than thrown at the first one. Four cases under four
     * schedulers is sixteen facts, and a fail-fast premise tells you one of them — which, on the night this section
     * was rewritten, meant three runs of a three-minute test to learn what one run could have said.
     */
    private fun assertTheNamedRegressionsStillHold() {
        val cases = knownCases()
        require(cases.map { it.name }.toSet() == NAMED_CASES) {
            "The named-case ledger moved: ${cases.map { it.name }.toSet()} against a pinned ${NAMED_CASES}. " +
                "Adding a case means adding its name here; removing one means saying why on " +
                "dev_docs/INTERACTION_NETS_LAMBDA.md."
        }
        require(cases.size == NAMED_CASES.size) { "Duplicate name in the ledger: ${cases.map { it.name }}" }

        val problems = ArrayList<String>()
        for (k in cases) {
            val term = LambdaTerms.parse(k.source)
            require(LambdaTerms.freeVariablesOf(term).isEmpty()) { "Named case '${k.name}' is not closed" }
            val oracle = LambdaTerms.format(
                LambdaTerms.referenceNormalOrder(term, maxSteps = SOUP_ORACLE_BUDGET, maxWorkNodes = SOUP_ORACLE_WORK)
            )
            if (oracle != k.oracle) problems += "'${k.name}': oracle moved to $oracle, pinned ${k.oracle}"
            val oracleKey = LambdaTerms.deBruijnKey(LambdaTerms.parse(k.oracle))
            for (order in ORDERS) {
                val run = LambdaTerms.normalise(term, order = order, maxSteps = SOUP_NET_BUDGET)
                val agrees = LambdaTerms.deBruijnKey(run.result) == oracleKey
                when (k.outcome) {
                    Outcome.AGREES -> {
                        if (!agrees) {
                            problems += "'${k.name}' under $order was FIXED and has regressed: " +
                                "net ${clip(run.printed)}, oracle ${clip(k.oracle)}"
                        }
                        // The counterfactual, floored at the exact old string rather than at "differs": the answer
                        // this term used to give must not come back.
                        if (k.wasBefore.isNotEmpty() && run.printed == k.wasBefore) {
                            problems += "'${k.name}' under $order is printing its pre-fix answer again: ${k.wasBefore}"
                        }
                        // The right answer is pinned as a string too, not only as a de Bruijn key: a readback picks
                        // its own binder names and the names are part of what a reader checks by eye.
                        if (k.netToday.isNotEmpty() && run.printed != k.netToday) {
                            problems += "'${k.name}' under $order: net prints ${clip(run.printed)}, " +
                                "pinned ${clip(k.netToday)}"
                        }
                        val marks = marksIn(run.result)
                        if (marks.isNotEmpty()) problems += "'${k.name}' under $order now carries marks $marks"
                        val free = freeNonMarkNames(run.result)
                        if (free.isNotEmpty()) {
                            problems += "'${k.name}' under $order reads a closed term back open: free $free"
                        }
                    }
                    Outcome.DISAGREES -> {
                        if (agrees) {
                            problems += "'${k.name}' under $order now AGREES with the oracle. That is good news: " +
                                "flip its outcome to Outcome.AGREES, move its old answer into wasBefore, and rewrite " +
                                "the 'What to be careful of' section of dev_docs/INTERACTION_NETS_LAMBDA.md."
                        }
                        if (k.netToday.isNotEmpty()) {
                            if (run.printed != k.netToday) {
                                problems += "'${k.name}' under $order: net now prints ${clip(run.printed)}, " +
                                    "pinned ${clip(k.netToday)}"
                            }
                            if (marksIn(run.result) != k.marks) {
                                problems += "'${k.name}' under $order: marks ${marksIn(run.result)}, pinned ${k.marks}"
                            }
                        } else {
                            // A case whose printed form is thousands of characters: the finding is the mark set, so
                            // that is what is pinned.
                            if (marksIn(run.result) != k.marks) {
                                problems += "'${k.name}' under $order: marks ${marksIn(run.result)}, pinned ${k.marks}"
                            }
                        }
                    }
                }
            }
            val verb = if (k.outcome == Outcome.AGREES) "agrees with the oracle" else "still disagrees"
            println("  premise: '${k.name}' $verb under all ${ORDERS.size} orders — ${k.note}")
        }
        require(problems.isEmpty()) {
            "The named-case ledger moved, ${problems.size} fact(s):\n" + problems.joinToString("\n") { "    - $it" }
        }
    }
}
