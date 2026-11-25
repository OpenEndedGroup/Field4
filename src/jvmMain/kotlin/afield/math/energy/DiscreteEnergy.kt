@file:afield.runtime.completion.CompletionCategory("Math")

package afield.math.energy

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

/**
 * A discrete optimisation problem stated as an energy: each of [variables] variables takes one
 * of `domains[v]` values, and the energy of an assignment is a sum of unary and pairwise costs.
 *
 * Internally every value of every variable is a *unit*, `unit(v, x)`, so a variable is a one-hot
 * group and the energy over beliefs `p` is the Hopfield–Tank form `½ pᵀ W p + B·p`, with [pairwise]
 * as `W` (symmetric, `units × units`, zero inside a group) and [unary] as `B`. Lower is better.
 *
 * State a problem with the builders ([allDifferent], [notEqual], [equal], [prefer], [forbid],
 * [pairCost], [fix]), or learn the costs from example solutions with
 * [PseudolikelihoodLearner]. Then settle an [EnergyState] with [MeanFieldDescent],
 * [GreedyMoves] or [GibbsAnnealing], one step at a time if a box is drawing it.
 *
 * After Hoover, Blayney and Whittington, "Classical Energies Learn to Reason 1,000× Faster than
 * Looped Models" (arXiv 2611.17309, CC-BY), which learns such energies for Sudoku and mazes.
 *
 * @param domains how many values each variable can take.
 */
class DiscreteEnergy(val domains: IntArray) {
    /** `variables` variables that each take one of `values` values. */
    constructor(variables: Int, values: Int) : this(IntArray(variables) { values })

    /** Number of variables. */
    val variables = domains.size

    /** First unit of each variable. */
    val offset = IntArray(variables).also { var o = 0; for (v in 0 until variables) { it[v] = o; o += domains[v] } }

    /** Number of units, the sum of the domain sizes. */
    val units = domains.sum()

    /** Per-unit cost `B`: the cost of variable `v` taking value `x` is `unary[unit(v, x)]`. */
    val unary = FloatArray(units)

    /** Pairwise costs `W`, row-major `units × units`, kept symmetric by every builder. */
    val pairwise = FloatArray(units * units)

    /** The value each variable is fixed to, or -1 when it is free. */
    val fixed = IntArray(variables) { -1 }

    /** The unit for variable `v` taking value `x`. */
    fun unit(v: Int, x: Int) = offset[v] + x

    /** Adds `cost` when `a` takes `x` and `b` takes `y` (to both halves of the symmetric matrix). */
    fun addPair(a: Int, x: Int, b: Int, y: Int, cost: Double): DiscreteEnergy {
        require(a != b) { "a pairwise cost needs two different variables" }
        val i = unit(a, x); val j = unit(b, y)
        pairwise[i * units + j] += cost.toFloat()
        pairwise[j * units + i] += cost.toFloat()
        return this
    }

    /** `cost(x, y)` for every pair of values of `a` and `b`. */
    fun pairCost(a: Int, b: Int, cost: (Int, Int) -> Double): DiscreteEnergy {
        for (x in 0 until domains[a]) for (y in 0 until domains[b]) {
            val c = cost(x, y)
            if (c != 0.0) addPair(a, x, b, y, c)
        }
        return this
    }

    /** Costs `weight` whenever `a` and `b` take the same value. */
    fun notEqual(a: Int, b: Int, weight: Double = 1.0) = pairCost(a, b) { x, y -> if (x == y) weight else 0.0 }

    /** Costs `weight` whenever `a` and `b` take different values. */
    fun equal(a: Int, b: Int, weight: Double = 1.0) = pairCost(a, b) { x, y -> if (x != y) weight else 0.0 }

    /** No two of `vars` may share a value: [notEqual] on every pair. */
    fun allDifferent(vars: List<Int>, weight: Double = 1.0): DiscreteEnergy {
        for (i in vars.indices) for (j in i + 1 until vars.size) notEqual(vars[i], vars[j], weight)
        return this
    }

    /** Lowers the cost of `v` taking `x` by `weight`. */
    fun prefer(v: Int, x: Int, weight: Double = 1.0): DiscreteEnergy { unary[unit(v, x)] -= weight.toFloat(); return this }

    /** Raises the cost of `v` taking `x` by `weight`. */
    fun forbid(v: Int, x: Int, weight: Double = 10.0): DiscreteEnergy { unary[unit(v, x)] += weight.toFloat(); return this }

