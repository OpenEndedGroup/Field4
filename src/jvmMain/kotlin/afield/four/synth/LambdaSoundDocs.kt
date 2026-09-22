@file:afield.CompletionCategory("Patterns")
package afield.four.synth

import afield.four.audio.WavFloatArrayWriter
import afield.stdlib.ActivationOrder
import afield.stdlib.Lambda
import afield.stdlib.LambdaTerms
import afield.stdlib.Net
import afield.stdlib.Reducer
import afield.stdlib.Term
import java.io.File
import kotlin.math.abs

/*
FIELD_TEST
id: synth.lambda.sound
runner: jvm-main
entrypoint: afield.four.synth.LambdaSoundDocs
tags: [smoke, synth, pattern, lambda, interaction-nets, audio, wav, capture, cpu]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "lambda sound docs rendered"
*/

/**
 * A λ-term's *reduction* as a rhythm — the schedule, not the answer.
 *
 * The route is four steps in four parts of Field that do not know about each other.
 * `afield.stdlib.lambdaterms` parses and compiles a term to an interaction net,
 * `afield.stdlib.interactionnets` reduces that net one reaction at a time, this file records each
 * reaction as a [Beat], and [patCondense], the mini-notation parser run backwards, finds the
 * shortest notation that plays the resulting step mask. [patRenderStereo] plays it.
 *
 * # What this measures
 *
 * `stdlib.lambda-terms.property` established on 2026-09-19 that the **answer** does not depend on
 * the scheduler, over 17 200 (term, scheduler) checks. It says nothing about the **route** taken to
 * reach it, which is what a trace records. So the measurement is the pair:
 *
 *  * the normal form, which is the same under every [ActivationOrder] the harness runs, and
 *  * the step count, the rule sequence, the β mask and its condensation, which are not.
 *
 * [patCondense] earns its place for the same reason it does in [CaSoundDocs]: it is a
 * **measurement**. A schedule whose β-steps fall into a figure gets a short spelling; one that
 * scatters them gets a long one. [Trace.betaCompression] is therefore a number about a *scheduler*
 * rather than about a term.
 *
 * # The oracles
 *
 * Every trace is checked against something outside the trace before it is allowed to make a sound:
 *
 *  * [Trace.result] is asserted α-equivalent to [LambdaTerms.referenceNormalOrder], the plain
 *    capture-avoiding evaluator, and equal across every order in [ORDERS].
 *  * every [Beat] carries at least one event, and every event kind is one of the six
 *    [Lambda.rules] declares — so [EVENT_KINDS] is checked against the rule book rather than
 *    trusted.
 *  * the β count is asserted **constant across schedulers** while the total is not: see
 *    [betaIsScheduleInvariant].
 *
 * # What it does not do
 *
 * No realtime engine, no audio device, no GPU, no Field window, and no layout:
 * `relaxIterations = 0` throughout, because the force-directed layout is `O(n²)` per iteration and
 * a reduction nobody is watching should not pay for a picture. The renders are deterministic, so a
 * committed `.wav` regenerates byte-stably. As the `synth.lambda.sound` FIELD_TEST every scene
 * renders to a temp dir and nothing committed is written; pass an output directory as `args[0]` to
 * regenerate the page's `.wav`s.
 */

/**
 * Reduces each term in [TERMS] under every order in [ORDERS], checks the traces against the
 * oracles, and renders them as `.wav`.
 *
 * [trace] and [traceAllOrders] are the entry points for a single term; [corpusSweep] runs all of
 * `LambdaTerms.corpus()`.
 */
object LambdaSoundDocs {

    /** Shared with [PatSoundDocs], because every docs gallery writes the same kind of file. */
    const val SR: Int = PatSoundDocs.SR

    /**
     * The schedulers this page compares — the same four as `stdlib.lambda-terms.smoke`.
     *
     * [ActivationOrder.RANDOM] is excluded because it is not reproducible and a docs figure has to
     * be, and [ActivationOrder.LOWEST_PAIR_ENERGY] because on this vocabulary it is a run-count
     * rather than a schedule.
     */
    val ORDERS: List<ActivationOrder> = listOf(
        ActivationOrder.FIRST_FOUND,
        ActivationOrder.LEFTMOST_BY_ID,
        ActivationOrder.RIGHTMOST_BY_ID,
        ActivationOrder.HIGHEST_PAIR_ENERGY,
    )

    /**
     * The whole event vocabulary [Lambda.rules] declares, in the order this file maps to pitch.
     *
     * Not copied from the KDoc and hoped for: [assertTheVocabularyIsWhatItSays] reduces every term
     * in [LambdaTerms.corpus] under every order and asserts the kinds it saw against this list,
     * both ways round — see [KINDS_UNREACHABLE_ON_CLOSED_TERMS] for the half that is never reached.
     */
    val EVENT_KINDS: List<String> = listOf("beta", "duplicate", "branch", "prune", "erase", "annihilate")

