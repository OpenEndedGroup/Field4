@file:afield.CompletionCategory("Graphics")

package afield.solidsl

import afield.stdlib.AD
import afield.stdlib.ADGradient
import afield.stdlib.OptimizeMethod
import afield.stdlib.OptimizeRun
import afield.stdlib.TriMesh
import afield.stdlib.minimizeWithGradient
import afield.stdlib.sq
import afield.stdlib.valueAndGrad
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Fitting a parametric solid to a **point cloud** — unoriented samples on a surface, with no signed
 * distances, no normals and no correspondence.
 *
 * ```kotlin
 * val cloud = someTarget.samplePointCloud(count = 3000, seed = 4, noise = 0.004)
 * val fit = template.fitToPointCloud(cloud, start = doubleArrayOf(0.3, 1.1, 0.4))
 * ```
 *
 * # Why this is a different problem from [fitParameters], and a harder one
 *
 * [fitParameters] regresses against a [SolidSamples] — a bag of points *with a signed distance at
 * each*. That is a rich target and it is also a slightly circular one: the distances come from
 * `toScalarField`, so the oracle already knows the answer in the same representation the model
 * speaks. It is the right test of whether the gradient works. It is not the problem anybody has.
 *
 * The problem anybody has is a **point cloud**: a `.psub` plane subdivision rendered as points, a
 * compressed-splat PLY, a depth scan. Points on a boundary. No sign, no inside/outside, no normals
 * you trust, no correspondence to anything, some noise, and — nearly always — only the part of the
 * surface that faced the sensor.
 *
 * The loss is the obvious one and it is worth stating exactly, because everything interesting here
 * follows from what it does *not* contain:
 *
 * ```
 * L(θ) = (1/N) · Σ f(pᵢ; θ)²
 * ```
 *
 * The model's own field, squared, at each cloud point. A surface point should be *on* the surface, so
 * `f` should be zero there. That is the whole objective.
 *
 * ## What the loss does not contain, and what goes wrong because of it
 *
 * There is **no term that wants the interior filled**. `L` is minimised by putting the model's
 * zero level set through the points, and a shape that puts *some* surface through them scores as well
 * as the shape that generated them. Three consequences, all of them real and all of them measured in
 * `SolidSLPointCloudTest` rather than asserted here:
 *
 *  - **A full cloud recovers well.** Points all round a closed surface pin it from every side, and
 *    the missing interior term does not get a chance to matter.
 *  - **A partial view does not, and it fails in a specific direction.** Crop the cloud to one
 *    hemisphere and every parameter that only shows itself on the missing side is unconstrained. The
 *    loss does not go up when they wander, because there are no points there to notice. This is not
 *    a solver failure — the solver finds a genuine minimum of the objective it was given — and no
 *    amount of restarting fixes it. It is the objective that is wrong for that data.
 *  - **`Shell` and `Round` are dangerous under this loss** in a way they are not under
 *    [fitParameters]. `abs(f) − t` is zero on *two* surfaces, so a shell can satisfy a cloud with the
 *    wrong one of them.
 *
 * [SolidCloudFit.unconstrainedParameters] reports the first-order version of the second bullet: a
 * parameter whose gradient stays near zero **across the whole cloud** is a parameter the data does
 * not see. It is the point-cloud analogue of [SolidFit.deadParameters] and it exists for the same
 * reason — the failure presents as a *converged* optimisation.
 *
 * ## The target generation shares no code with the model
 *
 * [SolidExpr.samplePointCloud] marches the shape with `afield.stdlib.marchingCubes` and samples its
 * triangles by area. That path touches nothing in `SolidGradient.kt`: no [AD], no tape, no
 * `toADField`. So a recovery here is two independent implementations agreeing, in the same sense
 * [SolidAgreement] means it, rather than one implementation checking its own arithmetic.
 *
 * It is also what a real cloud is like. Triangle-area sampling is uniform over the *surface*, so
 * (unlike vertex sampling) a coarsely marched flat face is not under-represented relative to a
 * finely marched curved one.
 *
 * @see SolidTemplate.fitToPointCloud
 * @see SolidExpr.samplePointCloud
 */

