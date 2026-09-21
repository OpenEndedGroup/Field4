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
 * combinators of its own. A reader is built from patterns and *is* a pattern: `signal()` hands back
 * a `PatPattern<Double>` in the sense of PatSignals.kt, and [MarfReader.triggers] hands back a
 * discrete `PatPattern<String>`. Both ends plug straight into `with(gain(...))`, `struct`,
 * `segment`, `respond` and the scheduler.
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
 * That recurrence is why this is not a sequencer: the reader's own traversal generates its clock,
 * so the duration pattern is consulted at instants the duration pattern chose. Give four values a
 * `"<1 4 .25 2>"` duration and you get
 *
 *     |--0--|--------.7--------|-.2-|----1----|
 *
 * rather than four equal steps. Give the address a period of 6 and the duration a period of 4 and
 * the two drift against each other with a period of 12 — which is the whole reason to keep them
 * apart.
 *
 * There is one real choice buried in "sampled at `Tₖ`", and [MarfFieldClock] is where it lives.
 * Reading the fields at the wall-clock instant `Tₖ` (`CYCLES`) is the hardware answer
 * and never fails silently: every pattern says something at every instant. Reading them at cycle
 * `k` instead (`STAGES`) makes the fields a per-stage *sequence*, so `duration = "<1 4 .25 2>"`
 * gives stages of exactly those four lengths and `address = "<0 1 2 1 3 4>"` walks the bank one
 * slot per stage no matter how long a stage takes. In `STAGES` one cycle of a field pattern is one
 * stage, so a bare `"0 .7 .2 1"` has three steps the clock cannot see — and rather than collapse to
 * `0`, that is now **refused at construction, by field name**. See [marfCheckFieldClock].
 *
 * `STEPS` is the third reading and the one that means what a sub-cycle field looks like it means:
 * it subdivides each cycle of `STAGES`, so a field contributes one stage per onset and at least one
 * per cycle. `address = "<0 3> 1"` is then the three-entry sequence `0, 1, 3, 1` rather than an
 * error. `STEPS` and `STAGES` agree exactly on every pattern with at most one onset per cycle, so
 * `<…>`, `slow(3)` and a signal read identically in both.
 *
 * Only a stack is beyond all three. `"<0 3>, 1"` puts two layers on one instant, a field latches to
 * one value, and no choice of clock recovers the other — so that too is refused in the sequence
 * readings rather than half-read.
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
 * thirty-two, and an aperiodic address (`perlin()`, `rand()`) never repeats at all. No exception is
 * raised in any of these cases, so the symptom is what you hear rather than what you read.
 *
 * Count the distinct [MarfSample.address] values **rounded** when you check. An aliased address
 * repeats to a few parts in 10⁴ rather than bit-exactly, because the probe instant is an exact
 * rational and `PatQ.toDouble()` rounds it at twelve places — a raw `distinct()` reports seventeen
 * different addresses for the four-value cycle above. `MarfSmokeTest` prints both cases.
 *
 * #### Values other than numbers
 *
 * A stage's value is a type parameter, and [MarfSpace] is the arithmetic a reader needs to move
 * one value toward another: [MarfSpace.sub] for the direction, [MarfSpace.scale] to walk part of
 * it, [MarfSpace.add] to arrive. The curve stays scalar, because a stage's shape is
 * one-dimensional however many dimensions its value has.
 *
 *        val path = marfBankIn(
 *            MarfVector3f,
 *            stageTo(Vector3f(0f, 0f, 0f), 1.0),
 *            stageTo(Vector3f(1f, 0.5f, 0f), 0.5, MarfCurve.Smooth)
 *        ).read(pat("<0 1>").nums(), fields = MarfFieldClock.STAGES)
 *
 *        path.at(1.5).value               // a Vector3f
 *        path.signal { it.x.toDouble() }  // any component, as an ordinary signal
 *
 * `marf(...)` and `marfBank(...)` are the scalar spellings and give a `MarfReader<Double>`;
 * [marfIn] and [marfBankIn] take a space. Instances for Field's generated linalg vectors are in
 * MarfVectors.kt, and they copy before they write — the in-place `add`/`mul`/`normalize` members
 * would rewrite the bank's own stage targets rather than produce one wrong frame.
 *
 * [MarfSpace] is a vector space, which rules out the types that are not one: a quaternion
 * interpolates by `slerp` and a hue wraps, so neither is `add`/`scale` and neither has an instance.
 * Those want a reader whose blend is its own function.
 *
 * #### Nesting a pattern inside a stage
 *
 * A stage's continuous behaviour is a [MarfCurve], and `MarfCurve.Shape` can close over anything,
 * including another reader. What is not modelled is a nested pattern with a local temporal scope
 * of its own: `stagePhase` reaches a `Shape` as a `Double`, so a pattern read in there has no
 * evaluation context to be slowed, reversed or thinned in.
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

// ── what a stage can be made of ───────────────────────────────────────────────────────────

