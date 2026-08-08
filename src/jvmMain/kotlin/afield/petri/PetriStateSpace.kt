@file:Suppress("MemberVisibilityCanBePrivate", "unused")
@file:afield.CompletionCategory("Visualization")

package afield.petri

import afield.four.synth.PatQ

/*
 The other question.

 `PetriAnalysis.kt` answers the *deterministic* question and says so in its own KDoc: [willDeadlock]
 runs the net forward under its own [PetriConflict] policy and reports whether *that one*
 interleaving wedged; [deadTransitions] calls a transition dead when it did not fire in *that one*
 run. For a live coder that is the useful question, because the conflict policy is part of the
 instrument.

 It is not the question the textbook asks, and it is not the question a five-philosopher table
 asks either. A symmetric dining table deadlocks under *some* interleavings and not others, so
 running it once and finding it still going proves nothing at all. That is why the demo in
 `petri/document.md` carries a `reverseLast` flag: the human already knew the answer and had to
 encode it by hand, because the analysis could not compute it.

 This file computes it. It builds the state space — every marking reachable by *any* interleaving —
 and reads the classical properties straight off it. Boundedness, safety, deadlock, quasi-liveness,
 liveness, reversibility, home markings, mutual exclusion. Exactly, with counterexamples.

 The one invariant everything here obeys
 ---------------------------------------

 **A positive answer is always sound. A negative answer needs [PetriSearchOutcome.COMPLETE].**

 A deadlock the search *found* is real whether or not the budget ran out — it came with a firing
 sequence, and [PetriTrace.verify] re-fires that sequence through the engine to prove it. But
 "there is no deadlock" is a claim about markings nobody visited, so every accessor that would make
 such a claim returns `null` — *unknown* — when the search was truncated. Nothing here reports a
 truncated search as safe. That failure mode is the whole reason to write an exhaustive search
 rather than a longer simulation, and building it in only to lose it at the last accessor would be
 worse than not building it.

 What this sees that the incidence matrix cannot
 -----------------------------------------------

 The successor relation is [PetriNet.fire], which calls the engine's own `petriBind`. So **read
 arcs, inhibitor arcs, colour guards, `enabledIf` and place capacities are all handled**, because an
 explicit search asks "is this transition enabled here?" and gets the real answer, where
 `tInvariants`/`pInvariants` ask the incidence matrix, which is a record of *change* and therefore
 blind to every arc that does not move a token. A net whose mutual exclusion is enforced by an
 inhibitor arc has no P-invariant that proves it and a state space that does.

 The price is the classical one: reachability for a net with inhibitor arcs is undecidable in
 general, so this may exhaust its budget where the algebra would have answered instantly. The two
 layers are complements, not replacements.

 Two searches, and when to use which
 -----------------------------------

 - [PetriNet.stateSpace] is exact and handles every feature of the model, but only terminates on a
   bounded net. An unbounded one exhausts the budget and says so.
 - [PetriNet.coverability] is Karp–Miller: it accelerates growth to ω and therefore *always*
   terminates, which is how you get per-place boundedness for a net whose state space is infinite.
   The price is that Karp–Miller is only valid for plain place/transition nets — inhibitor arcs,
   place capacities and colour-dependent behaviour each break the acceleration argument, and each
   adds an entry to [PetriCoverabilityGraph.caveats] rather than being quietly ignored.

 The state key, which is a real decision
 ---------------------------------------

 [PetriMarking.signature] is the obvious key and it is the wrong one here. Its own KDoc explains
 why: it compares colours by `toString`, so two distinct `PatPattern`s collide. It also *sorts* the
 colours within a place, so it loses arrival order — and consumption is FIFO, so two markings with
 the same multiset in a different order have genuinely different futures.

 Both of those make the key **coarser** than the state, and a coarse key in a reachability search
 merges states whose futures differ, which can hide a reachable deadlock behind an already-visited
 marking and report the net safe. That is the one direction of error this file may not make. So the
 key here is strict: places, and the colours in them **in arrival order**, compared by the colours'
 own `equals` rather than by their rendering.

 The cost lands in the other direction, which is the survivable one. A net that mints a fresh
 colour object per firing has an effectively infinite state space and will exhaust the budget —
 and that is the honest answer, because the question really is unanswerable by this method for that
 net. [PetriSearchOutcome.BUDGET] says so instead of guessing.

 `PetriToken.born` is not in the key. That is not much of an abstraction in practice: the search
 fires everything at [PatQ.ZERO] — it is the *untimed* semantics, and timing, grid and conflict
 policy are all deliberately discarded — so `born` never varies during a search. The residual
 exposure is a guard or an `enabledIf` that reads `born`, which the state space would evaluate at
 zero and a real run would not.
*/

// ── firing one transition, alone ───────────────────────────────────────────────────────────

/**
 * The marking after [transition] fires alone against [marking], or null if it cannot fire there.
 *
 * The *untimed* semantics: one transition, no grid, no conflict policy, no tokens in flight. This
 * is the successor relation the classical theory is stated over, and running it is not the same
 * thing as running the net — [PetriRun] fires whole maximal steps at grid ticks and resolves
 * conflicts by policy, which is one path through the tree this explores all of.
 *
 * Everything else about the model still applies, because the enabling test is the engine's own:
 * arc weights, colour guards, read arcs, inhibitor arcs, `enabledIf`, and the capacity rule.
 * Capacity is checked exactly the way [PetriRun] checks it — against the marking as it stood
 * *before* this transition consumed anything — so that a self-loop on a full place is refused here
 * for the same reason it is refused there. A state space describing a slightly different net from
 * the one that runs would be worse than none.
 */
fun PetriNet.fire(marking: PetriMarking, transition: String): PetriMarking? =
    transition(transition)?.let { fireOne(marking, it) }

/**
 * Every marking reachable from [marking] in one firing, with the transition that reaches it.
 *
 * Transitions are tried in declaration order, so the successor list — and therefore every state
 * index, every BFS layer and every rendered picture downstream of it — is deterministic.
 */
fun PetriNet.successors(marking: PetriMarking): List<Pair<String, PetriMarking>> =
    transitions.mapNotNull { transition -> fireOne(marking, transition)?.let { transition.name to it } }

