package afield.graphics

import afield.stdlib.*
import kotlin.math.sqrt

/** A 2D/3D curve as a vector-valued GP on its scalar parameter, with full output covariance. */
class FLineGaussianProcess internal constructor(val model: GpVectorProcess, private val planeZ: Double) {
    fun mean(t: Double): Vec3 = toPoint(model.mean(t))
    /** Coordinate variances; these are squared distances, not standard deviations or a ribbon width. */
    fun variance(t: Double): Vec3 { val v = model.variance(t); return vec(v[0], v[1], if (v.size == 3) v[2] else 0.0) }
    /** Full 3×3 covariance block; planar curves have a zero Z row and column. */
    fun covariance(t: Double, s: Double = t): Array<DoubleArray> {
        val c = model.covariance(doubleArrayOf(t), doubleArrayOf(s))
        return Array(3) { i -> DoubleArray(3) { j -> if (i < c.size && j < c.size) c[i][j] else 0.0 } }
    }
    fun posterior(parameters: DoubleArray): FLineGpJoint = FLineGpJoint(parameters.copyOf(), model.posterior(Array(parameters.size) { doubleArrayOf(parameters[it]) }), planeZ)
    private fun toPoint(v: DoubleArray): Vec3 = vec(v[0], v[1], if (v.size == 3) v[2] else planeZ)
}

/** Draws and uncertainty on the same parameter grid. All vertices of a draw are correlated. */
class FLineGpJoint internal constructor(private val ts: DoubleArray, val distribution: GpVectorJoint, private val planeZ: Double) {
    val parameters: DoubleArray get() = ts.copyOf()
    val meanPoints: List<Vec3> get() = distribution.mean.map { point(it) }
    val variances: List<Vec3> get() = distribution.variance.map { vec(it[0], it[1], if (it.size == 3) it[2] else 0.0) }
    fun meanLine(): FLine = line(meanPoints)
    fun samplePoints(seed: Long): List<Vec3> = distribution.sample(seed).map { point(it) }
    fun sampleLine(seed: Long): FLine = line(samplePoints(seed))
    fun covarianceAt(index: Int, other: Int = index): Array<DoubleArray> {
        val c = distribution.covarianceAt(index, other)
        return Array(3) { i -> DoubleArray(3) { j -> if (i < c.size && j < c.size) c[i][j] else 0.0 } }
    }
    /** Pointwise variance in a chosen direction; handles correlated XYZ, not just diagonal terms. */
    fun projectedVariance(index: Int, direction: Vec3): Double = distribution.projectedVariance(index,
        if (distribution.dimensions == 2) doubleArrayOf(direction.x.toDouble(), direction.y.toDouble())
        else doubleArrayOf(direction.x.toDouble(), direction.y.toDouble(), direction.z.toDouble()))
    /**
     * Two pointwise normal-offset curves around a planar mean. Not a simultaneous credible region
     * or the envelope of all draws. Degenerate tangents use the positive Y direction.
     */
    fun normalBand(standardDeviations: Double = 1.96): Pair<FLine, FLine> {
        require(distribution.dimensions == 2) { "Use covariance ellipsoids/projectedVariance for a spatial curve" }
        require(standardDeviations.isFinite() && standardDeviations >= 0)
        val points = meanPoints
        val low = ArrayList<Vec3>(); val high = ArrayList<Vec3>()
        for (i in points.indices) {
            val a = points[maxOf(0, i - 1)]; val b = points[minOf(points.lastIndex, i + 1)]
            val dx = b.x.toDouble() - a.x; val dy = b.y.toDouble() - a.y
            val length = sqrt(dx * dx + dy * dy)
            val nx = if (length > 0) -dy / length else 0.0
            val ny = if (length > 0) dx / length else 1.0
            val width = standardDeviations * sqrt(projectedVariance(i, vec(nx, ny, 0)))
            low.add(vec(points[i].x - nx * width, points[i].y - ny * width, planeZ))
            high.add(vec(points[i].x + nx * width, points[i].y + ny * width, planeZ))
        }
        return line(low) to line(high)
    }
    private fun point(v: DoubleArray): Vec3 = vec(v[0], v[1], if (v.size == 3) v[2] else planeZ)
    private fun line(points: List<Vec3>): FLine = FLine().also { out -> points.forEachIndexed { i, p -> if (i == 0) out.moveTo(p) else out.lineTo(p) } }
}

/**
 * Fit explicitly parameterised curve observations. Defaults to a constant mean at their centroid.
 * A 2D curve must have constant Z, retained in every result. Output covariance is dimensionless;
 * kernel variance supplies the squared coordinate units. Supply a periodic kernel for a closed loop.
 */