    /**
     * The two kinds no closed term can ever produce, and the reason.
     *
     * `duplicate` is emitted only by `dup-var`, `dup-const` and `dup-root`, and `erase` only by
     * `erase-var`, `erase-const` and `erase-root`. All six need a nullary atom — a free `VAR`, a
     * `CONST`, or the `ROOT` — to be the *non*-principal side of a meeting, and a closed term
     * compiled by [LambdaTerms.compile] has no free `VAR`, no `CONST` at all, and exactly one
     * `ROOT` whose principal port faces the answer rather than a fan or an eraser.
     *
     * So two of the six musical degrees are silent on every term in the corpus. Named here rather
     * than discovered by a reader who counts the pitches in the figure, and asserted in
     * [assertTheVocabularyIsWhatItSays] so that a rule-book change that reaches them fails a test
     * rather than quietly widening the tune.
     */
    val KINDS_UNREACHABLE_ON_CLOSED_TERMS: Set<String> = setOf("duplicate", "erase")

    /** MIDI note per [EVENT_KINDS] index: a minor pentatonic off A2, so the rule sequence is a tune. */
    val KIND_NOTES: List<Int> = listOf(45, 48, 50, 52, 55, 57)

    /** The MIDI note a rewrite of this kind plays, or the root for a kind the vocabulary does not have. */
    fun noteOf(kind: String): Int = KIND_NOTES[EVENT_KINDS.indexOf(kind).coerceAtLeast(0)]

    /**
     * The named terms this page reduces, as source text, taken from [LambdaTerms.corpus] **by name**
     * so that the page and the front end's own corpus cannot drift apart.
     *
     * Four shapes, chosen for what their schedules do rather than for what they compute: a pure
     * arithmetic term with plenty of sharing, one where reduction copies a redex, one that erases a
     * whole computation, and the exponentiation that is the corpus's longest reduction.
     */
    val TERMS: List<String> = listOf(
        "church-exp-two-cubed",
        "church-pred-three",
        "erase-argument",
        "church-mul-3-3",
    )

    /** The corpus entry [name] resolves to. */
    fun sourceOf(name: String): String =
        LambdaTerms.corpus()[name] ?: error("no corpus term named '$name'; known: ${LambdaTerms.corpus().keys}")

    // ── the trace ──────────────────────────────────────────────────────────────────────────

    /**
     * One reaction, as the recorder saw it — the unit of the time series this page plots and plays.
     *
     * [activePairs] is counted **after** the reaction and includes pairs no rule covers, which is
     * why [reduciblePairs] is a separate number rather than the same one: a normal form still has
     * an active pair in it (see [Trace.trailingActivePairs]).
     */
    class Beat(
        /** 1-based reaction index. */
        val index: Int,
        /** The [afield.stdlib.RewriteRule] name that fired. */
        val rule: String,
        /** Every kind this reaction emitted, in emission order. Never empty — asserted, not assumed. */
        val kinds: List<String>,
        /** Agents standing after the reaction. */
        val nodes: Int,
        /** Principal-to-principal pairs after the reaction, whether or not a rule covers them. */
        val activePairs: Int,
        /** Of those, the ones [afield.stdlib.RuleBook.findMatch] has a rule for. */
        val reduciblePairs: Int,
    ) {
        /** The first emitted kind, which is the one the melody plays. */
        val kind: String get() = kinds.first()
    }

