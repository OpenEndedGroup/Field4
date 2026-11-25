@file:afield.runtime.completion.CompletionCategory("Math")

package afield.math.energy

import kotlin.random.Random

/**
 * A learned Hopfield–Tank quadratic energy over Sudoku cell×digit units, after
 * "Classical Energies Learn to Reason 1k× Faster Than Looped Models" (arXiv 2611.17309).
 *
 * Units are `v[cell * n + digit]` in `[0, 1]`. The energy is
 * `E(v) = -½ vᵀ W v - bᵀ v`, with `W` symmetric and zero on the diagonal. [solve] runs projected
 * gradient descent on `E` with the clue units clamped, and stops by itself when a step moves no unit
 * by more than `tolerance`. [train] never differentiates through the solver: it is a contrastive
 * update that lowers the energy of the true solution and raises the energy of the state the
 * solver settled into.
 *
 * @param boxSize side of one Sudoku box; 2 gives a 4×4 puzzle, 3 the usual 9×9.
 */
class HopfieldSudoku(val boxSize: Int = 2) {
    /** Side of the grid and number of digits. */
    val n = boxSize * boxSize

    /** Number of units: cells × digits. */
    val units = n * n * n

    /** The learned couplings, row-major `units × units`. */
    val weights = DoubleArray(units * units)

    /** The learned biases, one per unit. */
    val bias = DoubleArray(units)

    /** Result of one [solve]: the settled units, the decoded grid and the step count. */
    class Solve(val state: DoubleArray, val grid: IntArray, val steps: Int, val converged: Boolean)

    /** Settles the energy from `puzzle` (0 = blank, 1..n = clue) by projected gradient descent. */
    fun solve(puzzle: IntArray, rate: Double = 0.1, tolerance: Double = 1e-4, maxSteps: Int = 2000): Solve {
        val v = DoubleArray(units) { 1.0 / n }
        val clamped = BooleanArray(units)
        for (c in 0 until n * n) if (puzzle[c] > 0) for (d in 0 until n) {
            v[c * n + d] = if (d == puzzle[c] - 1) 1.0 else 0.0
            clamped[c * n + d] = true
        }
        var steps = 0
        var converged = false
        while (steps < maxSteps) {
            steps++
            var moved = 0.0
            val field = DoubleArray(units)
            for (i in 0 until units) {
                var s = bias[i]
                val row = i * units
                for (j in 0 until units) s += weights[row + j] * v[j]
                field[i] = s
            }
            for (i in 0 until units) if (!clamped[i]) {
                val nv = (v[i] + rate * field[i]).coerceIn(0.0, 1.0)
                moved = maxOf(moved, kotlin.math.abs(nv - v[i]))
                v[i] = nv
            }
            if (moved < tolerance) { converged = true; break }
        }
        return Solve(v, decode(v), steps, converged)
    }

    /** Picks the strongest digit per cell (1-based). */
    fun decode(v: DoubleArray) = IntArray(n * n) { c -> (0 until n).maxBy { v[c * n + it] } + 1 }

    /** Energy of a unit state. */
    fun energy(v: DoubleArray): Double {
        var e = 0.0
        for (i in 0 until units) {
            var s = 0.0
            for (j in 0 until units) s += weights[i * units + j] * v[j]
            e -= 0.5 * v[i] * s + bias[i] * v[i]
        }
        return e
    }

    /** One-hot units of a full grid. */
    fun oneHot(grid: IntArray) = DoubleArray(units).also { for (c in 0 until n * n) it[c * n + grid[c] - 1] = 1.0 }

    /**
     * Contrastive training: for each (puzzle, solution) pair, settle the solver, then move `W` and `b`
     * toward the solution's statistics and away from the settled state's. Returns the wall time in ms.
     */
    fun train(pairs: List<Pair<IntArray, IntArray>>, epochs: Int = 5, lr: Double = 0.01, solveSteps: Int = 200): Long {
        val t0 = System.nanoTime()
        repeat(epochs) {
            for ((puzzle, solution) in pairs) {
                val s = oneHot(solution)
                val v = solve(puzzle, maxSteps = solveSteps).state
                for (i in 0 until units) {
                    bias[i] += lr * (s[i] - v[i])
                    for (j in i + 1 until units) {
                        val d = lr * (s[i] * s[j] - v[i] * v[j])
                        weights[i * units + j] += d
                        weights[j * units + i] += d
                    }
                }
            }
        }
        return (System.nanoTime() - t0) / 1_000_000
    }

    /** True when `grid` is a valid Sudoku that keeps every clue of `puzzle`. */
    fun isSolution(puzzle: IntArray, grid: IntArray): Boolean {
        for (c in 0 until n * n) if (puzzle[c] > 0 && puzzle[c] != grid[c]) return false
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

    /** A random solved grid and a puzzle with `blanks` cells removed. */
    fun generate(random: Random, blanks: Int): Pair<IntArray, IntArray> {
        val digits = (1..n).shuffled(random)
        val rows = (0 until boxSize).shuffled(random).flatMap { b -> (0 until boxSize).shuffled(random).map { b * boxSize + it } }
        val cols = (0 until boxSize).shuffled(random).flatMap { b -> (0 until boxSize).shuffled(random).map { b * boxSize + it } }
        val solution = IntArray(n * n) { c ->
            val r = rows[c / n]; val q = cols[c % n]
            digits[(boxSize * (r % boxSize) + r / boxSize + q) % n]
        }
        val puzzle = solution.copyOf()
        (0 until n * n).shuffled(random).take(blanks).forEach { puzzle[it] = 0 }
        return puzzle to solution
    }
}
