@file:afield.runtime.completion.CompletionCategory("Math")

package afield.math.energy

import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

/**
 * A learned Hopfield–Tank energy over Sudoku cells, after Hoover, Blayney and Whittington,
 * "Classical Energies Learn to Reason 1,000× Faster than Looped Models" (arXiv 2611.17309, CC-BY).
 *
 * Each cell `i` holds a belief `p_i = softmax(beta v_i)` over `n` digits. The energy is
 * `E(p) = β⁻¹ Σ p_i·log p_i + ½ Σ_{i≠j} p_iᵀ W_ij p_j + Σ B_i·p_i`, with `W` symmetric and zero
 * inside a cell. Units are indexed `cell * n + digit`; [weights] is `units × units`, row-major.
 * The model is told nothing about rows, columns or boxes: [train] fits `W` and `B` by
 * pseudolikelihood on solved grids alone, so no gradient ever passes through the solver.
 * [solve] runs mean-field Euler descent, then single-cell greedy moves, and stops once the
 * board's energy reaches the [threshold] fitted from training solutions.
 *
 * @param boxSize side of one Sudoku box; 2 gives a 4×4 puzzle, 3 the usual 9×9.
 */
class HopfieldSudoku(val boxSize: Int = 3) {
    /** Side of the grid and number of digits. */
    val n = boxSize * boxSize

    /** Number of cells. */
    val cells = n * n

    /** Number of units: cells × digits. */
    val units = cells * n

    /** The learned couplings `W`, row-major `units × units`, zero within a cell. */
    val weights = FloatArray(units * units)

    /** The learned biases `B`, one per unit. */
    val bias = FloatArray(units)

    /** Highest energy of a training solution plus a small tolerance; [solve] stops at or below it. */
    var threshold = Double.NaN

    /** Result of one [solve]. */
    class Solve(val grid: IntArray, val energy: Double, val descentSteps: Int, val moves: Int, val chains: Int, val solved: Boolean)

    /** Field `h_u = Σ_j W_uj p_j + B_u` for every unit. */
    fun field(p: FloatArray, out: FloatArray = FloatArray(units)): FloatArray {
        for (u in 0 until units) {
            var s = bias[u].toDouble()
            val row = u * units
            for (j in 0 until units) s += weights[row + j] * p[j]
            out[u] = s.toFloat()
        }
        return out
    }

    /** One Euler step `v ← (1-η)v - η h`, then `p = softmax(βv)`, on a flat unit array; clue cells stay fixed. */
    fun eulerStep(v: FloatArray, p: FloatArray, h: FloatArray, clamped: BooleanArray, eta: Float, beta: Float) {
        for (c in 0 until cells) {
            if (clamped[c]) continue
            var mx = Float.NEGATIVE_INFINITY
            for (d in 0 until n) {
                val u = c * n + d
                v[u] = (1 - eta) * v[u] - eta * h[u]
                mx = maxOf(mx, beta * v[u])
            }
            var z = 0f
            for (d in 0 until n) { val e = exp(beta * v[c * n + d] - mx); p[c * n + d] = e; z += e }
            for (d in 0 until n) p[c * n + d] /= z
        }
    }

    /** Clamped one-hot clue beliefs and the clamp mask for `puzzle` (0 = blank). */
    fun start(puzzle: IntArray, random: Random?): Triple<FloatArray, FloatArray, BooleanArray> {
        val v = FloatArray(units) { random?.nextFloat()?.times(0.1f) ?: 0f }
        val p = FloatArray(units) { 1f / n }
        val clamped = BooleanArray(cells) { puzzle[it] > 0 }
        for (c in 0 until cells) if (clamped[c]) for (d in 0 until n) p[c * n + d] = if (d == puzzle[c] - 1) 1f else 0f
        return Triple(v, p, clamped)
    }

    /** Discrete energy of a full grid (entropy is zero for a one-hot board). */
    fun energy(grid: IntArray): Double {
        var e = 0.0
        for (c in 0 until cells) {
            val u = c * n + grid[c] - 1
            e += bias[u]
            for (k in c + 1 until cells) e += weights[u * units + k * n + grid[k] - 1]
        }
        return e
    }

    /**
     * Solves `puzzle`: up to `chains` runs of mean-field descent from small random states, each
     * finished by greedy single-cell moves and, if the board is still above [threshold], an annealed
     * Gibbs search of `sweeps` sweeps; returns as soon as a board meets [threshold].
     */
    fun solve(puzzle: IntArray, chains: Int = 4, eta: Float = 0.5f, beta: Float = 1f, tolerance: Float = 1e-4f,
              maxSteps: Int = 500, sweeps: Int = 300, seed: Long = 0): Solve {
        val random = Random(seed)
        var best: Solve? = null
        var steps = 0; var moves = 0
        for (chain in 1..chains) {
            val (v, p, clamped) = start(puzzle, if (chain == 1) null else random)
            val h = FloatArray(units)
            var last = p.copyOf()
            for (s in 0 until maxSteps) {
                steps++
                field(p, h); eulerStep(v, p, h, clamped, eta, beta)
                var moved = 0f
                for (u in 0 until units) moved = maxOf(moved, kotlin.math.abs(p[u] - last[u]))
                if (moved < tolerance) break
                p.copyInto(last)
            }
            val grid = IntArray(cells) { c -> if (puzzle[c] > 0) puzzle[c] else (0 until n).maxBy { p[c * n + it] } + 1 }
            moves += greedy(grid, clamped, random)
            var e = energy(grid)
            if (!(e <= threshold) && sweeps > 0) {
                moves += anneal(grid, clamped, random, sweeps)
                moves += greedy(grid, clamped, random)
                e = energy(grid)
            }
            val r = Solve(grid, e, steps, moves, chain, !threshold.isNaN() && e <= threshold)
            if (best == null || e < best.energy) best = r
            if (r.solved) return r
        }
        return best!!.let { Solve(it.grid, it.energy, steps, moves, chains, false) }
    }

