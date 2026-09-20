@file:afield.CompletionCategory("Patterns")
/**
 * Function generation over the pattern language: stored material, a trajectory that reads it, and
 * the timing of that traversal, kept as three separate patterns.
 *
 * The idea is borrowed from the Buchla 248 Multiple Arbitrary Function Generator, and the part worth
 * borrowing is not its step sequencer. It is the separation the 248 makes between
 *
 * - the **material** stored in a bank of stages,
 * - the **address** that says which stage to read next,
 * - the **time** each stage is allowed to take,
 * - and the **continuous function** the generator draws while a stage is active.
 *
 * On the hardware those are four patch points. Here they are four ordinary [PatPattern]s, which is
 * the whole point: `slow`, `rev`, `every`, `euclid`, `degradeBy`, `randcat` and everything else in
 * TidalPatterns.kt / PatComposition.kt already work on all four, so this file adds almost no
 * combinators of its own. A reader is built from patterns and *is* a pattern:
 * [MarfReader.signal] hands back a `PatPattern<Double>` in the sense of PatSignals.kt, and
 * [MarfReader.triggers] hands back a discrete `PatPattern<String>`. Both ends plug straight into
 * `with(gain(...))`, `struct`, `segment`, `respond` and the scheduler.
 *
 * Read the surface from the outside in:
 *
 * 1. The four fields, patterned independently. Nothing here is a grid; the durations are real:
 *
 *        val env = marf(
 *            value    = pat("<0 .7 .2 1>").nums(),
 *            duration = pat("<1 4 .25 2>").nums(),
 *            curve    = curves("<linear exp hold osc>"),
 *            fields   = MarfFieldClock.STAGES
 *        )
 *
 *        env.at(3.5).value        // the continuous output, 3.5 cycles in
 *        env.at(3.5).stagePhase   // where inside its own stage that was
 *
 * 2. Stored material, read by a trajectory. Two readers, one bank, different journeys:
 *
 *        val b = marfBank(
 *            rampTo(0.0), rampTo(0.3), holdAt(0.2), oscTo(0.9, cycles = 3), rampTo(0.8)
 *        )
 *
 *        val a = b.read(pat("0 1 2 1 3 4").nums())
 *        val c = b.read(pat("0 2 4 1 3").nums().slow(3))
 *        val d = b.read(struct(euclid(5, 8), pat("0 4 2 3").nums()))
 *
 *    The address is a pattern, so the trajectory is compositional material rather than a mode
 *    flag. `"<0 1 2 3 2 1>"` is ping-pong. `.every(4) { it.rev() }` is periodic reversal. There is
 *    no `direction = PINGPONG` in this file and there should never be one.
 *
 * 3. Out, as a signal or as pulses — the same temporal structure, both ways:
 *
 *        pat("bd*8") with gain(a.signal().range(0.2, 1.0))   // continuous, into the algebra
 *        a.triggers().respond(cps = 1.0) { spawnParticles() } // discrete, one per stage
 *
 * 4. And the missing conversion, which is useful with or without a bank — an event stream read as
 *    a continuous function:
 *
 *        pat("0 .5 1 .25").nums().hold()             // sample and hold
 *        pat("0 .5 1 .25").nums().ramp()             // linear between onsets
 *        pat("0 .5 1 .25").nums().interp(MarfCurve.Smooth)
 *
 * #### Where a stage's numbers come from
 *
 * Stage `k` begins at an exact cycle instant `Tₖ`, and **every field is sampled at `Tₖ`** — value,
 * duration, curve, trigger, address, speed — through [patSampleOver], the same latch the parameter
 * algebra uses for `gain(...)` at a note onset. Then `Tₖ₊₁ = Tₖ + durationₖ`.
 *
 * A field pattern with **rests** holds rather than resets. If nothing is sounding at `Tₖ` — a
 * `struct(euclid(5, 8), …)` address on an off step, a `degradeBy` that dropped this one — the field
 * keeps the value the previous stage had. That is what a sample-and-hold does, and it is what makes
 * the thinning operators mean something on an address: `struct` and `degradeBy` become *how long an
 * address is held for*, rather than "and now, silently, address zero".
 *
 * That recurrence is the interesting part, and it is why this is not a sequencer. The reader's own
 * traversal generates its clock, so the duration pattern is consulted at instants the duration
 * pattern chose. Give four values a `"<1 4 .25 2>"` duration and you get
 *
 *     |--0--|--------.7--------|-.2-|----1----|
 *
 * rather than four equal steps. Give the address a period of 6 and the duration a period of 4 and
 * the two drift against each other with a period of 12 — which is the whole reason to keep them
 * apart.
 *
 * There is one real choice buried in "sampled at `Tₖ`", and [MarfFieldClock] is where it lives.
 * Reading the fields at the wall-clock instant `Tₖ` (`CYCLES`, the default) is the hardware answer
 * and never fails silently: every pattern says something at every instant. Reading them at cycle
 * `k` instead (`STAGES`) makes the fields a per-stage *sequence*, so `duration = "<1 4 .25 2>"`
 * gives stages of exactly those four lengths and `address = "<0 1 2 1 3 4>"` walks the bank one
 * slot per stage no matter how long a stage takes. The trade is that in `STAGES` one cycle of a
 * field pattern is one stage, so a bare `"0 .7 .2 1"` collapses to its first step. Both are useful;
 * neither is a default that can be left unexplained.
 *
 * Accumulation is exact. Durations arriving as `Double` are taken to [PatQ] at
 * [MARF_DURATION_DENOM] resolution, so a reader is still phase-locked to the bar after ten
 * thousand stages; a `Double` accumulator would not be. A duration is floored at
 * [MARF_MIN_DURATION] so that a zero or negative sample cannot stall the walk.
 *
 * Sampling a *continuous* field pattern needs an arc rather than an instant, and the probe used is
 * `[Tₖ, Tₖ + 1/65536)`, so `duration = sine().range(0.25, 2.0)` reads the sine essentially at the
 * stage boundary. A discrete field pattern is unaffected by the probe width: the latch picks the
 * event containing `Tₖ` however finely the source is subdivided.
 *
 * #### Local time
 *
 * [MarfSample] is the temporal context, and the reason it exists is that several notions of time
 * are live at once. `cycle` is the global coordinate, `stageIndex` counts stages, `stageTime` is
 * cycles since this stage began and `stagePhase` is that normalised to `0..1`. A curve is written
 * against `stagePhase` and never has to reconstruct it:
 *
 *        MarfCurve.Shape("chirp") { p -> 0.5 + 0.5 * kotlin.math.sin(p * p * 40.0) }
 *
 * The analogy worth keeping is a shader. A fragment shader computes over `uv`; a stage computes
 * over `stagePhase`, inside a structure the pattern algebra laid out.
 *
 * #### Continuous address
 *
 * The address is a `PatPattern<Double>`, not a `PatPattern<Int>`, and a fractional address blends.
 * At address `1.35` the reader evaluates stages 1 and 2 over the same local phase and mixes the
 * outputs `(1-α)·f₁(p) + α·f₂(p)` with `α = 0.35`, blending their durations the same way. So
 *
 *        b.read(sine().range(0.0, 4.0))
 *
 * glides through the bank instead of stepping through it, and the bank stops being five slots and
 * starts being a sampled one-dimensional family of functions. The entry value is shared between
 * the blended components, so the output stays continuous across a stage boundary exactly as it
 * does for an integer address.
 *
 * There is a catch, and it is arithmetic rather than a bug. A periodic address is **latched once
 * per stage**, so a reader with periodic durations visits exactly
 *
 *     lcm(address period, stage period) / stage period
 *
 * distinct addresses and then repeats forever. `sine().range(0, 4)` against quarter-cycle stages is
 * four of them — the address cycles `2, 4, 2, 0, 2, 4, 2, 0, …` and the "glide" is a four-step
 * sequencer wearing a signal's clothes. `sine().slow(3)` is twelve, which is better and still a
 * sequence. What actually reads as a glide is a long count: `saw().slow(8)` against those stages is
 * thirty-two, and an aperiodic address (`perlin()`, `rand()`) never repeats at all. Nothing here
 * errors; the reader is simply less interesting than it looks, so it is worth knowing which case
 * you are in.
 *
 * Count the distinct [MarfSample.address] values **rounded** when you check. An aliased address
 * repeats to a few parts in 10⁴ rather than bit-exactly, because the probe instant is an exact
 * rational and `PatQ.toDouble()` rounds it at twelve places — a raw `distinct()` reports seventeen
 * different addresses for the four-value cycle above. `MarfSmokeTest` prints both cases.
 *
 * #### What is deliberately not here yet
 *
 * - **Vector-valued stages.** Everything is `Double`. The place a `T` would go is [MarfStage.target]
 *   plus the mix in [MarfResolvedStage.valueAt]; the curve is a `0..1` shaping function and stays
 *   scalar either way, so the generalisation is a type parameter and an interpolator argument, not
 *   a redesign.
 * - **A bank entry that is itself a pattern.** A stage's continuous behaviour is a [MarfCurve], and
 *   `MarfCurve.Shape` can close over anything, including another reader — but a nested pattern
 *   given its own local temporal scope inside the stage is not modelled, and would want
 *   `stagePhase` threaded into a real evaluation context rather than passed as a `Double`.
 *
 * Neither is blocked by anything in this file, and both were left out on purpose: the prototype is
 * small so that whether the separation is the useful idea can be judged before it is generalised.
 */

