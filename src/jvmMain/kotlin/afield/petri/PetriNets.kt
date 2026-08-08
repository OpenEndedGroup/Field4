@file:Suppress("MemberVisibilityCanBePrivate", "unused")
@file:afield.CompletionCategory("Visualization")

package afield.petri

import afield.four.synth.PatArc
import afield.four.synth.PatQ
import afield.four.synth.splitmix64
import afield.four.synth.toUnitDouble

/*
 Petri nets: places hold tokens, transitions move them, and the whole thing is a clock.

 This file is the model and the engine. It knows nothing about music — the only thing it borrows
 from `afield.four.synth` is [PatQ], the exact rational used for time everywhere in Field, because
 duplicating a rational type would be worse than the import. `PetriMusic.kt` is what turns a net
 into a `PatPattern`; `PetriVisual.kt` is what draws one; `PetriNotation.kt` parses the `#petri`
 text form. Start here, though: everything else is a view onto these types.

 Read the surface from the outside in:

 1. Build a net. Places are auto-declared on first mention, so a two-transition loop is four lines:

        val net = petri {
            place("kick", tokens = 1)
            t("hit",  dur = cyc(1, 16), emit = "bd") { from("kick"); to("hats") }
            t("back", dur = cyc(1, 16), emit = "sd") { from("hats"); to("kick") }
        }

 2. Run it. A [PetriRun] is a memoised, append-only trajectory — ask it for any instant and it
    replays deterministically up to there:

        net.run().stepsUntil(PatQ.of(4)).flatMap { it.firings }.forEach(::println)

 3. Tokens carry colour, and colour can be anything — a note number, a sample name, a whole
    `PatPattern`. That is what makes a net a song structure rather than a drum machine:

        place("verse", colours = listOf(pat("bd*4")))
        t("build", dur = cyc(4)) { from("verse"); from("hats"); to("chorus") { b -> ... } }

 Three timings, and why
 ----------------------

 - [PetriTiming.STEP] — **maximal step**. Every transition that is enabled at a grid tick fires,
   all against the marking as it stood at the start of the tick, and produced tokens do not
   become available until the tick is over. This is the drum-machine semantics, and the one that
   matches `BooleanNetworkSequencing`'s notion of a step. Conflicts (two transitions wanting the
   same token) are resolved greedily in [PetriConflict] order.

 - [PetriTiming.EVENT] — one transition per tick, the first in conflict order. Interleaving
   semantics; useful when you want a net to *walk* rather than pulse.

 - [PetriTiming.TIMED] — firing takes time. A transition consumes its inputs at the instant it
   starts and produces its outputs `duration` later, so a token can be *in flight*, and a
   transition with `dur = cyc(4)` genuinely holds a four-cycle section open. Time advances to the
   next instant at which something happens, not on a grid.

 In STEP and EVENT, `duration` does not affect token flow at all — it only sets how long the
 emitted event lasts. In all three, `delay` shifts the emitted event and nothing else, so you can
 nudge a hit late without perturbing the net's logic.

 Determinism, which is load-bearing
 ----------------------------------

 `PetriMusic.kt` presents a net as a `PatPattern`, and a `PatPattern` must be a *pure function of
 time* — the scheduler queries it cycle by cycle and every combinator in the language is free to
 query it again, out of order. That only works if the trajectory is reproducible, so:

 - tokens are consumed FIFO, oldest first, never "some matching token";
 - [PetriConflict.RANDOM] hashes `(seed, step index, transition name)` rather than pulling from a
   stateful RNG, so the answer does not depend on how many times anything was asked;
 - [PetriRun] memoises, so replay is not merely reproducible but literally the same objects.

 A net with a genuinely non-deterministic policy has no honest pattern representation. That is
 what `PetriLive` is for.
*/

// ── time ───────────────────────────────────────────────────────────────────────────────────

/** An exact rational number of cycles — `cyc(1, 16)` is a sixteenth. The time unit of every net. */
fun cyc(n: Int, d: Int = 1): PatQ = PatQ.of(n, d)

// ── tokens and markings ────────────────────────────────────────────────────────────────────

/**
 * One token. [colour] is the token's value and may be anything at all — `null` for the classical
 * black token, a `String` sample name, an `Int` note, or a whole `PatPattern` when the net is
 * being used as a song structure.
 *
 * [born] is the instant the token was produced, which is what makes FIFO consumption meaningful
 * and lets the visualizer age a token as it sits in a place.
 */
data class PetriToken(val colour: Any? = null, val born: PatQ = PatQ.ZERO) {

    /** True for the classical indistinguishable token. */
    val isBlack: Boolean get() = colour == null

    /** The colour cast to [T], or null if it is absent or another type. */
    inline fun <reified T> colourAs(): T? = colour as? T

    override fun toString(): String = if (colour == null) "•" else "•($colour)"
}

/** Shorthand for a black token born at [at]. */
fun blackToken(at: PatQ = PatQ.ZERO): PetriToken = PetriToken(null, at)

/**
 * A marking: which tokens are in which place, in arrival order.
 *
 * Empty places are not stored, so two markings compare equal exactly when they hold the same
 * tokens in the same order — which is what the reachability search in `PetriAnalysis.kt` needs.
 */
class PetriMarking private constructor(private val byPlace: Map<String, List<PetriToken>>) {

    companion object {
        val EMPTY = PetriMarking(emptyMap())

        /** A marking from place name to tokens; empty entries are dropped. */
        fun of(tokens: Map<String, List<PetriToken>>): PetriMarking =
            PetriMarking(tokens.filterValues { it.isNotEmpty() })

        /** A marking of black tokens only — `counts("kick" to 1, "hats" to 0)`. */
        fun counts(vararg counts: Pair<String, Int>, at: PatQ = PatQ.ZERO): PetriMarking =
            of(counts.associate { (name, n) -> name to List(maxOf(0, n)) { blackToken(at) } })
    }