    /** Greedy single-cell moves (lowest local energy first) until no change lowers the energy. Returns the move count. */
    private fun greedy(grid: IntArray, clamped: BooleanArray, random: Random, maxMoves: Int = 20 * cells): Int {
        var moves = 0
        while (moves < maxMoves) {
            var bestGain = 1e-6; var bc = -1; var bd = 0
            for (c in 0 until cells) {
                if (clamped[c]) continue
                val cur = local(grid, c, grid[c] - 1)
                for (d in 0 until n) if (d != grid[c] - 1) {
                    val gain = cur - local(grid, c, d) + random.nextDouble() * 1e-9
                    if (gain > bestGain) { bestGain = gain; bc = c; bd = d }
                }
            }
            if (bc < 0) break
            grid[bc] = bd + 1; moves++
        }
        return moves
    }

    /** Largest coupling magnitude, the energy scale the Gibbs temperatures are set against. */
    val scale: Double get() = weights.maxOf { kotlin.math.abs(it) }.toDouble().coerceAtLeast(1e-6)

    /**
     * Annealed Gibbs search: each sweep resamples every blank cell from `softmax(-local energy / T)`,
     * with `T` falling geometrically from `0.5` to `0.02` of [scale]. Returns the number of changed cells.
     */
    private fun anneal(grid: IntArray, clamped: BooleanArray, random: Random, sweeps: Int): Int {
        val w = scale; val t0 = 0.5 * w; val t1 = 0.02 * w
        var changes = 0
        val e = DoubleArray(n)
        for (s in 0 until sweeps) {
            val t = t0 * Math.pow(t1 / t0, s / (sweeps - 1.0).coerceAtLeast(1.0))
            for (c in 0 until cells) {
                if (clamped[c]) continue
                var mn = Double.POSITIVE_INFINITY
                for (d in 0 until n) { e[d] = local(grid, c, d); mn = minOf(mn, e[d]) }
                var z = 0.0
                for (d in 0 until n) { e[d] = exp(-(e[d] - mn) / t); z += e[d] }
                var x = random.nextDouble() * z; var pick = n - 1
                for (d in 0 until n) { x -= e[d]; if (x <= 0) { pick = d; break } }
                if (pick + 1 != grid[c]) { grid[c] = pick + 1; changes++ }
            }
        }
        return changes
    }

    private fun local(grid: IntArray, c: Int, d: Int): Double {
        val u = c * n + d
        var e = bias[u].toDouble()
        for (k in 0 until cells) if (k != c) e += weights[u * units + k * n + grid[k] - 1]
        return e
    }

    /**
     * Pseudolikelihood training on solved grids. For each cell the conditional over its digits,
     * with every other cell at the solution, is `softmax(-local energy)`; descending its negative
     * log-likelihood moves `W` and `B` by `q - y`. Each update relabels the digits at random.
     * Ends by fitting [threshold]. Returns the wall time in milliseconds.
     */
    fun train(solutions: List<IntArray>, epochs: Int = 20, lr: Float = 0.05f, l1: Float = 1e-5f, seed: Long = 1): Long {
        val t0 = System.nanoTime()
        val random = Random(seed)
        val q = DoubleArray(n)
        repeat(epochs) {
            for (raw in solutions.shuffled(random)) {
                val perm = (1..n).shuffled(random)
                val x = IntArray(cells) { perm[raw[it] - 1] }
                for (c in 0 until cells) {
                    var mx = Double.NEGATIVE_INFINITY
                    for (d in 0 until n) { q[d] = -local(x, c, d); mx = maxOf(mx, q[d]) }
                    var z = 0.0
                    for (d in 0 until n) { q[d] = exp(q[d] - mx); z += q[d] }
                    for (d in 0 until n) {
                        val g = (q[d] / z - if (d == x[c] - 1) 1.0 else 0.0).toFloat() * lr
                        val u = c * n + d
                        bias[u] += g
                        for (k in 0 until cells) if (k != c) {
                            val w = k * n + x[k] - 1
                            // half to each side keeps W symmetric
                            weights[u * units + w] += g / 2
                            weights[w * units + u] += g / 2
                        }
                    }
                }
                if (l1 > 0) for (i in weights.indices) {
                    val w = weights[i]
                    weights[i] = if (w > l1) w - l1 else if (w < -l1) w + l1 else 0f
                }
            }
        }
        threshold = solutions.maxOf { energy(it) } + 1e-3
        return (System.nanoTime() - t0) / 1_000_000
    }