    /** Clamps `v` to `x`: solvers never change it. */
    fun fix(v: Int, x: Int): DiscreteEnergy { fixed[v] = x; return this }

    /** Frees every variable fixed with [fix]. */
    fun releaseAll(): DiscreteEnergy { fixed.fill(-1); return this }

    /** Energy of a full assignment. */
    fun energy(assignment: IntArray): Double {
        var e = 0.0
        for (a in 0 until variables) {
            val i = unit(a, assignment[a])
            e += unary[i]
            for (b in a + 1 until variables) e += pairwise[i * units + unit(b, assignment[b])]
        }
        return e
    }

    /** Energy of the terms that involve `v`, were it to take `x` with everything else as in `assignment`. */
    fun localEnergy(assignment: IntArray, v: Int, x: Int): Double {
        val i = unit(v, x)
        var e = unary[i].toDouble()
        val row = i * units
        for (b in 0 until variables) if (b != v) e += pairwise[row + unit(b, assignment[b])]
        return e
    }

    /**
     * For variables `a` and `b` with the same domain: the mean cost of their taking the same value
     * minus the mean cost of different values. Positive means "these two should differ".
     */
    fun sameValueCost(a: Int, b: Int): Double {
        if (a == b || domains[a] != domains[b]) return 0.0
        val n = domains[a]
        var same = 0.0; var diff = 0.0
        for (x in 0 until n) for (y in 0 until n) {
            val w = pairwise[unit(a, x) * units + unit(b, y)]
            if (x == y) same += w else diff += w
        }
        return same / n - (if (n > 1) diff / (n * (n - 1)) else 0.0)
    }

    /** [sameValueCost] for every pair of variables, as rows for `showHeatmap`. */
    fun sameValueImage(): Array<DoubleArray> = Array(variables) { a -> DoubleArray(variables) { b -> sameValueCost(a, b) } }

    /**
     * The state buffer the `discrete_energy_descent` compute shader reads and writes, as floats:
     * four header floats, beliefs A and B (`units` each, ping-ponged by step parity), the neuron
     * state `v`, the [unary] costs, then `offset`, domain size and fixed value for each variable.
     */
    fun gpuState(state: EnergyState): FloatArray {
        val out = FloatArray(gpuStateFloats)
        state.p.copyInto(out, 4); state.p.copyInto(out, 4 + units); state.v.copyInto(out, 4 + 2 * units)
        unary.copyInto(out, 4 + 3 * units)
        val base = 4 + 4 * units
        for (a in 0 until variables) {
            out[base + 3 * a] = offset[a].toFloat()
            out[base + 3 * a + 1] = domains[a].toFloat()
            out[base + 3 * a + 2] = fixed[a].toFloat()
        }
        return out
    }

    /** Number of floats in [gpuState]. */
    val gpuStateFloats get() = 4 + 4 * units + 3 * variables

    /** Copies the beliefs after `step` GPU steps out of a read-back [gpuState] buffer into `state`. */
    fun readGpuBeliefs(buffer: FloatArray, step: Int, state: EnergyState) {
        buffer.copyInto(state.p, 0, 4 + (step % 2) * units, 4 + (step % 2) * units + units)
        state.decode()
    }
}

/**
 * Beliefs and an assignment for a [DiscreteEnergy]: `p` holds one belief per unit (each
 * variable's beliefs sum to one), `v` the neuron state behind them, and [assignment] each
 * variable's current value. Solvers read and write it in place.
 */
class EnergyState(val model: DiscreteEnergy) {
    /** Neuron state, one per unit. */
    val v = FloatArray(model.units)

    /** Beliefs, one per unit; a softmax of `v` within each variable. */
    val p = FloatArray(model.units)

    /** The current value of each variable. */
    val assignment = IntArray(model.variables)

    init { reset() }

    /**
     * Uniform beliefs, with `noise` of random jitter in `v` when `random` is given (needed when
     * nothing breaks the symmetry between values); fixed variables are one-hot. Then [decode].
     */
    fun reset(random: Random? = null, noise: Float = 0.1f): EnergyState {
        for (a in 0 until model.variables) {
            val o = model.offset[a]; val n = model.domains[a]
            for (x in 0 until n) {
                v[o + x] = if (random != null) (random.nextFloat() - 0.5f) * 2 * noise else 0f
                p[o + x] = 1f / n
            }
        }
        if (random != null) softmaxAll(1f)
        decode()
        return this
    }

