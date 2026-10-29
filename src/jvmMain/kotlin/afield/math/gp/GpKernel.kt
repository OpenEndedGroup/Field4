@file:afield.runtime.completion.CompletionCategory("Math")
package afield.math.gp

import kotlin.math.*

/**
 * Covariance functions for Field's Gaussian processes, and the [GpKernels] catalogue of standard ones.
 *
 * A [GpKernel] is any pure function of two input points; kernels add, multiply and scale with `+` and
 * `*`. A [GpDifferentiableKernel] also supplies gradients and Hessians, which derivative observations
 * and slope queries need, and sums, products and scalings of differentiable kernels stay
 * differentiable. [GpKernels] provides RBF, Matérn, rational quadratic, periodic and periodic
 * mixtures, constant and linear kernels, plus `warp` for an input transform.
 */

/**
 * A covariance function. Implementations and mean functions must be pure and thread safe.
 * Sums, products and scalings of [GpDifferentiableKernel]s are differentiable whatever their
 * static type, so `rbf() + matern(1.5)` still answers derivative queries.
 */
fun interface GpKernel {
    /**
     * The prior covariance between the function's values at [a] and [b], in output-squared
     * units. The one method an implementation supplies.
     */
    fun covariance(a: DoubleArray, b: DoubleArray): Double
    /**
     * A kernel whose covariance is the sum of both: a model that is the sum of two independent
     * functions, such as a slow trend plus fast detail. Differentiable when both sides are.
     */
    operator fun plus(other: GpKernel): GpKernel =
        if (this is GpDifferentiableKernel && other is GpDifferentiableKernel) gpSum(this, other)
        else GpKernel { a, b -> covariance(a, b) + other.covariance(a, b) }
    /**
     * A kernel whose covariance is the product of both, which is how a periodic kernel is made
     * to decay or a kernel is restricted to a region. Differentiable when both sides are.
     */
    operator fun times(other: GpKernel): GpKernel =
        if (this is GpDifferentiableKernel && other is GpDifferentiableKernel) gpProduct(this, other)
        else GpKernel { a, b -> covariance(a, b) * other.covariance(a, b) }
    /** This kernel with its covariance scaled by [variance], which must be nonnegative. */
    operator fun times(variance: Double): GpKernel {
        gpNonnegative(variance, "kernel multiplier")
        return if (this is GpDifferentiableKernel) gpScaled(this, variance) else GpKernel { a, b -> variance * covariance(a, b) }
    }
}

/**
 * A kernel that also knows its input derivatives, so a process can be queried for, and trained
 * on, slopes, gradients and curve velocities without finite differences. [GpKernels.warp], Matérn
 * ν = 1/2 and Smile kernels are not differentiable.
 */
interface GpDifferentiableKernel : GpKernel {
    /** ∂k(a, b) / ∂b[axis]: the covariance between f(a) and ∂f/∂x[axis] at b. */
    fun covarianceGradient(a: DoubleArray, b: DoubleArray, axis: Int): Double
    /** ∂²k(a, b) / ∂a[i] ∂b[j]: the covariance between the slope along i at a and along j at b. */
    fun covarianceHessian(a: DoubleArray, b: DoubleArray, i: Int, j: Int): Double

    /** The sum of two differentiable kernels, differentiable by linearity. */
    operator fun plus(other: GpDifferentiableKernel): GpDifferentiableKernel = gpSum(this, other)
    /** The product of two differentiable kernels, differentiated by the product rule. */
    operator fun times(other: GpDifferentiableKernel): GpDifferentiableKernel = gpProduct(this, other)
    /** This kernel scaled by [variance], gradients and Hessians scaled with it. */
    override operator fun times(variance: Double): GpDifferentiableKernel {
        gpNonnegative(variance, "kernel multiplier")
        return gpScaled(this, variance)
    }
}

private fun gpSum(p: GpDifferentiableKernel, q: GpDifferentiableKernel): GpDifferentiableKernel = object : GpDifferentiableKernel {
    override fun covariance(a: DoubleArray, b: DoubleArray) = p.covariance(a, b) + q.covariance(a, b)
    override fun covarianceGradient(a: DoubleArray, b: DoubleArray, axis: Int) =
        p.covarianceGradient(a, b, axis) + q.covarianceGradient(a, b, axis)
    override fun covarianceHessian(a: DoubleArray, b: DoubleArray, i: Int, j: Int) =
        p.covarianceHessian(a, b, i, j) + q.covarianceHessian(a, b, i, j)
}