package afield.four.synth

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// ── curves: what a stage draws while it is active ─────────────────────────────────────────

/**
 * The shape of one stage's traversal, as data rather than as a lambda.
 *
 * A curve is a **shaping function** `0..1 → 0..1` on the stage's local phase; the reader turns that
 * into an output with `from + (to - from) · shape(phase)`, so a curve never needs to know the
 * values it is moving between. Keeping the standard family as `data object`/`data class` rather
 * than `(Double) -> Double` is what lets a stage be printed, compared, persisted, and — later —
 * compiled into a shader or a buffer. [Shape] is the escape hatch for when that does not matter.
 *
 * Shapes are not clamped. A shape may leave `0..1` and the output will overshoot, which is what you
 * want for anything spring-like.
 */
sealed interface MarfCurve {
    /** Step to the target on entry and stay there — the sample-and-hold stage. `shape ≡ 1`. */
    data object Hold : MarfCurve

    /** `shape(p) = p`. */
    data object Linear : MarfCurve

    /** Smoothstep, `p²(3-2p)` — zero slope at both ends, so stages join without a corner. */
    data object Smooth : MarfCurve

    /**
     * `shape(p) = p^amount`. Above 1 is a slow start and a fast arrival, below 1 the reverse;
     * `exp` in [marfCurve] is `Exp(3.0)` and `log` is `Exp(1/3)`.
     */
    data class Exp(val amount: Double = 3.0) : MarfCurve