internal fun PetriNet.fireOne(marking: PetriMarking, transition: PetriTransition): PetriMarking? {
    val bound = petriBind(transition, marking, marking, PatQ.ZERO) ?: return null
    val produced = petriProduce(transition, bound.binding)

    // Capacity, by PetriRun's rule: measured against the pre-consumption marking. See the KDoc.
    if (places.any { it.capacity != null }) {
        val extra = HashMap<String, Int>()
        for ((name, tokens) in produced) extra[name] = (extra[name] ?: 0) + tokens.size
        for ((name, count) in extra) {
            val capacity = place(name)?.capacity ?: continue
            if (marking.count(name) + count > capacity) return null
        }
    }

    var next = bound.consumeFrom(marking)
    for ((name, tokens) in produced) next = next.add(name, tokens)
    return next
}

/**
 * The strict state key. Places in name order, and the colours in each place **in arrival order**,
 * compared by their own `equals`.
 *
 * Deliberately not [PetriMarking.signature] — see the file header for the argument, which is the
 * most consequential decision in this file.
 */
private fun PetriMarking.stateKey(): List<Pair<String, List<Any?>>> =
    toMap().entries.sortedBy { it.key }.map { (name, tokens) -> name to tokens.map { it.colour } }

/**
 * [stateKey] as a 64-bit number, for interning states without *keeping* a key per state.
 *
 * A key is as long as the marking, so a map from key to state id costs O(states × marking size) —
 * which for a net with a filling place is quadratic in the budget, and is duplicated information
 * besides, since the search already retains every marking it interned. So the map holds hashes and
 * the search confirms a hit against the real [stateKey] of the state it collided with.
 *
 * Combined in *sequence* rather than commutatively, unlike [PetriMarking.signatureHash]: arrival
 * order inside a place is exactly what [stateKey] refuses to throw away. Colours contribute their
 * own `hashCode`, which is the hash that goes with the `equals` [stateKey] compares them by.
 */
private fun PetriMarking.stateKeyHash(): Long {
    var hash = 1L
    for (place in occupied.sorted()) {
        hash = hash * 1_000_003L + place.hashCode()
        for (token in this[place]) hash = hash * 1_000_003L + (token.colour?.hashCode() ?: 0)
    }
    return hash
}

// ── outcomes ───────────────────────────────────────────────────────────────────────────────

/** Whether a search saw the whole space, and therefore whether its negative answers mean anything. */
enum class PetriSearchOutcome {
    /** Every reachable state was visited. Answers derived from the graph are exact. */
    COMPLETE,

    /**
     * The node budget ran out first, so the graph is a *prefix* of the state space. Everything it
     * found is real; everything it did not find is unknown, and the accessors say `null` rather
     * than `false`.
     */
    BUDGET,
}

/** One labelled edge of a state graph: firing [transition] in state [from] reaches state [to]. */
data class PetriStateEdge(val from: Int, val transition: String, val to: Int)

/** How many markings a search will hold before it gives up and says so. */
const val PETRI_STATE_BUDGET: Int = 20_000

// ── traces ─────────────────────────────────────────────────────────────────────────────────

/**
 * A firing sequence and the markings it passes through — the deliverable of this whole file.
 *
 * "This net can deadlock" is a fact you have to take on trust. `grab.0, grab.1, grab.2, grab.3,
 * grab.4 → nothing is enabled` is a fact you can check, and [verify] checks it: it re-fires the
 * whole sequence from the start marking through the engine's own enabling test and returns a
 * complaint if any step does not go where this trace claims. A counterexample that validates itself
 * against the thing it is a counterexample about is worth several assertions.
 *
 * [markings] is always one longer than [transitions]: the marking before each firing, and the one
 * after the last.
 */
class PetriTrace(
    val net: PetriNet,
    val transitions: List<String>,
    val markings: List<PetriMarking>,
) {
    init {
        require(markings.size == transitions.size + 1) {
            "a trace of ${transitions.size} firings needs ${transitions.size + 1} markings, got ${markings.size}"
        }
    }

    /** How many firings. A shortest counterexample's length is a number worth asserting. */
    val length: Int get() = transitions.size

    /** Where the trace starts — the initial marking, for anything this file produces. */
    val start: PetriMarking get() = markings.first()

    /** Where it ends. For a deadlock trace, the marking that enables nothing. */
    val end: PetriMarking get() = markings.last()

    /**
     * Re-fires the sequence from [start] and checks it lands where claimed, returning null when it
     * does and a description of the first discrepancy when it does not.
     *
     * Uses [PetriNet.fire], which is the engine's enabling test, so this catches a bug in the search
     * — a wrong parent pointer, a state interned under the wrong key — rather than merely restating
     * it. Cheap enough to call in an assertion, and the tests do.
     */
    fun verify(): String? {
        var marking = start
        for ((step, name) in transitions.withIndex()) {
            if (marking != markings[step]) {
                return "step $step: the trace says the marking is ${markings[step]}, replay says $marking"
            }
            marking = net.fire(marking, name)
                ?: return "step $step: '$name' is not enabled at $marking"
        }
        if (marking != end) return "the last marking is ${end}, replay says $marking"
        return null
    }

    /** The firing sequence on one line — `grab.0, grab.1, grab.2` — or `(nothing)` when empty. */
    override fun toString(): String = transitions.joinToString(", ").ifEmpty { "(nothing)" }

    /**
     * The trace with a marking under every firing, for reading rather than for a log line.
     *
     * [ending] is appended after the last marking, which is where a deadlock trace says what it
     * proved: `→ nothing is enabled`.
     */
    fun describe(ending: String? = null): String = buildString {
        append("  ${markings.first()}\n")
        for ((step, name) in transitions.withIndex()) {
            append("  -[ $name ]->  ${markings[step + 1]}\n")
        }
        if (ending != null) append("  $ending\n")
    }
}

// ── the reachability graph ─────────────────────────────────────────────────────────────────

/**
 * Every marking reachable from the net's initial marking, under **every** interleaving, and the
 * labelled edges between them.
 *
 * Built breadth-first, which is not an implementation detail: the parent pointers a BFS leaves
 * behind *are* the shortest paths, so every counterexample this class hands back is a shortest one.
 * "The table can wedge after five grabs" is a sharper statement than "the table can wedge", and it
 * is the same search.
 *
 * State 0 is always the initial marking. Read [outcome] before trusting anything nullable; the
 * accessors that could otherwise lie return null when it is [PetriSearchOutcome.BUDGET].
 */