    /**
     * A whole reduction, recorded: the term, the schedule, every [Beat], and the answer read back.
     *
     * This is a class rather than a `List<Beat>` because the interesting quantities are derived
     * from the sequence — the β mask, its condensation, the rule tally — and a page that recomputed
     * them would be a second implementation to keep honest.
     */
    class Trace(
        /** The corpus name this came from. */
        val name: String,
        /** The source text reduced. */
        val source: String,
        /** The scheduler used. */
        val order: ActivationOrder,
        /** The parsed term. */
        val term: Term,
        /** [LambdaTerms.readback] of the reduced net. Only ever α-equivalent to the normal form. */
        val result: Term,
        /** Every reaction, in order. */
        val beats: List<Beat>,
        /** Fans [LambdaTerms.compile] built — the term's sharing structure as one number. */
        val fans: Int,
        /** Agents in the compiled net, before anything fired. */
        val initialNodes: Int,
        /** The reduced net, for a node count or a figure. */
        val net: Net,
        /** The reaction budget this run was given, for telling "normal form" from "gave up". */
        val budget: Int,
    ) {
        /** Reactions fired. */
        val steps: Int get() = beats.size

        /** False when the run hit its budget, so [result] is a term mid-flight. */
        val finished: Boolean get() = steps < budget

        /** [result] as source text. */
        val printed: String get() = LambdaTerms.format(result)

        /** Agents left standing. */
        val remainingNodes: Int get() = net.nodes.size

        /**
         * Active pairs in the *final* net.
         *
         * Not zero, and that is the point: `ROOT` faces the normal form principal to principal and
         * no rule covers `ROOT ⟂ LAM`. A normal form here is "no rule matches", not "nothing is
         * touching".
         */
        val trailingActivePairs: Int get() = net.activePairs().size

        /** One slot per reaction, true where the reaction was a β. The bar this page condenses. */
        val betaMask: List<Boolean> get() = beats.map { it.rule == "beta-linear" }

        /** How many reactions each rule accounted for, commonest first. */
        val ruleTally: Map<String, Int>
            get() = beats.groupingBy { it.rule }.eachCount().entries
                .sortedByDescending { it.value }.associate { it.key to it.value }

        /** Agents standing after each reaction — the net's size as a time series. */
        val nodeSeries: List<Double> get() = beats.map { it.nodes.toDouble() }

        /** Reducible pairs after each reaction — how much choice the scheduler had, step by step. */
        val choiceSeries: List<Double> get() = beats.map { it.reduciblePairs.toDouble() }

        /** β reactions. Constant across [ORDERS] for every term measured; see [betaIsScheduleInvariant]. */
        val betas: Int get() = betaMask.count { it }

        /** The shortest mini-notation that plays [betaMask], with `x` as the atom throughout. */
        val betaCondensation: PatCondensation get() = patCondense(betaMask, "x")

        /** [betaCondensation]'s length over the flat spelling's. Below one means the schedule had a figure in it. */
        val betaCompression: Double get() = betaCondensation.compression

        /** The rule sequence as MIDI notes, one per reaction — the melody. */
        val melody: List<Int> get() = beats.map { noteOf(it.kind) }

        override fun toString(): String =
            "$name/${order.name}: $steps reactions, $betas beta -> $printed"
    }

    /**
     * Compile [source], reduce it under [order], and record every reaction.
     *
     * `relaxIterations = 0`: this is a measurement, not a picture. The [Reducer] is built fresh per
     * call because it carries no state worth sharing and a [Lambda.rules] book is cheap.
     */
    fun trace(name: String, order: ActivationOrder = ActivationOrder.FIRST_FOUND, maxSteps: Int = 20_000): Trace =
        traceSource(name, sourceOf(name), order, maxSteps)

    /** [trace], on source text that need not be in [LambdaTerms.corpus] — what [CONFLUENCE_PROBES] uses. */
    fun traceSource(
        name: String,
        source: String,
        order: ActivationOrder = ActivationOrder.FIRST_FOUND,
        maxSteps: Int = 20_000,
    ): Trace {
        val term = LambdaTerms.parse(source)
        val compiled = LambdaTerms.compile(term)
        val book = Lambda.rules()
        val reducer = Reducer(book, order = order, relaxIterations = 0)
        val initialNodes = compiled.net.nodes.size
        val beats = ArrayList<Beat>()
        reducer.reduceDetailed(compiled.net, maxSteps = maxSteps) { index, net, step ->
            val pairs = net.activePairs()
            beats += Beat(
                index = index,
                rule = step.ruleName,
                kinds = step.events.map { it.kind },
                nodes = net.nodes.size,
                activePairs = pairs.size,
                reduciblePairs = pairs.count { book.findMatch(it.left, it.right) != null },
            )
        }
        return Trace(
            name = name,
            source = source,
            order = order,
            term = term,
            result = LambdaTerms.readback(compiled),
            beats = beats,
            fans = compiled.fans,
            initialNodes = initialNodes,
            net = compiled.net,
            budget = maxSteps,
        )
    }

    /** [trace] under every entry of [ORDERS], keyed by order. */
    fun traceAllOrders(name: String): Map<ActivationOrder, Trace> =
        ORDERS.associateWith { trace(name, it) }

    // ── the oracles ────────────────────────────────────────────────────────────────────────

    /**
     * The answer is the same under every scheduler, and it is the one a plain evaluator gets.
     *
     * Two claims in one, and the second is what stops the first being a tautology: four traces that
     * all agreed on the *wrong* term would pass the first check on its own.
     *
     * @return the printed normal form, which is then the same for every order
     */
    fun answerIsScheduleInvariant(traces: Map<ActivationOrder, Trace>): String {
        val any = traces.values.first()
        val oracle = LambdaTerms.referenceNormalOrder(any.term)
        traces.forEach { (order, t) ->
            check(t.finished) { "${t.name}/${order.name} hit its budget of ${t.budget} reactions" }
            check(LambdaTerms.alphaEquivalent(t.result, oracle)) {
                "${t.name}/${order.name} read back as ${t.printed}, and the normal-order " +
                    "evaluator says ${LambdaTerms.format(oracle)}"
            }
        }
        val keys = traces.values.map { LambdaTerms.deBruijnKey(it.result) }.distinct()
        check(keys.size == 1) { "${any.name}: the four schedulers disagree: $keys" }
        return LambdaTerms.format(oracle)
    }

