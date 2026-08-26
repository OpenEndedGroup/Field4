@file:Suppress("MemberVisibilityCanBePrivate")
@file:afield.CompletionCategory("Math")

package afield.stdlib

import kotlin.math.absoluteValue
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Gradient-descent optimizers that consume the exact gradients from `autodiff.kt` — [sgd], [adam]
 * and [lbfgs], behind one [minimize] front door.
 *
 * All three are **deterministic given their seed** and all three record a full [OptimizeRun.losses] trajectory
 * which you can hand straight to `showSignals("loss" to run.losses)` and look at. That discipline is the
 * same one [FnEvolveRun] and [FnFitRun] keep, and for the same reason: a fitted gesture in live art
 * is only usable if it comes back identical tomorrow.
 *
 * ```kotlin
 * val run = minimize(doubleArrayOf(-1.2, 1.0), OptimizeOptions(method = OptimizeMethod.LBFGS)) { p ->
 *     (1.0 - p[0]).sq() + 100.0 * (p[1] - p[0].sq()).sq()   // Rosenbrock
 * }
 * run.x          // ~[1, 1]
 * run.losses     // the trajectory, ready to plot
 * ```
 *
 * ## Which one
 *
 * - [lbfgs] is the right default for a smooth deterministic objective with a handful to a few
 *   hundred parameters: it builds a curvature model from the last `m` steps and takes
 *   Newton-shaped strides, so it reaches machine-precision minima in tens of iterations where Adam
 *   is still crawling. It needs the objective to actually be smooth; a kinked or noisy loss will
 *   make its line search flail.
 * - [adam] is the robust workhorse for a rough, kinked or stochastic landscape, and the one that
 *   tolerates a badly scaled parameter vector without preconditioning.
 * - [sgd] with momentum is here because it is sometimes what you want *artistically* — a slower,
 *   visibly settling trajectory with a tunable inertia, which reads as motion rather than as a
 *   solver converging.
 *
 * The line search inside [lbfgs] is backtracking Armijo, which guarantees monotone descent but not
 * the curvature (Wolfe) condition; the L-BFGS memory therefore skips any update pair with
 * non-positive curvature rather than letting an indefinite model through.
 */

/** Which descent to run. See the file KDoc for when each earns its place. */
enum class OptimizeMethod {
    /** Plain gradient descent with (optionally Nesterov) momentum. */
    SGD,

    /** Adaptive moment estimation — per-parameter step sizes from the gradient's running moments. */
    ADAM,

    /** Limited-memory BFGS with a backtracking Armijo line search. */
    LBFGS
}

/**
 * Everything that shapes a [minimize] run, kept as one immutable value so a result can carry its
 * own provenance.
 *
 * The defaults are a 500-iteration Adam at `lr = 0.05` — the same learning rate
 * [fitGradient] uses, so switching a fit from finite differences to exact gradients does not also
 * silently change the step size.
 */
