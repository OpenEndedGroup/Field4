@file:Suppress("MemberVisibilityCanBePrivate")
@file:afield.CompletionCategory("Math")

package afield.stdlib

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A Levenberg–Marquardt nonlinear least-squares solver, pure Kotlin and self-contained.
 *
 * [leastSquares] takes an initial parameter vector and a `residuals: (DoubleArray) -> DoubleArray`
 * lambda and minimises half the squared residual norm. [LeastSquaresOptions] controls the
 * iteration cap, the damping schedule and three convergence tolerances; [LeastSquaresResult]
 * reports the parameters, final residuals, cost, iteration count, an `rms`, and a `reason` string
 * — one of "gradient", "step", "improvement", "no accepted step" or "max iterations".
 *
 * The Jacobian is central finite differences **by default**, so you never have to supply
 * derivatives, but each iteration then costs `2 * parameters` extra residual evaluations. Pass an
 * explicit [jacobian] — or, easier, write the residuals in [AD] arithmetic and call
 * [leastSquaresAD], which derives an exact Jacobian by reverse-mode automatic differentiation — to
 * trade those `2n` evaluations for one backward sweep and get derivatives that are exact rather
 * than truncated.
 *
 * The damped normal equations are solved by Gaussian elimination with partial pivoting; a singular
 * system or a non-finite candidate raises the damping and retries (up to 12 times per iteration)
 * rather than failing. Non-finite initial residuals throw.
 */

data class LeastSquaresOptions(
    val maxIterations: Int = 64,
    val initialDamping: Double = 1e-3,
    val dampingUp: Double = 10.0,
    val dampingDown: Double = 0.35,
    val finiteDifferenceStep: Double = 1e-4,
    val gradientTolerance: Double = 1e-8,
    val stepTolerance: Double = 1e-9,
    val improvementTolerance: Double = 1e-12
) {
    init {
        require(maxIterations > 0) { "maxIterations must be > 0" }
        require(initialDamping > 0.0) { "initialDamping must be > 0" }
        require(dampingUp > 1.0) { "dampingUp must be > 1" }
        require(dampingDown in 0.0..1.0) { "dampingDown must be in 0..1" }
        require(finiteDifferenceStep > 0.0) { "finiteDifferenceStep must be > 0" }
        require(gradientTolerance >= 0.0) { "gradientTolerance must be >= 0" }
        require(stepTolerance >= 0.0) { "stepTolerance must be >= 0" }
        require(improvementTolerance >= 0.0) { "improvementTolerance must be >= 0" }
    }
}

data class LeastSquaresResult(
    val x: DoubleArray,
    val residuals: DoubleArray,
    val cost: Double,
    val iterations: Int,
    val converged: Boolean,
    val reason: String
) {
    val rms: Double get() = if (residuals.isEmpty()) 0.0 else sqrt(2.0 * cost / residuals.size)
}

/**
 * Levenberg-Marquardt minimisation of half the squared residual norm.
 *
 * @param initial the starting parameter vector; must be non-empty and give finite residuals.
 * @param options the iteration cap, damping schedule and convergence tolerances.
 * @param jacobian an exact `∂rᵢ/∂xⱼ` (rows = residuals, columns = parameters). Null is the default,
 *        and then the Jacobian is central finite differences at `2n` extra residual evaluations per
 *        iteration. [leastSquaresAD] fills this in for you from residuals written in [AD]
 *        arithmetic.
 * @param residuals the residual vector at a parameter vector.
 */
fun leastSquares(
    initial: DoubleArray,
    options: LeastSquaresOptions = LeastSquaresOptions(),
    jacobian: ((DoubleArray) -> Array<DoubleArray>)? = null,
    residuals: (DoubleArray) -> DoubleArray
): LeastSquaresResult {
    require(initial.isNotEmpty()) { "initial must not be empty" }

    var x = initial.clone()
    var r = residuals(x)
    require(r.all { it.isFinite() }) { "initial residuals contained non-finite values" }
    var cost = halfSquaredNorm(r)
    var damping = options.initialDamping

    repeat(options.maxIterations) { iteration ->
        val j = jacobian?.invoke(x) ?: finiteDifferenceJacobian(x, r, options.finiteDifferenceStep, residuals)
        val normal = Array(x.size) { DoubleArray(x.size) }
        val gradient = DoubleArray(x.size)

        for (row in r.indices) {
            val rr = r[row]
            for (col in x.indices) {
                val jc = j[row][col]
                gradient[col] += jc * rr
                for (col2 in x.indices) {
                    normal[col][col2] += jc * j[row][col2]
                }
            }
        }

        val gradientInf = gradient.maxOf { abs(it) }
        if (gradientInf <= options.gradientTolerance) {
            return LeastSquaresResult(x, r, cost, iteration, converged = true, reason = "gradient")
        }

        var accepted = false
        var acceptedX = x
        var acceptedR = r
        var acceptedCost = cost
        var acceptedStepNorm = 0.0

        repeat(12) {
            val damped = Array(x.size) { i -> normal[i].clone() }
            for (i in x.indices) {
                damped[i][i] += damping * max(1.0, normal[i][i])
            }

            val rhs = DoubleArray(x.size) { i -> -gradient[i] }
            val step = solveLinearSystem(damped, rhs)
            if (step == null) {
                damping *= options.dampingUp
                return@repeat
            }

            val candidateX = x + step
            val candidateR = residuals(candidateX)
            if (!candidateR.all { it.isFinite() }) {
                damping *= options.dampingUp
                return@repeat
            }

            val candidateCost = halfSquaredNorm(candidateR)
            if (candidateCost < cost) {
                accepted = true
                acceptedX = candidateX
                acceptedR = candidateR
                acceptedCost = candidateCost
                acceptedStepNorm = norm(step)
                damping = max(damping * options.dampingDown, 1e-15)
                return@repeat
            }

            damping *= options.dampingUp
        }

        if (!accepted) {
            return LeastSquaresResult(x, r, cost, iteration + 1, converged = false, reason = "no accepted step")
        }

        val improvement = cost - acceptedCost
        x = acceptedX
        r = acceptedR
        cost = acceptedCost

        if (acceptedStepNorm <= options.stepTolerance) {
            return LeastSquaresResult(x, r, cost, iteration + 1, converged = true, reason = "step")
        }
        if (improvement <= options.improvementTolerance) {
            return LeastSquaresResult(x, r, cost, iteration + 1, converged = true, reason = "improvement")
        }
    }

    return LeastSquaresResult(x, r, cost, options.maxIterations, converged = false, reason = "max iterations")
}

