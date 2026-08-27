@file:afield.CompletionCategory("Graphics")

package afield.solidsl

import afield.stdlib.AD
import afield.stdlib.ADTape
import afield.stdlib.OptimizeMethod
import afield.stdlib.OptimizeOptions
import afield.stdlib.OptimizeRun
import afield.stdlib.clamp
import afield.stdlib.minimizeWithGradient
import afield.stdlib.valueAndGrad
import afield.stdlib.ADGradient
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The payoff of lowering **(c)**: **inverse design.** Declare what a solid should look like and let a
 * gradient find the numbers that make it so.
 *
 * ```kotlin
 * val truth  = (sphere(0.80) + cylinder(1.60, 0.30)).let { it }
 * val target = truth.sampleField(count = 3000, seed = 7)
 *
 * val template = truth.parameterize {
 *     param("r") { it.node == "Sphere" && it.field == "radius" }
 *     param("h") { it.node == "Cylinder" && it.field == "height" }
 * }.orThrow()
 *
 * val fit = template.fitParameters(target, start = doubleArrayOf(0.35, 2.60))
 * println(fit.report())
 * fit.fitted.march(96)      // an ordinary SolidExpr again
 * ```
 *
 * This is `afield.rectsl.Track.fitParameters` in three dimensions, and it is the reason
 * `SolidGradient.kt` exists. RectSLFn's KDoc: "a track that lowers to an `Fn` is a track you can take
 * the derivative of, and therefore a track you can **solve**: declare where a rect should be at some
 * instant and let a gradient find the numbers that put it there."
 *
 * # The target is a sampled field, and why that and not a mesh
 *
 * A [SolidSamples] is a bag of points with a signed distance at each. Three things recommend it over
 * fitting against a mesh:
 *
 *  - **It has a gradient.** A mesh-to-mesh distance goes through a nearest-triangle search whose
 *    result is a discontinuous function of the parameters — the nearest triangle *changes* — and
 *    marching cubes has to be re-run at every step. A field residual is `pred − target` at a fixed
 *    point, and its derivative is exactly what [SolidTemplate.adFieldWith] hands over.
 *  - **The target need not be a solid at all.** [sampleField] builds one from another [SolidExpr],
 *    which is what a recovery test wants; but a scan, a level set from somewhere else, or a hand-
 *    written list of "this point must be 2mm outside" constraints is the same type.
 *  - **It is cheap and fixed.** Sample once; every iteration reads the same array. A 3000-point
 *    target costs one march-free forward pass and one backward sweep per step.
 *
 * The cost is that it fits the *field* and not the *surface*, and those are different objectives. Two
 * shapes can have the same zero set and different fields — [SolidExpr.Intersect]'s own
 * [SolidExpr.fieldNotes] entry says it over-estimates distance outside a concave corner — so a field
 * residual is a stricter target than "look the same". Strictly speaking that is a feature for
 * recovery (there is one right answer and the loss knows it) and a hazard for design (you may not
 * care about the field away from the surface). [SolidFitOptions.band] is the dial: clamp both sides
 * into a narrow band around zero and the objective becomes almost purely about the surface.
 *
 * # What makes a parameter refuse to move
 *
 * This is the failure mode of inverse design and it is worth naming before it happens, because it
 * presents as a *converged* optimisation:
 *
 *  - A hard [SolidExpr.Union] gives the whole derivative to whichever child is nearest. A child that
 *    is nowhere nearest — swallowed inside another — receives exactly zero from every sample, and its
 *    parameters have a zero gradient. Write the shape with [smoothUnion] and every child contributes
 *    within `k` of the seam.
 *  - A [SolidExpr.Difference] cutter entirely inside the body is the same story.
 *  - A [SolidExpr.Empty] subtree, or an `Extrude` sample past the end of the sweep, is a structural
 *    zero.
 *
 * [SolidFit.deadParameters] reports exactly which parameters had a zero gradient at the *start*, so
 * that reads as a diagnosis rather than as a mysteriously immediate convergence. And
 * [SolidExpr.gradientNotes] says the same things about an expression before it is ever fitted.
 *
 * @see SolidTemplate.checkGradient for the finite-difference second opinion on any single point
 * @see SolidExpr.gradientAgreement for lowering (c)'s value measured against lowering (a)
 */

// ==============================================================================================
// The target
// ==============================================================================================