    /** Sets each variable to the value of its largest belief (fixed variables to their fixed value). */
    fun decode() {
        for (a in 0 until model.variables) {
            val f = model.fixed[a]
            if (f >= 0) { assignment[a] = f; continue }
            val o = model.offset[a]
            var best = 0
            for (x in 1 until model.domains[a]) if (p[o + x] > p[o + best]) best = x
            assignment[a] = best
        }
    }

    /** Makes the beliefs one-hot on [assignment], after a discrete solver has moved it. */
    fun beliefsFromAssignment() {
        for (a in 0 until model.variables) for (x in 0 until model.domains[a]) p[model.unit(a, x)] = if (assignment[a] == x) 1f else 0f
    }

    /** Energy of [assignment]. */
    val energy get() = model.energy(assignment)

    internal fun softmaxAll(beta: Float) {
        for (a in 0 until model.variables) {
            val f = model.fixed[a]
            val o = model.offset[a]; val n = model.domains[a]
            if (f >= 0) { for (x in 0 until n) p[o + x] = if (x == f) 1f else 0f; continue }
            var mx = Float.NEGATIVE_INFINITY
            for (x in 0 until n) mx = maxOf(mx, beta * v[o + x])
            var z = 0f
            for (x in 0 until n) { val e = exp(beta * v[o + x] - mx); p[o + x] = e; z += e }
            for (x in 0 until n) p[o + x] /= z
        }
    }
}

/**
 * Mean-field Hopfield–Tank descent: each [step] is the Euler update `v ← (1-η)v - η(W p + B)`
 * followed by `p = softmax(βv)` within each variable, the same update the
 * `discrete_energy_descent` compute shader makes on the GPU.
 */
class MeanFieldDescent(val model: DiscreteEnergy, var eta: Float = 0.5f, var beta: Float = 1f) {
    private val h = FloatArray(model.units)

    /** One Euler step; returns the largest change in any belief. */
    fun step(state: EnergyState): Float {
        val u = model.units
        for (i in 0 until u) {
            var s = model.unary[i].toDouble()
            val row = i * u
            for (j in 0 until u) s += model.pairwise[row + j] * state.p[j]
            h[i] = s.toFloat()
        }
        for (i in 0 until u) state.v[i] = (1 - eta) * state.v[i] - eta * h[i]
        val before = state.p.copyOf()
        state.softmaxAll(beta)
        var moved = 0f
        for (i in 0 until u) moved = maxOf(moved, abs(state.p[i] - before[i]))
        state.decode()
        return moved
    }

    /** Steps until no belief moves by more than `tolerance`, or `maxSteps`; returns the steps taken. */
    fun settle(state: EnergyState, tolerance: Float = 1e-4f, maxSteps: Int = 500): Int {
        for (s in 1..maxSteps) if (step(state) < tolerance) return s
        return maxSteps
    }
}

/** Greedy single-variable moves: each [step] makes the one change that lowers the energy most. */
class GreedyMoves(val model: DiscreteEnergy) {
    /** Makes the best improving move; returns false when no move lowers the energy. */
    fun step(state: EnergyState): Boolean {
        var bestGain = 1e-9; var bv = -1; var bx = 0
        val a = state.assignment
        for (v in 0 until model.variables) {
            if (model.fixed[v] >= 0) continue
            val cur = model.localEnergy(a, v, a[v])
            for (x in 0 until model.domains[v]) if (x != a[v]) {
                val gain = cur - model.localEnergy(a, v, x)
                if (gain > bestGain) { bestGain = gain; bv = v; bx = x }
            }
        }
        if (bv < 0) return false
        a[bv] = bx
        return true
    }

    /** Moves until none improves, or `maxMoves`; returns the moves made. */
    fun settle(state: EnergyState, maxMoves: Int = 10_000): Int {
        var m = 0
        while (m < maxMoves && step(state)) m++
        state.beliefsFromAssignment()
        return m
    }
}

/**
 * Annealed Gibbs sampling: each [step] is one sweep that resamples every free variable from
 * `softmax(-local energy / T)`, with `T` falling geometrically from [startTemperature] to
 * [endTemperature] over [sweeps] sweeps.
 */
