package afield.stacksl.cpu

import kotlin.math.absoluteValue
import kotlin.math.exp
import kotlin.math.tanh

/**
 * A differentiable surrogate for the jump-discontinuous GLSL operations, as a **mode** of the one
 * AD evaluation rather than a second one.
 *
 * #### What is actually wrong, which is not what it looks like
 *
 * `floor`, `ceil`, `round`, `trunc`, `sign` and `step` carry **exactly zero** derivative in
 * [GlslAdScalars], and that is the *correct* derivative of a locally constant function, not a
 * missing feature. The problem a fit has with them is not that the answer is wrong; it is that the
 * answer is useless. A wire behind a `threshold` moves the loss only by pushing pixels across the
 * edge, and the derivative of that is a sum of deltas sitting on a measure-zero set — so gradient
 * descent reads zero everywhere and stands still.
 *
 * So the surrogate is not a smoothing of an operation. It is a **mollification of a delta train**.
 * Each of these operations is piecewise constant with jumps at known places, and its distributional
 * derivative is exactly `Σ (jump height) · δ(x − jump)`. Convolve each delta with a bump of width
 * `t` and you have a finite, smooth, honest object: the derivative of the operation *as seen at
 * scale `t`*. [kernel] is that bump, and every site in [GlslAdScalars] that used to push a zero
 * partial now pushes `jumpHeight × kernel(distance to the nearest jump)`.
 *
 * The bump is the logistic kernel, `K_t(u) = sech²(u / 2t) / 4t`. It integrates to 1 over the whole
 * line and to `tanh(1 / 4t)` over one unit period, so at `t = 0.25` a staircase surrogate carries
 * 76% of the jump it stands for and at `t = 0.05` it carries 99.3%. As `t → 0` the kernel converges
 * to the delta and the surrogate converges to the true (zero, almost everywhere) derivative — so an
 * annealing schedule is a well-defined thing to ask for rather than a knob with no limit.
 *
 * Whether it *helps* is a separate question, and the measured answer is **no**: a fixed `t = 0.25`
 * recovers marginally better than a `0.25 → 0.01` schedule on every continuous case in
 * `dev_docs/STACKSL_SURROGATE.md`'s corpus. [AdAnnealing] stays because a fixed temperature is one
 * of its cases and because convergence to the exact gradient is worth having near the end of a fit
 * — not because cooling was shown to buy anything.
 *
 * Cooling to `0.016` used to lose a nested predicate outright, and that is where the page's
 * temperature floor came from. It was not the kernel's doing: `select` lowers to a `step` inside a
 * `step` and the outer one was being charged its own `K_t(0.5)`. `GlslAdScalars`' mask tracking
 * passes that partial through instead, and every arm of the schedule sweep now recovers.
 *
 * #### Straight-through: the primal does not move, by construction
 *
 * The surrogate replaces **only the local partial**. Every value [GlslAdScalars] pushes is still
 * the value the `Float` path computes, at the same rounding points. Three things follow, and they
 * are the reason this design was chosen over smoothing the forward pass:
 *
 *  1. the differentiable pass's primal image stays **bit-identical** to `GlslFloatScalars`', so
 *     `StackSLGradientTest`'s and `StackSLCpuFloatPathManifestTest`'s claims survive untouched —
 *     and `StackSLSurrogateTest` asserts that at every temperature rather than assuming it;
 *  2. a descent reads the **true** loss and a surrogate *direction*. There is no relaxation gap to
 *     close at the end: the number the optimiser reports is the number the shader will render;
 *  3. the bias is therefore entirely in the direction, which is a thing that can be measured
 *     against one oracle. See `dev_docs/STACKSL_SURROGATE.md`: a surrogate at temperature `t`
 *     approximates the **`t`-scale central difference** of the true loss, not its derivative, and
 *     that is what it is checked against.
 *
 * #### Per family, because one global switch is the wrong shape
 *
 * `floor` is not one thing. In `posterize` and `bit-crush` it is a quantiser and its zero derivative
 * is the wall. In `_noise`'s lattice it is the **cell index**, where zero is the true derivative —
 * the four corner hashes genuinely are constants inside a cell — and switching a surrogate on there
 * leaks `_hash21`'s chaotic gradient into the answer and destroys a wire that already worked. The
 * landed measurement is blunt about it: *"`floor` is what makes a noise frequency differentiable"*.
 *
 * A single boolean cannot express "quantise softly but leave the lattice alone", so [families] is a
 * set and the measurement in `dev_docs/STACKSL_SURROGATE.md` says which combination to use for
 * which program. [STEP_ONLY] is the one that is safe everywhere measured.
 */