    /**
     * A raised cosine, `0.5 - 0.5·cos(2π·cycles·p)`.
     *
     * At `cycles = 0.5` this is an S-curve from `from` to `to`. At a whole number of cycles it
     * departs from `from` and returns to it, so the stage is an LFO burst whose rate is set by its
     * own duration rather than by a frequency in Hz — which is the [MarfCurve.Osc] worth having.
     */
    data class Osc(val cycles: Double = 1.0) : MarfCurve

    /** A staircase in [treads] equal treads, reaching the target on the last one. */
    data class Step(val treads: Int = 4) : MarfCurve

    /**
     * An arbitrary shaping function of local stage phase.
     *
     * [name] is carried so the stage still prints as something: two `Shape`s with the same name are
     * equal as data even though their lambdas are not comparable, which keeps a bank diffable at
     * the cost of trusting the name. Prefer the declarative family where it will do.
     */
    data class Shape(val name: String, val f: (phase: Double) -> Double) : MarfCurve {
        override fun equals(other: Any?): Boolean = other is Shape && other.name == name
        override fun hashCode(): Int = name.hashCode()
        override fun toString(): String = "Shape($name)"
    }
}

/** The shaping function of [curve] at local stage [phase]. Not clamped to `0..1`. */
fun marfCurveShape(curve: MarfCurve, phase: Double): Double {
    val p = phase
    return when (curve) {
        is MarfCurve.Hold -> 1.0
        is MarfCurve.Linear -> p
        is MarfCurve.Smooth -> p * p * (3.0 - 2.0 * p)
        is MarfCurve.Exp -> {
            val a = if (curve.amount > 0.0) curve.amount else 1.0
            if (p <= 0.0) 0.0 else Math.pow(p, a)
        }
        is MarfCurve.Osc -> 0.5 - 0.5 * cos(2.0 * PI * curve.cycles * p)
        is MarfCurve.Step -> {
            val treads = max(2, curve.treads)
            val tread = min(treads - 1, floor(p * treads).toInt().coerceAtLeast(0))
            tread.toDouble() / (treads - 1).toDouble()
        }
        is MarfCurve.Shape -> curve.f(p)
    }
}

/** The output of a stage moving from [from] toward [to], at local stage [phase]. */
fun marfCurveValue(curve: MarfCurve, from: Double, to: Double, phase: Double): Double =
    from + (to - from) * marfCurveShape(curve, phase)

/** Curve names accepted by [marfCurve] and validated eagerly by [curves]. */
private val MARF_CURVE_NAMES = setOf(
    "hold", "h", "linear", "lin", "l", "smooth", "s", "exp", "log", "osc", "sine", "step"
)

/**
 * One curve from a mini-notation atom. `exp`, `exp:1.5`, `osc:3`, `step:6`, `hold`, `smooth`.
 *
 * Throws on an unknown name rather than falling back to `linear`: a typo in a curve pattern is
 * silent otherwise, and the whole point of a curve pattern is that you cannot hear which stage you
 * misspelled.
 */
fun marfCurve(atom: String): MarfCurve {
    val trimmed = atom.trim()
    val name = trimmed.substringBefore(':').lowercase()
    val arg = trimmed.substringAfter(':', "").trim().toDoubleOrNull()
    return when (name) {
        "hold", "h" -> MarfCurve.Hold
        "linear", "lin", "l" -> MarfCurve.Linear
        "smooth", "s" -> MarfCurve.Smooth
        "exp" -> MarfCurve.Exp(arg ?: 3.0)
        "log" -> MarfCurve.Exp(arg ?: (1.0 / 3.0))
        "osc", "sine" -> MarfCurve.Osc(arg ?: 1.0)
        "step" -> MarfCurve.Step(arg?.toInt() ?: 4)
        else -> throw IllegalArgumentException(
            "unknown curve '$atom'; expected one of ${MARF_CURVE_NAMES.sorted().joinToString(" ")}" +
                " (optionally with a `:arg`, as in exp:1.5 or step:6)"
        )
    }
}

/**
 * A pattern of curves from mini-notation. `curves("<linear exp hold osc:3>")`.
 *
 * Every alphabetic word in [source] is checked against the known names **at construction time**,
 * not when the pattern is first queried, so a misspelled curve fails where you typed it instead of
 * four bars into a take.
 */
fun curves(source: String): PatPattern<MarfCurve> {
    for (word in Regex("[A-Za-z]+").findAll(source).map { it.value }) {
        require(word.lowercase() in MARF_CURVE_NAMES) {
            "unknown curve '$word' in curves(\"$source\"); expected one of " +
                MARF_CURVE_NAMES.sorted().joinToString(" ")
        }
    }
    return pat(source).map { marfCurve(it) }
}

// ── stages and banks: the stored material ─────────────────────────────────────────────────

/** Resolution at which a `Double` duration becomes an exact [PatQ]. See the file header. */
const val MARF_DURATION_DENOM: Long = 1_000_000L

