@file:afield.runtime.completion.CompletionCategory("Math")

package afield.math.functions

import afield.math.linalg.Vec2
import org.ejml.simple.SimpleMatrix
import kotlin.math.sqrt

/**
 * Radial basis function interpolation in two or more dimensions: a smooth surface through values
 * scattered at points in a plane, or a volume, with no grid. The 1D version, [RbfInterpolator] behind
 * `Functions.rbf`, fits a curve through `(x, y)` samples; this one fits `f(point)` through
 * `(point, value)` samples, with the same [RbfKernel]s.
 *
 * The model is `f(p) = Σᵢ αᵢ φ(|p − cᵢ| / rᵢ) + β₀ + β·p`: one kernel bump per sample centre `cᵢ`,
 * plus, with [trend] on, an affine term. The thin-plate kernel, the default here as it is in 1D, is
 * only well posed with that term, and with it a plane through the samples is reproduced exactly, so
 * the kernels only have to describe how the data departs from a tilt. The weights solve one dense
 * linear system of size `n + dimension + 1`, rebuilt lazily after samples change, so this is sized
 * like the 1D fitter: tens to a few thousand samples.
 *
 * Samples can be added and removed one at a time, each with its own radius, which divides the
 * distance before the kernel sees it: a sample with radius 2 has twice the reach of one with radius
 * 1. [leaveOneOutResiduals] say how well each sample is predicted by all the others, at the cost of
 * one matrix inverse rather than `n` refits, which is how to choose a kernel, a shape parameter or a
 * [smoothing].
 */