data class OptimizeOptions(
    /** Which optimizer to run. */
    val method: OptimizeMethod = OptimizeMethod.ADAM,
    /** Maximum number of iterations. */
    val iterations: Int = 500,
    /** Step size. Ignored by [OptimizeMethod.LBFGS], whose line search chooses its own. */
    val learningRate: Double = 0.05,
    /** Momentum coefficient for [OptimizeMethod.SGD]. */
    val momentum: Double = 0.9,
    /** Use the Nesterov look-ahead form of momentum. */
    val nesterov: Boolean = false,
    /** Adam's first-moment decay. */
    val beta1: Double = 0.9,
    /** Adam's second-moment decay. */
    val beta2: Double = 0.999,
    /** Adam's denominator floor. */
    val epsilon: Double = 1e-8,
    /** How many `(s, y)` curvature pairs L-BFGS remembers. */
    val memory: Int = 8,
    /** Armijo sufficient-decrease constant for the L-BFGS line search. */
    val armijo: Double = 1e-4,
    /** How many halvings the line search will try before giving up on an iteration. */
    val maxLineSearchSteps: Int = 32,
    /** Stop when the gradient's infinity norm falls to this. */
    val gradientTolerance: Double = 1e-9,
    /** Stop when a step moves the parameters less than this. */
    val stepTolerance: Double = 1e-14,
    /** If > 0, jitter the start by `±initJitter·(1+|x|)` using [rngSeed] before descending. */
    val initJitter: Double = 0.0,
    /** The single source of randomness; with `initJitter = 0` the run is randomness-free anyway. */
    val rngSeed: Long = 0L,
    /** Whether to offer a progress bar. Turn it off for an inner loop (a restart sweep, a demo). */
    val showProgress: Boolean = true
) {
    init {
        require(iterations >= 0) { "iterations must be non-negative" }
        require(learningRate > 0.0) { "learningRate must be positive" }
        require(memory >= 1) { "memory must be >= 1" }
        require(armijo > 0.0 && armijo < 1.0) { "armijo must be in (0,1)" }
    }
}

/** One recorded iteration: the objective and the gradient's infinity norm after the step. */
data class OptimizeStep(
    /** 1-based iteration index; iteration 0 is the starting point and lives only in [OptimizeRun.losses]. */
    val iteration: Int,
    /** The objective value after this step. */
    val value: Double,
    /** The gradient's infinity norm after this step — the convergence signal that counts. */
    val gradientInfNorm: Double
)

/**
 * The result and provenance of a [minimize] run, shaped like [FnFitRun] so the two read the same
 * way at a call site.
 *
 * [losses] is start-inclusive, so `losses.first()` is the objective before any step and
 * `losses.last()` is [value]; that is what makes it plottable without an off-by-one.
 */
data class OptimizeRun(
    /** The parameters at the end of the run. */
    val x: DoubleArray,
    /** The objective at [x]. */
    val value: Double,
    /** The exact gradient at [x]. */
    val gradient: DoubleArray,
    /** The starting parameters, after any [OptimizeOptions.initJitter]. */
    val initialX: DoubleArray,
    /** The objective at [initialX]. */
    val initialValue: Double,
    /** Per-iteration records. */
    val steps: List<OptimizeStep>,
    /** The objective trajectory including the starting point. */
    val losses: List<Double>,
    /** How many iterations actually ran. */
    val iterations: Int,
    /** How many times the objective (and its gradient) was evaluated. */
    val evaluations: Int,
    /** Whether a tolerance was met, as opposed to running out of iterations. */
    val converged: Boolean,
    /** One of "gradient", "step", "line search", "max iterations", "no iterations". */
    val reason: String,
    /** The options this run was made with. */
    val options: OptimizeOptions
) {
    /** True when the run ended strictly below where it started. */
    val improved: Boolean get() = value < initialValue

    /** The gradient's infinity norm at [x]. */
    val gradientInfNorm: Double get() = if (gradient.isEmpty()) 0.0 else gradient.maxOf { it.absoluteValue }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OptimizeRun) return false
        return x.contentEquals(other.x) && value == other.value && losses == other.losses &&
            iterations == other.iterations && evaluations == other.evaluations &&
            converged == other.converged && reason == other.reason && options == other.options
    }

    override fun hashCode(): Int {
        var result = x.contentHashCode()
        result = 31 * result + value.hashCode()
        result = 31 * result + losses.hashCode()
        result = 31 * result + reason.hashCode()
        return result
    }
}

/**
 * Minimises an objective written in [AD] arithmetic, using exact gradients.
 *
 * The overload every live-coder wants: pass the loss, get the answer and its whole trajectory.
 *
 * ```kotlin
 * val run = minimize(doubleArrayOf(0.0, 0.0)) { p -> (p[0] - 3.0).sq() + 2.0 * (p[1] + 1.0).sq() }
 * ```
 */