/**
 * The arithmetic a reader needs in order to move a value toward another one.
 *
 * A stage says *where to go*; the curve says *how far along the way we are*. Turning those two
 * into an output needs exactly three operations, and nothing about time or patterns:
 *
 *     value = from + Σ wᵢ · (toᵢ − from) · shapeᵢ(phase)
 *
 * so [sub] to get the direction, [scale] to walk part of it, and [add] to arrive. [zero] is the
 * accumulator's starting point and the default for a reader's initial value.
 *
 * Instances must be **non-mutating**. Field's generated linalg makes this a real hazard rather
 * than a formality: `Vector3f.add(other)`, `mul(scalar)`, `normalize()` and the `/` operator all
 * write into the receiver, so an instance that reached for the obvious spelling would corrupt the
 * stage targets stored in the bank. [MarfVector3f] and its siblings go through the out-parameter
 * forms (`a.add(b, Vector3f())`) for that reason, and `MarfVectorSpaceTest` asserts that reading a bank
 * twice gives the same answer.
 *
 * This is a vector space, which rules out the types that are not one. A quaternion interpolates by
 * `slerp` rather than componentwise, and a hue wraps; neither is expressible as `add`/`scale`, so
 * neither gets an instance here. They would want a reader whose blend is its own function rather
 * than a wider [MarfSpace].
 */
interface MarfSpace<T> {
    /** The additive identity, and the default departure value of a reader's first stage. */
    val zero: T

    fun add(a: T, b: T): T

    fun sub(a: T, b: T): T

    fun scale(a: T, k: Double): T

    /** `from` moved a [fraction] of the way toward `to`. Exactly `from` at 0 and `to` at 1. */
    fun lerp(from: T, to: T, fraction: Double): T = add(from, scale(sub(to, from), fraction))

    /** How a stage of this type prints in a figure or a trigger event. */
    fun label(value: T): String = value.toString()
}

/** The scalar space. The default everywhere, and what `marf(...)` and `marfBank(...)` produce. */
object MarfDoubles : MarfSpace<Double> {
    override val zero: Double get() = 0.0
    override fun add(a: Double, b: Double): Double = a + b
    override fun sub(a: Double, b: Double): Double = a - b
    override fun scale(a: Double, k: Double): Double = a * k
    override fun lerp(from: Double, to: Double, fraction: Double): Double = from + (to - from) * fraction
    override fun label(value: Double): String = patFormatNumber(value)
}

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
data class MarfStage<T>(
    val target: T,
    val duration: PatQ = PatQ.ONE,
    val curve: MarfCurve = MarfCurve.Linear,
    val trigger: Boolean = true,
    val label: String? = null
)

/**
 * The general stage constructor, for any value type. [duration] is in cycles.
 *
 * `stageTo(Vector3f(1f, 0f, 0f), 0.5, MarfCurve.Smooth)`. The scalar helpers below are this with a
 * curve chosen and a `Number` target; a stage needs no [MarfSpace], because nothing is interpolated
 * until a reader reads it.
 */
fun <T> stageTo(
    target: T,
    duration: Number = 1.0,
    curve: MarfCurve = MarfCurve.Linear,
    trigger: Boolean = true,
    label: String? = null
): MarfStage<T> = MarfStage(target, marfDuration(duration), curve, trigger, label)

/** The general scalar stage constructor. [duration] is in cycles. */
fun stage(
    target: Number,
    duration: Number = 1.0,
    curve: MarfCurve = MarfCurve.Linear,
    trigger: Boolean = true,
    label: String? = null
): MarfStage<Double> = MarfStage(target.toDouble(), marfDuration(duration), curve, trigger, label)

/** Step to [target] and stay there for [duration] cycles. */
fun holdAt(target: Number, duration: Number = 1.0, trigger: Boolean = true, label: String? = null): MarfStage<Double> =
    stage(target, duration, MarfCurve.Hold, trigger, label)

/** Slew linearly to [target] over [duration] cycles. */
fun rampTo(target: Number, duration: Number = 1.0, trigger: Boolean = true, label: String? = null): MarfStage<Double> =
    stage(target, duration, MarfCurve.Linear, trigger, label)

/** Slew to [target] as `phase^amount` — a convex rise above 1, concave below. */
fun curveTo(target: Number, duration: Number = 1.0, amount: Number = 3.0, trigger: Boolean = true, label: String? = null): MarfStage<Double> =
    stage(target, duration, MarfCurve.Exp(amount.toDouble()), trigger, label)

/** Oscillate toward [target] and back, [cycles] times across the stage. See [MarfCurve.Osc]. */
fun oscTo(target: Number, duration: Number = 1.0, cycles: Number = 1.0, trigger: Boolean = true, label: String? = null): MarfStage<Double> =
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
class MarfBank<T>(val stages: List<MarfStage<T>>, val space: MarfSpace<T>) {
    init { require(stages.isNotEmpty()) { "a bank needs at least one stage" } }

    val size: Int get() = stages.size

    operator fun get(index: Int): MarfStage<T> = stages[wrapIndex(index)]

    /** Bank indices wrap, so an address pattern never has to know how long the bank is. */
    fun wrapIndex(index: Int): Int {
        val m = index % size
        return if (m < 0) m + size else m
    }

    override fun toString(): String = "MarfBank(${stages.size} stages)"
}