/**
 * A bag of points with a signed distance at each — the thing a parametric solid is fitted *to*.
 *
 * Four parallel [DoubleArray]s rather than a `List<Pair<Vec3, Double>>` because the inner loop of a
 * fit walks all of them once per iteration and per line-search probe, and because `Vec3` is
 * `Vector3f`: `Float`, and mutable, which is the objection [P2] was introduced to answer.
 */
class SolidSamples(
    val xs: DoubleArray,
    val ys: DoubleArray,
    val zs: DoubleArray,
    val distances: DoubleArray,
) {
    init {
        require(xs.size == ys.size && ys.size == zs.size && zs.size == distances.size) {
            "a SolidSamples needs four arrays of one length, got ${xs.size}, ${ys.size}, ${zs.size}, ${distances.size}"
        }
        require(xs.isNotEmpty()) { "a SolidSamples needs at least one point" }
    }

    val size: Int get() = xs.size

    /** How many of these points are inside the shape they were sampled from — a sanity number. */
    val insideCount: Int get() = distances.count { it < 0.0 }

    /**
     * The root-mean-square distance in this target — the scale a loss should be read against.
     *
     * A loss of `0.01` means nothing on its own; `0.01` against an [rms] of `0.5` is a 2% residual.
     */
    val rms: Double get() = sqrt(distances.sumOf { it * it } / size)

    override fun toString(): String = "SolidSamples($size points, $insideCount inside, rms ${"%.4g".format(rms)})"
}

/**
 * Sample this shape's field at [count] pseudo-random points in its own bounds, expanded by [margin]
 * as a fraction of the longest axis.
 *
 * Uniform in the box rather than concentrated near the surface, on purpose: a near-surface sampling
 * is a better *surface* objective but it has to know where the surface is, which for a target that is
 * being recovered is circular. Uniform-in-a-box needs only the bounds, and [SolidFitOptions.band]
 * recovers the near-surface behaviour by clamping the residual instead of by moving the points.
 *
 * The sequence is a plain [Random] seeded by [seed], so a fit is reproducible to the last bit and a
 * test that depends on one is not depending on the wall clock.
 *
 * @param bounds overrides the shape's own [SolidExpr.bounds] — required for an unbounded shape (one
 *   containing a [SolidExpr.Gyroid]), which has no window of its own.
 */
fun SolidExpr.sampleField(
    count: Int = 2000,
    seed: Int = 0,
    bounds: afield.stdlib.AxisAlignedBoundingBox3D? = null,
    margin: Double = 0.25,
): SolidSamples {
    require(count > 0) { "count must be positive, got $count" }
    val raw = bounds ?: this.bounds() ?: error(
        "cannot sample $this: it is unbounded (it contains a Gyroid, or a Union with one) so there " +
            "is no window to sample in — pass `bounds` explicitly, or intersect it with something bounded",
    )
    val s = raw.size
    val longest = maxOf(s.x, s.y, s.z).toDouble()
    val box = raw.expandedBy(longest * margin)
    val lo = box.min
    val hi = box.max
    val f = toField3()
    val rng = Random(seed)
    val xs = DoubleArray(count); val ys = DoubleArray(count)
    val zs = DoubleArray(count); val ds = DoubleArray(count)
    for (i in 0 until count) {
        val x = lo.x + rng.nextDouble() * (hi.x - lo.x)
        val y = lo.y + rng.nextDouble() * (hi.y - lo.y)
        val z = lo.z + rng.nextDouble() * (hi.z - lo.z)
        xs[i] = x.toDouble(); ys[i] = y.toDouble(); zs[i] = z.toDouble()
        ds[i] = f.at(xs[i], ys[i], zs[i])
    }
    return SolidSamples(xs, ys, zs, ds)
}

// ==============================================================================================
// The measurement: lowering (c) against lowering (a)
// ==============================================================================================

/**
 * Lowering (c)'s value against lowering (a)'s, over one shape.
 *
 * [SolidAgreement]'s argument, applied to a third column: "Two implementations that share no code
 * agreeing to five figures is a much better argument that either one is right than any number of
 * assertions against remembered constants."
 *
 * It is a *separate* measurement from [crossCheck] rather than a fourth set of fields on
 * [SolidAgreement], and the reason is that they measure different objects. `crossCheck` compares
 * **surfaces** — volume, area, genus, Hausdorff deviation — and lowering (c) does not produce a
 * surface; it produces a field, exactly as lowering (a) does. So the honest comparison is
 * field-against-field, pointwise, and folding it into a surface report would have meant inventing a
 * "volume" for something that has none. What the two measurements share is the *argument*, and this
 * KDoc and `SolidAgreement`'s cross-reference each other so a reader finds both.
 *
 * The expected residual is not zero and not the faceting deficit either: it is `Float`. Lowering (a)
 * reaches a caller through [toScalarField], which is `(Vec3) -> Float`, so about seven decimal digits
 * survive. [maxRelativeError] against `1e-6` is therefore the *tight* assertion, and anything larger
 * is a real disagreement.
 */