class PetriStateGraph internal constructor(
    val net: PetriNet,
    val states: List<PetriMarking>,
    val edges: List<PetriStateEdge>,
    val outcome: PetriSearchOutcome,
    val budget: Int,
    /** Whether every interleaving was explored, or a deadlock-sufficient subset of them. */
    val reduction: PetriReduction,
    private val parent: IntArray,
    private val parentVia: Array<String?>,
    private val expanded: BooleanArray,
) {
    /** How many distinct markings were found. Exact when [complete]. */
    val stateCount: Int get() = states.size

    /** How many labelled firings connect them. */
    val edgeCount: Int get() = edges.size

    /** True when the whole space was visited, which is what every negative answer here depends on. */
    val complete: Boolean get() = outcome == PetriSearchOutcome.COMPLETE

    /**
     * True when this graph is the *whole* reachable state space, and therefore when a property other
     * than deadlock can be read off it.
     *
     * [complete] and `exact` differ in exactly one case, and it is the case that would otherwise
     * cause quiet nonsense: a [PetriReduction.STUBBORN] search that finished. It has visited every
     * marking it needed to in order to settle deadlock, and a small fraction of the rest, so its
     * bounds are lower bounds and its "nothing violates this" is not a proof. Every accessor below
     * that would make such a claim tests `exact`, not `complete`.
     */
    val exact: Boolean get() = complete && reduction == PetriReduction.NONE

    private val outgoing: Array<MutableList<PetriStateEdge>> =
        Array(states.size) { mutableListOf<PetriStateEdge>() }.also { table ->
            for (edge in edges) table[edge.from] += edge
        }

    /** How many firings from the initial marking to state [id] — its BFS layer. */
    fun depth(id: Int): Int {
        var steps = 0
        var at = id
        while (at != 0) {
            at = parent[at]
            steps++
        }
        return steps
    }

    /** The deepest BFS layer in the graph — how far the search got. */
    val diameterFound: Int get() = states.indices.maxOfOrNull { depth(it) } ?: 0

    /** The shortest firing sequence from the initial marking to state [id]. */
    fun pathTo(id: Int): PetriTrace {
        val names = ArrayList<String>()
        val visited = ArrayList<Int>()
        var at = id
        while (at != 0) {
            visited += at
            names += parentVia[at] ?: error("state $at has no parent transition")
            at = parent[at]
        }
        visited += 0
        return PetriTrace(net, names.reversed(), visited.reversed().map { states[it] })
    }

    // ── deadlock ───────────────────────────────────────────────────────────────────────────

    /**
     * States that were fully expanded and enable nothing — the reachable deadlocks.
     *
     * Sound even under [PetriSearchOutcome.BUDGET]: a state is only listed once every transition
     * was tried against it, so a truncated search under-reports rather than inventing.
     */
    val deadMarkings: List<Int> get() = states.indices.filter { expanded[it] && outgoing[it].isEmpty() }

    /**
     * The shortest firing sequence from the initial marking to a reachable deadlock, or null if
     * there is none — **which only means "there is none" when [complete]**.
     *
     * This is the answer to the question `willDeadlock` cannot ask. For the symmetric dining table
     * it is every philosopher picking up one fork:
     *
     *     grab.0, grab.1, grab.2, grab.3, grab.4  →  nothing is enabled
     */
    fun deadlockTrace(): PetriTrace? = deadMarkings.minByOrNull { depth(it) }?.let { pathTo(it) }

    /**
     * True when some interleaving wedges, false when none can, null when the search was truncated
     * and the honest answer is that nobody knows.
     *
     * The three-valued return is the point. `PetriNet.willDeadlock` is a `Boolean` because it
     * answers a question about one run, which always has an answer.
     */
    val canDeadlock: Boolean?
        get() = when {
            deadMarkings.isNotEmpty() -> true
            complete -> false
            else -> null
        }

    // ── boundedness ────────────────────────────────────────────────────────────────────────

    /** The largest token count seen in [place] anywhere in the graph. A lower bound, always. */
    fun observedBound(place: String): Int = states.maxOfOrNull { it.count(place) } ?: 0

    /** The exact bound on [place] — its maximum over all reachable markings — or null if unknown. */
    fun bound(place: String): Int? = if (exact) observedBound(place) else null

    /** Every place's exact bound, or null when the search was truncated or reduced. */
    val bounds: Map<String, Int>?
        get() = if (exact) net.placeNames.associateWith { observedBound(it) } else null

    /** True when no place ever holds more than one token — the net is 1-bounded. Null if unknown. */
    val isSafe: Boolean? get() = bounds?.values?.all { it <= 1 }

    // ── liveness ───────────────────────────────────────────────────────────────────────────

    /**
     * Transitions that fire in *some* reachable marking — the honest version of
     * [PetriNet.deadTransitions], which reports the ones that did not fire in one particular run.
     *
     * Always sound: everything listed really is fireable, whatever the outcome.
     */
    val quasiLive: Set<String> get() = edges.mapTo(LinkedHashSet()) { it.transition }

    /**
     * Transitions that can never fire under any interleaving, or null when the search was truncated
     * — because a transition nobody reached might be waiting in a marking nobody visited.
     *
     * Worth comparing against [PetriNet.deadTransitions] on the same net: on an asymmetric dining
     * table under [PetriConflict.ORDER] the two disagree, and the disagreement is the whole point.
     * `deadTransitions` names the starved philosopher, because under that policy the contested fork
     * always goes to the first claimant. This returns nothing, because *some* interleaving feeds
     * everybody. Starvation is a property of the policy; deadness is a property of the net.
     */
    fun neverFires(): List<String>? =
        if (exact) net.transitionNames.filter { it !in quasiLive } else null

    private val scc: IntArray by lazy { tarjanComponents(states.size, outgoing) }

    private val componentCount: Int by lazy { (scc.maxOrNull() ?: -1) + 1 }

    /**
     * The strongly-connected components with no edge leaving them — the places a run can end up and
     * never get out of.
     *
     * Every finite run ends inside one of these, which is what makes them the right thing to reason
     * about liveness with. A bottom component that is a single state with no self-loop *is* a
     * deadlock.
     */
    private val bottomComponents: List<Int> by lazy {
        val leaks = BooleanArray(componentCount)
        for (edge in edges) if (scc[edge.from] != scc[edge.to]) leaks[scc[edge.from]] = true
        (0 until componentCount).filter { !leaks[it] }
    }

    /**
     * Transitions that stay possible forever: from every reachable marking, some continuation fires
     * them. Null when the search was truncated.
     *
     * Computed from the bottom components rather than by re-searching from each state, which is the
     * standard characterisation and is linear: a run eventually enters some bottom component and
     * can then never leave it, so a transition is live exactly when **every** bottom component
     * contains an edge labelled with it. A deadlock is a bottom component with no edges at all, so
     * one deadlock makes everything not-live, which is the right answer.
     */
    val liveTransitions: Set<String>?
        get() {
            if (!exact) return null
            if (bottomComponents.isEmpty()) return emptySet()
            val perComponent = bottomComponents.map { component ->
                edges.filter { scc[it.from] == component }.mapTo(HashSet()) { it.transition }
            }
            return perComponent.reduce { a, b -> a.intersect(b).toHashSet() }
                .let { live -> net.transitionNames.filterTo(LinkedHashSet()) { it in live } }
        }

    /** True when every transition is live — the classical L4-liveness. Null when unknown. */
    val isLive: Boolean? get() = liveTransitions?.let { it.size == net.transitions.size }

    // ── getting home ───────────────────────────────────────────────────────────────────────

    /**
     * True when the initial marking is reachable from every reachable marking: the net can always
     * be put back. Null when the search was truncated.
     *
     * Answered by a breadth-first search over the *reversed* edges from state 0, which is the
     * direct reading of the definition and needs no component machinery.
     */
    val isReversible: Boolean?
        get() {
            if (!exact) return null
            return reachesInitial().all { it }
        }

    /**
     * Markings reachable from every reachable marking — the states the net can always get back to.
     *
     * There is a set of them exactly when the graph has a single bottom component, in which case it
     * is that component; two bottom components means two futures with no marking in common, and no
     * home markings at all. Null when the search was truncated.
     */
    val homeMarkings: List<Int>?
        get() {
            if (!exact) return null
            if (bottomComponents.size != 1) return emptyList()
            val only = bottomComponents.single()
            return states.indices.filter { scc[it] == only }
        }

    private fun reachesInitial(): BooleanArray {
        val incoming = Array(states.size) { mutableListOf<Int>() }
        for (edge in edges) incoming[edge.to] += edge.from
        val seen = BooleanArray(states.size)
        val queue = ArrayDeque<Int>()
        seen[0] = true
        queue += 0
        while (queue.isNotEmpty()) {
            for (from in incoming[queue.removeFirst()]) if (!seen[from]) {
                seen[from] = true
                queue += from
            }
        }
        return seen
    }

    // ── queries with counterexamples ───────────────────────────────────────────────────────

    /**
     * The shortest firing sequence to a reachable marking satisfying [predicate], or null if none
     * was found — *proof* that none exists only when [complete].
     *
     * This is `EF φ` with the witness attached, and it is the general form of every question below.
     * The shortest route to five tokens in one place:
     *
     *     graph.witness { it.count("queue") >= 5 }
     */
    fun witness(predicate: (PetriMarking) -> Boolean): PetriTrace? =
        states.indices.filter { predicate(states[it]) }.minByOrNull { depth(it) }?.let { pathTo(it) }

    /**
     * The shortest firing sequence that *breaks* [predicate], or null when nothing does — which is
     * a proof of the invariant when [complete].
     *
     * `AG φ`, expressed as [witness] of its negation, because an invariant is only interesting when
     * you can be shown how it fails.
     */
    fun invariant(predicate: (PetriMarking) -> Boolean): PetriTrace? = witness { !predicate(it) }

    /**
     * True when [predicate] holds in every reachable marking, false with a counterexample available
     * from [invariant], and null when neither can be claimed — the search was truncated or reduced.
     *
     * The three-valued form of [invariant], for the same reason [canDeadlock] is three-valued: a
     * null from `invariant` is only a *proof* when the graph is [exact].
     */
    fun proves(predicate: (PetriMarking) -> Boolean): Boolean? = when {
        invariant(predicate) != null -> false
        exact -> true
        else -> null
    }

    /**
     * The shortest firing sequence that puts tokens in two or more of [places] at once, or null when
     * no reachable marking does.
     *
     * The property a critical section is built to have, and the one thing the state space can prove
     * about a net whose exclusion is enforced by an **inhibitor arc** — `pInvariants` cannot see
     * that arc at all, because it moves no token, so the algebra returns no conservation law and
     * the search returns a proof.
     */
    fun mutualExclusionFailure(vararg places: String): PetriTrace? =
        invariant { marking -> places.count { marking.count(it) > 0 } <= 1 }

    /** True when [places] never hold tokens simultaneously; null when the search was truncated. */
    fun mutuallyExclusive(vararg places: String): Boolean? = when {
        mutualExclusionFailure(*places) != null -> false
        exact -> true
        else -> null
    }

    // ── the paragraph ──────────────────────────────────────────────────────────────────────

    /**
     * The classical properties in a paragraph — what to print at a net you are about to trust.
     *
     *     println(net.stateSpace().report())
     */
    fun report(): String = buildString {
        val scope = when {
            !complete -> "a $stateCount-state prefix (budget $budget)"
            reduction == PetriReduction.STUBBORN -> "a deadlock-sufficient subset (stubborn sets)"
            else -> "the whole state space"
        }
        append("state space: $stateCount markings, $edgeCount firings, $scope\n")
        if (complete && reduction == PetriReduction.STUBBORN) {
            append("  partial-order reduction: deadlock is exact here, nothing else is\n")
        }
        if (!complete) {
            append("  BUDGET EXHAUSTED — every 'no' below is 'unknown'. Raise the budget or bound the net.\n")
        }

        when (canDeadlock) {
            true -> {
                val trace = deadlockTrace()!!
                append("  DEADLOCK reachable in ${trace.length} firings: $trace\n")
                append("  stuck at ${trace.end}\n")
            }
            false -> append("  deadlock-free: no reachable marking enables nothing\n")
            null -> append("  deadlock: unknown — none found in the states visited\n")
        }

        bounds?.let { table ->
            val worst = table.maxByOrNull { it.value }
            append(
                if (isSafe == true) "  safe: no place ever holds more than one token\n"
                else "  bounded: ${worst?.value ?: 0} at worst, in '${worst?.key}'\n"
            )
        }

        neverFires()?.let { dead ->
            append(
                if (dead.isEmpty()) "  quasi-live: every transition fires in some reachable marking\n"
                else "  never fires under any interleaving: ${dead.joinToString(", ")}\n"
            )
        }
        isLive?.let { live ->
            if (live) append("  live: every transition stays possible forever\n")
            else liveTransitions?.let { alive ->
                val lost = net.transitionNames.filter { it !in alive }
                append("  not live — these can be lost for good: ${lost.joinToString(", ")}\n")
            }
        }
        isReversible?.let { append("  ${if (it) "reversible: the initial marking is always reachable again" else "not reversible: the initial marking cannot always be recovered"}\n") }
    }

    override fun toString(): String =
        "PetriStateGraph($stateCount states, $edgeCount edges, $outcome, $reduction)"
}