    /**
     * The β count is the same under every scheduler even though the total reaction count is not.
     *
     * The page's central claim, so it is asserted rather than observed. A β reaction consumes an
     * `APP` and a `LAM` and creates neither, and no other rule creates either, so the number of
     * them a term can afford is fixed when it is compiled — while the fan and eraser bookkeeping
     * around them is not, because `dup-app` and `dup-lam` build fans whose later meetings depend on
     * the order they were built in.
     *
     * @return the invariant β count
     */
    fun betaIsScheduleInvariant(traces: Map<ActivationOrder, Trace>): Int {
        val betas = traces.values.map { it.betas }.distinct()
        check(betas.size == 1) {
            "${traces.values.first().name}: beta counts differ across schedulers: " +
                traces.entries.joinToString { "${it.key.name}=${it.value.betas}" }
        }
        return betas.single()
    }

    /**
     * [EVENT_KINDS] is the rule book's vocabulary, checked against the rule book.
     *
     * Both directions. Every kind seen must be in the list, or the melody has a note it cannot
     * name; and every kind in the list must be seen somewhere in [TERMS], or the list is carrying a
     * kind this page never plays and the pitch mapping is wider than the data.
     */
    fun assertTheVocabularyIsWhatItSays(traces: List<Trace>): Set<String> {
        val seen = LinkedHashSet<String>()
        traces.forEach { t ->
            t.beats.forEach { beat ->
                check(beat.kinds.isNotEmpty()) {
                    "${t.name}/${t.order.name} reaction ${beat.index} (${beat.rule}) emitted nothing, " +
                        "and Lambda.rules' KDoc says every rule emits an event"
                }
                beat.kinds.forEach { kind ->
                    check(kind in EVENT_KINDS) { "unknown event kind '$kind' from rule ${beat.rule}" }
                    seen += kind
                }
            }
        }
        check(seen == EVENT_KINDS.toSet() - KINDS_UNREACHABLE_ON_CLOSED_TERMS) {
            "the reachable half of the vocabulary changed: saw $seen, expected " +
                "${EVENT_KINDS.toSet() - KINDS_UNREACHABLE_ON_CLOSED_TERMS}. If a closed term now reaches " +
                "a `duplicate` or an `erase`, KINDS_UNREACHABLE_ON_CLOSED_TERMS' reasoning is wrong"
        }
        return seen
    }

    /**
     * Every corpus term under every scheduler, with the reaction count and the rule multiset that
     * came out — the sweep behind this page's central claim.
     *
     * A map of corpus name to the four traces, so a caller can ask its own question of it. The two
     * questions this page asks are [stepCountIsScheduleInvariant] and [ruleMultisetIsScheduleInvariant].
     */
    fun corpusSweep(): Map<String, Map<ActivationOrder, Trace>> =
        LambdaTerms.corpus().keys.associateWith { traceAllOrders(it) }

    /**
     * The reaction *count* is the same under every scheduler, for every term in the corpus.
     *
     * This is the measurement the page is for, and it contradicts a sentence that was published on
     * `interaction_nets_examples.md` until 2026-09-20: "`Run.steps` is a property of this rule book
     * and this schedule, not of the term." The rule-book half is right; the schedule half is not.
     *
     * The reason is older than this repository. Interaction nets are **strongly confluent** — any
     * two distinct reactions available at once are on disjoint pairs of nodes, so firing one cannot
     * destroy the other, only move it — and a strongly confluent system that terminates reaches its
     * normal form in the same number of steps down every path. What the scheduler chooses is the
     * *order*, and nothing else.
     *
     * @return term name to the single reaction count every order produced for it
     * @throws IllegalStateException naming the term and the counts, if any term breaks it
     */
    fun stepCountIsScheduleInvariant(sweep: Map<String, Map<ActivationOrder, Trace>>): Map<String, Int> =
        sweep.mapValues { (name, traces) ->
            val counts = traces.values.map { it.steps }.distinct()
            check(counts.size == 1) {
                "$name is reduced in different numbers of reactions by different schedulers: " +
                    traces.entries.joinToString { "${it.key.name}=${it.value.steps}" } +
                    " — which would mean this rule book is not strongly confluent on it"
            }
            counts.single()
        }

    /**
     * Stronger than [stepCountIsScheduleInvariant], and also true: the *multiset of rules* is the
     * same under every scheduler, not merely its size.
     *
     * Worth separating out, because equal totals with different mixes would be a coincidence and
     * this is not one. Every schedule fires the same reactions; it fires them in a different order.
     * That is exactly the room the page's rhythms live in.
     */
    fun ruleMultisetIsScheduleInvariant(sweep: Map<String, Map<ActivationOrder, Trace>>): Int {
        var checked = 0
        sweep.forEach { (name, traces) ->
            val tallies = traces.values.map { it.ruleTally.toSortedMap() }.distinct()
            check(tallies.size == 1) {
                "$name fires different rules under different schedulers: " +
                    traces.entries.joinToString("\n") { "  ${it.key.name}=${it.value.ruleTally}" }
            }
            checked++
        }
        return checked
    }