/** The floor on a stage duration, in cycles, so that a bad sample cannot stall the walk. */
val MARF_MIN_DURATION: PatQ = PatQ.of(1, 1024)

/** Largest number of stages a reader keeps resolved before it drops the oldest half. */
private const val MARF_TIMELINE_RETAIN = 1 shl 16

/**
 * Stages one call to [MarfReader.stageAt] may resolve before it gives up.
 *
 * The walk is bounded because the alternative is a hang, and the way to get one is not exotic:
 * sampling a reader at a time in **seconds** when its durations are in cycles asks it to walk
 * `cps` times too far. A loud failure naming both numbers is worth more than a frozen box.
 */
private const val MARF_WALK_BUDGET = 1 shl 20

/**
 * One timed function segment: where to go, how long to take, and the shape of the going.
 *
 * There is no *from*. A stage's departure value is wherever the reader already was, which is what
 * makes a bank of stages behave like the 248's stepped voltage source rather than like a list of
 * disconnected ramps — and what makes the output continuous across a stage boundary even when the
 * address jumps.
 */
data class MarfStage(
    val target: Double,
    val duration: PatQ = PatQ.ONE,
    val curve: MarfCurve = MarfCurve.Linear,
    val trigger: Boolean = true,
    val label: String? = null
)

/** The general stage constructor. [duration] is in cycles. */
fun stage(
    target: Number,
    duration: Number = 1.0,
    curve: MarfCurve = MarfCurve.Linear,
    trigger: Boolean = true,
    label: String? = null
): MarfStage = MarfStage(target.toDouble(), marfDuration(duration), curve, trigger, label)

/** Step to [target] and stay there for [duration] cycles. */
fun holdAt(target: Number, duration: Number = 1.0, trigger: Boolean = true, label: String? = null): MarfStage =
    stage(target, duration, MarfCurve.Hold, trigger, label)

/** Slew linearly to [target] over [duration] cycles. */
fun rampTo(target: Number, duration: Number = 1.0, trigger: Boolean = true, label: String? = null): MarfStage =
    stage(target, duration, MarfCurve.Linear, trigger, label)

/** Slew to [target] as `phase^amount` — a convex rise above 1, concave below. */
fun curveTo(target: Number, duration: Number = 1.0, amount: Number = 3.0, trigger: Boolean = true, label: String? = null): MarfStage =
    stage(target, duration, MarfCurve.Exp(amount.toDouble()), trigger, label)

/** Oscillate toward [target] and back, [cycles] times across the stage. See [MarfCurve.Osc]. */
fun oscTo(target: Number, duration: Number = 1.0, cycles: Number = 1.0, trigger: Boolean = true, label: String? = null): MarfStage =
    stage(target, duration, MarfCurve.Osc(cycles.toDouble()), trigger, label)

/** A duration in cycles, exact and floored at [MARF_MIN_DURATION]. */
fun marfDuration(cycles: Number): PatQ =
    marfDuration(PatQ.fromDouble(cycles.toDouble(), MARF_DURATION_DENOM))

/** An already-exact duration, floored at [MARF_MIN_DURATION]. */
fun marfDuration(cycles: PatQ): PatQ = if (cycles < MARF_MIN_DURATION) MARF_MIN_DURATION else cycles

/**
 * Stored temporal material: a list of stages that several readers can traverse independently.
 *
 * A bank is passive. It has no position, no direction and no clock of its own — all of that lives
 * in the [MarfReader]s built by [read], which is exactly the property being borrowed from the
 * hardware. Two readers over one bank are two voices reading one score.
 */
class MarfBank(val stages: List<MarfStage>) {
    init { require(stages.isNotEmpty()) { "a bank needs at least one stage" } }

    val size: Int get() = stages.size

    operator fun get(index: Int): MarfStage = stages[wrapIndex(index)]

    /** Bank indices wrap, so an address pattern never has to know how long the bank is. */
    fun wrapIndex(index: Int): Int {
        val m = index % size
        return if (m < 0) m + size else m
    }

    override fun toString(): String = "MarfBank(${stages.size} stages)"
}

/** A bank from stages written out in order. */
fun marfBank(vararg stages: MarfStage): MarfBank = MarfBank(stages.toList())

/** A bank from a list — the form to use when the stages are computed. */
fun marfBank(stages: List<MarfStage>): MarfBank = MarfBank(stages)

/**
 * A bank of plain held values, for when only the addressing is interesting.
 * `marfValues(0, .3, .9, .2, .8)` is the five-slot voltage source of the test in the design note.
 */
fun marfValues(vararg targets: Number): MarfBank =
    MarfBank(targets.map { holdAt(it) })

// ── what a reader knows at an instant ─────────────────────────────────────────────────────

/** One bank stage and its weight in a blended (fractional-address) read. */
data class MarfComponent(val index: Int, val stage: MarfStage, val weight: Double)

/**
 * A stage as the reader actually entered it: resolved numbers, an exact span, and a departure value.
 *
 * [components] is the material being read. One component is the ordinary case; two appear when the
 * address landed between bank slots, and then [valueAt] mixes their outputs over a shared local
 * phase and a shared [from]. That sharing is what keeps a fractional address continuous rather
 * than crossfading between two functions that disagree about where they started.
 */