/** The product rule; ∂k/∂a is ∂k/∂b with the arguments swapped, since kernels are symmetric. */
private fun gpProduct(p: GpDifferentiableKernel, q: GpDifferentiableKernel): GpDifferentiableKernel = object : GpDifferentiableKernel {
    override fun covariance(a: DoubleArray, b: DoubleArray) = p.covariance(a, b) * q.covariance(a, b)
    override fun covarianceGradient(a: DoubleArray, b: DoubleArray, axis: Int) =
        p.covarianceGradient(a, b, axis) * q.covariance(a, b) + p.covariance(a, b) * q.covarianceGradient(a, b, axis)
    override fun covarianceHessian(a: DoubleArray, b: DoubleArray, i: Int, j: Int) =
        p.covarianceHessian(a, b, i, j) * q.covariance(a, b) +
            p.covarianceGradient(b, a, i) * q.covarianceGradient(a, b, j) +
            p.covarianceGradient(a, b, j) * q.covarianceGradient(b, a, i) +
            p.covariance(a, b) * q.covarianceHessian(a, b, i, j)
}

private fun gpScaled(p: GpDifferentiableKernel, variance: Double): GpDifferentiableKernel = object : GpDifferentiableKernel {
    override fun covariance(a: DoubleArray, b: DoubleArray) = variance * p.covariance(a, b)
    override fun covarianceGradient(a: DoubleArray, b: DoubleArray, axis: Int) = variance * p.covarianceGradient(a, b, axis)
    override fun covarianceHessian(a: DoubleArray, b: DoubleArray, i: Int, j: Int) = variance * p.covarianceHessian(a, b, i, j)
}

/**
 * Covariances in output-squared units. Length scales are in input units, one or one per axis.
 * Every kernel here except Matérn ν = 1/2 and [warp] is a [GpDifferentiableKernel].
 */
object GpKernels {
    /**
     * k = variance · g(s), s = Σ ((a−b)/ℓ)². [g1] and [g2] are dg/ds and d²g/ds². [g2] may be
     * singular at s = 0, where it multiplies (a−b)ᵢ(a−b)ⱼ = 0 and is skipped.
     */
    private class Radial(
        scales: DoubleArray, private val variance: Double,
        private val g: (Double) -> Double, private val g1: (Double) -> Double, private val g2: (Double) -> Double
    ) : GpDifferentiableKernel {
        private val ls = scales.copyOf()
        init {
            require(ls.isNotEmpty() && ls.all { it.isFinite() && it > 0 }) { "Positive finite length scales required" }
            gpNonnegative(variance, "kernel variance")
        }
        private fun scale(a: DoubleArray, i: Int): Double {
            require(ls.size == 1 || ls.size == a.size) { "Kernel/input dimension mismatch" }
            return ls[if (ls.size == 1) 0 else i]
        }
        private fun s(a: DoubleArray, b: DoubleArray): Double {
            require(a.size == b.size) { "Kernel/input dimension mismatch" }
            var d2 = 0.0
            for (i in a.indices) { val d = (a[i] - b[i]) / scale(a, i); d2 += d * d }
            return d2
        }
        override fun covariance(a: DoubleArray, b: DoubleArray): Double {
            val d2 = s(a, b)
            return if (d2.isInfinite()) 0.0 else variance * g(d2)
        }
        override fun covarianceGradient(a: DoubleArray, b: DoubleArray, axis: Int): Double {
            val d2 = s(a, b)
            if (d2.isInfinite()) return 0.0
            val l = scale(a, axis)
            return variance * g1(d2) * (-2 * (a[axis] - b[axis]) / (l * l))
        }
        override fun covarianceHessian(a: DoubleArray, b: DoubleArray, i: Int, j: Int): Double {
            val d2 = s(a, b)
            if (d2.isInfinite()) return 0.0
            val li = scale(a, i); val lj = scale(a, j)
            var h = if (i == j) g1(d2) else 0.0
            if (d2 > 0) h += g2(d2) * 2 * (a[i] - b[i]) * (a[j] - b[j]) / (li * li)
            return -2 * variance * h / (lj * lj)
        }
    }

