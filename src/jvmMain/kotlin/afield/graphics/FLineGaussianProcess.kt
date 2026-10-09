package afield.graphics

import afield.math.gp.*
import afield.stdlib.*
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A 2D/3D curve as a vector-valued GP on its scalar parameter, with full output covariance.
 * Velocities, frames and curvature need a [GpDifferentiableKernel] (the default RBF is one).
 */
class FLineGaussianProcess internal constructor(val model: GpVectorProcess, private val planeZ: Double) {
    val dimensions: Int get() = model.outputDimensions
    fun mean(t: Double): Vec3 = toPoint(model.mean(t))
    /** Coordinate variances; these are squared distances, not standard deviations or a ribbon width. */
    fun variance(t: Double): Vec3 { val v = model.variance(t); return vec(v[0], v[1], if (v.size == 3) v[2] else 0.0) }
    /** Full 3×3 covariance block; planar curves have a zero Z row and column. */
    fun covariance(t: Double, s: Double = t): Array<DoubleArray> = pad(model.covariance(doubleArrayOf(t), doubleArrayOf(s)))
    fun posterior(parameters: DoubleArray): FLineGpJoint = FLineGpJoint(parameters.copyOf(), model.posterior(column(parameters)), planeZ)

    /** Mean velocity d(position)/dt, in coordinate units per unit of parameter (Z is 0 for a planar curve). */
    fun velocity(t: Double): Vec3 = toVector(model.derivativePosterior(arrayOf(doubleArrayOf(t))).mean[0])
    /** The joint velocity at every parameter; sample it for coherent tangent fields. */
    fun velocities(parameters: DoubleArray): GpVectorJoint = model.derivativePosterior(column(parameters))

    /**
     * A moving frame along the mean curve, with how uncertain its tangent is. The normal of a
     * planar curve is the tangent turned a quarter to the left; a spatial curve's is the Frenet
     * normal, taken from the mean velocity [step] either side, and is null where the curve is straight.
     */
    fun frames(parameters: DoubleArray, step: Double = defaultStep(parameters)): List<GpCurveFrame> {
        require(step.isFinite() && step > 0)
        val positions = model.posterior(column(parameters)).mean
        val v = velocities(parameters)
        val around = model.derivativePosterior(Array(2 * parameters.size) { doubleArrayOf(parameters[it / 2] + if (it % 2 == 0) -step else step) }).mean
        return parameters.indices.map { i ->
            val vel = v.mean[i]
            val cov = v.covarianceAt(i)
            val speed = norm(vel)
            val tangent = if (speed > 0) DoubleArray(vel.size) { vel[it] / speed } else DoubleArray(vel.size)
            val along = quadratic(cov, tangent)
            val trace = cov.indices.sumOf { cov[it][it] }
            val angle = if (speed > 0) sqrt(max(0.0, trace - along)) / speed else Double.POSITIVE_INFINITY
            var normal: DoubleArray? = null
            var binormal: DoubleArray? = null
            if (speed > 0 && vel.size == 2) normal = doubleArrayOf(-tangent[1], tangent[0])
            if (speed > 0 && vel.size == 3) {
                val a = DoubleArray(3) { (around[2 * i + 1][it] - around[2 * i][it]) / (2 * step) }
                val at = gpDotOf(a, tangent)
                val n = DoubleArray(3) { a[it] - at * tangent[it] }
                val nn = norm(n)
                if (nn > 1e-12 * max(1.0, norm(a))) {
                    normal = DoubleArray(3) { n[it] / nn }
                    binormal = cross3(tangent, normal)
                }
            }
            GpCurveFrame(parameters[i], toPoint(positions[i]), toVector(tangent), normal?.let { toVector(it) },
                binormal?.let { toVector(it) }, speed, sqrt(max(0.0, along)), angle)
        }
    }