data class MarfResolvedStage(
    val index: Long,
    val start: PatQ,
    val duration: PatQ,
    val address: Double,
    val target: Double,
    val trigger: Boolean,
    val label: String,
    val from: Double,
    val components: List<MarfComponent>
) {
    /** The exclusive end of this stage, in cycles. */
    val end: PatQ get() = start + duration

    /** The half-open span this stage occupies. */
    val arc: PatArc get() = PatArc(start, end)

    /** The blended output at local [phase]. */
    fun valueAt(phase: Double): Double {
        if (components.size == 1) {
            val only = components[0]
            return marfCurveValue(only.stage.curve, from, only.stage.target, phase)
        }
        var sum = 0.0
        for (c in components) sum += c.weight * marfCurveValue(c.stage.curve, from, c.stage.target, phase)
        return sum
    }

    /** The value this stage leaves behind, which is the next stage's [from]. */
    val exitValue: Double get() = valueAt(1.0)
}

/**
 * Everything a reader knows at one instant — the temporal context a curve or a box is written
 * against.
 *
 * The several notions of time are the reason this is a record rather than a `Double`. [cycle] is
 * global, [stageIndex] counts stages since the origin, [stageTime] is cycles since this stage
 * began, and [stagePhase] is that normalised. `noise(cycle)`, `value(stageIndex)` and
 * `sin(stagePhase · 4π)` are three different things and all three are reachable from here.
 */
data class MarfSample(
    val reader: String,
    val value: Double,
    val cycle: Double,
    val stageIndex: Long,
    val stagePhase: Double,
    val stageTime: Double,
    val stageDuration: Double,
    val address: Double,
    val target: Double,
    val from: Double,
    val label: String
)

// ── the reader ────────────────────────────────────────────────────────────────────────────

/**
 * A traversal of stored material: its own address, its own timing, its own phase, its own state.
 *
 * A reader's stage timeline is a pure function of its [origin], its [initial] value and the field
 * patterns it was built from — `Tₖ₊₁ = Tₖ + durationₖ`, with every field latched at `Tₖ`. So it is
 * cached rather than stepped, and asking the same instant twice gives the same answer. The cache
 * grows forward and drops its oldest half past [MARF_TIMELINE_RETAIN] stages; a query that lands
 * before what is retained re-walks from the origin, which is correct but not cheap, so sample
 * roughly forward.
 *
 * The two ways out are [signal] and [triggers], and both are ordinary [PatPattern]s.
 */
class MarfReader internal constructor(
    val name: String,
    val origin: PatQ,
    val initial: Double,
    private val resolve: (index: Long, start: PatQ, previous: MarfResolvedStage?) -> MarfResolvedStage
) {
    private val lock = Any()
    private val timeline = ArrayList<MarfResolvedStage>()

    /** Drops the resolved timeline. Only matters for a reader whose fields read live box state. */
    fun reset() { synchronized(lock) { timeline.clear() } }

    /** The stage active at exact cycle [t]; before [origin], stage 0 at phase 0. */
    fun stageAt(t: PatQ): MarfResolvedStage = synchronized(lock) {
        if (timeline.isEmpty() || t < timeline[0].start) {
            timeline.clear()
            timeline += resolve(0L, origin, null)
        }
        var budget = MARF_WALK_BUDGET
        while (timeline[timeline.size - 1].end <= t) {
            val last = timeline[timeline.size - 1]
            if (budget-- <= 0) throw IllegalStateException(
                "reader '$name' would need more than $MARF_WALK_BUDGET stages to reach cycle " +
                    "${t.toDouble()} from ${last.end.toDouble()} (stage ${last.index}, duration " +
                    "${last.duration.toDouble()}). Either the durations are far shorter than the " +
                    "instant being sampled, or the sample is in seconds where cycles were wanted."
            )
            timeline += resolve(last.index + 1L, last.end, last)
            if (timeline.size > MARF_TIMELINE_RETAIN) timeline.subList(0, MARF_TIMELINE_RETAIN / 2).clear()
        }
        // Last stage with start <= t. The walk above guarantees one exists.
        var lo = 0
        var hi = timeline.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (timeline[mid].start <= t) lo = mid else hi = mid - 1
        }
        timeline[lo]
    }

    /** The full temporal context at exact cycle [t]. */
    fun sample(t: PatQ): MarfSample {
        val s = stageAt(t)
        val dur = s.duration.toDouble()
        val elapsed = (t - s.start).toDouble()
        val phase = if (dur <= 0.0) 0.0 else (elapsed / dur).coerceIn(0.0, 1.0)
        return MarfSample(
            reader = name,
            value = s.valueAt(phase),
            cycle = t.toDouble(),
            stageIndex = s.index,
            stagePhase = phase,
            stageTime = max(0.0, elapsed),
            stageDuration = dur,
            address = s.address,
            target = s.target,
            from = s.from,
            label = s.label
        )
    }

    /** The temporal context at cycle [cycle]. The form to call from a draw loop. */
    fun at(cycle: Number): MarfSample = sample(PatQ.fromDouble(cycle.toDouble(), MARF_DURATION_DENOM))

    /** The temporal context [seconds] into a performance at [cps] cycles per second. */
    fun atSeconds(seconds: Number, cps: Number = 1.0): MarfSample =
        at(seconds.toDouble() * cps.toDouble().coerceAtLeast(1e-6))

    /** Just the output, at cycle [cycle]. */
    fun value(cycle: Number): Double = at(cycle).value

    /**
     * The continuous output, as a signal in the sense of PatSignals.kt.
     *
     * Being a `PatPattern<Double>` and not a new type is the integration: `.range(200, 4000)`,
     * `.segment(16)`, `.quantise(.25)` and `gain(...)` all apply, and the reader can be the right
     * operand of the parameter algebra without knowing it is one.
     */
    fun signal(): PatPattern<Double> = signalOf { exact, _ -> sample(exact).value }

    /** The local stage phase as a signal — a `0..1` sawtooth whose teeth are the stage durations. */
    fun phase(): PatPattern<Double> = signalOf { exact, _ -> sample(exact).stagePhase }

    /** The current address as a signal. Useful mostly for drawing what a reader is doing. */
    fun address(): PatPattern<Double> = signalOf { exact, _ -> sample(exact).address }

    /** The current stage index as a signal. */
    fun stageIndex(): PatPattern<Double> = signalOf { exact, _ -> sample(exact).stageIndex.toDouble() }

    /** Every stage overlapping [arc], in order — the drawing and testing surface. */
    fun stagesIn(arc: PatArc): List<MarfResolvedStage> {
        val out = mutableListOf<MarfResolvedStage>()
        var s = stageAt(arc.start)
        while (s.start < arc.end) {
            out += s
            s = stageAt(s.end)
        }
        return out
    }

    /**
     * Stage boundaries as a discrete pattern: one event per stage, spanning that stage, valued with
     * its label.
     *
     * Stages whose `trigger` sampled false are dropped, so `trigger = "<1 0 0 1>"` thins the pulse
     * stream without touching the continuous output. The event's `whole` is the stage's real span,
     * which is what makes `respond` strike it once and gives the callback the stage's true length
     * even when the query clipped it.
     */
    fun triggers(): PatPattern<String> = PatPattern { arc ->
        stagesIn(arc).mapNotNull { s ->
            if (!s.trigger) return@mapNotNull null
            val visible = s.arc.intersect(arc) ?: return@mapNotNull null
            PatEvent(visible, s.label, whole = s.arc)
        }
    }

    override fun toString(): String = "MarfReader($name)"
}