    /** Squared exponential: infinitely mean-square differentiable. */
    fun rbf(lengthScale: Double = 1.0, variance: Double = 1.0): GpDifferentiableKernel = rbf(doubleArrayOf(lengthScale), variance)
    /**
     * The squared exponential with one length scale per input axis, so each axis can vary at
     * its own rate. A single length scale is applied to every axis.
     */
    fun rbf(lengthScales: DoubleArray, variance: Double = 1.0): GpDifferentiableKernel =
        Radial(lengthScales, variance, { exp(-0.5 * it) }, { -0.5 * exp(-0.5 * it) }, { 0.25 * exp(-0.5 * it) })

    /**
     * Matérn ν = 1/2, 3/2 or 5/2: respectively rough, once or twice mean-square differentiable.
     * ν = 3/2 and 5/2 are [GpDifferentiableKernel]s; ν = 1/2 has no derivative process.
     */
    fun matern(nu: Double = 1.5, lengthScale: Double = 1.0, variance: Double = 1.0): GpKernel =
        matern(nu, doubleArrayOf(lengthScale), variance)
    /**
     * Matérn with one length scale per input axis. [nu] must be 0.5, 1.5 or 2.5; anything else
     * throws. A single length scale is applied to every axis.
     */
    fun matern(nu: Double, lengthScales: DoubleArray, variance: Double = 1.0): GpKernel = when (nu) {
        0.5 -> {
            val radial = Radial(lengthScales, variance, { exp(-sqrt(it)) }, { 0.0 }, { 0.0 })
            GpKernel { a, b -> radial.covariance(a, b) }
        }
        1.5 -> Radial(lengthScales, variance,
            { s -> val r = sqrt(3 * s); (1 + r) * exp(-r) },
            { s -> -1.5 * exp(-sqrt(3 * s)) },
            { s -> val r = sqrt(3 * s); 2.25 * exp(-r) / r })
        2.5 -> Radial(lengthScales, variance,
            { s -> val r = sqrt(5 * s); (1 + r + r * r / 3) * exp(-r) },
            { s -> val r = sqrt(5 * s); -(5.0 / 6) * (1 + r) * exp(-r) },
            { s -> (25.0 / 12) * exp(-sqrt(5 * s)) })
        else -> throw IllegalArgumentException("Matérn nu must be 0.5, 1.5 or 2.5")
    }

    /** A scale mixture of RBF kernels; alpha controls the mixture. */
    fun rationalQuadratic(lengthScales: DoubleArray = doubleArrayOf(1.0), alpha: Double = 1.0, variance: Double = 1.0): GpDifferentiableKernel {
        require(alpha.isFinite() && alpha > 0)
        return Radial(lengthScales, variance,
            { (1 + it / (2 * alpha)).pow(-alpha) },
            { -0.5 * (1 + it / (2 * alpha)).pow(-alpha - 1) },
            { (alpha + 1) / (4 * alpha) * (1 + it / (2 * alpha)).pow(-alpha - 2) })
    }

    /** Exactly periodic along every axis. Multiply by an RBF to let cycles evolve. */
    fun periodic(period: Double = 1.0, lengthScale: Double = 1.0, variance: Double = 1.0): GpDifferentiableKernel {
        require(period.isFinite() && period > 0 && lengthScale.isFinite() && lengthScale > 0)
        gpNonnegative(variance, "kernel variance")
        val c = -2 * PI / (lengthScale * lengthScale * period)
        return object : GpDifferentiableKernel {
            override fun covariance(a: DoubleArray, b: DoubleArray): Double {
                require(a.size == b.size)
                var d = 0.0
                for (i in a.indices) { val s = sin(PI * ((a[i] - b[i]) / period)); d += s * s }
                return variance * exp(-2 * d / (lengthScale * lengthScale))
            }
            // With u = π(a−b)/p: ∂k/∂aᵢ = k·c·sin 2uᵢ, so ∂k/∂bⱼ = −k·c·sin 2uⱼ.
            override fun covarianceGradient(a: DoubleArray, b: DoubleArray, axis: Int): Double =
                -covariance(a, b) * c * sin(2 * PI * (a[axis] - b[axis]) / period)
            override fun covarianceHessian(a: DoubleArray, b: DoubleArray, i: Int, j: Int): Double {
                val k = covariance(a, b)
                var h = k * c * sin(2 * PI * (a[i] - b[i]) / period) * sin(2 * PI * (a[j] - b[j]) / period)
                if (i == j) h += k * (2 * PI / period) * cos(2 * PI * (a[i] - b[i]) / period)
                return -c * h
            }
        }
    }