class AdSurrogate private constructor(
    /** The bump width, in the units of the quantity being floored. `0.0` means off. */
    val temperature: Double,
    /** Which jump families get a surrogate partial; the rest keep their exact zero. */
    val families: Set<AdSurrogateFamily>
) {
    init {
        require(temperature >= 0.0 && temperature.isFinite()) { "temperature must be finite and >= 0" }
    }

    /** True when any partial in this object differs from the exact zero. */
    val enabled: Boolean get() = temperature > 0.0 && families.isNotEmpty()

    /** Is [family] surrogated here? */
    fun covers(family: AdSurrogateFamily): Boolean = enabled && family in families

    /** The same families at a different temperature — what an annealing schedule steps. */
    fun at(temperature: Double): AdSurrogate = AdSurrogate(temperature, families)

    /**
     * The mollified delta: `jump · sech²(u / 2t) / 4t`, with `u` the signed distance to the jump.
     *
     * Computed through `exp(-|z|)` rather than `cosh`, because `cosh(z)²` overflows a `Double` at
     * `|z| ≈ 355` while this expression simply underflows to zero, and `u / t` reaches that easily
     * at a small temperature.
     */
    fun kernel(u: Double, jump: Double): Double {
        if (!enabled) return 0.0
        if (!u.isFinite()) return 0.0
        val z = (u / (2.0 * temperature)).absoluteValue
        if (z > 40.0) return 0.0
        val e = exp(-z)
        // sech(z) = 2 e^-z / (1 + e^-2z); sech^2(z) = 4 e^-2z / (1 + e^-2z)^2
        val d = 1.0 + e * e
        val sech2 = 4.0 * e * e / (d * d)
        return jump * sech2 / (4.0 * temperature)
    }

    /**
     * The fraction of a unit-period jump this kernel actually carries: `tanh(1 / 4t)`.
     *
     * Reported rather than hidden because it is the surrogate's most load-bearing number. At
     * `t = 0.5` it is 0.462, so half the signal has leaked into the neighbouring jumps and the
     * surrogate is measuring a blur of the staircase rather than one step of it.
     */
    val unitPeriodMass: Double get() = if (enabled) tanh(1.0 / (4.0 * temperature)) else 0.0

    override fun toString(): String =
        if (!enabled) "surrogate:off"
        else "surrogate:t=$temperature:" + families.sortedBy { it.name }.joinToString("+") { it.name }

    override fun equals(other: Any?): Boolean =
        other is AdSurrogate && other.temperature == temperature && other.families == families

    override fun hashCode(): Int = temperature.hashCode() * 31 + families.hashCode()

    companion object {
        /** The exact derivative: every jump carries zero. This is the default everywhere. */
        val OFF = AdSurrogate(0.0, emptySet())

        /** The temperature a sweep starts from unless told otherwise — a quarter of a unit cell. */
        const val DEFAULT_TEMPERATURE = 0.25

        /**
         * Every family at [temperature]. Convenient for a probe, and *not* the right default for a
         * fit: see the file KDoc on why a noise lattice is worse off with a soft `floor`.
         */
        fun all(temperature: Double = DEFAULT_TEMPERATURE): AdSurrogate =
            AdSurrogate(temperature, AdSurrogateFamily.entries.toSet())

        /**
         * Only the `step` family — the combination measured to widen a fit without costing anything
         * elsewhere, because no smooth gallery program routes a wire through `step`.
         */
        fun stepOnly(temperature: Double = DEFAULT_TEMPERATURE): AdSurrogate =
            AdSurrogate(temperature, setOf(AdSurrogateFamily.STEP))

        /** Only the quantisers — `posterize`, `bit-crush`, and a bare `floor`/`round`/`ceil`. */
        fun staircaseOnly(temperature: Double = DEFAULT_TEMPERATURE): AdSurrogate =
            AdSurrogate(temperature, setOf(AdSurrogateFamily.STAIRCASE))

        /** An arbitrary combination. */
        fun of(temperature: Double, families: Set<AdSurrogateFamily>): AdSurrogate =
            AdSurrogate(temperature, families.toSet())
    }
}

/**
 * The three jump families, split by *where the jumps are* rather than by builtin name — because
 * that is what decides the surrogate partial, and two builtins with the same jump lattice want the
 * same treatment.
 */
