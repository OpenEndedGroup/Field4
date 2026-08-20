package afield.petri

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A randomised differential test of the partial-order reduction in `PetriStateSpace.kt`.
 *
 * `stubbornEnabled` is about eighty lines deciding what *not* to explore, and until this file it was
 * agreed with the full search on thirty hand-picked nets. Thirty hand-picked nets is a good start
 * and it is not evidence about the closure, because the nets were written by the same person who
 * wrote the closure and they explore the cases that person thought of. This runs the same
 * comparison over generated nets instead, and the generation is aimed at the place the theory is
 * thinnest.
 *
 * ### Where the theory is thin, and therefore where the nets are aimed
 *
 * The closure is standing on Valmari's D1/D2 for *strong* stubborn sets. D2 — the key transition
 * survives any firing sequence of non-members — is what [PetriArcIndex.mayDisable] buys, and that
 * function is carefully complete: the consumers of every place the transition consumes *or reads*,
 * the producers of every place that inhibits it, the producers of every capped place it fills.
 *
 * D1 is the exposure. D1 needs the fired member to not *disable* anything outside the set, and in a
 * plain place/transition net that is free: "t disables t'" implies they share an input place,
 * implies t' consumes from `•t`, implies t' is already in the set. This model is not a plain net,
 * and two of its three arc kinds make that relation **asymmetric**:
 *
 * - t consumes p and t' only **reads** p. `PetriArcIndex.consumers` filters to `NORMAL`, so
 *   `mayDisable(t)` does not pull t' in — but firing t can drop p below t's read weight.
 * - t **produces** into r and t' **inhibits** on r. `mayDisable` reads the transition's *inputs*,
 *   so again t' is not pulled in — but firing t arms the inhibitor.
 *
 * Capacity is already symmetric, through `fills` → `producers`. So the families below are half
 * plain (a control: the theorem should hold outright) and half saturated with read arcs, inhibitor
 * arcs, capacities and weights.
 *
 * ### What is compared, and why it is more than deadlock existence
 *
 * Existence of a deadlock is the weakest possible oracle: a net with several deadlocks agrees on
 * existence while the reduction loses all but one of them. The theorem for strong stubborn sets is
 * stronger than that — it preserves **every** reachable terminal marking — so the sharp assertion
 * is set equality of `deadMarkings` compared as markings, and that is what is asserted here.
 *
 * The other half is the exactness contract: a reduced graph may not claim to know anything the full
 * graph would contradict. Its state set must be a subset, its observed bounds must be lower bounds,
 * and every accessor gated on [PetriStateGraph.exact] must refuse.
 *
 * ### On vacuity
 *
 * A differential fuzz whose generator makes only trivial nets, or whose reduction quietly stops
 * reducing, passes at zero differences and says nothing. So [the generator's own premise] is a
 * separate test that fails loudly: net counts per family, both verdicts present, non-trivial state
 * counts, the feature families really carrying the features — and the aggregate pruning ratio
 * floored just under its measured value rather than at "some".
 */
class PetriReductionFuzzTest {

    // ── the generator ─────────────────────────────────────────────────────────────────────

    /**
     * Which shape of net a seed builds.
     *
     * Two random families and four structured ones. The random families cap every place, which
     * makes the state space finite by construction — the number of markings is at most the product
     * of the capacities — so almost every seed produces a net the full search can finish, which is
     * what a differential test needs. The ring families are the philosophers' shape generalised:
     * independent components walking private cycles and contending for shared resources, which is
     * where a partial-order reduction has something to reduce.
     */
    private enum class Family {
        /** Random arcs, every place capped, `NORMAL` arcs only — the control. */
        RANDOM_PLAIN,

        /** The same, with read and inhibitor arcs mixed in. */
        RANDOM_TESTS,

        /** Independent components contending for shared resources. Philosophers, generalised. */
        RINGS_PLAIN,

        /** …plus read arcs, which is the first asymmetry. */
        RINGS_READ,

        /** …plus inhibitor arcs, which is the second. */
        RINGS_INHIBIT,

        /** …plus capacities, weights and a component that drops its resources on the floor. */
        RINGS_MIXED,
    }

    /** One arc, before it is handed to the builder — so a transition can be inspected before it exists. */
    private class Arc(val place: String, val kind: PetriArcKind, val weight: Int)

    /** One transition, before it is handed to the builder. */
    private class Spec(val name: String, val inputs: MutableList<Arc>, val outputs: MutableList<Arc>)

    private fun assemble(
        places: List<Triple<String, Int, Int?>>,
        specs: List<Spec>,
    ): PetriNet = petri {
        for ((name, tokens, capacity) in places) place(name, tokens = tokens, capacity = capacity)
        for (spec in specs) t(spec.name) {
            for (arc in spec.inputs) when (arc.kind) {
                PetriArcKind.NORMAL -> from(arc.place, arc.weight)
                PetriArcKind.READ -> read(arc.place, arc.weight)
                PetriArcKind.INHIBIT -> inhibit(arc.place, arc.weight)
            }
            for (arc in spec.outputs) to(arc.place, arc.weight)
        }
    }

    /**
     * A random net whose every place is capped, so its state space is finite whatever the arcs say.
     *
     * A transition with no consuming input and no output would be enabled forever without changing
     * anything, which makes every marking non-terminal and quietly removes deadlock from the net.
     * That is not a shape worth a third of the corpus, so it is repaired rather than generated.
     */
    private fun randomCapped(rng: Random, tests: Boolean): PetriNet {
        val places = (0 until 4 + rng.nextInt(4)).map { "p$it" }
        val declared = places.map { name ->
            val capacity = 1 + rng.nextInt(3)
            Triple(name, if (rng.nextInt(2) == 0) 1 + rng.nextInt(capacity) else 0, capacity)
        }
        val specs = (0 until 4 + rng.nextInt(4)).map { index ->
            val inputs = mutableListOf<Arc>()
            val outputs = mutableListOf<Arc>()
            repeat(1 + rng.nextInt(2)) {
                val place = places[rng.nextInt(places.size)]
                val kind = if (!tests) PetriArcKind.NORMAL else when (rng.nextInt(4)) {
                    0, 1 -> PetriArcKind.NORMAL
                    2 -> PetriArcKind.READ
                    else -> PetriArcKind.INHIBIT
                }
                inputs += Arc(place, kind, 1)
            }
            repeat(rng.nextInt(3)) { outputs += Arc(places[rng.nextInt(places.size)], PetriArcKind.NORMAL, 1) }
            if (inputs.none { it.kind == PetriArcKind.NORMAL } && outputs.isEmpty()) {
                inputs += Arc(places[rng.nextInt(places.size)], PetriArcKind.NORMAL, 1)
            }
            Spec("t$index", inputs, outputs)
        }
        return assemble(declared, specs)
    }

    /**
     * Independent components walking private cycles and contending for shared resources.
     *
     * Bounded by construction: the private token of each component is conserved round its cycle, and
     * a resource is either held or on the table. `dropped` breaks the second half of that — one
     * component keeps what it took, which is how a net of this shape reaches a deadlock the
     * reduction has to find rather than one it stumbles over.
     *
     * The contention is deliberately *light* — usually one resource per component, sometimes two.
     * Heavy contention makes a net that wedges in two firings, and a two-firing net is four markings
     * wide and agrees with itself under any reduction. The interesting nets are the wide ones, so the
     * generator is tuned to make wide ones and let the deadlocks be rarer and deeper.
     */
    private fun rings(rng: Random, read: Boolean, inhibit: Boolean, mixed: Boolean): PetriNet {
        val components = 3 + rng.nextInt(4)
        val length = 3 + rng.nextInt(3)
        val resourceCount = 1 + rng.nextInt(3)
        val resources = (0 until resourceCount).map { "r$it" }
        val declared = mutableListOf<Triple<String, Int, Int?>>()
        for (name in resources) declared += Triple(name, if (rng.nextInt(3) == 0) 2 else 1, null)
        for (c in 0 until components) for (k in 0 until length) {
            val capped = if (mixed && rng.nextInt(6) == 0) 1 else null
            declared += Triple("s$c.$k", if (k == 0) 1 else 0, capped)
        }

        val specs = mutableListOf<Spec>()
        for (c in 0 until components) {
            // Which resources this component contends for, and in which rotation — the rotation is
            // what makes a ring of them deadlock, exactly as it does at the dinner table.
            val wanted = if (rng.nextInt(3) == 0) 2 else 1
            val held = (0 until minOf(wanted, resourceCount, length)).map { resources[(c + it) % resourceCount] }
            val dropped = mixed && c == 0
            for (k in 0 until length) {
                val inputs = mutableListOf(Arc("s$c.$k", PetriArcKind.NORMAL, 1))
                val outputs = mutableListOf(Arc("s$c.${(k + 1) % length}", PetriArcKind.NORMAL, 1))
                if (k < held.size) inputs += Arc(held[k], PetriArcKind.NORMAL, if (mixed && rng.nextInt(5) == 0) 2 else 1)
                if (k == length - 1 && !dropped) for (name in held) outputs += Arc(name, PetriArcKind.NORMAL, 1)
                specs += Spec("c$c.$k", inputs, outputs)
            }
        }

        val everyPlace = declared.map { it.first }
        if (read) repeat(1 + rng.nextInt(2)) {
            specs[rng.nextInt(specs.size)].inputs += Arc(everyPlace[rng.nextInt(everyPlace.size)], PetriArcKind.READ, 1)
        }
        if (inhibit) repeat(1 + rng.nextInt(2)) {
            specs[rng.nextInt(specs.size)].inputs +=
                Arc(everyPlace[rng.nextInt(everyPlace.size)], PetriArcKind.INHIBIT, 1 + rng.nextInt(2))
        }
        return assemble(declared, specs)
    }

    private fun generate(seed: Int): Pair<Family, PetriNet> {
        val family = Family.entries[seed % Family.entries.size]
        val rng = Random(seed.toLong() * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L)
        val net = when (family) {
            Family.RANDOM_PLAIN -> randomCapped(rng, tests = false)
            Family.RANDOM_TESTS -> randomCapped(rng, tests = true)
            Family.RINGS_PLAIN -> rings(rng, read = false, inhibit = false, mixed = false)
            Family.RINGS_READ -> rings(rng, read = true, inhibit = false, mixed = false)
            Family.RINGS_INHIBIT -> rings(rng, read = false, inhibit = true, mixed = false)
            Family.RINGS_MIXED -> rings(rng, read = true, inhibit = true, mixed = true)
        }
        return family to net
    }

    // ── running the corpus once ───────────────────────────────────────────────────────────

    /**
     * The seeds. A fixed range rather than a clock, so a failure names a net somebody can rebuild
     * with one line, and so the number this test prints is the same number tomorrow.
     */
    private val seeds = 1..1_500

    /** The budget. Small on purpose: the corpus is about breadth, and a net this cannot finish is skipped. */
    private val budget = 4_000

    /** A marking as a vector of counts — the state identity for a black-token net, and comparable across graphs. */
    private fun PetriNet.key(marking: PetriMarking): List<Int> = placeNames.map { marking.count(it) }

    /** One net's worth of comparison. */
    private class Row(
        val seed: Int,
        val family: Family,
        val net: PetriNet,
        val fullStates: Int,
        val reducedStates: Int,
        val canDeadlock: Boolean,
        val fullDead: Int,
        val reducedDead: Int,
        val fullLength: Int?,
        val reducedLength: Int?,
    )

    private class Corpus(
        val rows: List<Row>,
        val skipped: Int,
        val complaints: List<String>,
    )

    /**
     * The corpus, built once for the whole class.
     *
     * JUnit builds a fresh instance per test method, so an instance-level `by lazy` would run the
     * whole fuzz five times over. It lives in the companion for that reason and no other.
     */
    private val corpus: Corpus get() = shared

    private fun runCorpus(): Corpus {
        val rows = mutableListOf<Row>()
        val complaints = mutableListOf<String>()
        var skipped = 0

        for (seed in seeds) {
            val (family, net) = generate(seed)
            val full = net.stateSpace(budget)
            if (!full.complete) {
                skipped++
                continue
            }
            val reduced = net.stateSpace(budget, reduction = PetriReduction.STUBBORN)
            val where = "seed $seed ($family)"

            fun complain(message: String) {
                complaints += "$where: $message"
            }

            // The reduction must have actually been taken. A colour-dependent net declines it, and
            // nothing here is coloured, so a NONE here means the generator produced something this
            // test is not testing.
            if (reduced.reduction != PetriReduction.STUBBORN) complain("the reduction was declined")
            if (!reduced.complete) complain("the reduced search did not finish inside a budget the full one did")
            if (reduced.exact) complain("a reduced graph reported itself exact")

            val fullStates = full.states.mapTo(HashSet()) { net.key(it) }
            val reducedStates = reduced.states.mapTo(HashSet()) { net.key(it) }
            (reducedStates - fullStates).firstOrNull()?.let {
                complain("the reduced search visited $it, which the full search says is unreachable")
            }

            // The theorem preserves *every* reachable terminal marking, not merely whether one
            // exists. Compared as markings, because two graphs number their states differently.
            val fullDead = full.deadMarkings.mapTo(HashSet()) { net.key(full.states[it]) }
            val reducedDead = reduced.deadMarkings.mapTo(HashSet()) { net.key(reduced.states[it]) }
            (reducedDead - fullDead).firstOrNull()?.let {
                complain("the reduced search calls $it a deadlock and the full search does not")
            }
            (fullDead - reducedDead).firstOrNull()?.let {
                complain(
                    "the reduction lost the deadlock $it " +
                        "(full has ${fullDead.size}, reduced has ${reducedDead.size})"
                )
            }
            if (full.canDeadlock != reduced.canDeadlock) {
                complain("canDeadlock ${full.canDeadlock} full vs ${reduced.canDeadlock} reduced")
            }

            val fullTrace = full.deadlockTrace()
            val reducedTrace = reduced.deadlockTrace()
            reducedTrace?.verify()?.let { complain("the reduced counterexample does not re-fire: $it") }
            fullTrace?.verify()?.let { complain("the full counterexample does not re-fire: $it") }
            if (fullTrace != null && reducedTrace != null && reducedTrace.length < fullTrace.length) {
                complain(
                    "the reduced counterexample is *shorter* (${reducedTrace.length}) than the full " +
                        "search's shortest (${fullTrace.length}), so one of the two searches is wrong"
                )
            }

            for (place in net.placeNames) {
                if (reduced.observedBound(place) > full.observedBound(place)) {
                    complain("reduced saw ${reduced.observedBound(place)} in '$place', full saw ${full.observedBound(place)}")
                }
                if (reduced.bound(place) != null) complain("a reduced graph answered bound('$place')")
            }
            if (reduced.stateCount > full.stateCount) complain("the reduction made the graph bigger")

            rows += Row(
                seed = seed,
                family = family,
                net = net,
                fullStates = full.stateCount,
                reducedStates = reduced.stateCount,
                canDeadlock = full.canDeadlock == true,
                fullDead = fullDead.size,
                reducedDead = reducedDead.size,
                fullLength = fullTrace?.length,
                reducedLength = reducedTrace?.length,
            )
        }
        return Corpus(rows, skipped, complaints)
    }

    // ── the premise, asserted before the result ───────────────────────────────────────────

    @Test
    fun `the generator's own premise holds, so a green differential means something`() {
        val rows = corpus.rows
        println(
            "petri reduction fuzz premise: ${rows.size} usable, ${corpus.skipped} skipped, " +
                "${rows.count { it.canDeadlock }} wedge, ${rows.count { it.fullDead >= 2 }} wedge in 2+ places, " +
                "${rows.count { it.fullStates >= 32 }} at 32+ markings, widest ${rows.maxOf { it.fullStates }}, " +
                "${rows.count { it.fullStates >= 32 && it.reducedStates * 4 <= it.fullStates }} pruned to a quarter"
        )
        assertTrue(rows.size >= 1_450, "only ${rows.size} of ${seeds.count()} nets were usable")
        assertTrue(corpus.skipped <= 40, "${corpus.skipped} nets could not be finished at budget $budget")

        // Every family has to carry its weight. A family whose generator broke would otherwise
        // vanish into the average.
        val perFamily = rows.groupingBy { it.family }.eachCount()
        for (family in Family.entries) {
            assertTrue((perFamily[family] ?: 0) >= 220, "$family contributed only ${perFamily[family] ?: 0} nets")
        }

        // Both verdicts, or the corpus is testing one branch of a two-branch question.
        val wedging = rows.count { it.canDeadlock }
        assertTrue(wedging >= 700, "only $wedging nets can deadlock")
        assertTrue(rows.size - wedging >= 680, "only ${rows.size - wedging} nets are deadlock-free")

        // Nets with more than one deadlock are the whole reason `deadMarkings` is compared as a set
        // rather than as a boolean, so there had better be some.
        val plural = rows.count { it.fullDead >= 2 }
        assertTrue(plural >= 220, "only $plural nets have two or more distinct deadlocks")

        // Non-trivial. A four-state net agrees with itself under any reduction.
        val big = rows.count { it.fullStates >= 32 }
        assertTrue(big >= 560, "only $big nets have 32 or more reachable markings")
        assertTrue(rows.maxOf { it.fullStates } >= 2_500, "the widest net is only ${rows.maxOf { it.fullStates }} markings")

        // The feature families must really carry the features they exist for, or the asymmetry this
        // whole file is aimed at is never generated.
        fun arcs(family: Family, kind: PetriArcKind) = rows.filter { it.family == family }
            .count { row -> row.net.transitions.any { t -> t.inputs.any { it.kind == kind } } }
        assertTrue(arcs(Family.RINGS_READ, PetriArcKind.READ) >= 220, "RINGS_READ has no read arcs")
        assertTrue(arcs(Family.RINGS_INHIBIT, PetriArcKind.INHIBIT) >= 220, "RINGS_INHIBIT has no inhibitor arcs")
        assertTrue(arcs(Family.RANDOM_TESTS, PetriArcKind.READ) >= 170, "RANDOM_TESTS has no read arcs")
        assertTrue(arcs(Family.RANDOM_TESTS, PetriArcKind.INHIBIT) >= 170, "RANDOM_TESTS has no inhibitor arcs")
        assertEquals(
            0,
            arcs(Family.RANDOM_PLAIN, PetriArcKind.READ) + arcs(Family.RANDOM_PLAIN, PetriArcKind.INHIBIT),
            "RANDOM_PLAIN is supposed to be the plain control",
        )
        val capped = rows.filter { it.family == Family.RINGS_MIXED }
            .count { row -> row.net.places.any { it.capacity != null } }
        assertTrue(capped >= 170, "RINGS_MIXED produced only $capped capped nets")
    }

    @Test
    fun `the reduction is not quietly declining to reduce`() {
        // A reduction that explores everything agrees with the full search by construction, so the
        // differential below would be a tautology. The floor is set just under the measured value
        // rather than at "better than nothing", so a reduction that degrades has to go red.
        val rows = corpus.rows
        val fullTotal = rows.sumOf { it.fullStates }
        val reducedTotal = rows.sumOf { it.reducedStates }
        val ratio = reducedTotal.toDouble() / fullTotal
        val strictly = rows.count { it.reducedStates < it.fullStates }

        println(
            "petri reduction fuzz: ${rows.size} nets, ${corpus.skipped} skipped, " +
                "$fullTotal full markings vs $reducedTotal reduced (%.1f%%), $strictly strictly pruned"
                    .format(ratio * 100.0)
        )
        // The shape of the corpus, printed because the floors below are set from it: a fuzz whose
        // nets are all four markings wide would satisfy any ratio and prove nothing.
        for ((label, range) in listOf(
            "  1..7" to 1..7, "  8..31" to 8..31, " 32..127" to 32..127,
            "128..511" to 128..511, "512+" to 512..Int.MAX_VALUE,
        )) {
            val bucket = rows.filter { it.fullStates in range }
            if (bucket.isEmpty()) continue
            println(
                "  full $label markings: %4d nets, reduced to %.1f%%"
                    .format(bucket.size, 100.0 * bucket.sumOf { it.reducedStates } / bucket.sumOf { it.fullStates })
            )
        }

        assertTrue(fullTotal >= 125_000, "the corpus is only $fullTotal markings wide")
        assertTrue(ratio <= 0.24, "the reduced exploration visited %.1f%% of the full state count".format(ratio * 100.0))
        assertTrue(
            strictly >= rows.size * 8 / 15,
            "only $strictly of ${rows.size} nets were strictly pruned",
        )
        // And at least some nets have to be pruned *hard*, or the average is being carried by a
        // long tail of one-marking savings.
        val hard = rows.count { it.fullStates >= 32 && it.reducedStates * 4 <= it.fullStates }
        assertTrue(hard >= 260, "only $hard nets were pruned to a quarter or less")
    }

    // ── the differential itself ───────────────────────────────────────────────────────────

    @Test
    fun `the reduced search agrees with the full one on every generated net`() {
        val complaints = corpus.complaints
        assertTrue(
            complaints.isEmpty(),
            "${complaints.size} disagreements over ${corpus.rows.size} nets:\n" +
                complaints.take(20).joinToString("\n") { "  $it" },
        )
    }

    @Test
    fun `the reduction's counterexample is real but not always shortest, measured`() {
        // The KDoc on `PetriReduction.STUBBORN` says the trace it hands back is a genuine firing
        // sequence and is not guaranteed minimal. Both halves of that are checked above; this
        // records how often the second half bites, because a claim like that is worth a number.
        val both = corpus.rows.filter { it.fullLength != null && it.reducedLength != null }
        assertTrue(both.size >= 700, "only ${both.size} nets produced a counterexample from both searches")
        val longer = both.count { it.reducedLength!! > it.fullLength!! }
        println("petri reduction fuzz: ${both.size} counterexample pairs, $longer of them longer under reduction")
        assertTrue(both.all { it.reducedLength!! >= it.fullLength!! }, "a reduced trace beat the full BFS")
    }

    // ── the two nets the fuzz found, minimised by hand ────────────────────────────────────

    /*
     The fuzz's first run reported eight disagreements over 834 generated nets, and they were two
     mechanisms, not eight. Both are the *same* omission — a stubborn set has to be closed under "t
     may disable t'" as well as "t' may disable t", and only the second was implemented, which is
     free in a plain place/transition net and not free once read and inhibitor arcs exist. The two
     nets below are those mechanisms hand-minimised out of seeds 869 and 304, and they are pinned
     separately from the fuzz because a generated counterexample is evidence and a minimal one is an
     explanation. Each is three or four transitions; each failed before `mayBeDisabledBy` and passes
     after; and each fails *differently*, which is the part worth keeping.
    */

    /**
     * Hole one: a transition consumes a place another transition only **reads**.
     *
     * `take` drains `r`. `peek` needs a token in `r` and does not consume it, so it is not among the
     * `consumers` of `r` and the old closure never pulled it in — and `{take}` alone was a stubborn
     * set. Firing `take` kills `peek` for good, and the terminal marking behind `peek` is lost.
     *
     * Note what this one does *not* do: both searches still agree that the net can deadlock, because
     * the `take` branch wedges too. That is the whole argument for comparing `deadMarkings` as a set
     * — on existence alone this net is silently green.
     */
    private fun readArcHole() = petri {
        place("r", tokens = 1)
        place("s", tokens = 1)
        place("u", tokens = 1)
        t("take") { from("s"); from("r") }
        t("peek") { read("r"); from("u") }
    }

    /**
     * Hole two: a transition **produces** into a place another transition **inhibits** on.
     *
     * `push` moves the token from `a` to `x`; `gate` is blocked while `x` holds anything. `mayDisable`
     * reads a transition's *inputs*, so it cannot see that `push`'s output arms `gate`'s inhibitor —
     * and `{push}` alone was a stubborn set. The `push`/`recycle` cycle then spins forever and the
     * reduced search reports a net **deadlock-free that can deadlock**, which is the one direction of
     * error this whole file is written not to make.
     */
    private fun inhibitorHole() = petri {
        place("a", tokens = 1)
        place("g", tokens = 1)
        place("x")
        t("push") { from("a"); to("x") }
        t("recycle") { from("x"); from("g"); to("a"); to("g") }
        t("gate") { inhibit("x"); from("g") }
    }

    @Test
    fun `a read arc used to cost the reduction a whole terminal marking`() {
        val net = readArcHole()
        val full = net.stateSpace()
        val reduced = net.stateSpace(reduction = PetriReduction.STUBBORN)
        assertEquals(PetriReduction.STUBBORN, reduced.reduction, "the net must actually be reduced")

        // Both branches wedge, so existence agrees — which is exactly why this was invisible.
        assertEquals(true, full.canDeadlock)
        assertEquals(true, reduced.canDeadlock)

        val fullDead = full.deadMarkings.mapTo(HashSet()) { net.key(full.states[it]) }
        val reducedDead = reduced.deadMarkings.mapTo(HashSet()) { net.key(reduced.states[it]) }
        assertEquals(setOf(listOf(0, 0, 1), listOf(0, 0, 0)), fullDead, "order is ${net.placeNames}")
        assertEquals(fullDead, reducedDead, "the reduction lost a terminal marking behind the read arc")
    }

    @Test
    fun `an inhibitor arc used to make the reduction call a wedging net deadlock-free`() {
        val net = inhibitorHole()
        val full = net.stateSpace()
        val reduced = net.stateSpace(reduction = PetriReduction.STUBBORN)
        assertEquals(PetriReduction.STUBBORN, reduced.reduction, "the net must actually be reduced")

        assertEquals(true, full.canDeadlock, "gate then push empties everything")
        assertEquals(2, assertNotNull(full.deadlockTrace()).length)
        assertEquals(true, reduced.canDeadlock, "this is the answer that used to be false")
        assertNull(assertNotNull(reduced.deadlockTrace()).verify())
        assertEquals(
            full.deadMarkings.mapTo(HashSet()) { net.key(full.states[it]) },
            reduced.deadMarkings.mapTo(HashSet()) { net.key(reduced.states[it]) },
        )
    }

    @Test
    fun `coverability agrees with the full state space wherever it claims to be sound`() {
        // A third oracle, and an independent one: Karp-Miller is a different algorithm reading a
        // different structure, so where it says it is sound its per-place bounds have to match the
        // exhaustive search's exact ones. This catches a wrong `bound` in either of them.
        var checked = 0
        for (row in corpus.rows) {
            val net = row.net
            val cover = net.coverability(budget)
            if (!cover.sound || cover.outcome != PetriSearchOutcome.COMPLETE) continue
            val full = net.stateSpace(budget)
            if (!full.exact) continue
            for (place in net.placeNames) {
                val exact = full.bound(place)!!
                val covered = cover.bound(place)
                if (covered == null) continue  // omega: unbounded, and a bounded net cannot say so
                assertTrue(
                    covered >= exact,
                    "seed ${row.seed}: coverability bounds '$place' at $covered, reachability at $exact",
                )
                checked++
            }
        }
        println("petri reduction fuzz: $checked place bounds cross-checked against coverability")
        assertTrue(checked >= 8_500, "only $checked place bounds could be cross-checked")
    }

    private companion object {
        private val shared: Corpus by lazy { PetriReductionFuzzTest().runCorpus() }
    }
}