    /** The tokens in [place], oldest first; empty if the place holds none. */
    operator fun get(place: String): List<PetriToken> = byPlace[place] ?: emptyList()

    /** How many tokens are in [place]. */
    fun count(place: String): Int = byPlace[place]?.size ?: 0

    /** Every place that currently holds at least one token. */
    val occupied: Set<String> get() = byPlace.keys

    /** Total tokens across the whole net. */
    val size: Int get() = byPlace.values.sumOf { it.size }

    /** The colours in [place], in arrival order — `null` entries are black tokens. */
    fun colours(place: String): List<Any?> = this[place].map { it.colour }

    /** A copy with [tokens] appended to the back of [place]. */
    fun add(place: String, tokens: List<PetriToken>): PetriMarking {
        if (tokens.isEmpty()) return this
        return PetriMarking(byPlace + (place to (this[place] + tokens)))
    }

    /** A copy with exactly [tokens] removed from [place], matched by identity of position. */
    internal fun removeAt(place: String, indices: Set<Int>): PetriMarking {
        if (indices.isEmpty()) return this
        val remaining = this[place].filterIndexed { index, _ -> index !in indices }
        return if (remaining.isEmpty()) PetriMarking(byPlace - place)
        else PetriMarking(byPlace + (place to remaining))
    }

    /** A copy with [place] set to exactly [tokens]. */
    fun withPlace(place: String, tokens: List<PetriToken>): PetriMarking =
        if (tokens.isEmpty()) PetriMarking(byPlace - place)
        else PetriMarking(byPlace + (place to tokens))

    /** The whole marking as a plain map — a defensive copy, safe to keep. */
    fun toMap(): Map<String, List<PetriToken>> = LinkedHashMap(byPlace)

    /** The marking as counts only, which is what the analysis layer works in. */
    fun toCounts(): Map<String, Int> = byPlace.mapValues { (_, tokens) -> tokens.size }

    /**
     * A stable identity for recurrence detection: places, and the colours in them, but **not** the
     * instants those tokens were born.
     *
     * Birth times are what make two otherwise identical markings unequal, and a net whose markings
     * are never equal has no detectable period — every bar would look brand new. Colours are
     * compared by their `toString`, which is exactly as coarse as it sounds: two different
     * `PatPattern`s both render as `PatPattern(...)` and so compare the same. That is the right
     * coarseness here (a section swap that substitutes one rhythm for another has not changed the
     * *structure* of the piece) but it is a real limit, and `PetriAnalysis` says so where it bites.
     */
    fun signature(): String = byPlace.entries
        .sortedBy { it.key }
        .joinToString(";") { (place, tokens) ->
            "$place=" + tokens.map { it.colour?.toString() ?: "•" }.sorted().joinToString(",")
        }

    override fun equals(other: Any?): Boolean = other is PetriMarking && other.byPlace == byPlace

    override fun hashCode(): Int = byPlace.hashCode()

    override fun toString(): String =
        if (byPlace.isEmpty()) "∅"
        else byPlace.entries.sortedBy { it.key }.joinToString(", ") { (place, tokens) ->
            "$place:${tokens.size}" + if (tokens.any { !it.isBlack }) tokens.joinToString(prefix = "[", postfix = "]") { "${it.colour}" } else ""
        }
}

// ── the net ────────────────────────────────────────────────────────────────────────────────

/** A place. [capacity] of `null` is unbounded; a bounded place disables any transition that would overfill it. */
data class PetriPlace(val name: String, val capacity: Int? = null) {
    init {
        require(name.isNotBlank()) { "A place needs a name" }
        require(capacity == null || capacity > 0) { "Place '$name' capacity must be > 0 or null (unbounded)" }
    }
}

/** What an input arc does to the tokens it looks at. */
enum class PetriArcKind {
    /** Requires and consumes `weight` tokens. The ordinary arc. */
    NORMAL,

    /** Requires `weight` tokens but consumes none — a test arc. Written `&p` in the notation. */
    READ,

    /** Requires *fewer* than `weight` tokens. Written `~p`. "Not while that is armed." */
    INHIBIT,
}

/**
 * An input arc: what a transition needs from a place, and which tokens count.
 *
 * [guard] filters by colour — `from("notes", guard = { (it.colour as? Int ?: 0) > 60 })` consumes
 * only high notes. Guards apply to all three [PetriArcKind]s: an inhibitor arc with a guard means
 * "not while that place holds a token of *this* kind".
 */
class PetriInput(
    val place: String,
    val weight: Int = 1,
    val kind: PetriArcKind = PetriArcKind.NORMAL,
    val guard: ((PetriToken) -> Boolean)? = null,
) {
    init { require(weight >= 1) { "Arc weight must be >= 1 (place '$place')" } }

    internal fun accepts(token: PetriToken): Boolean = guard?.invoke(token) ?: true

    override fun toString(): String = buildString {
        when (kind) {
            PetriArcKind.NORMAL -> {}
            PetriArcKind.READ -> append('&')
            PetriArcKind.INHIBIT -> append('~')
        }
        if (weight != 1) append("$weight*")
        append(place)
    }
}

/**
 * An output arc: how many tokens a transition puts into a place, and what colour they are.
 *
 * When [produce] is null the transition's [PetriColourFlow] decides. That default is the reason
 * pattern-valued tokens work without ceremony: `CARRY` threads the consumed colours through, so a
 * `PatPattern` that enters a net comes out the other side unless something deliberately changes it.
 */
class PetriOutput(
    val place: String,
    val weight: Int = 1,
    val produce: ((PetriBinding) -> List<PetriToken>)? = null,
) {
    init { require(weight >= 1) { "Arc weight must be >= 1 (place '$place')" } }

    override fun toString(): String = if (weight == 1) place else "$weight*$place"
}

/** What colour a transition's output tokens take when an output arc has no explicit `produce`. */
enum class PetriColourFlow {
    /** Always produce black tokens. The classical place/transition net. */
    BLACK,