data class SolidFieldAgreement(
    val expr: SolidExpr,
    val samples: Int,
    /** Largest `|c − a|` over the samples, in model units. */
    val maxAbsoluteError: Double,
    /** Largest `|c − a| / max(1, |a|)` — relative, but floored so a near-zero value is not a blow-up. */
    val maxRelativeError: Double,
    /** Where the worst disagreement was, for a `println` that can be re-evaluated by hand. */
    val worstAt: Triple<Double, Double, Double>,
    /** How many samples produced a non-finite value on either side — must be 0. */
    val nonFinite: Int,
) {
    fun report(): String =
        "field agreement over $samples samples: max abs ${"%.4e".format(maxAbsoluteError)}, " +
            "max rel ${"%.4e".format(maxRelativeError)} at " +
            "(${"%.4g".format(worstAt.first)}, ${"%.4g".format(worstAt.second)}, ${"%.4g".format(worstAt.third)})" +
            (if (nonFinite > 0) ", $nonFinite NON-FINITE" else "")
}

/**
 * Evaluate this shape both through [toADField] and through [toScalarField] at the same points, and
 * report how far apart they land.
 *
 * One tape for the whole sweep, and no backward pass: this measures the *value* half of the
 * differentiable reading, which is the half that has an independent second opinion. The gradient half
 * is checked against central differences instead, by [SolidTemplate.checkGradient] — there is no
 * second implementation of the derivative to compare against, which is precisely why the numerical
 * oracle is used there and not here.
 */
fun SolidExpr.gradientAgreement(
    count: Int = 4000,
    seed: Int = 0,
    bounds: afield.stdlib.AxisAlignedBoundingBox3D? = null,
    margin: Double = 0.25,
): SolidFieldAgreement {
    val target = sampleField(count, seed, bounds, margin)
    val tape = ADTape()
    val ad = toADField(SolidNumerics(tape))
    var maxAbs = 0.0
    var maxRel = 0.0
    var worst = Triple(0.0, 0.0, 0.0)
    var bad = 0
    val approx = toScalarField()
    for (i in 0 until target.size) {
        val x = target.xs[i]; val y = target.ys[i]; val z = target.zs[i]
        val a = approx(afield.stdlib.Vec3(x.toFloat(), y.toFloat(), z.toFloat())).toDouble()
        val c = ad.at(tape.constant(x), tape.constant(y), tape.constant(z)).value
        if (!a.isFinite() || !c.isFinite()) { bad++; continue }
        val e = abs(c - a)
        val rel = e / max(1.0, abs(a))
        if (rel > maxRel) { maxRel = rel; worst = Triple(x, y, z) }
        if (e > maxAbs) maxAbs = e
    }
    return SolidFieldAgreement(this, target.size, maxAbs, maxRel, worst, bad)
}

// ==============================================================================================
// The finite-difference second opinion
// ==============================================================================================

/**
 * An analytic gradient beside a central-difference one, component by component.
 *
 * The oracle is central rather than forward differences for two reasons, and the second is the one
 * that matters. Central differences are `O(h²)` accurate instead of `O(h)`, which buys three digits;
 * and — as `autodiff.kt`'s kink convention says explicitly — a central difference at a kink reports
 * the **average of the two one-sided slopes**, which is exactly the 0.5/0.5 split the analytic
 * gradient takes there. So the two agree at kinks as well as away from them, and a CSG seam does not
 * have to be excluded from a census.
 *
 * # The verdict is per component, and there is a verdict about the verdict
 *
 * [usableComponents] marks which entries the oracle is willing to speak about. A central difference
 * is `O(h²)`, so halving `h` should barely move it; if it moves a lot, the third derivative is
 * enormous or the function is not differentiable at this point at all, and the numerical answer is
 * not evidence about anything. That happens at a sphere's own centre, on a polygon's outline, and
 * within `h` of any kink — a real property of distance fields, not a weakness of the analytic side.
 *
 * Per *component* rather than per *point* because the alternative loses most of the census: a
 * twisted star extrusion has twenty-six parameters, and one of them being near its own kink is not a
 * reason to discard the verdict on the other twenty-five.
 *
 * And a census that counts the unusable ones is the only kind worth running. A check with nothing
 * usable has no verdict, and a suite of those passes silently — which is exactly the failure
 * `RectSLFnBackendTest` documents guarding against. [usableCount] against [size] is the number to
 * print and then floor.
 */