/**
 * Every marking reachable from [PetriNet.initial] under every interleaving, breadth-first, stopping
 * at [budget] markings.
 *
 * Timing, grid and conflict policy are deliberately discarded — see [PetriNet.fire]. What comes back
 * is a fact about the *net*, not about the way it happens to be being played.
 *
 * The budget is a hard ceiling on states, not a hint. Hitting it produces a graph flagged
 * [PetriSearchOutcome.BUDGET] whose negative answers are all null; it never produces a wrong
 * "safe".
 */
fun PetriNet.stateSpace(
    budget: Int = PETRI_STATE_BUDGET,
    reduction: PetriReduction = PetriReduction.NONE,
): PetriStateGraph {
    require(budget >= 1) { "a state-space budget must be at least 1" }

    // A colour-dependent net cannot be reduced: the closure reasons about arcs, and a guard or an
    // `enabledIf` is an arbitrary predicate no arc analysis can see. Falling back to the full search
    // is the only safe answer, and it is recorded on the graph rather than assumed by the caller.
    @Suppress("NAME_SHADOWING")
    val reduction = if (reduction == PetriReduction.STUBBORN && isColourDependent) PetriReduction.NONE else reduction
    val arcs = if (reduction == PetriReduction.STUBBORN) PetriArcIndex(this) else null

    val states = ArrayList<PetriMarking>()
    val parent = ArrayList<Int>()
    val parentVia = ArrayList<String?>()
    val index = HashMap<Long, MutableList<Int>>()
    val edges = ArrayList<PetriStateEdge>()
    val expanded = ArrayList<Boolean>()

    // Interning is by hash and then by confirmation: see [stateKeyHash] for why the map cannot
    // afford to hold the keys themselves. A collision costs two key builds and no wrong answers.
    fun lookup(marking: PetriMarking, hash: Long): Int? {
        val candidates = index[hash] ?: return null
        val key = marking.stateKey()
        return candidates.firstOrNull { states[it].stateKey() == key }
    }

    fun intern(marking: PetriMarking, hash: Long, from: Int, via: String?): Int {
        val id = states.size
        states += marking
        parent += from
        parentVia += via
        expanded += false
        index.getOrPut(hash) { ArrayList(1) } += id
        return id
    }

    intern(initial, initial.stateKeyHash(), 0, null)

    var head = 0
    var truncated = false
    expansion@ while (head < states.size) {
        val marking = states[head]
        val moves = transitions.mapNotNull { transition -> fireOne(marking, transition)?.let { transition to it } }
        val chosen = if (arcs == null) moves else {
            val fireable = stubbornEnabled(arcs, marking, moves.map { it.first })
            moves.filter { it.first in fireable }
        }
        for ((transition, next) in chosen) {
            val hash = next.stateKeyHash()
            val to = lookup(next, hash) ?: run {
                if (states.size >= budget) return@run null
                intern(next, hash, head, transition.name)
            }
            if (to == null) {
                // Out of room mid-expansion. The state stays un-expanded, so it will not be
                // mistaken for a deadlock, and the edges already recorded remain true.
                truncated = true
                break@expansion
            }
            edges += PetriStateEdge(head, transition.name, to)
        }
        expanded[head] = true
        head++
    }

    return PetriStateGraph(
        net = this,
        states = states,
        edges = edges,
        outcome = if (truncated) PetriSearchOutcome.BUDGET else PetriSearchOutcome.COMPLETE,
        budget = budget,
        reduction = reduction,
        parent = parent.toIntArray(),
        parentVia = parentVia.toTypedArray(),
        expanded = expanded.toBooleanArray(),
    )
}