/**
 * Unoriented points on a surface — the target [SolidTemplate.fitToPointCloud] takes.
 *
 * Three parallel [DoubleArray]s and nothing else. Deliberately **not** a `List<Vec3>` and
 * deliberately carrying no normals: a type that could hold normals is a type somebody will assume
 * has them, and the whole point of this file is what can be recovered without them.
 */
class SolidPointCloud(
    val xs: DoubleArray,
    val ys: DoubleArray,
    val zs: DoubleArray,
) {
    init {
        require(xs.size == ys.size && ys.size == zs.size) {
            "a SolidPointCloud needs three arrays of one length, got ${xs.size}, ${ys.size}, ${zs.size}"
        }
        require(xs.isNotEmpty()) { "a SolidPointCloud needs at least one point" }
    }

    val size: Int get() = xs.size

    /** The cloud's axis-aligned extent, as `(minX, minY, minZ, maxX, maxY, maxZ)`. */
    fun extent(): DoubleArray = doubleArrayOf(
        xs.min(), ys.min(), zs.min(), xs.max(), ys.max(), zs.max(),
    )

    /** The longest axis of [extent] — the scale a residual should be read against. */
    val scale: Double
        get() = extent().let { maxOf(it[3] - it[0], maxOf(it[4] - it[1], it[5] - it[2])) }

    /** This cloud with only the points [keep] accepts — how a partial view is made. */
    fun filter(keep: (Double, Double, Double) -> Boolean): SolidPointCloud {
        val idx = (0 until size).filter { keep(xs[it], ys[it], zs[it]) }
        require(idx.isNotEmpty()) { "the filter kept no points at all" }
        return SolidPointCloud(
            DoubleArray(idx.size) { xs[idx[it]] },
            DoubleArray(idx.size) { ys[idx[it]] },
            DoubleArray(idx.size) { zs[idx[it]] },
        )
    }

    override fun toString(): String =
        "SolidPointCloud($size points, scale ${"%.4g".format(scale)})"
}

/**
 * Sample [count] points uniformly over this shape's **surface**, by marching it and sampling its
 * triangles by area.
 *
 * Nothing in this function touches the differentiable reading — it goes
 * [SolidExpr.march] → triangle areas → barycentric samples — so a fit against its output is a fit
 * against an independent implementation of the same shape.
 *
 * @param noise standard deviation of isotropic Gaussian jitter added to each point, in model units.
 *   `0.0` for a clean cloud. Real clouds are not clean, and a fit that only works on a clean one is
 *   worth knowing about.
 * @param resolution the march resolution. Higher costs time and buys a surface whose triangles are
 *   closer to the true one; the sampling is uniform over the *marched* surface either way, which is
 *   itself an approximation and is one of the error terms in any recovery below.
 */
fun SolidExpr.samplePointCloud(
    count: Int = 3000,
    seed: Int = 0,
    noise: Double = 0.0,
    resolution: Int = 110,
): SolidPointCloud {
    require(count > 0) { "count must be positive, got $count" }
    require(noise >= 0.0) { "noise must not be negative, got $noise" }
    val mesh = march(resolution = resolution, margin = 0.05)
    require(!mesh.isEmpty) { "marching $this produced no triangles, so there is no surface to sample" }
    return mesh.samplePoints(count, seed, noise)
}

/**
 * [count] points scattered uniformly over this mesh's triangles, by area.
 *
 * Area-weighted and not vertex-sampled, because a marched surface has wildly uneven triangle sizes —
 * a flat face comes out as a handful of large triangles and a curved one as thousands of small ones —
 * and sampling vertices would put nearly all the points on the curved parts. That biases a fit toward
 * whatever the march happened to tessellate finely, which is a property of the algorithm and not of
 * the shape.
 */