data class SolidGradientCheck(
    val analytic: DoubleArray,
    val numerical: DoubleArray,
    /** The same central difference at half the step — what [usableComponents] is decided from. */
    val numericalHalfStep: DoubleArray,
    val step: Double,
) {
    /** How many components there are. */
    val size: Int get() = analytic.size

    /** Which components the numerical oracle is prepared to have an opinion about. */
    val usableComponents: BooleanArray = BooleanArray(analytic.size) { i ->
        numerical[i].isFinite() && numericalHalfStep[i].isFinite() && analytic[i].isFinite() &&
            abs(numerical[i] - numericalHalfStep[i]) /
            max(1.0, max(abs(numerical[i]), abs(numericalHalfStep[i]))) < 1e-4
    }

    /** How many of them, i.e. how much evidence this check actually carries. */
    val usableCount: Int get() = usableComponents.count { it }

    /** Whether there is any verdict here at all. */
    val usable: Boolean get() = usableCount > 0

    /**
     * The disagreement over the usable components, measured against a **floored** scale —
     * `max(1, |analytic|, |numerical|)`.
     *
     * The floor is the point. A relative error against a derivative that happens to be `1e-9` is
     * meaningless and fails any tolerance; a floor of 1 makes it an absolute test exactly where an
     * absolute test is the right one, and a relative one where the derivative is large (a `Scale`
     * factor's `1/s²` reaches the thousands).
     *
     * `0.0` when nothing is usable — which is why a caller must consult [usableCount] and not read a
     * zero here as a pass.
     */
    val maxError: Double
        get() = analytic.indices.filter { usableComponents[it] }.maxOfOrNull { i ->
            abs(analytic[i] - numerical[i]) / max(1.0, max(abs(analytic[i]), abs(numerical[i])))
        } ?: 0.0

    /** Which component disagreed the most, or `-1` when there is no verdict. */
    val worstComponent: Int
        get() = analytic.indices.filter { usableComponents[it] }.maxByOrNull { i ->
            abs(analytic[i] - numerical[i]) / max(1.0, max(abs(analytic[i]), abs(numerical[i])))
        } ?: -1

    override fun equals(other: Any?): Boolean =
        other is SolidGradientCheck && analytic.contentEquals(other.analytic) &&
            numerical.contentEquals(other.numerical) &&
            numericalHalfStep.contentEquals(other.numericalHalfStep) && step == other.step

    override fun hashCode(): Int =
        31 * (31 * analytic.contentHashCode() + numerical.contentHashCode()) + step.hashCode()

    override fun toString(): String =
        "$usableCount/$size usable, max floored relative error ${"%.3e".format(maxError)} at " +
            "component $worstComponent  analytic [${analytic.joinToString(", ") { "%.6g".format(it) }}]  " +
            "numerical [${numerical.joinToString(", ") { "%.6g".format(it) }}]"
}

/**
 * The parameter gradient at one point, beside its central-difference oracle.
 *
 * Both directions matter and they are checked separately, because they fail differently: a wrong
 * *position* gradient is a wrong normal and shows up in a render, while a wrong *parameter* gradient
 * is a fit that walks the wrong way and shows up as a plausible-looking non-answer.
 *
 * @param h the difference step. `1e-5` is the sweet spot for a `Double` field of unit scale — big
 *   enough that the `O(h²)` truncation is around `1e-10` and small enough that cancellation in
 *   `(f(x+h) − f(x−h))` leaves ten digits.
 */
fun SolidTemplate.checkGradient(
    x: Double,
    y: Double,
    z: Double,
    values: DoubleArray = seed,
    h: Double = 1e-5,
): SolidGradientCheck {
    val analytic = gradientAt(x, y, z, values).dParameters
    val numerical = DoubleArray(size) { central(x, y, z, values, it, h) }
    val half = DoubleArray(size) { central(x, y, z, values, it, h / 2.0) }
    return SolidGradientCheck(analytic, numerical, half, h)
}