    /**
     * Thread the consumed colours through: output token *i* takes consumed colour *i*, cycling if
     * the counts differ, and black if nothing was consumed. Degenerates to [BLACK] for a black net,
     * so it is the default.
     */
    CARRY,
}

/**
 * A transition.
 *
 * [duration] and [delay] are both in cycles. `delay` shifts the *emitted event* only — it never
 * changes when tokens move — so it is how you humanise or swing a hit. `duration` sets the emitted
 * event's length in every timing, and additionally holds the tokens in flight under
 * [PetriTiming.TIMED].
 *
 * [emit] is what the firing sounds; [emitOf] computes it from the binding when the answer depends
 * on which tokens were consumed, and wins over [emit] when both are set.
 */
class PetriTransition(
    val name: String,
    val inputs: List<PetriInput> = emptyList(),
    val outputs: List<PetriOutput> = emptyList(),
    val duration: PatQ = PatQ.ZERO,
    val delay: PatQ = PatQ.ZERO,
    /** Higher fires first under [PetriConflict.PRIORITY]. */
    val priority: Int = 0,
    /** Relative likelihood under [PetriConflict.RANDOM]. Must be > 0. */
    val weight: Double = 1.0,
    val emit: Any? = null,
    val emitOf: ((PetriBinding) -> Any?)? = null,
    /** A final veto once the tokens are chosen — the place for cross-place conditions. */
    val enabledIf: ((PetriBinding) -> Boolean)? = null,
    val colourFlow: PetriColourFlow = PetriColourFlow.CARRY,
) {
    init {
        require(name.isNotBlank()) { "A transition needs a name" }
        require(duration >= PatQ.ZERO) { "Transition '$name' duration must be >= 0" }
        require(weight > 0.0 && weight.isFinite()) { "Transition '$name' weight must be finite and > 0" }
    }

    /** The places this transition consumes from — [PetriArcKind.NORMAL] inputs only. */
    val consumes: List<PetriInput> get() = inputs.filter { it.kind == PetriArcKind.NORMAL }

    override fun toString(): String {
        val pre = inputs.joinToString(" + ").ifEmpty { "∅" }
        val post = outputs.joinToString(" + ").ifEmpty { "∅" }
        return "$pre -[ $name${if (duration > PatQ.ZERO) " @$duration" else ""}${emit?.let { " : $it" } ?: ""} ]-> $post"
    }
}

/** How a maximal step picks between transitions competing for the same tokens. */
enum class PetriConflict {
    /** Declaration order. Predictable and boring, which is often what you want. */
    ORDER,

    /** Highest [PetriTransition.priority] first, declaration order breaking ties. The default. */
    PRIORITY,

    /** Rotate the declaration order by the step index, so nobody starves. */
    ROUND_ROBIN,

    /** A deterministic shuffle hashed from `(seed, step index)`, biased by [PetriTransition.weight]. */
    RANDOM,
}

/** See the file header: STEP is a drum machine, EVENT walks, TIMED holds sections open. */
enum class PetriTiming { STEP, EVENT, TIMED }

/**
 * A net: places, transitions, an initial marking, and the semantics under which it runs.
 *
 * Nets are immutable. [withMarking] and [withTiming] give you a variant, which is what live coding
 * does between evaluations.
 */
class PetriNet(
    val places: List<PetriPlace>,
    val transitions: List<PetriTransition>,
    val initial: PetriMarking = PetriMarking.EMPTY,
    val timing: PetriTiming = PetriTiming.STEP,
    /** The tick for [PetriTiming.STEP] and [PetriTiming.EVENT], and the idle advance for TIMED. */
    val grid: PatQ = PatQ.of(1, 16),
    val conflict: PetriConflict = PetriConflict.PRIORITY,
    val seed: Int = 0,
) {
    init {
        require(grid > PatQ.ZERO) { "grid must be > 0" }
        val duplicatePlace = places.groupBy { it.name }.entries.firstOrNull { it.value.size > 1 }
        require(duplicatePlace == null) { "Duplicate place '${duplicatePlace?.key}'" }
        val duplicateTransition = transitions.groupBy { it.name }.entries.firstOrNull { it.value.size > 1 }
        require(duplicateTransition == null) { "Duplicate transition '${duplicateTransition?.key}'" }
        val known = places.map { it.name }.toSet()
        for (transition in transitions) {
            for (arc in transition.inputs) require(arc.place in known) {
                "Transition '${transition.name}' reads undeclared place '${arc.place}'"
            }
            for (arc in transition.outputs) require(arc.place in known) {
                "Transition '${transition.name}' writes undeclared place '${arc.place}'"
            }
        }
    }

    private val placeIndex: Map<String, PetriPlace> = places.associateBy { it.name }
    private val transitionIndex: Map<String, PetriTransition> = transitions.associateBy { it.name }

    /** The place called [name], or null. */
    fun place(name: String): PetriPlace? = placeIndex[name]

    /** The transition called [name], or null. */
    fun transition(name: String): PetriTransition? = transitionIndex[name]

    /** The names of every place, in declaration order. */
    val placeNames: List<String> get() = places.map { it.name }

    /** The names of every transition, in declaration order. */
    val transitionNames: List<String> get() = transitions.map { it.name }

    fun withMarking(marking: PetriMarking): PetriNet =
        PetriNet(places, transitions, marking, timing, grid, conflict, seed)

    fun withTiming(timing: PetriTiming = this.timing, grid: PatQ = this.grid): PetriNet =
        PetriNet(places, transitions, initial, timing, grid, conflict, seed)

    fun withConflict(conflict: PetriConflict = this.conflict, seed: Int = this.seed): PetriNet =
        PetriNet(places, transitions, initial, timing, grid, conflict, seed)

    /**
     * A fresh memoised trajectory from [from] (the net's own initial marking by default).
     *
     * Runs are cheap to make and expensive to keep filling, so hold one for as long as you want the
     * same performance and make a new one when the marking should reset.
     */
    fun run(from: PetriMarking = initial, startAt: PatQ = PatQ.ZERO, maxUnroll: Int = DEFAULT_MAX_UNROLL): PetriRun =
        PetriRun(this, from, startAt, maxUnroll)

    override fun toString(): String = buildString {
        append("PetriNet(${places.size} places, ${transitions.size} transitions, $timing @$grid)\n")
        append("  marking $initial\n")
        transitions.forEach { append("  $it\n") }
    }

    companion object {
        /** How many steps a run will replay before it refuses. See the file header on purity. */
        const val DEFAULT_MAX_UNROLL: Int = 200_000
    }
}