    /**
     * Curvature with uncertainty, by drawing [samples] whole velocity fields and differencing each
     * [step] either side. [GpCurvature.ofMean] is the curvature of the mean curve, which is not
     * the mean curvature of the draws: wiggly draws curve more than their average does. Planar
     * curvature is signed (positive turns left); spatial curvature is unsigned. Costs a joint of
     * 3 × parameters × dimensions values, so keep parameters to a few hundred. [step] defaults to
     * a thousandth of the parameter span (at least 0.001); much smaller and the sampler can no longer
     * resolve the difference between neighbouring velocities, understating the uncertainty. The
     * same floor applies to a curve pinned so tightly that its velocity variance nears the
     * [GpNumerics] tolerances; if [GpCurvature.std] changes with [step], it is at that floor.
     */
    fun curvature(parameters: DoubleArray, samples: Int = 256, seed: Long = 0L, step: Double = defaultStep(parameters)): GpCurvature {
        require(samples >= 2 && step.isFinite() && step > 0)
        val m = parameters.size
        val joint = model.derivativePosterior(Array(3 * m) { doubleArrayOf(parameters[it / 3] + (it % 3 - 1) * step) })
        val ofMean = curvatures(joint.mean, m, step)
        val sum = DoubleArray(m); val sum2 = DoubleArray(m)
        val rng = gpRandom(seed)
        repeat(samples) {
            val k = curvatures(joint.sample(rng), m, step)
            for (i in 0 until m) { sum[i] += k[i]; sum2[i] += k[i] * k[i] }
        }
        val mean = DoubleArray(m) { sum[it] / samples }
        val std = DoubleArray(m) { sqrt(max(0.0, (sum2[it] - samples * mean[it] * mean[it]) / (samples - 1))) }
        return GpCurvature(parameters.copyOf(), ofMean, mean, std)
    }

    private fun curvatures(v: Array<DoubleArray>, m: Int, step: Double): DoubleArray = DoubleArray(m) { i ->
        val vel = v[3 * i + 1]
        val a = DoubleArray(vel.size) { (v[3 * i + 2][it] - v[3 * i][it]) / (2 * step) }
        val s = norm(vel)
        if (s == 0.0) 0.0 else if (vel.size == 2) (vel[0] * a[1] - vel[1] * a[0]) / (s * s * s)
        else norm(cross3(vel, a)) / (s * s * s)
    }
    private fun defaultStep(parameters: DoubleArray): Double {
        val span = if (parameters.isEmpty()) 0.0 else parameters.max() - parameters.min()
        return 1e-3 * max(span, 1.0)
    }
    private fun column(p: DoubleArray) = Array(p.size) { doubleArrayOf(p[it]) }
    private fun pad(c: Array<DoubleArray>) = Array(3) { i -> DoubleArray(3) { j -> if (i < c.size && j < c.size) c[i][j] else 0.0 } }
    private fun toPoint(v: DoubleArray): Vec3 = vec(v[0], v[1], if (v.size == 3) v[2] else planeZ)
    private fun toVector(v: DoubleArray): Vec3 = vec(v[0], v[1], if (v.size == 3) v[2] else 0.0)
}

/**
 * One frame of [FLineGaussianProcess.frames]. [speed] is in coordinate units per unit parameter;
 * [tangentAngleStd] is the delta-method standard deviation of the tangent's direction, in radians.
 */
data class GpCurveFrame(
    val parameter: Double, val position: Vec3, val tangent: Vec3, val normal: Vec3?, val binormal: Vec3?,
    val speed: Double, val speedStd: Double, val tangentAngleStd: Double
)

/** Curvature in 1 / coordinate units. See [FLineGaussianProcess.curvature]. */
class GpCurvature internal constructor(parameters: DoubleArray, ofMean: DoubleArray, mean: DoubleArray, std: DoubleArray) {
    private val t = parameters; private val om = ofMean; private val m = mean; private val s = std
    val parameters: DoubleArray get() = t.copyOf()
    val ofMean: DoubleArray get() = om.copyOf()
    val mean: DoubleArray get() = m.copyOf()
    val std: DoubleArray get() = s.copyOf()
}