fun minimize(
    initial: DoubleArray,
    options: OptimizeOptions = OptimizeOptions(),
    objective: (List<AD>) -> AD
): OptimizeRun = minimizeWithGradient(initial, options, valueAndGradOf(objective))

/**
 * Minimises an objective you already have a value-and-gradient function for — the seam for a
 * gradient that does not come from this file's [AD] (a hand-derived one, or the exact `Fn`-graph
 * gradient in `autodiff_fn.kt`).
 */
fun minimizeWithGradient(
    initial: DoubleArray,
    options: OptimizeOptions = OptimizeOptions(),
    valueAndGradient: (DoubleArray) -> ADGradient
): OptimizeRun {
    require(initial.isNotEmpty()) { "initial must not be empty" }

    val x = initial.copyOf()
    if (options.initJitter > 0.0) {
        val rng = Random(options.rngSeed)
        for (i in x.indices) {
            x[i] += (rng.nextDouble() * 2.0 - 1.0) * options.initJitter * (1.0 + x[i].absoluteValue)
        }
    }
    val startX = x.copyOf()

    var evaluations = 0
    fun eval(p: DoubleArray): ADGradient {
        evaluations++
        return valueAndGradient(p)
    }

    var current = eval(x)
    val losses = mutableListOf(current.value)
    val steps = mutableListOf<OptimizeStep>()

    if (options.iterations == 0) {
        return OptimizeRun(
            x, current.value, current.gradient, startX, losses.first(), steps, losses,
            0, evaluations, converged = false, reason = "no iterations", options = options
        )
    }

    val state: DescentState = when (options.method) {
        OptimizeMethod.SGD -> SgdState(x.size)
        OptimizeMethod.ADAM -> AdamState(x.size)
        OptimizeMethod.LBFGS -> LbfgsState(options.memory)
    }

    // Bounded like evolve/fitGradient — a descent can never disappear into an animation loop.
    val reporter = if (options.showProgress) afield.progressReporter("minimize") else null
    var converged = false
    var reason = "max iterations"
    var iterationsRun = 0

    try {
        for (iteration in 1..options.iterations) {
            if (current.gradientInfNorm <= options.gradientTolerance) {
                converged = true
                reason = "gradient"
                break
            }

            val outcome = state.step(iteration, x, current, options, ::eval)
            if (outcome == null) {
                converged = true
                reason = "line search"
                iterationsRun = iteration - 1
                break
            }
            current = outcome.next
            iterationsRun = iteration
            losses += current.value
            steps += OptimizeStep(iteration, current.value, current.gradientInfNorm)
            reporter?.update(iteration.toLong(), options.iterations.toLong())

            if (outcome.stepNorm <= options.stepTolerance) {
                converged = true
                reason = "step"
                break
            }
        }
    } finally {
        reporter?.finish()
    }

    if (!converged && current.gradientInfNorm <= options.gradientTolerance) {
        converged = true
        reason = "gradient"
    }

    return OptimizeRun(
        x = x,
        value = current.value,
        gradient = current.gradient,
        initialX = startX,
        initialValue = losses.first(),
        steps = steps,
        losses = losses,
        iterations = iterationsRun,
        evaluations = evaluations,
        converged = converged,
        reason = reason,
        options = options
    )
}

/** [minimize] with [OptimizeMethod.SGD] — plain descent with momentum, and a visibly settling path. */
fun sgd(
    initial: DoubleArray,
    iterations: Int = 500,
    lr: Double = 0.05,
    momentum: Double = 0.9,
    nesterov: Boolean = false,
    objective: (List<AD>) -> AD
): OptimizeRun = minimize(
    initial,
    OptimizeOptions(
        method = OptimizeMethod.SGD, iterations = iterations, learningRate = lr,
        momentum = momentum, nesterov = nesterov
    ),
    objective
)