/** [checkGradient] for the position gradient — the surface normal. */
fun SolidTemplate.checkPositionGradient(
    x: Double,
    y: Double,
    z: Double,
    values: DoubleArray = seed,
    h: Double = 1e-5,
): SolidGradientCheck {
    val analytic = gradientAt(x, y, z, values).dPosition
    val field = withValues(values).toField3()
    fun at(step: Double) = doubleArrayOf(
        (field.at(x + step, y, z) - field.at(x - step, y, z)) / (2.0 * step),
        (field.at(x, y + step, z) - field.at(x, y - step, z)) / (2.0 * step),
        (field.at(x, y, z + step) - field.at(x, y, z - step)) / (2.0 * step),
    )
    return SolidGradientCheck(analytic, at(h), at(h / 2.0), h)
}

/**
 * One component of the central difference.
 *
 * The tree is rebuilt and re-lowered per probe, which is `O(nodes)` and looks wasteful. It is the
 * honest way: substituting into a [SolidExpr] and lowering it afresh is exactly what the *analytic*
 * side is being compared against, so a shared cache between the two would be a shared assumption,
 * and a shared assumption is the one thing a second opinion must not have.
 */
private fun SolidTemplate.central(
    x: Double,
    y: Double,
    z: Double,
    values: DoubleArray,
    i: Int,
    h: Double,
): Double {
    val up = values.copyOf(); up[i] += h
    val down = values.copyOf(); down[i] -= h
    val fu = withValues(up).toField3().at(x, y, z)
    val fd = withValues(down).toField3().at(x, y, z)
    return (fu - fd) / (2.0 * h)
}

// ==============================================================================================
// The fit
// ==============================================================================================

/**
 * The dials on [SolidTemplate.fitParameters].
 *
 * @param band clamps both the prediction and the target into `[-band, +band]` before differencing,
 *   turning a whole-field objective into a near-surface one. `null` leaves the residual alone. This
 *   is the standard truncated-SDF trick and it does exactly one thing here: it stops a sample far
 *   outside the shape, where every candidate is roughly equally wrong, from outvoting the samples
 *   that decide what the surface looks like. It also **kills the gradient** from those far samples
 *   outright — which is the honest description of "stops them outvoting", and is why the default is
 *   `null`: on a recovery problem the far field is informative and there is no reason to discard it.
 * @param method [OptimizeMethod.LBFGS] by default. A distance field's parameters are badly scaled
 *   against each other — [SolidExpr.gradientNotes] says an angle in degrees carries a π/180 and a
 *   [SolidExpr.Scale] factor carries a `1/s²` — and a line search handles that where a fixed learning
 *   rate does not. [OptimizeMethod.ADAM] is the fallback when the objective is rough enough that the
 *   line search keeps failing.
 * @param iterations bounded, like everything else in `autodiff_optimize.kt`, so a descent cannot
 *   disappear into an animation loop.
 */
data class SolidFitOptions(
    val band: Double? = null,
    val method: OptimizeMethod = OptimizeMethod.LBFGS,
    val iterations: Int = 200,
    val gradientTolerance: Double = 1e-10,
    val learningRate: Double = 0.05,
    val memory: Int = 8,
) {
    internal fun toOptimizeOptions(): OptimizeOptions = OptimizeOptions(
        method = method,
        iterations = iterations,
        gradientTolerance = gradientTolerance,
        learningRate = learningRate,
        memory = memory,
        // A fit is a library call, not an animation: a progress reporter on it would print from
        // inside every test and every headless docs capture.
        showProgress = false,
    )
}

/**
 * What a fit did — including, deliberately, what it started from.
 *
 * [initialLoss] is not decoration. "The fit improved things" is unfalsifiable without the
 * counterfactual in the same object, measured in the same run, by the same code: a remembered
 * before-number is a number that can go stale while the after-number stays green.
 * [improvementFactor] is the ratio, and it is the thing to assert a floor on.
 */