class RbfField(
    /** The number of coordinates per point: 2 for a surface over a plane. */
    val dimension: Int,
    /** The radial function; distances are in the points' units, divided by each sample's radius. */
    val kernel: RbfKernel = RbfKernels.thinPlate,
    /**
     * Added to the kernel matrix's diagonal as a ridge term. 0 passes exactly through every sample; a
     * positive value trades that for a calmer surface through noisy data.
     */
    val smoothing: Double = 0.0,
    /**
     * Whether to fit an affine trend `β₀ + β·p` alongside the kernels. Needed for the thin-plate
     * kernel to be well posed; switched off automatically while there are fewer than
     * `dimension + 1` samples, too few to determine it.
     */
    val trend: Boolean = true,
) {
    init {
        require(dimension >= 1) { "dimension must be at least 1, got $dimension" }
        require(smoothing >= 0.0) { "smoothing must not be negative, got $smoothing" }
    }

    private val centres = mutableListOf<DoubleArray>()
    private val values = mutableListOf<Double>()
    private val radii = mutableListOf<Double>()

    private var alpha = DoubleArray(0)
    private var beta = DoubleArray(0)
    private var inverseDiagonal: DoubleArray? = null
    private var system: SimpleMatrix? = null
    private var dirty = true

    /** How many samples the field passes through. */
    val size: Int get() = centres.size

    /** Add a sample: [value] at [point], reaching [radius] times as far as a radius-1 sample. Returns its index. */
    fun add(point: DoubleArray, value: Double, radius: Double = 1.0): Int {
        require(point.size == dimension) { "expected a point with $dimension coordinates, got ${point.size}" }
        require(radius > 0.0 && radius.isFinite()) { "radius must be positive, got $radius" }
        centres += point.copyOf()
        values += value
        radii += radius
        dirty = true
        return centres.lastIndex
    }

    /** [add] for a 2D point. */
    fun add(x: Double, y: Double, value: Double, radius: Double = 1.0): Int = add(doubleArrayOf(x, y), value, radius)

    /** [add] for a 2D point. */
    fun add(point: Vec2, value: Double, radius: Double = 1.0): Int = add(point.x.toDouble(), point.y.toDouble(), value, radius)

    /** Remove the sample at [index]; later samples move down one. */
    fun removeAt(index: Int) {
        require(index in centres.indices) { "no sample $index; there are ${centres.size}" }
        centres.removeAt(index)
        values.removeAt(index)
        radii.removeAt(index)
        dirty = true
    }

    /** Remove every sample. */
    fun clear() {
        centres.clear()
        values.clear()
        radii.clear()
        dirty = true
    }

    /** The fitted value at [point]. With no samples it is 0. */
    operator fun invoke(point: DoubleArray): Double {
        require(point.size == dimension) { "expected a point with $dimension coordinates, got ${point.size}" }
        build()
        var sum = 0.0
        for (i in centres.indices) sum += alpha[i] * kernel.eval(distance(point, centres[i]) / radii[i])
        if (beta.isNotEmpty()) {
            sum += beta[0]
            for (k in 0 until dimension) sum += beta[k + 1] * point[k]
        }
        return sum
    }

    /** The fitted value at `(x, y)`. */
    operator fun invoke(x: Double, y: Double): Double = invoke(doubleArrayOf(x, y))

    /** The fitted value at [point]. */
    operator fun invoke(point: Vec2): Double = invoke(point.x.toDouble(), point.y.toDouble())

    /**
     * For each sample, the value the field would predict there if that sample were left out and the
     * rest refitted. Computed from one inverse of the system matrix (Rippa's formula), not `n` refits.
     * Needs at least two samples.
     */
    fun leaveOneOut(): DoubleArray {
        val residuals = leaveOneOutResiduals()
        return DoubleArray(size) { values[it] - residuals[it] }
    }

    /** For each sample, its value minus the [leaveOneOut] prediction there. */
    fun leaveOneOutResiduals(): DoubleArray {
        check(size >= 2) { "leave-one-out needs at least two samples, there are $size" }
        build()
        val diagonal = inverseDiagonal ?: system!!.invert().let { inv ->
            DoubleArray(size) { inv.get(it, it) }.also { inverseDiagonal = it }
        }
        return DoubleArray(size) { alpha[it] / diagonal[it] }
    }

    /** The root mean square of [leaveOneOutResiduals]: one number for how well the field generalises. */
    fun leaveOneOutRmse(): Double {
        val residuals = leaveOneOutResiduals()
        return sqrt(residuals.sumOf { it * it } / residuals.size)
    }

    private fun build() {
        if (!dirty) return
        dirty = false
        inverseDiagonal = null
        val n = centres.size
        if (n == 0) {
            alpha = DoubleArray(0)
            beta = DoubleArray(0)
            system = null
            return
        }
        val terms = if (trend && n >= dimension + 1) dimension + 1 else 0
        val size = n + terms
        val m = SimpleMatrix(size, size)
        val rhs = SimpleMatrix(size, 1)
        for (i in 0 until n) {
            for (j in 0 until n) m.set(i, j, kernel.eval(distance(centres[i], centres[j]) / radii[j]))
            m.set(i, i, m.get(i, i) + smoothing)
            rhs.set(i, 0, values[i])
            if (terms > 0) {
                // [1, p] on the right of the kernel block, and its transpose below it
                m.set(i, n, 1.0)
                m.set(n, i, 1.0)
                for (k in 0 until dimension) {
                    m.set(i, n + 1 + k, centres[i][k])
                    m.set(n + 1 + k, i, centres[i][k])
                }
            }
        }
        val solution = m.solve(rhs)
        alpha = DoubleArray(n) { solution.get(it, 0) }
        beta = DoubleArray(terms) { solution.get(n + it, 0) }
        system = m
    }

    private fun distance(p: DoubleArray, q: DoubleArray): Double {
        var s = 0.0
        for (k in p.indices) {
            val t = p[k] - q[k]
            s += t * t
        }
        return sqrt(s)
    }

    override fun toString(): String =
        "RbfField(${dimension}D, $size samples, kernel=$kernel, smoothing=$smoothing, trend=$trend)"
}

/**
 * A 2D [RbfField] through [samples], each a point and the value there. The thin-plate kernel and the
 * affine trend are the defaults, so with no shape parameter to choose this is the one to try first.
 */
fun rbfField(
    samples: List<Pair<Vec2, Number>>,
    kernel: RbfKernel = RbfKernels.thinPlate,
    smoothing: Double = 0.0,
    trend: Boolean = true,
): RbfField = RbfField(2, kernel, smoothing, trend).apply {
    samples.forEach { (point, value) -> add(point, value.toDouble()) }
}