fun TriMesh.samplePoints(count: Int, seed: Int = 0, noise: Double = 0.0): SolidPointCloud {
    require(!isEmpty) { "an empty mesh has no surface to sample" }
    val n = triangleCount
    val cumulative = DoubleArray(n)
    var total = 0.0
    for (t in 0 until n) {
        val a = indices[3 * t]; val b = indices[3 * t + 1]; val c = indices[3 * t + 2]
        val ax = positions[3 * a].toDouble(); val ay = positions[3 * a + 1].toDouble(); val az = positions[3 * a + 2].toDouble()
        val bx = positions[3 * b].toDouble(); val by = positions[3 * b + 1].toDouble(); val bz = positions[3 * b + 2].toDouble()
        val cx = positions[3 * c].toDouble(); val cy = positions[3 * c + 1].toDouble(); val cz = positions[3 * c + 2].toDouble()
        val ux = bx - ax; val uy = by - ay; val uz = bz - az
        val vx = cx - ax; val vy = cy - ay; val vz = cz - az
        val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
        total += 0.5 * sqrt(nx * nx + ny * ny + nz * nz)
        cumulative[t] = total
    }
    require(total > 0.0) { "this mesh has zero total surface area" }

    val rng = Random(seed)
    val xs = DoubleArray(count); val ys = DoubleArray(count); val zs = DoubleArray(count)
    for (i in 0 until count) {
        // Binary search into the area prefix sums: uniform over surface area, not over triangles.
        val u = rng.nextDouble() * total
        var lo = 0; var hi = n - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (cumulative[mid] < u) lo = mid + 1 else hi = mid
        }
        val a = indices[3 * lo]; val b = indices[3 * lo + 1]; val c = indices[3 * lo + 2]
        // The square-root trick: uniform barycentric coordinates over a triangle.
        var s = rng.nextDouble(); var t = rng.nextDouble()
        if (s + t > 1.0) { s = 1.0 - s; t = 1.0 - t }
        val w = 1.0 - s - t
        xs[i] = w * positions[3 * a] + s * positions[3 * b] + t * positions[3 * c] +
            (if (noise > 0.0) rng.nextGaussian() * noise else 0.0)
        ys[i] = w * positions[3 * a + 1] + s * positions[3 * b + 1] + t * positions[3 * c + 1] +
            (if (noise > 0.0) rng.nextGaussian() * noise else 0.0)
        zs[i] = w * positions[3 * a + 2] + s * positions[3 * b + 2] + t * positions[3 * c + 2] +
            (if (noise > 0.0) rng.nextGaussian() * noise else 0.0)
    }
    return SolidPointCloud(xs, ys, zs)
}

/** Box–Muller, because `kotlin.random.Random` has no Gaussian and pulling in `java.util.Random` for one call would need a second seed to keep straight. */
private fun Random.nextGaussian(): Double {
    val u1 = nextDouble().coerceAtLeast(1e-12)
    val u2 = nextDouble()
    return sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
}