    /**
     * The terms whose β mask is spelled differently by different schedulers, and how many distinct
     * spellings each has.
     *
     * The complement of [stepCountIsScheduleInvariant]: same reactions, same count, different
     * *sequence*, so [patCondense] returns a different string. Non-empty is the page's subject.
     */
    fun spellingsPerTerm(sweep: Map<String, Map<ActivationOrder, Trace>>): Map<String, Int> =
        sweep.mapValues { (_, traces) -> traces.values.map { it.betaCondensation.notation }.distinct().size }

    /**
     * The four terms `stdlib.lambda-terms.property` pins by name, verbatim.
     *
     * The corpus is 25 terms the front end is known to be *right* on, so a step-count invariance
     * measured only there is a claim about a friendly population. These four are the heaviest
     * sharing the harness has found, and three of them are still answered wrongly, so they are
     * where the invariance has the best chance of breaking. Running them is what stops
     * [stepCountIsScheduleInvariant] being a measurement of the corpus's own good manners.
     *
     * Copied rather than imported because `LambdaPropertyOracleTest.knownCases` is private and the
     * two files answer different questions of the same terms; [DISAGREEING_PROBES] and
     * [assertTheProbesAreStillCounterexamples] re-derive which of them disagree here, so the copy
     * cannot go stale in the direction that matters.
     */
    val CONFLUENCE_PROBES: Map<String, String> = linkedMapOf(
        "quiet-wrong-branch" to
            "(\\m n f x. f (f x)) (\\f. f) (\\x. x) ((\\n f x. f (n f x)) (\\n f x. n f x))",
        "dup-of-an-erasing-abstraction" to "(\\x z. x z x) (\\x y. y y)",
        "two-applied-to-two-unwalkable" to "(\\x y. y y) (\\x. x) (\\f x. f (f x))",
        "compose-self-apply-scope-leak" to "(\\m n f. m (n f)) (\\f. f f) (\\n f. f (n f))",
    )

    /**
     * Which of [CONFLUENCE_PROBES] disagree with the normal-order evaluator — three of the four.
     *
     * `quiet-wrong-branch` was the fourth until 2026-09-22, when the cause of its wrong answer turned
     * out to be a stale binder name in `LambdaTerms.readback` rather than the fan labelling; see
     * `dev_docs/INTERACTION_NETS_LAMBDA.md`. It stays in the probe set, because what makes it a stress
     * case for step-count invariance is its *sharing*, and that has not changed: 26 reactions under
     * every scheduler, before the fix and after it.
     */
    val DISAGREEING_PROBES: Set<String> = setOf(
        "dup-of-an-erasing-abstraction",
        "two-applied-to-two-unwalkable",
        "compose-self-apply-scope-leak"
    )

    /**
     * Exactly [DISAGREEING_PROBES] of [CONFLUENCE_PROBES] disagree with the normal-order evaluator.
     *
     * The premise, asserted as **set equality** rather than as "each one still disagrees". A probe that
     * starts agreeing is news either way — good if it was fixed, bad if the evaluator broke — and a set
     * comparison names which one moved and in which direction. The one-sided version of this check is
     * what fired on 2026-09-22: it was right to fire, and it could say only that *a* probe had stopped
     * disagreeing, not that three still did.
     */
    fun assertTheProbesAreStillCounterexamples(maxSteps: Int = 4_000) {
        val disagreeing = LinkedHashSet<String>()
        CONFLUENCE_PROBES.forEach { (name, source) ->
            val term = LambdaTerms.parse(source)
            check(LambdaTerms.freeVariablesOf(term).isEmpty()) { "probe '$name' is not closed" }
            val oracle = LambdaTerms.referenceNormalOrder(term, maxSteps = 2_000, maxWorkNodes = 4_000_000)
            val run = LambdaTerms.normalise(term, maxSteps = maxSteps)
            if (!LambdaTerms.alphaEquivalent(run.result, oracle)) disagreeing += name
        }
        check(disagreeing == DISAGREEING_PROBES) {
            "the probes that disagree with the evaluator are $disagreeing, pinned $DISAGREEING_PROBES. " +
                "Newly agreeing (${DISAGREEING_PROBES - disagreeing}) is good news, and this page's count " +
                "of terms the reducer gets wrong has to be recounted with it; newly disagreeing " +
                "(${disagreeing - DISAGREEING_PROBES}) is a regression."
        }
        check(DISAGREEING_PROBES.isNotEmpty()) {
            "no probe disagrees any more, so the stress case is gone — the invariance below would be " +
                "measured on ordinary terms. Repick the probes from LambdaPropertyOracleTest's named cases."
        }
    }