// ── building readers ──────────────────────────────────────────────────────────────────────

/** The probe arc width used to latch a field pattern at a stage boundary. See the file header. */
private val MARF_PROBE: PatQ = PatQ.of(1, 65536)

/**
 * Which time coordinate a reader's field patterns are read in.
 *
 * This is the one genuine fork in the design, and it is a fork because the two readings answer
 * different questions about a pattern like `"<1 4 .25 2>"` used as a duration.
 *
 * [CYCLES] asks *what does this pattern say right now*. The address, the duration and the value are
 * control voltages read at the instant the stage is entered, which is what the hardware does. Any
 * pattern works: `"0 .7 .2 1"` is four values across the bar and `sine()` is a sweep across it. When
 * the durations happen to sum to a cycle the reader locks to the bar and the fields line up with
 * their own steps; when they do not, everything drifts against everything, which is the point.
 *
 * [STAGES] asks *what is this pattern's k-th entry*, by reading it at cycle `k` — so one cycle of a
 * field pattern is one stage. That makes the fields a sequence: `"<1 4 .25 2>"` gives stages of
 * exactly those four lengths, `slow(3)` holds an entry for three stages, `every(4) { it.rev() }`
 * reverses every fourth group of four. The cost is that a bare `"0 .7 .2 1"` collapses to `0` —
 * four steps inside a cycle, and only the one at the cycle boundary is ever latched. In this mode
 * `<…>` is the idiom and a bare step list is a mistake.
 *
 * [CYCLES] is the default because it has no silent failure: every pattern means something in it.
 */
enum class MarfFieldClock {
    /** Field patterns are read at the stage's wall-clock cycle instant. */
    CYCLES,

    /** Field patterns are read at cycle `k` for stage `k` — one cycle per stage. */
    STAGES;

    internal fun instant(index: Long, start: PatQ): PatQ =
        if (this == CYCLES) start else PatQ.of(index)
}

/** Latches [pattern] at the exact instant [t], sampling a continuous pattern essentially at [t]. */
private fun <A> marfLatch(pattern: PatPattern<A>, t: PatQ, fallback: A): A =
    pattern.patSampleOver(PatArc(t, t + MARF_PROBE), t)?.value ?: fallback

/**
 * A reader whose stages come from independently patterned fields.
 *
 * None of the four patterns needs to share a period with any other, and the useful complexity is
 * exactly that drift:
 *
 *     marf(
 *         value    = struct(euclid(5, 8), pat("0 .25 .8").nums()),
 *         duration = pat("<1 1 2>").nums(),
 *         curve    = curves("<linear exp>").slow(4),
 *         trigger  = bools("<1 0 0 1>").every(3) { it.rev() }
 *     )
 *
 * @param value the target each stage moves toward, latched at the stage's start.
 * @param duration the stage's length in cycles, latched likewise, floored at [MARF_MIN_DURATION].
 * @param curve the shape of the traversal.
 * @param trigger whether this stage emits a pulse from [MarfReader.triggers]. Does not affect the
 *   continuous output.
 * @param speed divides the sampled duration, so `speed = constant(2)` reads twice as fast.
 * @param fields which time coordinate the four patterns are read in. See [MarfFieldClock].
 * @param initial where the output starts, which is stage 0's departure value.
 * @param origin the cycle instant at which stage 0 begins.
 */