// ── firing ─────────────────────────────────────────────────────────────────────────────────

/**
 * The tokens a transition is about to consume, and where they came from.
 *
 * Passed to guards, to `emitOf`, and to every output arc's `produce`, so a transition's output can
 * depend on its input — which is how a net does pattern algebra rather than mere token counting.
 */
class PetriBinding(
    val transition: String,
    /** The instant the transition fires. */
    val at: PatQ,
    /** The 0-based cycle containing [at]. */
    val cycle: Long,
    /** Tokens about to be consumed, in input-arc declaration order. READ arcs are not included. */
    val consumed: List<PetriToken>,
    /** Every token this binding looked at, keyed by place — including the ones READ arcs only tested. */
    val byPlace: Map<String, List<PetriToken>>,
) {
    /** Tokens taken from [place] (empty if none). */
    operator fun get(place: String): List<PetriToken> = byPlace[place] ?: emptyList()

    /** The [index]-th consumed colour, or null. */
    fun colour(index: Int = 0): Any? = consumed.getOrNull(index)?.colour

    /** The [index]-th consumed colour cast to [T], or null. */
    inline fun <reified T> colourAs(index: Int = 0): T? = colour(index) as? T

    /** Every consumed colour that is a [T], in order — the usual way to reach pattern-valued tokens. */
    inline fun <reified T> colours(): List<T> = consumed.mapNotNull { it.colour as? T }

    /** One token of [colour], born now. Sugar for output arcs. */
    fun token(colour: Any?): PetriToken = PetriToken(colour, at)

    override fun toString(): String = "PetriBinding($transition@$at, consumed=$consumed)"
}

/**
 * One transition firing: when it happened, what it moved, and what it sounds.
 *
 * [arc] already has `delay` and `duration` applied, so it is the span the *event* occupies and can
 * be handed straight to the pattern layer. [at] is when the net's logic actually fired, which is
 * not the same instant if `delay` is non-zero.
 */
data class PetriFiring(
    val transition: String,
    val at: PatQ,
    val arc: PatArc,
    val cycle: Long,
    val consumed: List<PetriToken>,
    val produced: List<PetriToken>,
    val emit: Any? = null,
    /** Monotone across a whole run — a firing's identity. */
    val index: Long = 0,
) {
    override fun toString(): String =
        "PetriFiring($transition @$at${emit?.let { " : $it" } ?: ""}, ${consumed.size}→${produced.size})"
}

/**
 * One instant of a run: everything that happened at [at], and the marking either side of it.
 *
 * A STEP tick with nothing enabled does not produce a step — it produces a deadlock, because in
 * step semantics a marking that enables nothing now will never enable anything.
 */
data class PetriStep(
    val index: Long,
    val at: PatQ,
    val before: PetriMarking,
    val after: PetriMarking,
    val firings: List<PetriFiring>,
) {
    /** True when the marking is unchanged — only possible for a TIMED step that merely completed. */
    val quiet: Boolean get() = firings.isEmpty()

    override fun toString(): String =
        "PetriStep(#$index @$at, ${firings.joinToString { it.transition }.ifEmpty { "—" }}, $after)"
}

/** Why a run stopped, when it did. */
enum class PetriHalt {
    /** Still going. */
    RUNNING,

    /** Nothing is enabled and nothing is in flight. The net is stuck for good. */
    DEADLOCK,

    /** The run hit its `maxUnroll` ceiling. Not a property of the net — a property of the query. */
    EXHAUSTED,
}

/**
 * A trajectory that has started repeating: a run-in of [tailSteps], then a loop of [loopSteps]
 * lasting [duration].
 *
 * [duration] is the answer to "how long is this net's bar?" — measured from the trajectory rather
 * than assumed from the grid, which is why a net built out of a five-step ring and a three-step
 * ring reports fifteen rather than either.
 */
data class PetriLasso(
    /** How many steps happen once, before the loop. */
    val tailSteps: Int,
    /** How many steps the loop is. */
    val loopSteps: Int,
    /** The instant the loop's first step happens at. */
    val startsAt: PatQ,
    /** How long one turn of the loop takes, in cycles. The net's own bar length. */
    val duration: PatQ,
    /** How many times each transition fires per turn — a realised T-invariant. */
    val firingCounts: Map<String, Int>,
) {
    override fun toString(): String =
        "PetriLasso(tail=$tailSteps steps, loop=$loopSteps steps = $duration cycles, $firingCounts)"
}

/** A transition's outputs waiting to land, under [PetriTiming.TIMED]. */
private class PetriPending(
    val due: PatQ,
    val place: String,
    val tokens: List<PetriToken>,
    val order: Long,
)

/**
 * A deterministic, memoised, append-only trajectory through a net.
 *
 * This is the object the pattern layer sits on. Every query extends the trajectory as far as it
 * needs and never rewinds, so asking about cycle 3, then cycle 1, then cycle 3 again returns the
 * *same* steps all three times. That is what lets `net.pattern()` satisfy `PatPattern`'s contract
 * — see the file header.
 *
 * Not thread-safe by accident: every extension is under a lock, because the pattern scheduler and
 * the visualizer will query the same run from different threads.
 */