    /**
     * The probes' reaction counts under every scheduler — the harder half of the invariance claim.
     *
     * @return probe name to the distinct reaction counts the four schedulers produced, sorted. A
     *   singleton list means the invariance held on a term the reducer is known to get *wrong*.
     */
    fun probeStepCounts(maxSteps: Int = 4_000): Map<String, List<Int>> =
        CONFLUENCE_PROBES.mapValues { (name, source) ->
            ORDERS.map { traceSource(name, source, it, maxSteps).steps }.distinct().sorted()
        }

    // ── scene model ────────────────────────────────────────────────────────────────────────

    /** Shares [CaSoundDocs.Scene]'s shape, so the two galleries' harnesses stay comparable. */
    class Scene(
        val name: String,
        val title: String,
        /** One line describing what you hear. */
        val blurb: String,
        val cycles: Int,
        val cps: Double,
        val render: (Int, Double) -> PatSoundDocs.Stereo,
        val verify: Scene.(PatSoundDocs.Stereo) -> Unit = {},
    ) {
        val seconds: Double get() = cycles / cps
    }

    val SCENES: List<Scene> = listOf(

        // 1 and 2 ── the same reduction, twice. Same reactions, same count, different order, so the
        // two clips are the page's claim in the only form you can hear.
        betaBar(
            name = "exp-first-found",
            order = ActivationOrder.FIRST_FOUND,
            title = "the eight beta-steps of `three two` where FIRST_FOUND puts them",
            blurb = "One slot per reaction, struck where that reaction was a beta. Two strikes " +
                "early, a pair, then a long stretch of fan bookkeeping with nothing on it, and " +
                "four strikes hard against the end.",
        ),
        betaBar(
            name = "exp-rightmost",
            order = ActivationOrder.RIGHTMOST_BY_ID,
            title = "the same eight beta-steps, where RIGHTMOST_BY_ID puts them",
            blurb = "The same term, the same twenty-six reactions, the same eight betas, the same " +
                "answer. Only the order is different, and the ending is the difference you hear: " +
                "this schedule spreads its last four out instead of landing them together.",
        ),

        // 3 ── the same reduction as pitch: one note per reaction, pitched by what it emitted.
        Scene(
            name = "erase-rule-melody",
            title = "the rule sequence of `K I (mul 2 3)` as pitch, one note per reaction",
            blurb = "Each reaction plays the degree its emitted event kind maps to, up a minor " +
                "pentatonic. This is the one term in the four that reaches all four kinds a closed " +
                "term can reach, because the whole `mul 2 3` it throws away has to be pruned away " +
                "agent by agent — nine of its twenty-three reactions are that.",
            cycles = 2,
            cps = 0.2,
            render = { cycles, cps ->
                patRenderStereo(
                    pat(trace(MELODY_TERM).melody.joinToString(" ")),
                    cycles = cycles, cps = cps,
                    voice = patVoice(wave = PatWave.TRIANGLE, decay = 0.04, sustain = 0.25),
                )
            },
            verify = { audio ->
                val t = trace(MELODY_TERM)
                check(t.melody.size == t.steps) { "the melody is not one note per reaction" }
                t.beats.forEachIndexed { i, beat ->
                    check(t.melody[i] == noteOf(beat.kind)) { "note $i does not match its reaction's kind" }
                }
                // Two of the six degrees are unreachable on a closed term, so the tune uses four.
                val used = t.melody.distinct().sorted()
                check(used.none { it == noteOf("duplicate") || it == noteOf("erase") }) {
                    "a closed term reached a degree KINDS_UNREACHABLE_ON_CLOSED_TERMS says it cannot: $used"
                }
                val onsets = PatSoundDocs.onsetPeaks(audio.mono, threshold = 0.05)
                check(onsets.size >= t.steps) { "expected at least ${t.steps} notes, found ${onsets.size}" }
                println("    $used are the MIDI notes it uses, of $KIND_NOTES")
            },
        ),
    )

    /**
     * The corpus term whose rule sequence becomes the melody.
     *
     * `K I (mul 2 3)` — 23 reactions, and the only one of [TERMS] that reaches all four event
     * kinds a closed term can reach. It is the erasing term: the argument it discards is a whole
     * multiplication, and taking that apart is what the `prune` degree is.
     */
    const val MELODY_TERM: String = "erase-argument"

    /** The corpus term both beta bars reduce. Its beta mask is spelled four different ways by the four orders. */
    const val BAR_TERM: String = "church-exp-two-cubed"