class GibbsAnnealing(
    val model: DiscreteEnergy,
    val sweeps: Int = 300,
    val startTemperature: Double = 1.0,
    val endTemperature: Double = 0.02,
    seed: Long = 0,
) {
    private val random = Random(seed)

    /** Sweeps taken so far. */
    var sweep = 0
        private set

    /** The temperature of the next sweep. */
    val temperature get() = startTemperature * Math.pow(endTemperature / startTemperature, sweep / (sweeps - 1.0).coerceAtLeast(1.0))

    /** One sweep; returns false once [sweeps] sweeps have been taken. */
    fun step(state: EnergyState): Boolean {
        if (sweep >= sweeps) return false
        val t = temperature
        val a = state.assignment
        for (v in 0 until model.variables) {
            if (model.fixed[v] >= 0) continue
            val n = model.domains[v]
            val e = DoubleArray(n) { model.localEnergy(a, v, it) }
            val mn = e.min()
            var z = 0.0
            for (x in 0 until n) { e[x] = exp(-(e[x] - mn) / t); z += e[x] }
            var r = random.nextDouble() * z
            var pick = n - 1
            for (x in 0 until n) { r -= e[x]; if (r <= 0) { pick = x; break } }
            a[v] = pick
        }
        sweep++
        return true
    }

    /** Runs the remaining sweeps; returns the lowest energy seen and leaves `state` at that assignment. */
    fun settle(state: EnergyState): Double {
        var best = state.assignment.copyOf(); var bestE = state.energy
        while (step(state)) { val e = state.energy; if (e < bestE) { bestE = e; best = state.assignment.copyOf() } }
        best.copyInto(state.assignment)
        state.beliefsFromAssignment()
        return bestE
    }

    /** Starts the schedule again. */
    fun restart() { sweep = 0 }
}

/**
 * Learns a [DiscreteEnergy]'s [DiscreteEnergy.pairwise] and [DiscreteEnergy.unary] costs from
 * example solutions by pseudolikelihood, never running a solver: for each variable of an
 * example, the conditional over its values with every other variable held at the example is
 * `softmax(-local energy)`, and each update moves the costs by `q - y` to make the example's
 * value more likely. An L1 step of [l1] after each example keeps unused couplings at zero.
 *
 * @param augment optional symmetry applied to each example before it is used (Sudoku relabels
 *   its digits, so the model learns what the digits do rather than which is which).
 */
class PseudolikelihoodLearner(
    val model: DiscreteEnergy,
    var rate: Float = 0.2f,
    var l1: Float = 1e-4f,
    val augment: ((IntArray, Random) -> IntArray)? = null,
    seed: Long = 1,
) {
    private val random = Random(seed)

    /** One pass over `examples` in random order. */
    fun epoch(examples: List<IntArray>) {
        val m = model
        for (raw in examples.shuffled(random)) {
            val x = augment?.invoke(raw, random) ?: raw
            for (v in 0 until m.variables) {
                val n = m.domains[v]
                val q = DoubleArray(n) { -m.localEnergy(x, v, it) }
                val mx = q.max()
                var z = 0.0
                for (k in 0 until n) { q[k] = exp(q[k] - mx); z += q[k] }
                for (k in 0 until n) {
                    val g = ((q[k] / z) - if (k == x[v]) 1.0 else 0.0).toFloat() * rate
                    val i = m.unit(v, k)
                    m.unary[i] += g
                    for (b in 0 until m.variables) if (b != v) {
                        val j = m.unit(b, x[b])
                        m.pairwise[i * m.units + j] += g / 2
                        m.pairwise[j * m.units + i] += g / 2
                    }
                }
            }
            if (l1 > 0) for (i in m.pairwise.indices) {
                val w = m.pairwise[i]
                m.pairwise[i] = if (w > l1) w - l1 else if (w < -l1) w + l1 else 0f
            }
        }
    }

    /** Mean negative log pseudolikelihood per variable of `examples`. */
    fun loss(examples: List<IntArray>): Double {
        var s = 0.0
        for (x in examples) for (v in 0 until model.variables) {
            val e = DoubleArray(model.domains[v]) { -model.localEnergy(x, v, it) }
            val mx = e.max()
            s -= e[x[v]] - mx - ln(e.sumOf { exp(it - mx) })
        }
        return s / (examples.size * model.variables)
    }
}