class PetriRun internal constructor(
    val net: PetriNet,
    val from: PetriMarking,
    val startAt: PatQ = PatQ.ZERO,
    val maxUnroll: Int = PetriNet.DEFAULT_MAX_UNROLL,
) {
    private val lock = Any()
    private val steps = mutableListOf<PetriStep>()
    private val pending = mutableListOf<PetriPending>()

    /**
     * One key per materialised step: the marking, plus whatever is still in flight expressed as an
     * offset from the instant the run has reached.
     *
     * Recorded as the run advances because it cannot be reconstructed afterwards — [pending] is
     * mutable state, not part of a [PetriStep], and a TIMED net with a token in flight has a
     * different future from the same marking with nothing in flight.
     */
    private val stepKeys = mutableListOf<String>()

    private var cursor: PatQ = startAt
    private var marking: PetriMarking = from
    private var firingCounter: Long = 0
    private var pendingCounter: Long = 0
    private var halted: PetriHalt = PetriHalt.RUNNING

    /** Why the run stopped, or [PetriHalt.RUNNING] if it has not. Only meaningful after a query. */
    val halt: PetriHalt get() = synchronized(lock) { halted }

    /** How many steps have been materialised so far. */
    val materialised: Int get() = synchronized(lock) { steps.size }

    /** The instant the trajectory currently reaches. */
    val reach: PatQ get() = synchronized(lock) { cursor }

    /** The first [count] steps, extending the trajectory if needed. Shorter if the net halts. */
    fun steps(count: Int): List<PetriStep> = synchronized(lock) {
        while (steps.size < count && halted == PetriHalt.RUNNING) advanceLocked()
        steps.take(count)
    }

    /** Every step strictly before [instant]. */
    fun stepsUntil(instant: PatQ): List<PetriStep> = synchronized(lock) {
        extendToLocked(instant)
        steps.filter { it.at < instant }
    }

    /** Every step whose instant lies in `[from, until)`. */
    fun stepsIn(from: PatQ, until: PatQ): List<PetriStep> = synchronized(lock) {
        extendToLocked(until)
        steps.filter { it.at >= from && it.at < until }
    }

    /**
     * Every firing whose **event arc** intersects [arc].
     *
     * Not the same question as "every firing that happened in this window": a transition with a
     * long duration is still sounding after it fired, and the pattern layer needs it back. The
     * trajectory is extended past `arc.end` by the longest transition duration in the net so a
     * firing that started earlier and reaches into the window is not missed.
     */
    fun firingsOver(arc: PatArc): List<PetriFiring> = synchronized(lock) {
        extendToLocked(arc.end)
        steps.asSequence()
            .flatMap { it.firings }
            .filter { it.arc.intersect(arc) != null }
            .toList()
    }

    /**
     * The marking as it stood immediately before [instant].
     *
     * Binary search, not a scan: the signal layer asks this question once per query per place, and
     * a run that has been playing for a while has a lot of steps behind it.
     */
    fun markingAt(instant: PatQ): PetriMarking = synchronized(lock) {
        extendToLocked(instant)
        lastStepBeforeLocked(instant)?.after ?: from
    }

    /** How many tokens were in [place] at [instant]. The raw material for a control signal. */
    fun countAt(place: String, instant: PatQ): Int = markingAt(instant).count(place)

    /**
     * Every instant at which [place]'s token count changed, up to [until], with the count it changed
     * to. The first entry is always `startAt` and the initial count, so the list is total.
     *
     * This is what an interpolated control signal reads: the raw marking is a step function and the
     * interpolators need to know where the steps are.
     */
    fun changes(place: String, until: PatQ): List<Pair<PatQ, Int>> = synchronized(lock) {
        extendToLocked(until)
        val out = mutableListOf(startAt to from.count(place))
        for (step in steps) {
            if (step.at >= until) break
            val count = step.after.count(place)
            if (count != out.last().second) out += step.at to count
        }
        out
    }

    /**
     * The state the run is in *after* the step just appended: the marking, plus everything still in
     * flight written as an offset from the instant the run has now reached, so the key is about
     * shape rather than absolute position.
     */
    private fun stateKeyLocked(): String {
        val flight = pending
            .map { "${(it.due - cursor)}@${it.place}x${it.tokens.size}" }
            .sorted()
            .joinToString(",")
        return marking.signature() + "|" + flight
    }

    /** The last materialised step strictly before [instant], by binary search over `at`. */
    private fun lastStepBeforeLocked(instant: PatQ): PetriStep? {
        var low = 0
        var high = steps.size - 1
        var found: PetriStep? = null
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (steps[mid].at < instant) {
                found = steps[mid]
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    /**
     * Where the run settles: how long before it starts repeating, and how long the repeat is.
     *
     * With a deterministic conflict policy a net has exactly one future, so its trajectory is a
     * **lasso** — a run-in followed by a loop, or a run-in followed by a wall. That makes the
     * musically interesting question cheap to answer: the loop's duration is the net's own bar
     * length, discovered rather than declared.
     *
     * Returns null if the run neither repeats nor stops within [maxSteps] — which for a net that
     * keeps minting fresh colours it never will, since a marking it has never held before cannot
     * be one it is returning to.
     */
    fun lasso(maxSteps: Int = 4_096): PetriLasso? = synchronized(lock) {
        val limit = minOf(maxSteps, maxUnroll)
        while (steps.size < limit && halted == PetriHalt.RUNNING) advanceLocked()

        val seen = HashMap<String, Int>()
        for ((index, key) in stepKeys.withIndex()) {
            val earlier = seen.putIfAbsent(key, index) ?: continue
            return PetriLasso(
                tailSteps = earlier + 1,
                loopSteps = index - earlier,
                startsAt = steps[earlier + 1].at,
                duration = steps[index].at - steps[earlier].at,
                firingCounts = steps.subList(earlier + 1, index + 1)
                    .flatMap { it.firings }
                    .groupingBy { it.transition }
                    .eachCount(),
            )
        }
        null
    }

    /** Drop every materialised step and start again from the initial marking. */
    fun reset() = synchronized(lock) {
        steps.clear()
        stepKeys.clear()
        pending.clear()
        cursor = startAt
        marking = from
        firingCounter = 0
        pendingCounter = 0
        halted = PetriHalt.RUNNING
    }

    override fun toString(): String =
        "PetriRun(${net.transitions.size} transitions, ${materialised} steps to $reach, $halt)"

    // ── the engine ─────────────────────────────────────────────────────────────────────────

    /** The longest transition duration — how far past a query we must look for still-sounding firings. */
    private val lookahead: PatQ =
        net.transitions.fold(PatQ.ZERO) { acc, t -> if (t.duration + t.delay > acc) t.duration + t.delay else acc }

    private fun extendToLocked(instant: PatQ) {
        val target = instant + lookahead
        while (halted == PetriHalt.RUNNING && cursor < target) advanceLocked()
    }

    private fun advanceLocked() {
        if (steps.size >= maxUnroll) {
            halted = PetriHalt.EXHAUSTED
            return
        }
        when (net.timing) {
            PetriTiming.STEP -> advanceGridLocked(maximal = true)
            PetriTiming.EVENT -> advanceGridLocked(maximal = false)
            PetriTiming.TIMED -> advanceTimedLocked()
        }
    }

    /**
     * STEP and EVENT. Everything fires against the marking as it stood at the tick's start and the
     * produced tokens land only once the tick is over, so a chain of transitions takes a tick each
     * rather than collapsing into one instant.
     */
    private fun advanceGridLocked(maximal: Boolean) {
        val at = cursor
        val before = marking
        var working = marking
        val firings = mutableListOf<PetriFiring>()
        val produced = mutableListOf<Pair<String, List<PetriToken>>>()

        for (transition in orderedTransitions(steps.size.toLong())) {
            val bound = bind(transition, working, before, at) ?: continue
            val out = producedTokens(transition, bound.binding)
            if (!capacityAllows(working, produced + out)) continue
            working = bound.consumeFrom(working)
            produced += out
            firings += firing(transition, bound.binding, out.flatMap { it.second })
            if (!maximal) break
        }

        if (firings.isEmpty()) {
            halted = PetriHalt.DEADLOCK
            return
        }

        for ((place, tokens) in produced) working = working.add(place, tokens)
        marking = working
        steps += PetriStep(steps.size.toLong(), at, before, working, firings)
        cursor = at + net.grid
        stepKeys += stateKeyLocked()
    }

    /**
     * TIMED. An instant is interesting if something completes there or something can start there;
     * time jumps to the next such instant rather than walking a grid.
     */
    private fun advanceTimedLocked() {
        var guard = 0
        while (true) {
            val at = cursor
            val before = marking
            var working = marking

            val due = pending.filter { it.due <= at }.sortedBy { it.order }
            if (due.isNotEmpty()) {
                pending.removeAll(due.toSet())
                for (entry in due) working = working.add(entry.place, entry.tokens)
            }

            // Tests (read and inhibitor arcs) are answered against the marking as it stands at the
            // *start* of this instant, after completions have landed but before anything fires.
            val atInstant = working

            val firings = mutableListOf<PetriFiring>()
            for (transition in orderedTransitions(steps.size.toLong())) {
                val bound = bind(transition, working, atInstant, at) ?: continue
                val out = producedTokens(transition, bound.binding)
                // Only immediate production can overfill a place right now; a delayed production
                // is checked again when it lands, because the marking will have moved on by then.
                if (transition.duration <= PatQ.ZERO && !capacityAllows(working, out)) continue
                working = bound.consumeFrom(working)
                val landing = at + transition.duration
                if (transition.duration <= PatQ.ZERO) {
                    for ((place, tokens) in out) working = working.add(place, tokens)
                } else {
                    for ((place, tokens) in out) pending += PetriPending(landing, place, tokens, pendingCounter++)
                }
                firings += firing(transition, bound.binding, out.flatMap { it.second })
            }

            if (firings.isNotEmpty() || due.isNotEmpty()) {
                marking = working
                steps += PetriStep(steps.size.toLong(), at, before, working, firings)
                cursor = pending.minOfOrNull { it.due }?.takeIf { it > at } ?: (at + net.grid)
                stepKeys += stateKeyLocked()
                return
            }

            // Nothing at this instant. Jump to the next thing in flight, or admit defeat.
            val next = pending.minOfOrNull { it.due }
            if (next == null || next <= at) {
                halted = PetriHalt.DEADLOCK
                return
            }
            cursor = next
            if (++guard > MAX_IDLE_JUMPS) {
                halted = PetriHalt.EXHAUSTED
                return
            }
        }
    }

    private fun firing(transition: PetriTransition, binding: PetriBinding, produced: List<PetriToken>): PetriFiring {
        val start = binding.at + transition.delay
        val length = if (transition.duration > PatQ.ZERO) transition.duration else net.grid
        return PetriFiring(
            transition = transition.name,
            at = binding.at,
            arc = PatArc(start, start + length),
            cycle = qFloorLong(binding.at),
            consumed = binding.consumed,
            produced = produced,
            emit = transition.emitOf?.invoke(binding) ?: transition.emit,
            index = firingCounter++,
        )
    }

    private fun bind(
        transition: PetriTransition,
        available: PetriMarking,
        tested: PetriMarking,
        at: PatQ,
    ): PetriBound? = petriBind(transition, available, tested, at)

    private fun producedTokens(
        transition: PetriTransition,
        binding: PetriBinding,
    ): List<Pair<String, List<PetriToken>>> = petriProduce(transition, binding)

    /**
     * Would landing [adding] on top of [marking] overfill a bounded place?
     *
     * The caller passes everything the step has promised so far, not just this transition's share,
     * because in a maximal step two transitions can each fit and still overflow together.
     */
    private fun capacityAllows(marking: PetriMarking, adding: List<Pair<String, List<PetriToken>>>): Boolean {
        val extra = HashMap<String, Int>()
        for ((place, tokens) in adding) extra[place] = (extra[place] ?: 0) + tokens.size
        for ((place, count) in extra) {
            val capacity = net.place(place)?.capacity ?: continue
            if (marking.count(place) + count > capacity) return false
        }
        return true
    }

    /** The order conflicts are resolved in for the step numbered [step]. */
    private fun orderedTransitions(step: Long): List<PetriTransition> = when (net.conflict) {
        PetriConflict.ORDER -> net.transitions
        PetriConflict.PRIORITY -> net.transitions.sortedByDescending { it.priority }
        PetriConflict.ROUND_ROBIN -> {
            val all = net.transitions
            if (all.isEmpty()) all
            else {
                val offset = (step.mod(all.size.toLong())).toInt()
                all.drop(offset) + all.take(offset)
            }
        }
        PetriConflict.RANDOM -> net.transitions.sortedBy { transition ->
            // A weighted deterministic shuffle: exponential race, hashed rather than sampled, so
            // the order depends only on (seed, step, name) and never on call history.
            val hash = splitmix64(
                net.seed.toLong() * 0x9E3779B97F4A7C15uL.toLong() xor
                    (step * 0x2545F4914F6CDD1DuL.toLong()) xor
                    transition.name.hashCode().toLong()
            )
            -Math.log(1.0 - toUnitDouble(hash).coerceIn(1e-12, 1.0 - 1e-12)) / transition.weight
        }
    }

    private companion object {
        /** How many empty instants a TIMED run will skip before it calls the query exhausted. */
        const val MAX_IDLE_JUMPS = 1024
    }
}

// ── enabling, shared by the engine, the live runtime and the visualizer ────────────────────

/** A binding plus the exact token positions it claimed, so consumption is unambiguous. */
internal class PetriBound(val binding: PetriBinding, private val claimed: Map<String, Set<Int>>) {
    fun consumeFrom(marking: PetriMarking): PetriMarking {
        var working = marking
        for ((place, indices) in claimed) working = working.removeAt(place, indices)
        return working
    }
}

/**
 * Chooses the tokens [transition] would consume from [available], or null if it is not enabled.
 *
 * Tokens are taken oldest first from each place; guards filter which ones count. READ arcs are
 * tested but their tokens are not consumed, and inhibitor arcs are satisfied by *scarcity*.
 *
 * The two markings are not the same question. Consumption reads [available], which shrinks as
 * earlier transitions in the same step claim tokens — that is how conflict is resolved. Tests read
 * [tested], the marking as it stood at the *start* of the instant, so that disarming a place and
 * acting on it being disarmed cannot happen in one tick. Without that split, an inhibitor arc turns
 * a step boundary back into a cascade.
 *
 * Claims are tracked as **positions**, not values, so two arcs reading the same place with different
 * guards cannot both claim the same token and identical tokens are never confused.
 */
internal fun petriBind(
    transition: PetriTransition,
    available: PetriMarking,
    tested: PetriMarking,
    at: PatQ,
): PetriBound? {
    val consumed = mutableListOf<PetriToken>()
    val looked = LinkedHashMap<String, List<PetriToken>>()
    val claimed = HashMap<String, MutableSet<Int>>()

    for (arc in transition.inputs) {
        when (arc.kind) {
            PetriArcKind.INHIBIT -> {
                if (tested[arc.place].count { arc.accepts(it) } >= arc.weight) return null
            }
            PetriArcKind.READ -> {
                // A read arc only tests, so it neither claims positions nor respects claims: a
                // token can be read and consumed in the same firing, which is what "test" means.
                // It does not join `consumed`.
                val matching = tested[arc.place].filter { arc.accepts(it) }
                if (matching.size < arc.weight) return null
                looked[arc.place] = (looked[arc.place] ?: emptyList()) + matching.take(arc.weight)
            }
            PetriArcKind.NORMAL -> {
                val here = available[arc.place]
                val taken = claimed.getOrPut(arc.place) { linkedSetOf() }
                val free = here.indices.filter { it !in taken && arc.accepts(here[it]) }
                if (free.size < arc.weight) return null
                val take = free.take(arc.weight)
                taken += take
                val tokens = take.map { here[it] }
                consumed += tokens
                looked[arc.place] = (looked[arc.place] ?: emptyList()) + tokens
            }
        }
    }

    val binding = PetriBinding(transition.name, at, qFloorLong(at), consumed, looked)
    if (transition.enabledIf?.invoke(binding) == false) return null
    return PetriBound(binding, claimed)
}

/** The tokens each output arc contributes, before they are placed. */
internal fun petriProduce(
    transition: PetriTransition,
    binding: PetriBinding,
): List<Pair<String, List<PetriToken>>> = transition.outputs.map { arc ->
    val tokens = arc.produce?.invoke(binding) ?: petriDefaultProduction(transition, binding, arc)
    arc.place to tokens
}

private fun petriDefaultProduction(
    transition: PetriTransition,
    binding: PetriBinding,
    arc: PetriOutput,
): List<PetriToken> = when (transition.colourFlow) {
    PetriColourFlow.BLACK -> List(arc.weight) { PetriToken(null, binding.at) }
    PetriColourFlow.CARRY ->
        if (binding.consumed.isEmpty()) List(arc.weight) { PetriToken(null, binding.at) }
        else List(arc.weight) { index ->
            PetriToken(binding.consumed[index % binding.consumed.size].colour, binding.at)
        }
}

/**
 * Which transitions could fire against [marking], ignoring conflict — two transitions can both be
 * listed here and still not both fire, because they may want the same token.
 *
 * This is what the visualizer highlights and what a live runtime offers as the set of things you
 * could force. Capacity is not checked, because a transition blocked only by a full output place is
 * still enabled in the usual sense of the word.
 */
fun PetriNet.enabled(marking: PetriMarking = initial, at: PatQ = PatQ.ZERO): List<String> =
    transitions.filter { petriBind(it, marking, marking, at) != null }.map { it.name }

/** `floor(q)` as a Long — the cycle a rational instant falls in. */
internal fun qFloorLong(q: PatQ): Long {
    val (quotient, remainder) = q.n.divideAndRemainder(q.d)
    val floored = if (remainder.signum() < 0) quotient.subtract(java.math.BigInteger.ONE) else quotient
    return floored.toLong()
}

// ── the builder ────────────────────────────────────────────────────────────────────────────

/**
 * Builds a net. Places are auto-declared the first time a transition mentions them, so you only
 * write [place] when you want an initial marking, a capacity, or coloured tokens.
 *
 *     val net = petri {
 *         timing = PetriTiming.STEP
 *         grid = cyc(1, 16)
 *         place("kick", tokens = 1)
 *         t("hit",  dur = cyc(1, 16), emit = "bd") { from("kick"); to("hats") }
 *         t("back", dur = cyc(1, 16), emit = "sd") { from("hats"); to("kick") }
 *     }
 */
class PetriBuilder internal constructor() {
    var timing: PetriTiming = PetriTiming.STEP
    var grid: PatQ = PatQ.of(1, 16)
    var conflict: PetriConflict = PetriConflict.PRIORITY
    var seed: Int = 0

    private val declared = LinkedHashMap<String, PetriPlace>()
    private val marking = LinkedHashMap<String, MutableList<PetriToken>>()
    private val builtTransitions = mutableListOf<PetriTransition>()

    /**
     * Declares a place.
     *
     * [tokens] black tokens and one token per entry of [colours] are placed in it, black ones
     * first. Returns the name so it composes into an arc call.
     */
    fun place(
        name: String,
        tokens: Int = 0,
        capacity: Int? = null,
        colours: List<Any?> = emptyList(),
        at: PatQ = PatQ.ZERO,
    ): String {
        declared[name] = PetriPlace(name, capacity)
        val bucket = marking.getOrPut(name) { mutableListOf() }
        repeat(maxOf(0, tokens)) { bucket += PetriToken(null, at) }
        colours.forEach { bucket += PetriToken(it, at) }
        return name
    }

    /** Declares a place holding exactly these coloured tokens — `place("verse", pat("bd*4"))`. */
    fun place(name: String, vararg colours: Any?): String = place(name, colours = colours.toList())

    /**
     * Declares a transition. The block wires its arcs.
     *
     * `dur` sets how long the emitted event lasts (and, under [PetriTiming.TIMED], how long the
     * firing holds its tokens); `delay` shifts the event without touching the net's logic.
     */
    fun t(
        name: String,
        dur: PatQ = PatQ.ZERO,
        delay: PatQ = PatQ.ZERO,
        priority: Int = 0,
        weight: Double = 1.0,
        emit: Any? = null,
        colourFlow: PetriColourFlow = PetriColourFlow.CARRY,
        build: PetriTransitionBuilder.() -> Unit = {},
    ): String {
        val spec = PetriTransitionBuilder(name).apply(build)
        spec.mentioned.forEach { declared.getOrPut(it) { PetriPlace(it) } }
        builtTransitions += PetriTransition(
            name = name,
            inputs = spec.inputs.toList(),
            outputs = spec.outputs.toList(),
            duration = dur,
            delay = delay,
            priority = priority,
            weight = weight,
            emit = emit,
            emitOf = spec.emitOf,
            enabledIf = spec.enabledIf,
            colourFlow = colourFlow,
        )
        return name
    }

    internal fun build(): PetriNet = PetriNet(
        places = declared.values.toList(),
        transitions = builtTransitions.toList(),
        initial = PetriMarking.of(marking),
        timing = timing,
        grid = grid,
        conflict = conflict,
        seed = seed,
    )
}

/** Wires one transition's arcs. See [PetriBuilder.t]. */
class PetriTransitionBuilder internal constructor(private val name: String) {
    internal val inputs = mutableListOf<PetriInput>()
    internal val outputs = mutableListOf<PetriOutput>()
    internal val mentioned = LinkedHashSet<String>()
    internal var emitOf: ((PetriBinding) -> Any?)? = null
    internal var enabledIf: ((PetriBinding) -> Boolean)? = null

    /** Consume [weight] tokens from [place], optionally only those matching [guard]. */
    fun from(place: String, weight: Int = 1, guard: ((PetriToken) -> Boolean)? = null) {
        mentioned += place
        inputs += PetriInput(place, weight, PetriArcKind.NORMAL, guard)
    }

    /** Require [weight] tokens in [place] but consume none — a test arc, `&p` in the notation. */
    fun read(place: String, weight: Int = 1, guard: ((PetriToken) -> Boolean)? = null) {
        mentioned += place
        inputs += PetriInput(place, weight, PetriArcKind.READ, guard)
    }

    /** Require *fewer* than [weight] tokens in [place] — an inhibitor arc, `~p` in the notation. */
    fun inhibit(place: String, weight: Int = 1, guard: ((PetriToken) -> Boolean)? = null) {
        mentioned += place
        inputs += PetriInput(place, weight, PetriArcKind.INHIBIT, guard)
    }

    /**
     * Produce [weight] tokens into [place]. Without [produce] the transition's
     * [PetriColourFlow] decides their colour — by default the consumed colours are carried through.
     */
    fun to(place: String, weight: Int = 1, produce: ((PetriBinding) -> List<PetriToken>)? = null) {
        mentioned += place
        outputs += PetriOutput(place, weight, produce)
    }

    /** Produce one token into [place] whose colour is computed from the binding. */
    fun toColoured(place: String, colour: (PetriBinding) -> Any?) {
        to(place) { binding -> listOf(PetriToken(colour(binding), binding.at)) }
    }

    /** What this firing sounds, when it depends on which tokens were consumed. */
    fun emit(of: (PetriBinding) -> Any?) {
        emitOf = of
    }

    /** A final veto once the tokens are chosen. */
    fun onlyIf(predicate: (PetriBinding) -> Boolean) {
        enabledIf = predicate
    }

    override fun toString(): String = "PetriTransitionBuilder($name)"
}

/** Builds a net with the Kotlin DSL. See [PetriBuilder]. */
fun petri(build: PetriBuilder.() -> Unit): PetriNet = PetriBuilder().apply(build).build()