    /**
     * Uncertainty over the period itself: a weighted sum of periodic kernels, one per candidate.
     * Weights are normalised to sum to one, so [variance] keeps its meaning.
     */
    fun periodicMixture(periods: DoubleArray, weights: DoubleArray = DoubleArray(periods.size) { 1.0 },
                        lengthScale: Double = 1.0, variance: Double = 1.0): GpDifferentiableKernel {
        require(periods.isNotEmpty() && periods.size == weights.size)
        weights.forEach { gpNonnegative(it, "period weight") }
        val total = weights.sum()
        require(total > 0) { "At least one period weight must be positive" }
        return periods.indices.map { periodic(periods[it], lengthScale, variance) * (weights[it] / total) }
            .reduce { a, b -> a + b }
    }

    /** [components] candidate periods spread evenly in log₂ over [octaves] octaves centred on [centerPeriod]. */
    fun octavePeriodic(centerPeriod: Double, components: Int = 7, octaves: Double = 1.0,
                       lengthScale: Double = 1.0, variance: Double = 1.0): GpDifferentiableKernel {
        require(centerPeriod.isFinite() && centerPeriod > 0 && components >= 2 && octaves.isFinite() && octaves > 0)
        val periods = DoubleArray(components) { centerPeriod * 2.0.pow(octaves * (it / (components - 1.0) - 0.5)) }
        return periodicMixture(periods, lengthScale = lengthScale, variance = variance)
    }

    /**
     * The same covariance between every pair of points: one unknown level, drawn once, shared
     * by the whole function. Add it to another kernel so the process can settle away from its
     * mean function instead of returning to it between observations.
     */
    fun constant(variance: Double = 1.0): GpDifferentiableKernel {
        gpNonnegative(variance, "kernel variance")
        return object : GpDifferentiableKernel {
            override fun covariance(a: DoubleArray, b: DoubleArray) = variance
            override fun covarianceGradient(a: DoubleArray, b: DoubleArray, axis: Int) = 0.0
            override fun covarianceHessian(a: DoubleArray, b: DoubleArray, i: Int, j: Int) = 0.0
        }
    }

    /** Random linear functions through [origin], plus a separately composable constant kernel. */
    fun linear(variance: Double = 1.0, origin: DoubleArray = doubleArrayOf(0.0)): GpDifferentiableKernel {
        gpNonnegative(variance, "kernel variance")
        require(origin.isNotEmpty() && origin.all { it.isFinite() })
        val o = origin.copyOf()
        fun c(a: DoubleArray, i: Int): Double {
            require(o.size == 1 || o.size == a.size)
            return o[if (o.size == 1) 0 else i]
        }
        return object : GpDifferentiableKernel {
            override fun covariance(a: DoubleArray, b: DoubleArray): Double {
                require(a.size == b.size)
                var d = 0.0
                for (i in a.indices) d += (a[i] - c(a, i)) * (b[i] - c(a, i))
                return variance * d
            }
            override fun covarianceGradient(a: DoubleArray, b: DoubleArray, axis: Int) = variance * (a[axis] - c(a, axis))
            override fun covarianceHessian(a: DoubleArray, b: DoubleArray, i: Int, j: Int) = if (i == j) variance else 0.0
        }
    }

    /** Pull a covariance back through a deterministic input map (e.g. circular coordinates). */
    fun warp(kernel: GpKernel, transform: (DoubleArray) -> DoubleArray): GpKernel =
        GpKernel { a, b -> kernel.covariance(transform(a), transform(b)) }
}