fun marf(
    value: PatPattern<Double>,
    duration: PatPattern<Double> = constant(1.0),
    curve: PatPattern<MarfCurve> = marfCurvePattern(MarfCurve.Linear),
    trigger: PatPattern<Boolean> = constantTrue(),
    speed: PatPattern<Double> = constant(1.0),
    fields: MarfFieldClock = MarfFieldClock.CYCLES,
    initial: Number = 0.0,
    origin: Number = 0.0,
    name: String = "marf"
): MarfReader = MarfReader(
    name = name,
    origin = PatQ.fromDouble(origin.toDouble(), MARF_DURATION_DENOM),
    initial = initial.toDouble()
) { index, start, previous ->
    val at = fields.instant(index, start)
    val was = previous?.components?.firstOrNull()?.stage
    val target = marfLatch(value, at, previous?.target ?: 0.0)
    val shape = marfLatch(curve, at, was?.curve ?: MarfCurve.Linear)
    val dur = marfStageDuration(
        marfLatch(duration, at, previous?.duration?.toDouble() ?: 1.0),
        marfLatch(speed, at, 1.0)
    )
    MarfResolvedStage(
        index = index,
        start = start,
        duration = dur,
        address = index.toDouble(),
        target = target,
        trigger = marfLatch(trigger, at, previous?.trigger ?: true),
        label = patFormatNumber(target),
        from = previous?.exitValue ?: initial.toDouble(),
        components = listOf(MarfComponent(0, MarfStage(target, dur, shape), 1.0))
    )
}

/**
 * A reader that traverses [this] bank along [address].
 *
 * The address is the trajectory and nothing else decides it: `"<0 1 2 1 3 4>"`,
 * `"<0 2 4 1 3>".slow(3)`, `struct(euclid(5, 8), pat("0 4 2 7").nums())` and
 * `sine().range(0, 4)` are four readings of the same material. Out-of-range integer addresses
 * wrap; fractional addresses blend, per the file header.
 *
 * @param duration overrides each bank stage's own duration when given. `null` reads the stage's.
 * @param curve overrides each bank stage's own curve when given.
 * @param speed divides the duration in force, whichever that is.
 * @param fields which time coordinate the address, duration and curve patterns are read in.
 *   See [MarfFieldClock] — `STAGES` is what makes `"<0 1 2 1 3 4>"` step the bank one slot per
 *   stage whatever the durations are, and `CYCLES` reads the address as a control voltage.
 * @param initial where the output starts, which is the first stage's departure value.
 */
fun MarfBank.read(
    address: PatPattern<Double>,
    duration: PatPattern<Double>? = null,
    curve: PatPattern<MarfCurve>? = null,
    speed: PatPattern<Double> = constant(1.0),
    fields: MarfFieldClock = MarfFieldClock.CYCLES,
    initial: Number = 0.0,
    origin: Number = 0.0,
    name: String = "reader"
): MarfReader {
    val bank = this
    return MarfReader(
        name = name,
        origin = PatQ.fromDouble(origin.toDouble(), MARF_DURATION_DENOM),
        initial = initial.toDouble()
    ) { index, start, previous ->
        val at = fields.instant(index, start)
        val addr = marfLatch(address, at, previous?.address ?: 0.0)
        val components = bank.componentsAt(addr, curve?.let { marfLatch(it, at, MarfCurve.Linear) })
        val baseDuration = duration?.let { marfLatch(it, at, previous?.duration?.toDouble() ?: 1.0) }
        val dur = if (baseDuration != null) {
            marfStageDuration(baseDuration, marfLatch(speed, at, 1.0))
        } else {
            // Blend the stages' own durations, then apply speed — so a fractional address glides in
            // time as well as in value.
            var blended = 0.0
            for (c in components) blended += c.weight * c.stage.duration.toDouble()
            marfStageDuration(blended, marfLatch(speed, at, 1.0))
        }
        var target = 0.0
        for (c in components) target += c.weight * c.stage.target
        MarfResolvedStage(
            index = index,
            start = start,
            duration = dur,
            address = addr,
            target = target,
            trigger = components.any { it.stage.trigger },
            label = components.maxByOrNull { it.weight }!!.let { it.stage.label ?: it.index.toString() },
            from = previous?.exitValue ?: initial.toDouble(),
            components = components
        )
    }
}

/**
 * The bank slots contributing at [address], with weights summing to 1.
 *
 * An integer address gives one component. A fractional address gives two, `floor` and `floor + 1`,
 * weighted `1-α` and `α` — the scalar output interpolation of the design note. [curveOverride]
 * replaces each component's curve when given.
 */