/**
 * Tarjan's strongly-connected components, iteratively.
 *
 * Iterative rather than the textbook recursion because a state graph is routinely twenty thousand
 * nodes deep in a chain and the recursive form overflows the JVM stack somewhere in the low
 * thousands — a crash that would only ever show up on the large nets this file exists for.
 */
private fun tarjanComponents(count: Int, outgoing: Array<MutableList<PetriStateEdge>>): IntArray {
    val order = IntArray(count) { -1 }
    val low = IntArray(count)
    val onStack = BooleanArray(count)
    val component = IntArray(count) { -1 }
    val pending = ArrayDeque<Int>()
    val frames = ArrayDeque<IntArray>()
    var counter = 0
    var components = 0

    for (root in 0 until count) {
        if (order[root] != -1) continue
        order[root] = counter
        low[root] = counter
        counter++
        pending.addLast(root)
        onStack[root] = true
        frames.addLast(intArrayOf(root, 0))

        while (frames.isNotEmpty()) {
            val frame = frames.last()
            val node = frame[0]
            if (frame[1] < outgoing[node].size) {
                val next = outgoing[node][frame[1]++].to
                if (order[next] == -1) {
                    order[next] = counter
                    low[next] = counter
                    counter++
                    pending.addLast(next)
                    onStack[next] = true
                    frames.addLast(intArrayOf(next, 0))
                } else if (onStack[next] && order[next] < low[node]) {
                    low[node] = order[next]
                }
            } else {
                frames.removeLast()
                frames.lastOrNull()?.let { above -> if (low[node] < low[above[0]]) low[above[0]] = low[node] }
                if (low[node] == order[node]) {
                    while (true) {
                        val popped = pending.removeLast()
                        onStack[popped] = false
                        component[popped] = components
                        if (popped == node) break
                    }
                    components++
                }
            }
        }
    }
    return component
}