enum class AdSurrogateFamily {
    /**
     * The quantisers: `floor` and `ceil` (jumps at every integer), `trunc` (every integer **except
     * zero** — `trunc` is flat across the origin, which is the one asymmetry in this family), and
     * `round`/`roundEven` (jumps at the half-integers). Every jump has height 1.
     *
     * `fract` and `mod` inherit it: they are `x − floor(x)` and `x − y·floor(x/y)` in
     * [GlslAdScalars] already, so a surrogated `floor` makes the sawtooth's `−1` jump mollified too,
     * with no separate site.
     */
    STAIRCASE,

    /**
     * `step(edge, x)`: one jump of height 1 at `x == edge`, and the partial is `+K` in `x` and
     * `−K` in `edge`, since the jump moves with the edge.
     *
     * This is the family that matters most in practice, because three StackSL words lower to it:
     * `threshold` is `step(edge, value)`, `step` is itself, and `select`/`if` is
     * `mix(f, t, step(0.5, abs(cond)))` — so a soft `step` is also what lets a gradient reach a
     * `select`'s condition.
     *
     * That last lowering puts a `step` *inside* a `step`, and the outer one is **not** a second
     * jump: `cond` is a 0/1 mask in the common case, and `step(0.5, abs(·))` restricted to {0, 1}
     * is the identity. `GlslAdScalars`'s mask tracking recognises that and passes the partial
     * through, so the chain carries the inner jump's kernel once instead of multiplying it by
     * `K_t(0.5)` — which decayed like `exp(−1/2t)` and used to switch `select` off as a schedule
     * cooled. `and` and `or` come along, since they reduce two masks with `min`/`max`. `not` does
     * not: it is `1.0 − mask`, which the tracking deliberately does not follow.
     */
    STEP,

    /** `sign(x)`: one jump at the origin, of height **2** — it goes from `−1` to `+1`. */
    SIGN
}

/**
 * A geometric annealing schedule from [start] down to [end], plus the driver that steps it.
 *
 * Geometric rather than linear because the quantity being annealed is a *width*, and the
 * interesting range spans two decades (a quarter of a cell down to a percent of one); a linear
 * schedule spends most of its iterations in the top decade, where the surrogate is a blur.
 *
 * **The driver is the evaluation counter, not the iteration counter,** and that is a deliberate
 * approximation rather than an oversight. `minimizeWithGradient` owns the loop and does not hand a
 * closure its iteration number; under `ADAM` and `SGD` it evaluates exactly once per iteration so
 * the two coincide, but `LBFGS` line-searches and will evaluate several times. Progress is therefore
 * `min(1, evaluations / budget)` and a line search makes the schedule cool *faster* than the
 * iteration count suggests. Stated here so a reading of an LBFGS run is not surprised by it.
 */
class AdAnnealing(
    /** The temperature at the first evaluation. */
    val start: Double,
    /** The temperature at the last evaluation of the budget. */
    val end: Double,
    /** The evaluation count the schedule is scaled to — normally the iteration count. */
    val budget: Int,
    /** Which families the schedule anneals. */
    val families: Set<AdSurrogateFamily>
) {
    init {
        require(start > 0.0 && end > 0.0) { "an annealed temperature must be positive at both ends" }
        require(budget > 0) { "budget must be positive" }
    }

    private var evaluations = 0

    /** The temperature at evaluation [index], counting from zero. Pure — no state is touched. */
    fun temperatureAt(index: Int): Double {
        if (budget <= 1) return end
        val progress = (index.toDouble() / (budget - 1).toDouble()).coerceIn(0.0, 1.0)
        return start * Math.pow(end / start, progress)
    }

    /** The surrogate for the *next* evaluation, and advance. Call once per loss evaluation. */
    fun next(): AdSurrogate {
        val s = AdSurrogate.of(temperatureAt(evaluations), families)
        evaluations++
        return s
    }

    /** How many times [next] has been called. */
    val used: Int get() = evaluations

    override fun toString(): String =
        "anneal:$start->$end over $budget:" + families.sortedBy { it.name }.joinToString("+") { it.name }

    companion object {
        /** `step`-family annealing over [iterations], from a quarter cell to a percent of one. */
        fun stepOnly(iterations: Int, start: Double = 0.25, end: Double = 0.01): AdAnnealing =
            AdAnnealing(start, end, iterations, setOf(AdSurrogateFamily.STEP))

        /** Every family, annealed. See [AdSurrogate.all] for why this is not a default. */
        fun all(iterations: Int, start: Double = 0.25, end: Double = 0.01): AdAnnealing =
            AdAnnealing(start, end, iterations, AdSurrogateFamily.entries.toSet())
    }
}