/** What a point-cloud fit did, and — as importantly — what the cloud could not tell it. */
data class SolidCloudFit(
    val template: SolidTemplate,
    val start: DoubleArray,
    val values: DoubleArray,
    /** Mean squared field value over the cloud at [start], measured now. */
    val initialLoss: Double,
    /** The same at [values]. */
    val loss: Double,
    val fitted: SolidExpr,
    val run: OptimizeRun,
    /**
     * Parameters whose gradient was negligible across the *whole cloud* at the start — the ones this
     * data cannot see.
     *
     * The point-cloud analogue of [SolidFit.deadParameters], and the more important of the two,
     * because a partial view produces them routinely and by construction. "Negligible" is relative
     * to the largest component of the same gradient: a parameter carrying less than a thousandth of
     * the largest one is not going to move before the others have finished, and if the others reach
     * the tolerance first it never moves at all.
     */
    val unconstrainedParameters: List<String>,
) {
    /** `initialLoss / loss`. */
    val improvementFactor: Double get() = if (loss <= 0.0) Double.POSITIVE_INFINITY else initialLoss / loss

    /**
     * The RMS field value over the cloud — an approximate **distance from the cloud to the fitted
     * surface**, in model units, and the number to read as "how far off is it".
     *
     * Approximate because `f` is only a true distance where the expression's [SolidExpr.fieldNotes]
     * is empty; elsewhere it is an over- or under-estimate, and this inherits that.
     */
    val rmsDistance: Double get() = sqrt(kotlin.math.max(0.0, loss))

    fun report(): String = buildString {
        appendLine("point-cloud fit, ${template.names.size} parameter(s), ${run.iterations} iterations (${run.reason})")
        template.names.forEachIndexed { i, name ->
            appendLine("  %-14s %12.6f  ->  %12.6f".format(name, start[i], values[i]))
        }
        appendLine("  loss           ${"%.6e".format(initialLoss)}  ->  ${"%.6e".format(loss)}")
        appendLine("  rms distance   ${"%.6e".format(sqrt(kotlin.math.max(0.0, initialLoss)))}  ->  ${"%.6e".format(rmsDistance)}")
        appendLine("  improvement    ${"%.4g".format(improvementFactor)}x")
        if (unconstrainedParameters.isNotEmpty()) {
            appendLine("  UNCONSTRAINED by this cloud: ${unconstrainedParameters.joinToString(", ")}")
        }
        append("  fitted         $fitted")
    }

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * Fit this template's named parameters so its **surface passes through** [cloud], by gradient descent
 * on the mean squared field value.
 *
 * ```kotlin
 * val fit = template.fitToPointCloud(cloud, start = doubleArrayOf(0.3, 1.1, 0.4))
 * fit.rmsDistance                    // how far the cloud sits off the fitted surface
 * fit.unconstrainedParameters        // what this cloud could not tell it — read this first
 * ```
 *
 * [OptimizeMethod.ADAM] by default and not [OptimizeMethod.LBFGS], which is the opposite of
 * [fitParameters]' default. The reason is the objective: a squared *field* residual over surface
 * points is much flatter than a signed-distance regression — every point contributes only through
 * how far it is from the zero set, so the landscape has long shallow valleys where a line search
 * decides it has converged. Adam walks them.
 *
 * @param start where to begin. There is no `seed` default worth having here: a point cloud arrives
 *   without a prior, and the caller has to say what they think the shape is.
 */
fun SolidTemplate.fitToPointCloud(
    cloud: SolidPointCloud,
    start: DoubleArray,
    options: SolidFitOptions = SolidFitOptions(
        method = OptimizeMethod.ADAM,
        iterations = 1200,
        learningRate = 0.01,
    ),
): SolidCloudFit {
    require(start.size == size) {
        "this template has $size parameter(s) (${names.joinToString(", ")}), got ${start.size} start values"
    }

    val valueAndGradient: (DoubleArray) -> ADGradient = { p ->
        valueAndGrad(p) { parameters ->
            val tape = parameters[0].tape
            val field = adFieldWith(parameters)
            var loss: AD = tape.constant(0.0)
            for (i in 0 until cloud.size) {
                loss += field.at(
                    tape.constant(cloud.xs[i]),
                    tape.constant(cloud.ys[i]),
                    tape.constant(cloud.zs[i]),
                ).sq()
            }
            loss / cloud.size.toDouble()
        }
    }

    val at0 = valueAndGradient(start)
    val biggest = at0.gradient.maxOfOrNull { abs(it) } ?: 0.0
    val unconstrained = names.indices
        .filter { biggest <= 0.0 || abs(at0.gradient[it]) < 1e-3 * biggest }
        .map { names[it] }

    val run = minimizeWithGradient(start, options.toOptimizeOptions(), valueAndGradient)
    val end = valueAndGradient(run.x)

    return SolidCloudFit(
        template = this,
        start = start.copyOf(),
        values = run.x.copyOf(),
        initialLoss = at0.value,
        loss = end.value,
        fitted = withValues(run.x),
        run = run,
        unconstrainedParameters = unconstrained,
    )
}