// ── the coverability graph ─────────────────────────────────────────────────────────────────

/** The ω of a coverability graph: "this place grows without bound". */
const val PETRI_OMEGA: Int = Int.MAX_VALUE

/**
 * A marking in which a place may hold ω tokens — arbitrarily many, as many as you like.
 *
 * ω is the whole content of the Karp–Miller construction: a net that can pump a place is described
 * by one node saying so rather than by an infinite chain of nodes counting up to it, which is how
 * an infinite state space gets a finite picture.
 */
class PetriOmegaMarking internal constructor(internal val counts: Map<String, Int>) {

    /** Tokens in [place], or [PETRI_OMEGA]. */
    operator fun get(place: String): Int = counts[place] ?: 0

    /** True when [place] holds ω. */
    fun isOmega(place: String): Boolean = counts[place] == PETRI_OMEGA

    /** Every place holding ω here. */
    val omegaPlaces: Set<String> get() = counts.filterValues { it == PETRI_OMEGA }.keys

    /** True when this marking is at least [other] everywhere — ω covers anything. */
    internal fun covers(other: PetriOmegaMarking): Boolean =
        (counts.keys + other.counts.keys).all { this[it] == PETRI_OMEGA || this[it] >= other[it] }

    internal fun strictlyCovers(other: PetriOmegaMarking): Boolean = covers(other) && counts != other.counts

    override fun equals(other: Any?): Boolean = other is PetriOmegaMarking && other.counts == counts

    override fun hashCode(): Int = counts.hashCode()

    override fun toString(): String =
        if (counts.isEmpty()) "∅"
        else counts.entries.sortedBy { it.key }
            .joinToString(", ") { (name, n) -> "$name:" + if (n == PETRI_OMEGA) "ω" else "$n" }
}

/**
 * A Karp–Miller coverability graph: finite for **every** net, at the cost of only being exact about
 * covering rather than about reachability.
 *
 * The reason to have it next to [PetriStateGraph]: an unbounded net has an infinite state space, so
 * the exhaustive search can only ever report [PetriSearchOutcome.BUDGET] on one — it cannot tell you
 * *which* place is the leak, which is the thing you wanted to know. This can, and terminates while
 * doing it.
 *
 * ### When it is not to be believed
 *
 * The acceleration to ω rests on a monotonicity argument — if a firing sequence takes a marking to
 * something strictly larger, repeating it grows those places forever. Three features of this model
 * break that argument, and each one adds an entry to [caveats] rather than being silently ignored:
 *
 * - **inhibitor arcs**, which make more tokens *disable* a transition. Reachability for nets with
 *   inhibitor arcs is undecidable, so no terminating construction can be exact about them;
 * - **place capacities**, which are an inhibitor arc written another way — a repetition that would
 *   overflow is blocked, so it cannot be repeated forever;
 * - **colour**, because this construction counts tokens and cannot see guards, `enabledIf` or
 *   colour-dependent production, all of which can only remove behaviour.
 *
 * [sound] is the conjunction of their absence. When it is false, use [PetriNet.stateSpace], which
 * handles all three exactly and merely might not finish.
 */
class PetriCoverabilityGraph internal constructor(
    val net: PetriNet,
    val nodes: List<PetriOmegaMarking>,
    val edges: List<PetriStateEdge>,
    val outcome: PetriSearchOutcome,
    val caveats: List<String>,
) {
    /** True when Karp–Miller's assumptions actually hold for this net. See the class KDoc. */
    val sound: Boolean get() = caveats.isEmpty()

    /** Places that can hold arbitrarily many tokens. */
    val unboundedPlaces: List<String> get() = net.placeNames.filter { place -> nodes.any { it.isOmega(place) } }

    /** True when no place is unbounded. */
    val isBounded: Boolean get() = unboundedPlaces.isEmpty()

    /**
     * The bound on [place]: the largest number of tokens it can hold, or null when it is unbounded.
     *
     * Note the direction of the null: here it means ω, not "unknown". Karp–Miller's whole point is
     * that unboundedness is an *answer*.
     */
    fun bound(place: String): Int? =
        if (nodes.any { it.isOmega(place) }) null else nodes.maxOfOrNull { it[place] } ?: 0

    /** One paragraph: how big the graph is, what grows, and whether to believe any of it. */
    fun report(): String = buildString {
        append("coverability: ${nodes.size} ω-markings, ${edges.size} firings")
        append(if (outcome == PetriSearchOutcome.COMPLETE) "\n" else " (budget exhausted)\n")
        append(
            if (isBounded) "  bounded: " + net.placeNames.joinToString(", ") { "$it≤${bound(it)}" } + "\n"
            else "  unbounded: ${unboundedPlaces.joinToString(", ")}\n"
        )
        for (caveat in caveats) append("  NOT SOUND — $caveat\n")
    }

    override fun toString(): String = "PetriCoverabilityGraph(${nodes.size} nodes, $outcome, sound=$sound)"
}

/**
 * The Karp–Miller coverability graph: a finite over-approximation of the state space in which a
 * place that can grow without bound is marked ω.
 *
 * The construction, in one paragraph. Explore breadth-first from the initial marking. Whenever a new
 * marking strictly covers one of its own ancestors, every place that grew becomes ω, because the
 * sequence that grew it can be repeated. Dickson's lemma says an infinite branch must eventually
 * contain such a pair, and each acceleration adds an ω that never goes away, so no branch can run
 * forever: the graph is finite for every net.
 *
 * Identical nodes are merged, which is what keeps the result small enough to look at. The price is
 * that a merged node's ancestor chain is the path it was *first* discovered by rather than the one
 * currently being explored, so a pathological net can need [budget] after all — reported, as ever,
 * rather than hidden.
 *
 * Read [PetriCoverabilityGraph.sound] before believing the answer for a net with inhibitor arcs,
 * place capacities or coloured tokens.
 */