/** [minimize] with [OptimizeMethod.ADAM] — the robust default for a rough or badly scaled landscape. */
fun adam(
    initial: DoubleArray,
    iterations: Int = 500,
    lr: Double = 0.05,
    beta1: Double = 0.9,
    beta2: Double = 0.999,
    epsilon: Double = 1e-8,
    objective: (List<AD>) -> AD
): OptimizeRun = minimize(
    initial,
    OptimizeOptions(
        method = OptimizeMethod.ADAM, iterations = iterations, learningRate = lr,
        beta1 = beta1, beta2 = beta2, epsilon = epsilon
    ),
    objective
)

/** [minimize] with [OptimizeMethod.LBFGS] — the line-searched quasi-Newton, for smooth objectives. */
fun lbfgs(
    initial: DoubleArray,
    iterations: Int = 200,
    memory: Int = 8,
    gradientTolerance: Double = 1e-9,
    objective: (List<AD>) -> AD
): OptimizeRun = minimize(
    initial,
    OptimizeOptions(
        method = OptimizeMethod.LBFGS, iterations = iterations,
        memory = memory, gradientTolerance = gradientTolerance
    ),
    objective
)

// ---------------------------------------------------------------------------------------------
// The three descents
// ---------------------------------------------------------------------------------------------

/** What one optimizer iteration produced: the new value/gradient and how far the step moved. */
private class StepOutcome(val next: ADGradient, val stepNorm: Double)

/** The mutable per-run state of one descent; `step` mutates [x] in place and returns the outcome. */
private interface DescentState {
    fun step(
        iteration: Int,
        x: DoubleArray,
        current: ADGradient,
        options: OptimizeOptions,
        eval: (DoubleArray) -> ADGradient
    ): StepOutcome?
}

private class SgdState(n: Int) : DescentState {
    private val velocity = DoubleArray(n)

    override fun step(
        iteration: Int,
        x: DoubleArray,
        current: ADGradient,
        options: OptimizeOptions,
        eval: (DoubleArray) -> ADGradient
    ): StepOutcome {
        var moved = 0.0
        for (i in x.indices) {
            val g = current.gradient[i]
            if (!g.isFinite()) continue
            val previous = velocity[i]
            velocity[i] = options.momentum * previous - options.learningRate * g
            // Nesterov's look-ahead: apply the *new* velocity plus the momentum-weighted change.
            val delta = if (options.nesterov) {
                options.momentum * velocity[i] - options.learningRate * g
            } else {
                velocity[i]
            }
            if (delta.isFinite()) {
                x[i] += delta
                moved += delta * delta
            }
        }
        return StepOutcome(eval(x), sqrt(moved))
    }
}

private class AdamState(n: Int) : DescentState {
    private val m = DoubleArray(n)
    private val v = DoubleArray(n)

    override fun step(
        iteration: Int,
        x: DoubleArray,
        current: ADGradient,
        options: OptimizeOptions,
        eval: (DoubleArray) -> ADGradient
    ): StepOutcome {
        var moved = 0.0
        val correction1 = 1.0 - Math.pow(options.beta1, iteration.toDouble())
        val correction2 = 1.0 - Math.pow(options.beta2, iteration.toDouble())
        for (i in x.indices) {
            val g = current.gradient[i]
            if (!g.isFinite()) continue
            m[i] = options.beta1 * m[i] + (1.0 - options.beta1) * g
            v[i] = options.beta2 * v[i] + (1.0 - options.beta2) * g * g
            val update = options.learningRate * (m[i] / correction1) / (sqrt(v[i] / correction2) + options.epsilon)
            if (update.isFinite()) {
                x[i] -= update
                moved += update * update
            }
        }
        return StepOutcome(eval(x), sqrt(moved))
    }
}

private class LbfgsState(private val memory: Int) : DescentState {
    private val sHistory = ArrayDeque<DoubleArray>()
    private val yHistory = ArrayDeque<DoubleArray>()
    private val rhoHistory = ArrayDeque<Double>()