/** Draws and uncertainty on the same parameter grid. All vertices of a draw are correlated. */
class FLineGpJoint internal constructor(private val ts: DoubleArray, val distribution: GpVectorJoint, private val planeZ: Double) {
    val parameters: DoubleArray get() = ts.copyOf()
    val meanPoints: List<Vec3> get() = distribution.mean.map { point(it) }
    val variances: List<Vec3> get() = distribution.variance.map { vec(it[0], it[1], if (it.size == 3) it[2] else 0.0) }
    fun meanLine(): FLine = line(meanPoints)
    fun samplePoints(seed: Long): List<Vec3> = distribution.sample(seed).map { point(it) }
    fun sampleLine(seed: Long): FLine = line(samplePoints(seed))
    /** [spread] 0 is the mean line, 1 an honest draw, above 1 an exaggerated one. */
    fun sampleLine(seed: Long, spread: Double): FLine = line(distribution.sample(seed, spread).map { point(it) })
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
 * [velocities] pins d(position)/dt at chosen parameters (coordinate units per unit parameter),
 * e.g. to make a curve leave a point in a given direction; it needs a [GpDifferentiableKernel].
 */
fun gpCurve(parameters: DoubleArray, points: List<Vec3>, kernel: GpKernel = GpKernels.rbf(0.15),
            noiseStd: DoubleArray = DoubleArray(points.size), dimensions: Int = 3, mean: Vec3? = null,
            outputCovariance: Array<DoubleArray> = Array(dimensions) { i -> DoubleArray(dimensions) { j -> if (i == j) 1.0 else 0.0 } },
            numerics: GpNumerics = GpNumerics(),
            velocities: List<Pair<Double, Vec3>> = emptyList(), velocityNoiseStd: Double = 0.0): FLineGaussianProcess {
    require(dimensions == 2 || dimensions == 3)
    require(points.isNotEmpty() && points.size == parameters.size && points.size == noiseStd.size)
    require(outputCovariance.size == dimensions)
    require(points.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() })
    val z = points.first().z.toDouble()
    if (dimensions == 2) require(points.all { it.z.toDouble() == z }) { "A planar XY curve must have constant Z" }
    val origin = mean?.let { doubleArrayOf(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) }
        ?: doubleArrayOf(points.map { it.x.toDouble() }.average(), points.map { it.y.toDouble() }.average(), points.map { it.z.toDouble() }.average())
    val model = GpVectorProcess.fit(Array(parameters.size) { doubleArrayOf(parameters[it]) },
        Array(points.size) { i -> coordinates(points[i], dimensions) },
        kernel, Array(points.size) { i -> DoubleArray(dimensions) { noiseStd[i] } }, outputCovariance,
        { origin.copyOf(dimensions) }, 1, numerics,
        velocities.map { (t, v) -> GpVectorDerivativeObservation(doubleArrayOf(t), 0, coordinates(v, dimensions), velocityNoiseStd) })
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
fun GpJoint.sampleFLine(xs: DoubleArray, seed: Long, spread: Double): FLine = gpScalarLine(xs, sample(seed, spread))
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
private fun coordinates(p: Vec3, dimensions: Int) =
    if (dimensions == 2) doubleArrayOf(p.x.toDouble(), p.y.toDouble()) else doubleArrayOf(p.x.toDouble(), p.y.toDouble(), p.z.toDouble())
private fun norm(v: DoubleArray): Double = sqrt(v.sumOf { it * it })
private fun gpDotOf(a: DoubleArray, b: DoubleArray): Double { var s = 0.0; for (i in a.indices) s += a[i] * b[i]; return s }
private fun quadratic(c: Array<DoubleArray>, d: DoubleArray): Double { var s = 0.0; for (i in d.indices) for (j in d.indices) s += d[i] * c[i][j] * d[j]; return s }
private fun cross3(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