/**
 * [leastSquares] with an **exact** Jacobian, from residuals written once in [AD] arithmetic.
 *
 * The residual lambda takes the parameters as tracked [AD] values and returns the residual list; the
 * plain values drive the Levenberg-Marquardt loop and one reverse-mode sweep per row supplies the
 * Jacobian. That replaces `2n` residual evaluations per iteration with a single forward pass, and
 * removes [LeastSquaresOptions.finiteDifferenceStep] from the things you have to get right.
 *
 * ```kotlin
 * val xs = doubleArrayOf(0.0, 0.25, 0.5, 0.75, 1.0)
 * val ys = xs.map { 2.3 * exp(-1.7 * it) + 0.4 }
 * val fit = leastSquaresAD(doubleArrayOf(1.0, -1.0, 0.0)) { p ->
 *     xs.indices.map { i -> p[0] * exp(p[1] * xs[i]) + p[2] - ys[i] }
 * }
 * ```
 *
 * The residual lambda must be expressible in [AD] arithmetic all the way down — a table lookup or a
 * `when` on a parameter's value will differentiate only the branch taken, which is correct for the
 * piecewise function but not for the one you may have meant.
 */
fun leastSquaresAD(
    initial: DoubleArray,
    options: LeastSquaresOptions = LeastSquaresOptions(),
    residuals: (List<AD>) -> List<AD>
): LeastSquaresResult = leastSquares(
    initial = initial,
    options = options,
    jacobian = { x -> jacobian(x) { p -> residuals(p) } },
    residuals = { x ->
        val tape = ADTape()
        val inputs = tape.variables(x)
        residuals(inputs).map { it.value }.toDoubleArray()
    }
)

private fun finiteDifferenceJacobian(
    x: DoubleArray,
    baseResiduals: DoubleArray,
    stepScale: Double,
    residuals: (DoubleArray) -> DoubleArray
): Array<DoubleArray> {
    val out = Array(baseResiduals.size) { DoubleArray(x.size) }

    for (col in x.indices) {
        val h = stepScale * max(1.0, abs(x[col]))
        val xp = x.clone()
        val xm = x.clone()
        xp[col] += h
        xm[col] -= h

        val rp = residuals(xp)
        val rm = residuals(xm)
        val denom = 2.0 * h

        for (row in baseResiduals.indices) {
            out[row][col] = (rp[row] - rm[row]) / denom
        }
    }

    return out
}

private operator fun DoubleArray.plus(other: DoubleArray): DoubleArray {
    require(size == other.size) { "vector sizes differ" }
    return DoubleArray(size) { i -> this[i] + other[i] }
}

private fun halfSquaredNorm(v: DoubleArray): Double =
    0.5 * v.sumOf { it * it }

private fun norm(v: DoubleArray): Double =
    sqrt(v.sumOf { it * it })

private fun solveLinearSystem(aIn: Array<DoubleArray>, bIn: DoubleArray): DoubleArray? {
    val n = bIn.size
    val a = Array(n) { i -> aIn[i].clone() }
    val b = bIn.clone()

    for (k in 0 until n) {
        var pivot = k
        var pivotAbs = abs(a[k][k])
        for (i in k + 1 until n) {
            val candidate = abs(a[i][k])
            if (candidate > pivotAbs) {
                pivot = i
                pivotAbs = candidate
            }
        }
        if (pivotAbs < 1e-14 || !pivotAbs.isFinite()) return null

        if (pivot != k) {
            val row = a[k]
            a[k] = a[pivot]
            a[pivot] = row
            val bv = b[k]
            b[k] = b[pivot]
            b[pivot] = bv
        }

        for (i in k + 1 until n) {
            val factor = a[i][k] / a[k][k]
            a[i][k] = 0.0
            for (j in k + 1 until n) {
                a[i][j] -= factor * a[k][j]
            }
            b[i] -= factor * b[k]
        }
    }

    val x = DoubleArray(n)
    for (i in n - 1 downTo 0) {
        var sum = b[i]
        for (j in i + 1 until n) {
            sum -= a[i][j] * x[j]
        }
        x[i] = sum / a[i][i]
    }
    return x
}