fun gpCurve(parameters: DoubleArray, points: List<Vec3>, kernel: GpKernel = GpKernels.rbf(0.15),
            noiseStd: DoubleArray = DoubleArray(points.size), dimensions: Int = 3, mean: Vec3? = null,
            outputCovariance: Array<DoubleArray> = Array(dimensions) { i -> DoubleArray(dimensions) { j -> if (i == j) 1.0 else 0.0 } },
            numerics: GpNumerics = GpNumerics()): FLineGaussianProcess {
    require(dimensions == 2 || dimensions == 3)
    require(points.isNotEmpty() && points.size == parameters.size && points.size == noiseStd.size)
    require(outputCovariance.size == dimensions)
    require(points.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() })
    val z = points.first().z.toDouble()
    if (dimensions == 2) require(points.all { it.z.toDouble() == z }) { "A planar XY curve must have constant Z" }
    val origin = mean?.let { doubleArrayOf(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) }
        ?: doubleArrayOf(points.map { it.x.toDouble() }.average(), points.map { it.y.toDouble() }.average(), points.map { it.z.toDouble() }.average())
    val model = GpVectorProcess.fit(Array(parameters.size) { doubleArrayOf(parameters[it]) },
        Array(points.size) { i -> if (dimensions == 2) doubleArrayOf(points[i].x.toDouble(), points[i].y.toDouble()) else doubleArrayOf(points[i].x.toDouble(), points[i].y.toDouble(), points[i].z.toDouble()) },
        kernel, Array(points.size) { i -> DoubleArray(dimensions) { noiseStd[i] } }, outputCovariance,
        { origin.copyOf(dimensions) }, 1, numerics)
    return FLineGaussianProcess(model, z)
}

/** Fit one continuous path at normalised arc length, retaining posterior uncertainty for later use. */
fun FLine.gaussianProcess(trainingSamples: Int = 48, kernel: GpKernel = GpKernels.rbf(0.15), noiseStd: Double = 0.0,
                          dimensions: Int = 3, mean: Vec3? = null,
                          outputCovariance: Array<DoubleArray> = Array(dimensions) { i -> DoubleArray(dimensions) { j -> if (i == j) 1.0 else 0.0 } },
                          sampler: FLineSampler? = null, numerics: GpNumerics = GpNumerics()): FLineGaussianProcess {
    require(trainingSamples >= 2 && noiseStd.isFinite() && noiseStd >= 0)
    require(nodes.count { it is FLine.MoveTo } == 1) { "Fit each continuous FLine subpath separately" }
    val points = samplePoints(trainingSamples, sampler = sampler)
    require(points.size >= 2)
    val t = DoubleArray(points.size)
    for (i in 1 until points.size) {
        val dx = points[i].x.toDouble() - points[i-1].x; val dy = points[i].y.toDouble() - points[i-1].y; val dz = points[i].z.toDouble() - points[i-1].z
        t[i] = t[i-1] + sqrt(dx * dx + dy * dy + dz * dz)
    }
    val length = t.last()
    require(length > 0 && length.isFinite()) { "Curve must have nonzero finite length" }
    for (i in t.indices) t[i] /= length
    return gpCurve(t, points, kernel, DoubleArray(points.size) { noiseStd }, dimensions, mean, outputCovariance, numerics)
}

/** A scalar joint drawn as y(x), useful for 1D means, bands and samples. */
fun GpJoint.meanFLine(xs: DoubleArray): FLine = gpScalarLine(xs, mean)
fun GpJoint.sampleFLine(xs: DoubleArray, seed: Long): FLine = gpScalarLine(xs, sample(seed))
fun GpJoint.intervalFLines(xs: DoubleArray, standardDeviations: Double = 1.96): Pair<FLine, FLine> {
    require(standardDeviations.isFinite() && standardDeviations >= 0)
    val m = mean; val v = variance
    return gpScalarLine(xs, DoubleArray(size) { m[it] - standardDeviations * sqrt(v[it]) }) to
        gpScalarLine(xs, DoubleArray(size) { m[it] + standardDeviations * sqrt(v[it]) })
}
private fun gpScalarLine(xs: DoubleArray, ys: DoubleArray): FLine {
    require(xs.size == ys.size && xs.all { it.isFinite() })
    return FLine().also { out -> for (i in xs.indices) if (i == 0) out.moveTo(xs[i], ys[i]) else out.lineTo(xs[i], ys[i]) }
}