    /**
     * One beta bar: [BAR_TERM]'s beta mask under [order], condensed and played.
     *
     * A factory rather than two hand-written scenes, because the whole point of the pair is that
     * nothing differs between them except the scheduler — and two copies of a scene body are two
     * places for something else to differ by accident.
     */
    private fun betaBar(name: String, order: ActivationOrder, title: String, blurb: String): Scene = Scene(
        name = name,
        title = title,
        blurb = blurb,
        cycles = 3,
        cps = 0.4,
        render = { cycles, cps ->
            patRenderStereo(
                trace(BAR_TERM, order).betaCondensation.pattern,
                cycles = cycles, cps = cps,
                // A short tail on purpose: at 0.4 cps a 26-slot bar puts consecutive slots 96 ms
                // apart, and a longer decay runs two adjacent beta-steps together into one flam,
                // which an onset detector then counts as one.
                voice = patVoice(wave = PatWave.SQUARE, baseHz = 660.0, decay = 0.008, sustain = 0.05),
            )
        },
        verify = { audio ->
            val t = trace(BAR_TERM, order)
            check(t.betaMask.size == t.steps) { "the mask is not one slot per reaction" }
            check(t.steps == 26) { "$BAR_TERM took ${t.steps} reactions, and this scene is written for 26" }
            check(t.betas == 8) { "$BAR_TERM has ${t.betas} beta reactions, and this scene is written for 8" }
            check(t.betaCondensation.exact) { "the condenser would not sign for ${t.betaCondensation.notation}" }
            val onsets = PatSoundDocs.onsetPeaks(audio.mono, threshold = 0.04)
            check(onsets.size >= t.betas * cycles - 2) {
                "expected about ${t.betas * cycles} strikes, found ${onsets.size}"
            }
            println("    ${t.betaCondensation.describe()}")
        },
    )

    private val byName: Map<String, Scene> = SCENES.associateBy { it.name }

    /** Offline-render scene [name] and write a stereo 16-bit PCM WAV to [outPath]. */
    fun renderWav(name: String, outPath: String) {
        val scene = byName[name] ?: error("unknown LambdaSoundDocs scene '$name'; known: ${byName.keys.sorted()}")
        val audio = scene.render(scene.cycles, scene.cps)
        require(audio.frames > 0) { "scene '$name' rendered no samples" }
        require(audio.left.any { it != 0f } || audio.right.any { it != 0f }) { "scene '$name' rendered silence" }
        WavFloatArrayWriter.writeStereo16(outPath, audio.left, audio.right, SR, normalizePeak = 0.9f)
    }

    // ── FIELD_TEST entry point ─────────────────────────────────────────────────────────────