fun PetriNet.coverability(budget: Int = PETRI_STATE_BUDGET): PetriCoverabilityGraph {
    require(budget >= 1) { "a coverability budget must be at least 1" }

    val caveats = mutableListOf<String>()
    if (transitions.any { transition -> transition.inputs.any { it.kind == PetriArcKind.INHIBIT } }) {
        caveats += "this net has inhibitor arcs; more tokens can disable a transition, so ω decides nothing"
    }
    if (places.any { it.capacity != null }) {
        caveats += "this net has bounded places, which block the repetition ω is an abstraction of"
    }
    if (isColourDependent) {
        caveats += "this net's behaviour depends on token colour, which a count abstraction cannot see"
    }

    val nodes = ArrayList<PetriOmegaMarking>()
    val parent = ArrayList<Int>()
    val index = HashMap<PetriOmegaMarking, Int>()
    val edges = ArrayList<PetriStateEdge>()

    fun intern(marking: PetriOmegaMarking, from: Int): Int {
        val id = nodes.size
        nodes += marking
        parent += from
        index[marking] = id
        return id
    }

    intern(PetriOmegaMarking(initial.toCounts().filterValues { it > 0 }), 0)

    var head = 0
    var truncated = false
    expansion@ while (head < nodes.size) {
        val marking = nodes[head]
        for (transition in transitions) {
            var next = omegaFire(marking, transition) ?: continue

            // Acceleration. Walk this node's ancestors; any place that strictly grew since an
            // ancestor this marking covers can be pumped, so it becomes ω. Repeat to a fixpoint,
            // because setting one place to ω can make a further ancestor covered.
            var again = true
            while (again) {
                again = false
                var ancestor = head
                while (true) {
                    val seen = nodes[ancestor]
                    if (next.strictlyCovers(seen)) {
                        val lifted = HashMap(next.counts)
                        var grew = false
                        for (place in placeNames) {
                            // A place with a capacity is bounded by it, whatever the pumping
                            // argument says — the firing that would overflow is simply refused. So
                            // ω is never assigned there, which makes the answer for that place
                            // right even on a net the construction is otherwise unsound for. The
                            // caveat still stands for its neighbours: a blocked repetition can stop
                            // some *other* place growing too, and this cannot see that.
                            if (place(place)?.capacity != null) continue
                            if (next[place] != PETRI_OMEGA && next[place] > seen[place]) {
                                lifted[place] = PETRI_OMEGA
                                grew = true
                            }
                        }
                        if (grew) {
                            next = PetriOmegaMarking(lifted.filterValues { it != 0 })
                            again = true
                        }
                    }
                    if (ancestor == 0) break
                    ancestor = parent[ancestor]
                }
            }

            val to = index[next] ?: run {
                if (nodes.size >= budget) return@run null
                intern(next, head)
            }
            if (to == null) {
                truncated = true
                break@expansion
            }
            edges += PetriStateEdge(head, transition.name, to)
        }
        head++
    }

    return PetriCoverabilityGraph(
        net = this,
        nodes = nodes,
        edges = edges,
        outcome = if (truncated) PetriSearchOutcome.BUDGET else PetriSearchOutcome.COMPLETE,
        caveats = caveats,
    )
}

/**
 * True when anything about this net's behaviour depends on what colour its tokens are — a guard, an
 * `enabledIf`, an explicit `produce`, or a coloured token in the initial marking.
 *
 * The condition under which a token *count* is a faithful abstraction of a marking, which is what
 * [PetriNet.coverability] needs and what [PetriNet.stateSpace] does not.
 */
val PetriNet.isColourDependent: Boolean
    get() = initial.toMap().values.any { tokens -> tokens.any { !it.isBlack } } ||
        transitions.any { transition ->
            transition.enabledIf != null ||
                transition.inputs.any { it.guard != null } ||
                transition.outputs.any { it.produce != null }
        }

/** One transition against an ω-marking, counting tokens only. Null when it cannot fire. */
private fun PetriNet.omegaFire(marking: PetriOmegaMarking, transition: PetriTransition): PetriOmegaMarking? {
    for (arc in transition.inputs) {
        val held = marking[arc.place]
        when (arc.kind) {
            PetriArcKind.INHIBIT -> if (held == PETRI_OMEGA || held >= arc.weight) return null
            else -> if (held != PETRI_OMEGA && held < arc.weight) return null
        }
    }
    val counts = HashMap(marking.counts)
    for (arc in transition.inputs) {
        if (arc.kind != PetriArcKind.NORMAL) continue
        val held = counts[arc.place] ?: 0
        if (held != PETRI_OMEGA) counts[arc.place] = held - arc.weight
    }
    for (arc in transition.outputs) {
        val held = counts[arc.place] ?: 0
        if (held == PETRI_OMEGA) continue
        val capacity = place(arc.place)?.capacity
        if (capacity != null && marking[arc.place] != PETRI_OMEGA && marking[arc.place] + arc.weight > capacity) return null
        counts[arc.place] = held + arc.weight
    }
    return PetriOmegaMarking(counts.filterValues { it != 0 })
}

// ── partial-order reduction ────────────────────────────────────────────────────────────────

/**
 * Whether the search explores every interleaving or a sufficient subset of them.
 *
 * The state space of a concurrent net is exponential in the number of *independent* components, and
 * almost all of that size is the same few firings shuffled into a different order. Five philosophers
 * reaching for their left fork is one event five times over; a full search visits all 5! = 120
 * orderings of it. A **partial-order reduction** explores one ordering and proves that is enough.
 */
enum class PetriReduction {
    /** Explore every interleaving. Every property in [PetriStateGraph] is exact. */
    NONE,

    /**
     * Explore a *stubborn set* at each marking — a subset of the enabled transitions sufficient to
     * preserve **deadlock reachability**, and nothing else.
     *
     * The theorem (Valmari) is about terminal markings only, so a graph built this way answers
     * [PetriStateGraph.canDeadlock] and [PetriStateGraph.deadlockTrace] and refuses everything else:
     * its state count is not the reachable state count, so a bound read off it would be a lower
     * bound wearing the word "exact". [PetriStateGraph.exact] is false for such a graph and every
     * accessor that would need exactness returns null.
     *
     * One thing the theorem does *not* promise is the shortest path. A reduced search reaches a
     * deadlock that really is reachable, by a route that is at least as long as the true shortest
     * one and possibly longer. Every trace it hands back is still a real firing sequence and still
     * passes [PetriTrace.verify]; it is just not guaranteed minimal.
     */
    STUBBORN,
}

/**
 * Which transitions can affect which places, precomputed once per net.
 *
 * The stubborn-set closure asks "who could enable this?" and "who could disable that?" once per
 * transition per marking, and both questions are answered from the arcs alone. Computing the
 * incidence relation once and reusing it is the difference between the reduction paying for itself
 * and costing more than it saves.
 */