/** A scalar bank from stages written out in order. */
fun marfBank(vararg stages: MarfStage<Double>): MarfBank<Double> = MarfBank(stages.toList(), MarfDoubles)

/** A scalar bank from a list — the form to use when the stages are computed. */
fun marfBank(stages: List<MarfStage<Double>>): MarfBank<Double> = MarfBank(stages, MarfDoubles)

/** A bank of any value type. `marfBankIn(MarfVector3f, stageTo(a), stageTo(b))`. */
fun <T> marfBankIn(space: MarfSpace<T>, vararg stages: MarfStage<T>): MarfBank<T> =
    MarfBank(stages.toList(), space)

/** A bank of any value type, from a list. */
fun <T> marfBankIn(space: MarfSpace<T>, stages: List<MarfStage<T>>): MarfBank<T> = MarfBank(stages, space)

/**
 * A bank of plain held values, for when only the addressing is interesting.
 * `marfValues(0, .3, .9, .2, .8)` is the five-slot voltage source of the test in the design note.
 */
fun marfValues(vararg targets: Number): MarfBank<Double> =
    MarfBank(targets.map { holdAt(it) }, MarfDoubles)

// ── what a reader knows at an instant ─────────────────────────────────────────────────────

/** One bank stage and its weight in a blended (fractional-address) read. */
data class MarfComponent<T>(val index: Int, val stage: MarfStage<T>, val weight: Double)

/**
 * A stage as the reader actually entered it: resolved numbers, an exact span, and a departure value.
 *
 * [components] is the material being read. One component is the ordinary case; two appear when the
 * address landed between bank slots, and then [valueAt] mixes their outputs over a shared local
 * phase and a shared [from]. That sharing is what keeps a fractional address continuous rather
 * than crossfading between two functions that disagree about where they started.
 */