fun MarfBank.componentsAt(address: Double, curveOverride: MarfCurve? = null): List<MarfComponent> {
    fun slot(i: Int, weight: Double): MarfComponent {
        val wrapped = wrapIndex(i)
        val s = stages[wrapped]
        return MarfComponent(wrapped, if (curveOverride != null) s.copy(curve = curveOverride) else s, weight)
    }
    if (!address.isFinite()) return listOf(slot(0, 1.0))
    val lower = floor(address)
    val alpha = address - lower
    val i = lower.toInt()
    if (alpha <= 1e-12) return listOf(slot(i, 1.0))
    if (alpha >= 1.0 - 1e-12) return listOf(slot(i + 1, 1.0))
    return listOf(slot(i, 1.0 - alpha), slot(i + 1, alpha))
}

/** A sampled duration and a sampled speed, as one exact stage length. */
private fun marfStageDuration(duration: Double, speed: Double): PatQ {
    val s = if (speed.isFinite() && speed > 1e-9) speed else 1.0
    val d = if (duration.isFinite()) duration else 1.0
    return marfDuration(d / s)
}

/** A constant curve, as a pattern — the default `curve` argument of [marf]. */
fun marfCurvePattern(curve: MarfCurve): PatPattern<MarfCurve> =
    PatPattern { arc -> listOf(PatEvent(arc, curve, whole = null)) }

/** A pattern that is `true` everywhere — the default `trigger` argument of [marf]. */
fun constantTrue(): PatPattern<Boolean> = PatPattern { arc -> listOf(PatEvent(arc, true, whole = null)) }

// ── discrete → continuous, with or without a reader ───────────────────────────────────────

/**
 * How far back and forward [hold] and [interp] look for the events around an instant.
 *
 * These conversions are pure functions of the query — no accumulated timeline — so they have to
 * find the neighbouring onsets by asking, and the window bounds what "neighbouring" can mean. Two
 * cycles either side covers anything whose steps are shorter than a couple of bars, which is every
 * ordinary use; a pattern whose onsets are further apart than that will read as held at the edges.
 */
private val MARF_INTERP_WINDOW: Long = 2L

private fun <A> PatPattern<A>.onsetsAround(t: PatQ): List<PatEvent<A>> {
    val centre = qFloor(t)
    val arc = PatArc(PatQ.of(centre - MARF_INTERP_WINDOW), PatQ.of(centre + MARF_INTERP_WINDOW + 1))
    return events(arc).filter { it.hasOnset }.sortedBy { it.extent.start }
}

/**
 * Sample and hold: the value of the event active at each instant, as a continuous signal.
 *
 * This is the conversion the language was missing. `pat("0 .5 1 .25").nums()` is four events a
 * quarter-cycle apart and nothing samples it between them; `.hold()` is a staircase defined
 * everywhere, so it can be a filter cutoff, an `x` coordinate or a radius.
 *
 * Unlike `segment`, this does not change the rhythm — it removes it. The result is analog
 * (`whole == null`) and has no onsets at all; `.segment(n)` is how you get them back.
 *
 * The source must be **discrete**. A signal has one event per query and no onsets to hold between,
 * so `sine().hold()` is meaningless rather than wrong — it is already defined everywhere. A source
 * that is entirely rests reads as `0.0`.
 */
fun PatPattern<Double>.hold(): PatPattern<Double> = signalish { onsets, t ->
    onsets.lastOrNull { it.extent.start <= t }?.value ?: 0.0
}

/**
 * Interpolates between successive event onsets with [curve], as a continuous signal.
 *
 * The phase runs from one onset to the next and the curve shapes it, so `ramp()` is
 * `interp(MarfCurve.Linear)`. Anything richer — a *different* shape per segment, real durations, a
 * bank — is a [MarfReader].
 *
 * `hold()` is **not** `interp(MarfCurve.Hold)`, and the difference is worth knowing because both
 * are defensible. A segment here is named by both its ends, so `Hold`'s "arrive at once" arrives at
 * the *next* onset's value — a staircase one step early. A stage in a [MarfReader] is named only by
 * its destination, so the same curve there is an ordinary sample-and-hold. If you want the
 * staircase, ask for [hold].
 *
 * Before the first onset in the window the value is held at that onset's value; after the last it
 * is held likewise, because there is no next value to aim at.
 */
fun PatPattern<Double>.interp(curve: MarfCurve = MarfCurve.Linear): PatPattern<Double> = signalish { onsets, t ->
    if (onsets.isEmpty()) return@signalish 0.0
    val atOrBefore = onsets.indexOfLast { it.extent.start <= t }
    if (atOrBefore < 0) return@signalish onsets.first().value
    val current = onsets[atOrBefore]
    val next = onsets.getOrNull(atOrBefore + 1) ?: return@signalish current.value
    val span = (next.extent.start - current.extent.start).toDouble()
    if (span <= 0.0) return@signalish next.value
    val phase = ((t - current.extent.start).toDouble() / span).coerceIn(0.0, 1.0)
    marfCurveValue(curve, current.value, next.value, phase)
}

/** Linear interpolation between successive event onsets. `interp(MarfCurve.Linear)`. */
fun PatPattern<Double>.ramp(): PatPattern<Double> = interp(MarfCurve.Linear)

/** Builds a continuous pattern from a function of the onsets around an instant and the instant. */
private fun <A, B> PatPattern<A>.signalish(f: (List<PatEvent<A>>, PatQ) -> B): PatPattern<B> =
    PatPattern { arc ->
        val mid = patArcMidpoint(arc)
        listOf(PatEvent(arc, f(onsetsAround(mid), mid), whole = null))
    }