private class PetriArcIndex(net: PetriNet) {

    /** Transitions with a consuming (NORMAL) input arc on this place — they can lower its count. */
    val consumers: Map<String, List<PetriTransition>> = net.places.associate { place ->
        place.name to net.transitions.filter { transition ->
            transition.inputs.any { it.place == place.name && it.kind == PetriArcKind.NORMAL }
        }
    }

    /** Transitions with an output arc on this place — they can raise its count. */
    val producers: Map<String, List<PetriTransition>> = net.places.associate { place ->
        place.name to net.transitions.filter { transition -> transition.outputs.any { it.place == place.name } }
    }

    /** Total tokens each transition consumes from each place — two arcs on one place add up. */
    val needs: Map<String, Map<String, Int>> = net.transitions.associate { transition ->
        transition.name to transition.inputs
            .filter { it.kind == PetriArcKind.NORMAL }
            .groupBy { it.place }
            .mapValues { (_, arcs) -> arcs.sumOf { it.weight } }
    }

    /** The strongest read requirement per place. */
    val reads: Map<String, Map<String, Int>> = net.transitions.associate { transition ->
        transition.name to transition.inputs
            .filter { it.kind == PetriArcKind.READ }
            .groupBy { it.place }
            .mapValues { (_, arcs) -> arcs.maxOf { it.weight } }
    }

    /** The tightest inhibitor limit per place — the count at or above which the transition is blocked. */
    val inhibits: Map<String, Map<String, Int>> = net.transitions.associate { transition ->
        transition.name to transition.inputs
            .filter { it.kind == PetriArcKind.INHIBIT }
            .groupBy { it.place }
            .mapValues { (_, arcs) -> arcs.minOf { it.weight } }
    }

    /** Total tokens each transition produces into each capped place. */
    val fills: Map<String, Map<String, Int>> = net.transitions.associate { transition ->
        transition.name to transition.outputs
            .filter { net.place(it.place)?.capacity != null }
            .groupBy { it.place }
            .mapValues { (_, arcs) -> arcs.sumOf { it.weight } }
    }

    /**
     * Everything that could make [transition] stop being enabled: whoever can drain a place it
     * consumes from or tests, whoever can arm a place that inhibits it, and whoever can fill a
     * capped place it writes to.
     *
     * The classical condition only names the first of those, because the classical net has only the
     * first kind of arc. The other two are this model's, and leaving them out would be exactly the
     * kind of quiet unsoundness the rest of this file is written to avoid.
     */
    fun mayDisable(transition: PetriTransition): List<PetriTransition> = buildList {
        for (arc in transition.inputs) when (arc.kind) {
            PetriArcKind.NORMAL, PetriArcKind.READ -> consumers[arc.place]?.let { addAll(it) }
            PetriArcKind.INHIBIT -> producers[arc.place]?.let { addAll(it) }
        }
        for (place in fills[transition.name].orEmpty().keys) producers[place]?.let { addAll(it) }
    }

    /**
     * One reason [transition] cannot fire at [marking], expressed as the transitions that could
     * remove that reason — or null when no single arc explains it, in which case the caller must
     * not reduce.
     *
     * Returning null rather than guessing is the whole safety margin here. A disabled transition
     * whose blocking place the closure cannot name is a transition the reduction cannot reason
     * about, and the correct response is to explore everything at that marking.
     */
    fun mayEnable(transition: PetriTransition, marking: PetriMarking, net: PetriNet): List<PetriTransition>? {
        for ((place, weight) in needs[transition.name].orEmpty()) {
            if (marking.count(place) < weight) return producers[place].orEmpty()
        }
        for ((place, weight) in reads[transition.name].orEmpty()) {
            if (marking.count(place) < weight) return producers[place].orEmpty()
        }
        for ((place, weight) in inhibits[transition.name].orEmpty()) {
            if (marking.count(place) >= weight) return consumers[place].orEmpty()
        }
        for ((place, weight) in fills[transition.name].orEmpty()) {
            val capacity = net.place(place)?.capacity ?: continue
            if (marking.count(place) + weight > capacity) return consumers[place].orEmpty()
        }
        return null
    }
}

/**
 * A stubborn set's enabled members at [marking], or the whole enabled set when the net cannot be
 * reduced safely here.
 *
 * The closure: seed with one enabled transition; for every enabled member add everything that could
 * disable it, for every disabled member add everything that could remove one specific reason it is
 * blocked, and repeat. The result is a strong stubborn set containing at least one enabled
 * transition, which is exactly the hypothesis of the deadlock-preservation theorem.
 *
 * Every enabled transition is tried as the seed and the smallest answer wins, because the reduction
 * is only worth its cost when the set it finds is small and which seed produces the small one is not
 * predictable from the net.
 */
private fun PetriNet.stubbornEnabled(
    index: PetriArcIndex,
    marking: PetriMarking,
    enabled: List<PetriTransition>,
): List<PetriTransition> {
    if (enabled.size <= 1) return enabled
    val enabledNames = enabled.mapTo(HashSet()) { it.name }
    var best: List<PetriTransition>? = null

    for (seed in enabled) {
        val closed = LinkedHashSet<PetriTransition>()
        val work = ArrayDeque<PetriTransition>()
        closed += seed
        work += seed
        var usable = true
        while (work.isNotEmpty()) {
            val transition = work.removeFirst()
            val more = if (transition.name in enabledNames) {
                index.mayDisable(transition)
            } else {
                index.mayEnable(transition, marking, this) ?: run { usable = false; null }
            } ?: break
            for (other in more) if (closed.add(other)) work += other
        }
        if (!usable) return enabled
        val fireable = enabled.filter { it in closed }
        if (best == null || fireable.size < best.size) best = fireable
        if (best.size == 1) break
    }
    return best ?: enabled
}

// ── the three different statements ─────────────────────────────────────────────────────────

/**
 * The classical analysis in a paragraph, alongside the deterministic one from
 * [PetriNet.report].
 *
 * Kept as its own function as well as being folded into `report()` so that a caller who only wants
 * the expensive half can ask for it, and choose its budget.
 */
fun PetriNet.stateSpaceReport(budget: Int = PETRI_STATE_BUDGET): String = stateSpace(budget).report()