data class MarfResolvedStage<T>(
    val index: Long,
    val start: PatQ,
    val duration: PatQ,
    val address: Double,
    val target: T,
    val trigger: Boolean,
    val label: String,
    val from: T,
    val components: List<MarfComponent<T>>,
    val space: MarfSpace<T>
) {
    /** The exclusive end of this stage, in cycles. */
    val end: PatQ get() = start + duration

    /** The half-open span this stage occupies. */
    val arc: PatArc get() = PatArc(start, end)

    /**
     * The blended output at local [phase].
     *
     * The blend is written as `from + Σ wᵢ·(toᵢ - from)·shapeᵢ(phase)` rather than as
     * `Σ wᵢ·valueᵢ(phase)`. The two are the same arithmetic, because the weights partition 1, but
     * only the first is *exactly* [from] at phase 0: in the second, `Σ wᵢ` for a fractional address
     * is `1 ± 1e-16`, and a value scaled by that lands a few bits away from where the previous
     * stage left off. The gallery's continuity check compares those two numbers with no tolerance,
     * which is how the difference showed up.
     */
    fun valueAt(phase: Double): T {
        if (components.size == 1) {
            val only = components[0]
            return space.lerp(from, only.stage.target, marfCurveShape(only.stage.curve, phase))
        }
        var delta = space.zero
        for (c in components) {
            val weighted = c.weight * marfCurveShape(c.stage.curve, phase)
            delta = space.add(delta, space.scale(space.sub(c.stage.target, from), weighted))
        }
        return space.add(from, delta)
    }

    /** The value this stage leaves behind, which is the next stage's [from]. */
    val exitValue: T get() = valueAt(1.0)
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
data class MarfSample<T>(
    val reader: String,
    val value: T,
    val cycle: Double,
    val stageIndex: Long,
    val stagePhase: Double,
    val stageTime: Double,
    val stageDuration: Double,
    val address: Double,
    val target: T,
    val from: T,
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
class MarfReader<T> internal constructor(
    val name: String,
    val origin: PatQ,
    val initial: T,
    val space: MarfSpace<T>,
    /**
     * The clock this reader's fields were built against.
     *
     * Carried rather than inferred so that a figure can be *asked* which reading it is of, which is
     * what `MarfDocsGuardTest` does: a published snippet that drops `fields = MarfFieldClock.STAGES`
     * while the code that drew the figure kept it is a page describing a picture it cannot produce,
     * and that had already happened twice on `marf.md` before this property existed.
     */
    val fieldClock: MarfFieldClock = MarfFieldClock.STEPS,
    private val resolve: (index: Long, start: PatQ, previous: MarfResolvedStage<T>?) -> MarfResolvedStage<T>
) {
    private val lock = Any()
    private val timeline = ArrayList<MarfResolvedStage<T>>()

    /** Drops the resolved timeline. Only matters for a reader whose fields read live box state. */
    fun reset() { synchronized(lock) { timeline.clear() } }

    /** The stage active at exact cycle [t]; before [origin], stage 0 at phase 0. */
    fun stageAt(t: PatQ): MarfResolvedStage<T> = synchronized(lock) {
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
    fun sample(t: PatQ): MarfSample<T> {
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
    fun at(cycle: Number): MarfSample<T> = sample(PatQ.fromDouble(cycle.toDouble(), MARF_DURATION_DENOM))

    /** The temporal context [seconds] into a performance at [cps] cycles per second. */
    fun atSeconds(seconds: Number, cps: Number = 1.0): MarfSample<T> =
        at(seconds.toDouble() * cps.toDouble().coerceAtLeast(1e-6))

    /** Just the output, at cycle [cycle]. */
    fun value(cycle: Number): T = at(cycle).value

    /**
     * One scalar [component] of the output, as a signal in the sense of PatSignals.kt.
     *
     * This is the way a vector-valued reader reaches the rest of the language, which is scalar
     * nearly everywhere: `path.signal { it.x.toDouble() }` is an ordinary `PatPattern<Double>`, so
     * `.range(200, 4000)`, `.segment(16)` and `gain(...)` all apply to it. A scalar reader has
     * [signal] with no argument for the same purpose.
     */
    fun signal(component: (T) -> Double): PatPattern<Double> =
        signalOf { exact, _ -> component(sample(exact).value) }

    /** The local stage phase as a signal — a `0..1` sawtooth whose teeth are the stage durations. */
    fun phase(): PatPattern<Double> = signalOf { exact, _ -> sample(exact).stagePhase }

    /** The current address as a signal. Useful mostly for drawing what a reader is doing. */
    fun address(): PatPattern<Double> = signalOf { exact, _ -> sample(exact).address }

    /** The current stage index as a signal. */
    fun stageIndex(): PatPattern<Double> = signalOf { exact, _ -> sample(exact).stageIndex.toDouble() }

    /** Every stage overlapping [arc], in order — the drawing and testing surface. */
    fun stagesIn(arc: PatArc): List<MarfResolvedStage<T>> {
        val out = mutableListOf<MarfResolvedStage<T>>()
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
 * reverses every fourth group of four. The cost used to be that a bare `"0 .7 .2 1"` collapsed to
 * `0` — four steps inside a cycle, and only the one at the cycle boundary is ever latched. That is
 * now a construction-time refusal rather than a silence; see [marfCheckFieldClock].
 *
 * [STEPS] asks the same question one level finer: **it subdivides each cycle of [STAGES]**. Cycle
 * `c` of a field contributes as many stages as it has onsets, and at least one, so `"<0 3> 1"` is
 * the three-entry sequence `0, 1, 3, 1` rather than the two-entry `0, 3`. For every pattern with at
 * most one onset per cycle — which is every `<…>`, every `slow(n)` of one, and every signal —
 * [STEPS] and [STAGES] are the same reading, exactly. It is a refinement, not a rival.
 *
 * [AUTO] is the default, and it is the only entry that is not a reading: it asks whether the leading
 * field has a discrete event and answers [STEPS] if it does, [CYCLES] if it does not. [CYCLES] held
 * that seat until 2026-09-21, on the argument that it has no silent failure because every pattern
 * means something in it. That argument was sound and the conclusion was still wrong: the pattern
 * that means something in [CYCLES] is frequently not the pattern the author wrote, and reading
 * `"<0 3> 1"` as `"<0 3>"` without comment is the silent failure it claimed not to have. Naming
 * [STEPS] the blanket default instead trades that for a worse one — a `saw()` address sampled once
 * per cycle is a *constant*, so every continuous field freezes. [AUTO] takes neither trade.
 */
enum class MarfFieldClock {
    /** Field patterns are read at the stage's wall-clock cycle instant. */
    CYCLES,

    /** Field patterns are read at cycle `k` for stage `k` — one cycle per stage. */
    STAGES,

    /** Field patterns are read one *step* per stage — [STAGES] with each cycle subdivided. */
    STEPS,

    /**
     * Read the fields as the sequence they look like, unless they are not a sequence.
     *
     * The default, and the only entry that is not itself a reading: a reader resolves it at
     * construction to [STEPS] or [CYCLES] and reports the answer in [MarfReader.fieldClock], so
     * nothing downstream ever sees `AUTO`.
     *
     * The rule is one question — **does this field have any discrete event at all?**
     *
     * - **Yes** → [STEPS]. `"<0 3> 1"`, `"0 1 2 3"`, `struct(euclid(5, 8), …)` and `"0 ~ .8 ~"` are
     *   written as sequences and are read as the sequences they are, one step per stage.
     * - **No** → [CYCLES]. `saw()`, `sine()` and anything else built from `signalOf` answer a query
     *   with one analog event spanning it, so they have no steps to walk. Walking them anyway
     *   samples once per cycle, and a cycle-periodic signal sampled once per cycle is a *constant* —
     *   `saw().range(0.0, 3.0)` as an address stops moving. Read at the stage's own instant it is
     *   the control voltage it was written as.
     *
     * Why a default that is not one reading: these are two different uses of the same parameter and
     * the layer wants both. A field is a *sequence* when you write steps and a *voltage* when you
     * write a signal, and which one you meant is legible from what you typed. The previous default
     * was [CYCLES] for every field, which read `"<0 3> 1"` as `"<0 3>"` and said nothing; naming
     * [STEPS] as a blanket default instead freezes every `saw`. State a clock explicitly whenever
     * you want the other reading of the same pattern — that is what the three concrete entries are
     * for, and `MarfDocsGuardTest` requires a published snippet to name the clock its figure used.
     */
    AUTO;

    /** True when this clock reads the fields as a per-stage sequence rather than as a voltage. */
    internal val isSequence: Boolean get() = this == STAGES || this == STEPS
}

/**
 * Whether [pattern] carries any **discrete** event over [MARF_FIELD_CHECK_CYCLES] cycles.
 *
 * The whole of [MarfFieldClock.AUTO]'s decision, and the test is `whole != null` rather than
 * [PatEvent.hasOnset]. That distinction is the one thing to get right here, because `hasOnset` says
 * the opposite of what it sounds like: it is `whole == null || whole.start == arc.start`, and its own
 * KDoc explains that **analog events are always onsets** — "there is no earlier fragment they could
 * be the tail of". So `saw()` reports an onset in every cycle, and an `AUTO` written against
 * `hasOnset` sends every signal to [MarfFieldClock.STEPS], which is exactly the freeze it exists to
 * avoid. `whole` is the field that actually separates a step from a voltage: `signalOf` builds its
 * event with `whole = null` (`PatSignals.kt:75`) and mini-notation gives every step a whole.
 *
 * Probed over the same finite window as the refusal check rather than over one cycle, because
 * `"<0 2 4 1 3>".slow(3)` puts a discrete event in every cycle but `"<0 1>"` under a long `slow`
 * need not, and a one-cycle probe would call the sparse case continuous.
 */
internal fun <A> marfFieldIsStepped(pattern: PatPattern<A>): Boolean =
    (0 until MARF_FIELD_CHECK_CYCLES).any { cycle ->
        pattern.events(PatArc(PatQ.of(cycle.toLong()), PatQ.of(cycle.toLong() + 1)))
            .any { it.whole != null }
    }

/**
 * [clock] itself, or the reading [MarfFieldClock.AUTO] resolves to for a reader whose leading field
 * is [primary].
 *
 * Decided from the **leading** field — `value` for [marf]/[marfIn], `address` for
 * [MarfBank.read] — rather than from all of them together, because that is the field whose shape
 * says what the caller was writing. A reader is a sequence because its addresses are a sequence.
 *
 * The mixed case is therefore real and worth knowing: a stepped address with a `saw()` duration
 * resolves to [MarfFieldClock.STEPS], and that `saw` is then sampled once per step, which for a
 * cycle-periodic signal is close to a constant. Say `fields = MarfFieldClock.CYCLES` if the duration
 * is the part you meant to be continuous — one reader carries one clock, so a field that wants the
 * other reading wants its own reader and a `signal()` between them.
 */
internal fun <A> marfResolveClock(clock: MarfFieldClock, primary: PatPattern<A>): MarfFieldClock =
    if (clock != MarfFieldClock.AUTO) clock
    else if (marfFieldIsStepped(primary)) MarfFieldClock.STEPS else MarfFieldClock.CYCLES

// ── the step grid, which is what makes a field a sequence ─────────────────────────────────

/**
 * How many cycles of a field pattern [marfCheckFieldClock] inspects before it accepts one.
 *
 * A finite probe, and it is worth being exact about what that buys: a pattern whose first
 * misbehaving cycle is the seventeenth passes. Sixteen covers every period this file's own idioms
 * produce — `every(4)`, `slow(3)`, `<…>` of six — and the check is run once per reader at
 * construction, so widening it is cheap if a counter-example turns up.
 */
private const val MARF_FIELD_CHECK_CYCLES = 16

/**
 * The instants at which cycle [cycle] of [pattern] has something new to say.
 *
 * The **step grid**: the sorted distinct *boundaries* events draw inside `[cycle, cycle + 1)` — each
 * event's start, plus its end where that end is still inside the cycle — or the cycle's own start
 * when there are none. That fallback is forced rather than chosen. A field written
 * `pat("<0 2 4 1 3>").nums().slow(3)` has onsets only at cycles 0, 3, 6 …; its intervening cycles
 * are a held event with no onset of their own, and the whole point of `slow` on a field is that
 * those cycles are stages too. One step per empty cycle is what keeps `slow(3)` meaning "hold this
 * entry for three stages", which is what [MarfFieldClock.STAGES] has always meant.
 *
 * **Ends are in the grid because that is where a rest begins, and a rest is a step.** An
 * onset-only grid — the first spelling of this function — reads `pat("0 ~ .8 ~")` as the two-step
 * sequence `0 .8`, while the same pattern under [MarfFieldClock.CYCLES] at `duration = .25` reads
 * `0 0 .8 .8`. Two clocks disagreeing about how many stages a pattern *has* is not a choice of
 * reading; it is one of them being wrong.
 *
 * A continuous field has no discrete events at all — `signalOf` answers any query with a single
 * analog event spanning it — so it contributes exactly one step per cycle. That reading is
 * degenerate rather than useful, and it is why [MarfFieldClock.AUTO] sends a field with no onsets
 * to [MarfFieldClock.CYCLES] instead: a cycle-periodic signal sampled once per cycle is a
 * *constant*, which is how a `saw` address came to sit still.
 */
internal fun <A> marfFieldSteps(pattern: PatPattern<A>, cycle: Long): List<PatQ> {
    val c = PatQ.of(cycle)
    val next = PatQ.of(cycle + 1)
    val events = pattern.events(PatArc(c, next))
    // Every boundary an event draws inside the cycle: its start, and its end when that end is still
    // inside the cycle. The end matters because it is where a REST begins, and a rest is a step.
    //
    // Onsets alone were the first spelling of this and they lose the rests. `pat("0 ~ .8 ~")` has
    // onsets at 0 and 1/2 only, so an onset grid reads it as the two-step sequence `0 .8` — while
    // the same pattern under CYCLES at `duration = .25` reads `0 0 .8 .8`, because a rest holds the
    // field it interrupts. Two clocks disagreeing about how many stages a pattern has is not a
    // choice of reading, it is one of them being wrong: `~` is a step you can count, and
    // `struct(euclid(5, 8), …)` on an address is *only* meaningful if its three rests are stages
    // that hold (see `MarfTest.a thinned address pattern holds rather than snapping to zero`).
    //
    // Nothing here needs to supply the held VALUE. The resolve path already answers an instant with
    // no event by holding the previous stage's, which is exactly why CYCLES was right about this
    // all along; the grid only has to stop hiding the instant.
    val bounds = buildList {
        events.forEach { e ->
            add(e.arc.start)
            if (e.arc.end < next) add(e.arc.end)
        }
    }.distinct().sorted().filter { it >= c }
    return if (bounds.isEmpty()) listOf(c) else bounds
}

/** How many events cycle [cycle] of [pattern] carries, onset or not. Used to spot a stack. */
private fun <A> marfFieldEventCount(pattern: PatPattern<A>, cycle: Long): Int =
    pattern.events(PatArc(PatQ.of(cycle), PatQ.of(cycle + 1))).size

/**
 * The **value-bearing** instants in cycle [cycle] of [pattern]: its distinct onsets, or the cycle's
 * own start when it has none.
 *
 * What [marfFieldSteps] was before it grew to include event *ends*, and kept separately rather than
 * folded into it, because the two counts answer different questions and using one for both is a bug
 * in each direction:
 *
 * - [marfFieldSteps] asks **where do stages fall**, so a rest is a step: it is a stage that holds.
 * - This asks **how many values did the author write**, which is what [marfCheckFieldClock] refuses
 *   on. A rest carries no value, so discarding one loses nothing an author could observe.
 *
 * Measured, when they were briefly the same function: refusing on boundaries made
 * `MarfFieldClock.STAGES` reject `"<0 3> ~"` — one onset, one value, nothing discarded — and it
 * made the *stack* check miss `"0 ~, 1"`, because `events > steps` compares two layers against a
 * grid the trailing rest had just widened to two. Onsets restore both.
 */
private fun <A> marfFieldOnsets(pattern: PatPattern<A>, cycle: Long): List<PatQ> {
    val c = PatQ.of(cycle)
    val onsets = pattern.events(PatArc(c, PatQ.of(cycle + 1)))
        .filter { it.hasOnset }
        .map { it.arc.start }
        .distinct()
        .sorted()
    return if (onsets.isEmpty()) listOf(c) else onsets
}

/**
 * The instant at which stage [index] reads [pattern], under [clock].
 *
 * Stateful for [MarfFieldClock.STEPS] only, because the `k`-th step of a pattern cannot be computed
 * without walking the grid to it. A reader asks for indices `0, 1, 2, …` and restarts at `0`
 * whenever its timeline is dropped, so a cursor that rewinds on a backwards ask is exact and
 * usually O(1). Confined by [MarfReader.stageAt]'s lock: one probe belongs to one reader.
 */
internal class MarfFieldProbe<A>(private val pattern: PatPattern<A>, private val clock: MarfFieldClock) {
    private var nextIndex = 0L
    private var cycle = 0L
    private var within = 0
    private var grid: List<PatQ> = emptyList()

    fun instant(index: Long, start: PatQ): PatQ = when (clock) {
        MarfFieldClock.CYCLES -> start
        MarfFieldClock.STAGES -> PatQ.of(index)
        MarfFieldClock.STEPS -> stepInstant(index)
        // Unreachable by construction: `marfIn` and `MarfBank.read` both put `fields` through
        // `marfResolveClock` before a probe is built, so AUTO becomes STEPS or CYCLES there and
        // nothing downstream can observe it. Stated as an error rather than folded into an `else`
        // so that a future entry point which forgets to resolve fails here, loudly, instead of
        // silently picking whichever branch an `else` happened to name.
        MarfFieldClock.AUTO -> error(
            "MarfFieldProbe was built with AUTO — resolve it with marfResolveClock first"
        )
    }

    private fun stepInstant(index: Long): PatQ {
        if (index < nextIndex) rewind()
        while (nextIndex < index) advance()
        if (grid.isEmpty()) grid = marfFieldSteps(pattern, cycle)
        return grid[within]
    }

    private fun rewind() {
        nextIndex = 0L
        cycle = 0L
        within = 0
        grid = emptyList()
    }

    private fun advance() {
        if (grid.isEmpty()) grid = marfFieldSteps(pattern, cycle)
        within++
        if (within >= grid.size) {
            cycle++
            within = 0
            grid = marfFieldSteps(pattern, cycle)
        }
        nextIndex++
    }
}

/**
 * Refuses a field pattern whose structure a per-stage clock cannot see, at construction.
 *
 * This exists because the failure it replaces was measured rather than imagined. On the night
 * `Marf.kt` landed, one person spent four minutes and thirty-eight edit-and-run cycles on a single
 * box, moving an address between `"<0 3> 1"`, `"<0 3> 1*8"`, `"<0 3>*2"`, `"<0 3>, 1"`,
 * `"1 <0 3>"`, `"3 <0 3>"` and `"<0 3> 1 2"`. Eight of those ten spellings produce a sequence
 * byte-identical to something strictly simpler than what was typed, and the layer said nothing
 * about any of them. `marfCurve` already takes the other side of this argument for the same reason
 * — "a typo in a curve pattern is silent otherwise" — and this is the larger instance of it.
 *
 * Two different mistakes, so two different messages:
 *
 * - **more than one step in a cycle** under [MarfFieldClock.STAGES]. Only the step at the cycle
 *   boundary is ever latched. This one has a remedy, and the message names it:
 *   [MarfFieldClock.STEPS] reads exactly the sequence that was written.
 * - **more than one event on a step**, which is a stack (`","`). `patSampleOver` takes the first
 *   layer and the rest are unreachable. No clock fixes that, because a stack is simultaneity and a
 *   field is a sequence, so the message says so rather than suggesting a mode that would not help.
 *
 * [MarfFieldClock.CYCLES] is exempt: a field is a control voltage there, every pattern means
 * something, and a stack read at a wall-clock instant is the language's ordinary behaviour
 * everywhere else in the parameter algebra.
 */
internal fun <A> marfCheckFieldClock(
    field: String,
    reader: String,
    pattern: PatPattern<A>,
    clock: MarfFieldClock
) {
    if (!clock.isSequence) return
    for (cycle in 0L until MARF_FIELD_CHECK_CYCLES.toLong()) {
        val steps = marfFieldOnsets(pattern, cycle)
        if (clock == MarfFieldClock.STAGES && steps.size > 1) {
            throw IllegalArgumentException(
                "reader '$reader': the $field pattern puts ${steps.size} steps inside cycle $cycle " +
                    "(at ${steps.joinToString(", ") { it.toString() }}), and MarfFieldClock.STAGES " +
                    "reads one instant per cycle — so only the step at $cycle is ever latched and " +
                    "the other ${steps.size - 1} " +
                    (if (steps.size == 2) "is" else "are") + " discarded in silence. Either write " +
                    "the $field as an alternation, `<…>`, which is one step a cycle, or ask for " +
                    "the sequence you wrote with fields = MarfFieldClock.STEPS."
            )
        }
        val events = marfFieldEventCount(pattern, cycle)
        if (events > steps.size) {
            throw IllegalArgumentException(
                "reader '$reader': the $field pattern stacks $events layers across ${steps.size} " +
                    "step(s) in cycle $cycle, and a field is latched to a single value per stage — " +
                    "the first layer wins and the rest are unreachable. A stack (`,`) is " +
                    "simultaneity and a field is a sequence, so no MarfFieldClock reads this; " +
                    "pick one layer, or combine them into one pattern before handing it over."
            )
        }
    }
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
    fields: MarfFieldClock = MarfFieldClock.AUTO,
    initial: Number = 0.0,
    origin: Number = 0.0,
    name: String = "marf"
): MarfReader<Double> = marfIn(
    space = MarfDoubles,
    value = value,
    duration = duration,
    curve = curve,
    trigger = trigger,
    speed = speed,
    fields = fields,
    initial = initial.toDouble(),
    origin = origin,
    name = name
)

/**
 * [marf] for any value type: a reader whose stages come from independently patterned fields, with
 * the arithmetic supplied by [space].
 *
 *     marfIn(
 *         MarfVector3f,
 *         value = pat("<0 1 2>").nums().map { Vector3f(it.toFloat(), 0f, 0f) },
 *         duration = pat("<1 .5>").nums(),
 *         curve = curves("<smooth linear>")
 *     )
 *
 * Only `value` changes type. The duration, curve, trigger and speed patterns stay scalar, because a
 * stage's *timing* and *shape* are one-dimensional whatever its value is.
 */
fun <T> marfIn(
    space: MarfSpace<T>,
    value: PatPattern<T>,
    duration: PatPattern<Double> = constant(1.0),
    curve: PatPattern<MarfCurve> = marfCurvePattern(MarfCurve.Linear),
    trigger: PatPattern<Boolean> = constantTrue(),
    speed: PatPattern<Double> = constant(1.0),
    fields: MarfFieldClock = MarfFieldClock.AUTO,
    initial: T = space.zero,
    origin: Number = 0.0,
    name: String = "marf"
): MarfReader<T> {
    val clock = marfResolveClock(fields, value)
    marfCheckFieldClock("value", name, value, clock)
    marfCheckFieldClock("duration", name, duration, clock)
    marfCheckFieldClock("curve", name, curve, clock)
    marfCheckFieldClock("trigger", name, trigger, clock)
    marfCheckFieldClock("speed", name, speed, clock)
    val atValue = MarfFieldProbe(value, clock)
    val atDuration = MarfFieldProbe(duration, clock)
    val atCurve = MarfFieldProbe(curve, clock)
    val atTrigger = MarfFieldProbe(trigger, clock)
    val atSpeed = MarfFieldProbe(speed, clock)
    return MarfReader(
        name = name,
        origin = PatQ.fromDouble(origin.toDouble(), MARF_DURATION_DENOM),
        initial = initial,
        space = space,
        fieldClock = clock
    ) { index, start, previous ->
    val was = previous?.components?.firstOrNull()?.stage
    val target = marfLatch(value, atValue.instant(index, start), previous?.target ?: space.zero)
    val shape = marfLatch(curve, atCurve.instant(index, start), was?.curve ?: MarfCurve.Linear)
    val dur = marfStageDuration(
        marfLatch(duration, atDuration.instant(index, start), previous?.duration?.toDouble() ?: 1.0),
        marfLatch(speed, atSpeed.instant(index, start), 1.0)
    )
    MarfResolvedStage(
        index = index,
        start = start,
        duration = dur,
        address = index.toDouble(),
        target = target,
        trigger = marfLatch(trigger, atTrigger.instant(index, start), previous?.trigger ?: true),
        label = space.label(target),
        from = previous?.exitValue ?: initial,
        components = listOf(MarfComponent(0, MarfStage(target, dur, shape), 1.0)),
        space = space
    )
    }
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
 *   stage whatever the durations are, `STEPS` does the same for an address with several steps in a
 *   cycle (`"<0 3> 1"`), and `CYCLES` reads the address as a control voltage. The two sequence
 *   readings refuse, at construction, an address whose structure they cannot see.
 * @param initial where the output starts, which is the first stage's departure value.
 */
fun <T> MarfBank<T>.read(
    address: PatPattern<Double>,
    duration: PatPattern<Double>? = null,
    curve: PatPattern<MarfCurve>? = null,
    speed: PatPattern<Double> = constant(1.0),
    fields: MarfFieldClock = MarfFieldClock.AUTO,
    initial: T = space.zero,
    origin: Number = 0.0,
    name: String = "reader"
): MarfReader<T> {
    val bank = this
    val clock = marfResolveClock(fields, address)
    marfCheckFieldClock("address", name, address, clock)
    duration?.let { marfCheckFieldClock("duration", name, it, clock) }
    curve?.let { marfCheckFieldClock("curve", name, it, clock) }
    marfCheckFieldClock("speed", name, speed, clock)
    val atAddress = MarfFieldProbe(address, clock)
    val atDuration = duration?.let { MarfFieldProbe(it, clock) }
    val atCurve = curve?.let { MarfFieldProbe(it, clock) }
    val atSpeed = MarfFieldProbe(speed, clock)
    return MarfReader(
        name = name,
        origin = PatQ.fromDouble(origin.toDouble(), MARF_DURATION_DENOM),
        initial = initial,
        space = bank.space,
        fieldClock = clock
    ) { index, start, previous ->
        val addr = marfLatch(address, atAddress.instant(index, start), previous?.address ?: 0.0)
        val components = bank.componentsAt(
            addr,
            curve?.let { marfLatch(it, atCurve!!.instant(index, start), MarfCurve.Linear) }
        )
        val baseDuration = duration?.let {
            marfLatch(it, atDuration!!.instant(index, start), previous?.duration?.toDouble() ?: 1.0)
        }
        val dur = if (baseDuration != null) {
            marfStageDuration(baseDuration, marfLatch(speed, atSpeed.instant(index, start), 1.0))
        } else {
            // Blend the stages' own durations, then apply speed — so a fractional address glides in
            // time as well as in value.
            var blended = 0.0
            for (c in components) blended += c.weight * c.stage.duration.toDouble()
            marfStageDuration(blended, marfLatch(speed, atSpeed.instant(index, start), 1.0))
        }
        var target = bank.space.zero
        for (c in components) target = bank.space.add(target, bank.space.scale(c.stage.target, c.weight))
        MarfResolvedStage(
            index = index,
            start = start,
            duration = dur,
            address = addr,
            target = target,
            trigger = components.any { it.stage.trigger },
            label = components.maxByOrNull { it.weight }!!.let { it.stage.label ?: it.index.toString() },
            from = previous?.exitValue ?: initial,
            components = components,
            space = bank.space
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
fun <T> MarfBank<T>.componentsAt(address: Double, curveOverride: MarfCurve? = null): List<MarfComponent<T>> {
    fun slot(i: Int, weight: Double): MarfComponent<T> {
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

/**
 * The continuous output of a scalar reader, as a signal in the sense of PatSignals.kt.
 *
 * Being a `PatPattern<Double>` and not a new type is the integration: `.range(200, 4000)`,
 * `.segment(16)`, `.quantise(.25)` and `gain(...)` all apply, and the reader can be the right
 * operand of the parameter algebra without knowing it is one.
 *
 * An extension rather than a member because it only means anything for `T = Double`. A reader over
 * some other type reaches the same place one component at a time, through
 * [MarfReader.signal]`{ it.x }`.
 */
fun MarfReader<Double>.signal(): PatPattern<Double> = signal { it }

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