    /** Mean negative log pseudolikelihood of `solutions`, a training diagnostic. */
    fun pseudoNll(solutions: List<IntArray>): Double {
        var s = 0.0
        for (x in solutions) for (c in 0 until cells) {
            val e = DoubleArray(n) { -local(x, c, it) }
            val mx = e.max()
            s -= e[x[c] - 1] - mx - ln(e.sumOf { exp(it - mx) })
        }
        return s / (solutions.size * cells)
    }

    /**
     * Beliefs after exactly `steps` mean-field Euler steps from the zero state, the CPU reference the
     * GPU descent is compared against.
     */
    fun beliefsAfter(puzzle: IntArray, steps: Int, eta: Float = 0.5f, beta: Float = 1f): FloatArray {
        val (v, p, clamped) = start(puzzle, null)
        val h = FloatArray(units)
        repeat(steps) { field(p, h); eulerStep(v, p, h, clamped, eta, beta) }
        return p
    }

    /**
     * The state buffer the GPU descent in the `hopfield_sudoku` demo transcript reads and writes, as floats: four unused header floats, then beliefs
     * A and B (`units` each, ping-ponged by step parity), the neuron state `v` (`units`), the
     * biases (`units`) and a clue mask (`cells`).
     */
    fun gpuState(puzzle: IntArray): FloatArray {
        val (v, p, clamped) = start(puzzle, null)
        val out = FloatArray(4 + 4 * units + cells)
        p.copyInto(out, 4); p.copyInto(out, 4 + units); v.copyInto(out, 4 + 2 * units); bias.copyInto(out, 4 + 3 * units)
        for (c in 0 until cells) out[4 + 4 * units + c] = if (clamped[c]) 1f else 0f
        return out
    }

    /** Number of floats in [gpuState]. */
    val gpuStateFloats get() = 4 + 4 * units + cells

    /** The beliefs after `step` GPU steps, from a [gpuState] buffer read back from the GPU. */
    fun gpuBeliefs(state: FloatArray, step: Int): FloatArray {
        val at = 4 + (step % 2) * units
        return state.copyOfRange(at, at + units)
    }

    /** Decodes beliefs into a grid of 1-based digits, keeping the clues of `puzzle`. */
    fun decode(puzzle: IntArray, p: FloatArray) =
        IntArray(cells) { c -> if (puzzle[c] > 0) puzzle[c] else (0 until n).maxBy { p[c * n + it] } + 1 }

    /** True when `grid` is a valid Sudoku that keeps every clue of `puzzle`. */
    fun isSolution(puzzle: IntArray, grid: IntArray): Boolean {
        for (c in 0 until cells) if (puzzle[c] > 0 && puzzle[c] != grid[c]) return false
        for (k in 0 until n) {
            val row = HashSet<Int>(); val col = HashSet<Int>(); val box = HashSet<Int>()
            for (m in 0 until n) {
                row += grid[k * n + m]; col += grid[m * n + k]
                val br = (k / boxSize) * boxSize + m / boxSize; val bc = (k % boxSize) * boxSize + m % boxSize
                box += grid[br * n + bc]
            }
            if (row.size != n || col.size != n || box.size != n) return false
        }
        return true
    }

    /** A random solved grid (randomized backtracking fill) and a puzzle with `blanks` cells removed. */
    fun generate(random: Random, blanks: Int): Pair<IntArray, IntArray> {
        val solution = IntArray(cells)
        fun ok(c: Int, d: Int): Boolean {
            val r = c / n; val q = c % n
            val br = r / boxSize * boxSize; val bq = q / boxSize * boxSize
            for (m in 0 until n) {
                if (solution[r * n + m] == d || solution[m * n + q] == d) return false
                if (solution[(br + m / boxSize) * n + bq + m % boxSize] == d) return false
            }
            return true
        }
        fun fill(c: Int): Boolean {
            if (c == cells) return true
            for (d in (1..n).shuffled(random)) if (ok(c, d)) {
                solution[c] = d
                if (fill(c + 1)) return true
                solution[c] = 0
            }
            return false
        }
        check(fill(0))
        val puzzle = solution.copyOf()
        (0 until cells).shuffled(random).take(blanks).forEach { puzzle[it] = 0 }
        return puzzle to solution
    }

    /**
     * The constraint-strength image: for each cell pair, the mean of `W_ij` on equal digits
     * minus its mean on unequal digits, as a `cells × cells` row-major array. Rows, columns and
     * boxes show up as positive (equal digits cost energy) structure.
     */
    fun sameDigitCost(): FloatArray = FloatArray(cells * cells) { idx ->
        val a = idx / cells; val b = idx % cells
        if (a == b) 0f else {
            var same = 0f; var diff = 0f
            for (d in 0 until n) for (e in 0 until n) {
                val w = weights[(a * n + d) * units + b * n + e]
                if (d == e) same += w else diff += w
            }
            same / n - diff / (n * (n - 1))
        }
    }
}