    override fun step(
        iteration: Int,
        x: DoubleArray,
        current: ADGradient,
        options: OptimizeOptions,
        eval: (DoubleArray) -> ADGradient
    ): StepOutcome? {
        val n = x.size
        val direction = twoLoopRecursion(current.gradient, n)

        // The model can go uphill after a skipped curvature pair; fall back to steepest descent.
        var slope = 0.0
        for (i in 0 until n) slope += direction[i] * current.gradient[i]
        if (slope >= 0.0) {
            for (i in 0 until n) direction[i] = -current.gradient[i]
            slope = 0.0
            for (i in 0 until n) slope += direction[i] * current.gradient[i]
        }
        if (slope >= 0.0 || !slope.isFinite()) return null

        // Backtracking Armijo. The first iteration starts small because the initial inverse-Hessian
        // guess is the identity and the raw gradient can be enormous.
        var alpha = if (iteration == 1 && sHistory.isEmpty()) {
            val norm = sqrt(current.gradient.sumOf { it * it })
            if (norm > 1.0) 1.0 / norm else 1.0
        } else {
            1.0
        }

        val candidate = DoubleArray(n)
        var accepted: ADGradient? = null
        var acceptedAlpha = 0.0
        for (attempt in 0 until options.maxLineSearchSteps) {
            for (i in 0 until n) candidate[i] = x[i] + alpha * direction[i]
            val probe = eval(candidate)
            if (probe.value.isFinite() && probe.value <= current.value + options.armijo * alpha * slope) {
                accepted = probe
                acceptedAlpha = alpha
                break
            }
            alpha *= 0.5
        }
        val next = accepted ?: return null

        val s = DoubleArray(n) { acceptedAlpha * direction[it] }
        val y = DoubleArray(n) { next.gradient[it] - current.gradient[it] }
        var sy = 0.0
        for (i in 0 until n) sy += s[i] * y[i]
        // Skip a pair with non-positive curvature rather than admit an indefinite model.
        if (sy > 1e-16 && sy.isFinite()) {
            sHistory.addLast(s)
            yHistory.addLast(y)
            rhoHistory.addLast(1.0 / sy)
            if (sHistory.size > memory) {
                sHistory.removeFirst()
                yHistory.removeFirst()
                rhoHistory.removeFirst()
            }
        }

        for (i in 0 until n) x[i] = candidate[i]
        return StepOutcome(next, sqrt(s.sumOf { it * it }))
    }

    /** Nocedal's two-loop recursion: the L-BFGS descent direction `-H∇f` without forming `H`. */
    private fun twoLoopRecursion(gradient: DoubleArray, n: Int): DoubleArray {
        val q = DoubleArray(n) { -gradient[it] }
        val k = sHistory.size
        val alphas = DoubleArray(k)
        for (index in k - 1 downTo 0) {
            val s = sHistory[index]
            val y = yHistory[index]
            var sq = 0.0
            for (i in 0 until n) sq += s[i] * q[i]
            val a = rhoHistory[index] * sq
            alphas[index] = a
            for (i in 0 until n) q[i] -= a * y[i]
        }
        if (k > 0) {
            val s = sHistory[k - 1]
            val y = yHistory[k - 1]
            var sy = 0.0
            var yy = 0.0
            for (i in 0 until n) {
                sy += s[i] * y[i]
                yy += y[i] * y[i]
            }
            val gamma = if (yy > 0.0) sy / yy else 1.0
            for (i in 0 until n) q[i] *= gamma
        }
        for (index in 0 until k) {
            val s = sHistory[index]
            val y = yHistory[index]
            var yq = 0.0
            for (i in 0 until n) yq += y[i] * q[i]
            val beta = rhoHistory[index] * yq
            val diff = alphas[index] - beta
            for (i in 0 until n) q[i] += diff * s[i]
        }
        return q
    }
}