data class SolidFit(
    val template: SolidTemplate,
    /** Where the descent started. */
    val start: DoubleArray,
    /** Where it finished. */
    val values: DoubleArray,
    /** The loss at [start], measured now. */
    val initialLoss: Double,
    /** The loss at [values], measured now. */
    val loss: Double,
    /** The fitted parameters substituted back in — an ordinary [SolidExpr]. */
    val fitted: SolidExpr,
    /** The whole descent, for a loss curve or a convergence reason. */
    val run: OptimizeRun,
    /**
     * Parameters whose gradient was **exactly zero** at [start] — the ones that could not have moved.
     *
     * See the note on [afield.solidsl.SolidFit] about what causes it. This is reported rather than
     * thrown on, because a legitimately-dead parameter is a fine thing to have in a vector (a fit
     * over six holes where one is genuinely irrelevant), but an *unexpectedly* dead one is the
     * failure that looks like a success.
     */
    val deadParameters: List<String>,
) {
    /** `initialLoss / loss` — how many times better the answer is than the seed. */
    val improvementFactor: Double get() = if (loss <= 0.0) Double.POSITIVE_INFINITY else initialLoss / loss

    /** The residual as an RMS distance, which is comparable with [SolidSamples.rms]. */
    val rmsError: Double get() = sqrt(max(0.0, loss))

    /** The whole story, for a `println` in a box. */
    fun report(): String = buildString {
        appendLine("fit ${template.names.size} parameter(s) in ${run.iterations} iterations (${run.reason})")
        template.names.forEachIndexed { i, name ->
            appendLine("  %-14s %12.6f  ->  %12.6f".format(name, start[i], values[i]))
        }
        appendLine("  loss           ${"%.6e".format(initialLoss)}  ->  ${"%.6e".format(loss)}")
        appendLine("  rms distance   ${"%.6e".format(sqrt(max(0.0, initialLoss)))}  ->  ${"%.6e".format(rmsError)}")
        appendLine("  improvement    ${"%.4g".format(improvementFactor)}x")
        if (deadParameters.isNotEmpty()) {
            appendLine("  DEAD (zero gradient at the start): ${deadParameters.joinToString(", ")}")
        }
        append("  fitted         $fitted")
    }

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * Fit this template's named parameters so its field matches [target], by gradient descent on the
 * mean squared distance residual.
 *
 * The loss is `mean over samples of (predicted − target)²`, and every gradient in it is exact: one
 * [ADTape] per evaluation carries all [SolidSamples.size] residuals and all [SolidTemplate.size]
 * parameters, and one backward sweep reads every derivative off it. That is the whole reason to have
 * built lowering (c) — the alternative, central-differencing the loss, costs `2n` extra full sweeps
 * per step and needs an `fdStep` tuned per problem, which is the complaint `autodiff.kt` opens with.
 *
 * ```kotlin
 * val fit = template.fitParameters(target, start = doubleArrayOf(0.35, 2.6))
 * fit.improvementFactor          // assert a floor on this
 * fit.fitted.crossCheck()        // and then check the answer with the other two lowerings
 * ```
 *
 * @param start where to begin; the template's own [SolidTemplate.seed] by default. A recovery test
 *   passes something measurably far away and asserts how far back it came.
 */
fun SolidTemplate.fitParameters(
    target: SolidSamples,
    start: DoubleArray = seed,
    options: SolidFitOptions = SolidFitOptions(),
): SolidFit {
    require(start.size == size) {
        "this template has $size parameter(s) (${names.joinToString(", ")}), got ${start.size} start values"
    }
    val band = options.band
    require(band == null || band > 0.0) { "band must be positive when given, got $band" }

    val valueAndGradient: (DoubleArray) -> ADGradient = { p ->
        valueAndGrad(p) { parameters ->
            val tape = parameters[0].tape
            val field = adFieldWith(parameters)
            var loss = tape.constant(0.0)
            for (i in 0 until target.size) {
                val raw = field.at(
                    tape.constant(target.xs[i]),
                    tape.constant(target.ys[i]),
                    tape.constant(target.zs[i]),
                )
                val pred = if (band == null) raw else raw.clamp(-band, band)
                val want = if (band == null) target.distances[i] else target.distances[i].coerceIn(-band, band)
                val e = pred - want
                loss = loss + e * e
            }
            loss / target.size.toDouble()
        }
    }

    val at0 = valueAndGradient(start)
    val dead = names.indices.filter { at0.gradient[it] == 0.0 }.map { names[it] }

    val run = minimizeWithGradient(start, options.toOptimizeOptions(), valueAndGradient)
    val end = valueAndGradient(run.x)

    return SolidFit(
        template = this,
        start = start.copyOf(),
        values = run.x.copyOf(),
        initialLoss = at0.value,
        loss = end.value,
        fitted = withValues(run.x),
        run = run,
        deadParameters = dead,
    )
}