    @JvmStatic
    fun main(args: Array<String>) {
        val outDir = File(
            args.getOrNull(0) ?: System.getenv("LAMBDA_SOUND_DIR")
                ?: File(System.getProperty("java.io.tmpdir"), "lambda-sound-docs").path,
        )
        outDir.mkdirs()

        // The whole corpus under every scheduler, once. Every number the page prints comes out of
        // this sweep, so the page and the test cannot disagree about one.
        val sweep = corpusSweep()
        val all = sweep.values.flatMap { it.values }
        val counts = stepCountIsScheduleInvariant(sweep)
        val multisets = ruleMultisetIsScheduleInvariant(sweep)
        val spellings = spellingsPerTerm(sweep)
        sweep.forEach { (_, traces) ->
            answerIsScheduleInvariant(traces)
            betaIsScheduleInvariant(traces)
        }

        println("term                     reactions  β   fans  spellings  the FIRST_FOUND β mask")
        sweep.forEach { (name, traces) ->
            val first = traces.getValue(ActivationOrder.FIRST_FOUND)
            println(
                "  ${name.padEnd(22)} ${counts.getValue(name).toString().padStart(6)} " +
                    "${first.betas.toString().padStart(4)} ${first.fans.toString().padStart(5)} " +
                    "${spellings.getValue(name).toString().padStart(9)}  " +
                    "${"%.2f".format(first.betaCompression)} ${first.betaCondensation.notation}",
            )
        }
        println()
        println(
            "${sweep.size} terms x ${ORDERS.size} schedulers = ${all.size} reductions, " +
                "${all.sumOf { it.steps }} reactions",
        )
        println(
            "reaction count is scheduler-invariant on all ${counts.size} terms; " +
                "so is the rule multiset, on all $multisets",
        )
        val disagreeing = spellings.filterValues { it > 1 }
        check(disagreeing.isNotEmpty()) {
            "no term's beta mask is spelled differently by different schedulers, so this page has no subject"
        }
        println(
            "${disagreeing.size} of ${spellings.size} terms condense to more than one notation " +
                "across the four schedulers: ${disagreeing.keys}",
        )

        // The invariance, re-measured where it is most likely to break: the four terms this
        // reducer is known to get WRONG. A corpus of terms it gets right is a friendly population.
        assertTheProbesAreStillCounterexamples()
        val probes = probeStepCounts()
        println()
        println("the four pinned confluence counterexamples, reaction counts per scheduler:")
        probes.forEach { (name, counts) ->
            println("  ${name.padEnd(30)} ${if (counts.size == 1) "${counts.single()}, every order" else "$counts"}")
        }
        val probesInvariant = probes.count { it.value.size == 1 }
        println("  -> $probesInvariant of ${probes.size} are step-count invariant anyway")

        val kinds = assertTheVocabularyIsWhatItSays(all)
        println(
            "event kinds seen: $kinds — $KINDS_UNREACHABLE_ON_CLOSED_TERMS are unreachable on closed terms",
        )

        // A normal form still has an active pair in it, and no scheduler makes that go away.
        all.forEach { t ->
            check(t.trailingActivePairs >= 1) {
                "${t.name}/${t.order.name} ended with no active pair at all, which the ROOT should prevent"
            }
        }
        println(
            "trailing active pairs (ROOT against the answer): " +
                all.map { it.trailingActivePairs }.distinct().sorted(),
        )

        println()
        println("the four terms this page draws:")
        TERMS.forEach { name ->
            val traces = sweep.getValue(name)
            traces.forEach { (order, t) ->
                println(
                    "  ${name.padEnd(22)} ${order.name.padEnd(20)} " +
                        "${t.steps.toString().padStart(4)} reactions, β=${t.betas}, " +
                        "${"%.2f".format(t.betaCompression)} ${t.betaCondensation.notation}",
                )
            }
            val kindTally = traces.getValue(ActivationOrder.FIRST_FOUND)
                .beats.groupingBy { it.kind }.eachCount()
            println("    event kinds under FIRST_FOUND: $kindTally")
        }

        println()
        SCENES.forEach { scene ->
            val audio = scene.render(scene.cycles, scene.cps)
            check(audio.frames > 0) { "${scene.name}: rendered no samples" }
            check(audio.left.size == audio.right.size) { "${scene.name}: channel length mismatch" }
            check(audio.left.all { it.isFinite() } && audio.right.all { it.isFinite() }) {
                "${scene.name}: non-finite samples"
            }
            val expectedFrames = (scene.seconds * SR).toInt()
            check(abs(audio.frames - expectedFrames) <= SR / 10) {
                "${scene.name}: expected ~$expectedFrames frames, got ${audio.frames}"
            }
            val monoPeak = PatSoundDocs.peak(audio.mono)
            val monoRms = PatSoundDocs.rms(audio.mono)
            check(monoPeak > 0.02) { "${scene.name}: effectively silent (peak=$monoPeak)" }
            check(monoPeak <= 4.0) { "${scene.name}: runaway level (peak=$monoPeak)" }
            check(monoRms > 0.002) { "${scene.name}: RMS too low to be music (rms=$monoRms)" }

            println(
                "scene ${scene.name}: ${"%.2f".format(audio.frames.toDouble() / SR)}s, " +
                    "peak=${"%.3f".format(monoPeak)}, rms=${"%.4f".format(monoRms)}",
            )
            scene.verify(scene, audio)

            val file = File(outDir, "lambda-${scene.name}.wav")
            WavFloatArrayWriter.writeStereo16(file, audio.left, audio.right, SR, normalizePeak = 0.9f)
            val bytes = file.readBytes()
            check(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") { "${scene.name}: missing RIFF" }
            check(String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "${scene.name}: missing WAVE" }
            check(bytes.size == 44 + audio.frames * 4) {
                "${scene.name}: WAV data size mismatch: ${bytes.size} vs ${44 + audio.frames * 4}"
            }
            println("  wrote ${file.absolutePath} (${bytes.size} bytes)")
        }

        // The two beta bars are the page's claim, so "they are audibly different" has to be a
        // measurement rather than an adjective. Same term, same 26 reactions, same 8 betas, so the
        // peak and the RMS are identical by construction and say nothing — the correlation is what
        // separates "a different order" from "the same file twice".
        val barA = SCENES.first { it.name == "exp-first-found" }
        val barB = SCENES.first { it.name == "exp-rightmost" }
        val a = barA.render(barA.cycles, barA.cps).mono
        val b = barB.render(barB.cycles, barB.cps).mono
        check(a.size == b.size) { "the two beta bars are different lengths, so they are not the same reduction" }
        val corr = PatSoundDocs.correlation(a, b)
        check(corr < 0.5) {
            "the two schedulers' beta bars correlate at $corr, which is not two different rhythms"
        }
        println()
        println(
            "the two beta bars: ${"%.3f".format(corr)} correlation, " +
                "identical length ${a.size} frames, " +
                "peaks ${"%.3f".format(PatSoundDocs.peak(a))}/${"%.3f".format(PatSoundDocs.peak(b))}",
        )

        println()
        println("lambda sound docs rendered to ${outDir.absolutePath} (${SCENES.size} scenes)")
    }
}
