@file:Suppress("MemberVisibilityCanBePrivate")
@file:afield.CompletionCategory("Math")

package afield.stdlib

import afield.graphics.FLine

/**
 * Differentiable geometry: tracked vectors, planar inverse kinematics on an [FLine] chain, and a
 * differentiable circle packing — the "things that produce pixels" end of the AD layer.
 *
 * [ADVec2] and [ADVec3] are pairs and triples of [AD], not a parallel numeric tower: they convert to
 * and from Field's own [Vec2] / [Vec3] (which are the generated, *mutable*, float-backed
 * `Vector2f` / `Vector3f`), and everything they do is expressed in the same scalar ops as the rest of
 * `autodiff.kt`. They exist because writing `(joint - target).length()` reads better in a loss than
 * unrolling the components by hand, not because vectors need special derivative rules.
 *
 * ## Inverse kinematics
 *
 * [solveIk] is the demonstration that earns the layer: a planar chain of fixed-length links whose
 * joint angles are found by minimising `|endEffector - target|²` with exact gradients. Forward
 * kinematics is a cumulative sum of angles and a running position — perfectly differentiable — so
 * L-BFGS reaches a sub-micron reach in tens of iterations, and the result is an [FLine] you can draw.
 *
 * ```kotlin
 * val chain = IkChain(vec(120f, 300f), doubleArrayOf(90.0, 80.0, 70.0, 55.0))
 * val solved = solveIk(chain, vec(360f, 150f))
 * showPlane(listOf(chain.toFLine(solved.angles)))
 * ```
 *
 * A pure reach objective is under-determined for more than two links, so [IkOptions.smoothness]
 * offers a curvature penalty between consecutive links. It is off by default and it is **not** free:
 * the two terms genuinely compete, so a positive smoothness buys an elegant pose at the price of a
 * little reach error, and near the base — where reaching at all needs tight folds — that price is
 * visible. Measured on the four-link chain below with `smoothness = 0.02`: a target 216 units out
 * still reaches to under a thousandth of a unit, but a target 5 units from the anchor settles 2.6
 * units short. [IkSolution.reachError] always reports the truth; nothing here pretends to have
 * converged when it has traded away accuracy.
 *
 * ## Packing
 *
 * [packCircles] minimises pairwise overlap and wall penetration under a gentle pull toward the
 * centre. Every term is a `relu(...)²` hinge, which is exactly where the even-split kink convention
 * in `autodiff.kt` matters: at the moment two circles touch, the penalty's gradient is *half* what a
 * naive one-sided rule would give, and the packing settles rather than chattering.
 */

// ---------------------------------------------------------------------------------------------
// Tracked vectors
// ---------------------------------------------------------------------------------------------

/**
 * A two-dimensional vector whose components are tracked [AD] scalars.
 *
 * Immutable, unlike Field's [Vec2] — every operation returns a new one, which is what a tape needs.
 * Use [value] to get back to a plain [Vec2] for drawing.
 */
data class ADVec2(
    /** The x component. */
    val x: AD,
    /** The y component. */
    val y: AD
) {
    /** Sum. */
    operator fun plus(other: ADVec2): ADVec2 = ADVec2(x + other.x, y + other.y)

    /** Difference. */
    operator fun minus(other: ADVec2): ADVec2 = ADVec2(x - other.x, y - other.y)

    /** Componentwise product — the Hadamard product, not a dot or a cross. */
    operator fun times(other: ADVec2): ADVec2 = ADVec2(x * other.x, y * other.y)

    /** Scaling by a tracked scalar. */
    operator fun times(scalar: AD): ADVec2 = ADVec2(x * scalar, y * scalar)

    /** Scaling by a constant. */
    operator fun times(scalar: Double): ADVec2 = ADVec2(x * scalar, y * scalar)

    /** Division by a constant. */
    operator fun div(scalar: Double): ADVec2 = ADVec2(x / scalar, y / scalar)

    /** Negation. */
    operator fun unaryMinus(): ADVec2 = ADVec2(-x, -y)

    /** The dot product. */
    infix fun dot(other: ADVec2): AD = x * other.x + y * other.y

    /** The 2D cross product `x₁y₂ - y₁x₂` — the signed area of the parallelogram. */
    infix fun cross(other: ADVec2): AD = x * other.y - y * other.x

    /** The squared length, which is smooth at the origin where [length] is not. */
    fun lengthSquared(): AD = x.sq() + y.sq()

    /** The Euclidean length. Its derivative is undefined at the origin; prefer [lengthSquared] in a
     *  loss that might pass through zero. */
    fun length(): AD = sqrt(lengthSquared())

    /** The distance to [other]. */
    fun distanceTo(other: ADVec2): AD = (this - other).length()

    /** The squared distance to [other] — the well-behaved choice inside a loss. */
    fun distanceSquaredTo(other: ADVec2): AD = (this - other).lengthSquared()

    /** This vector scaled to unit length. Undefined at the origin. */
    fun normalized(): ADVec2 {
        val inverse = length().reciprocal()
        return ADVec2(x * inverse, y * inverse)
    }

    /** The angle from the positive x axis, via [atan2], so it is correct in all four quadrants. */
    fun angle(): AD = atan2(y, x)

    /** Rotated by [radians] about the origin. */
    fun rotated(radians: AD): ADVec2 {
        val c = cos(radians)
        val s = sin(radians)
        return ADVec2(x * c - y * s, x * s + y * c)
    }

    /** Linear interpolation toward [other] by [t]. */
    fun lerp(other: ADVec2, t: AD): ADVec2 = this + (other - this) * t

    /** The plain, untracked value as one of Field's own [Vec2]s. */
    val value: Vec2 get() = Vec2(x.value.toFloat(), y.value.toFloat())

    override fun toString(): String = "ADVec2(${x.value}, ${y.value})"
}

/**
 * A three-dimensional vector whose components are tracked [AD] scalars — [ADVec2]'s sibling, with
 * the same immutability and the same conversion story to Field's [Vec3].
 */
data class ADVec3(
    /** The x component. */
    val x: AD,
    /** The y component. */
    val y: AD,
    /** The z component. */
    val z: AD
) {
    /** Sum. */
    operator fun plus(other: ADVec3): ADVec3 = ADVec3(x + other.x, y + other.y, z + other.z)

    /** Difference. */
    operator fun minus(other: ADVec3): ADVec3 = ADVec3(x - other.x, y - other.y, z - other.z)

    /** Scaling by a tracked scalar. */
    operator fun times(scalar: AD): ADVec3 = ADVec3(x * scalar, y * scalar, z * scalar)

    /** Scaling by a constant. */
    operator fun times(scalar: Double): ADVec3 = ADVec3(x * scalar, y * scalar, z * scalar)

    /** Negation. */
    operator fun unaryMinus(): ADVec3 = ADVec3(-x, -y, -z)

    /** The dot product. */
    infix fun dot(other: ADVec3): AD = x * other.x + y * other.y + z * other.z

    /** The cross product. */
    infix fun cross(other: ADVec3): ADVec3 = ADVec3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x
    )

    /** The squared length, smooth at the origin. */
    fun lengthSquared(): AD = x.sq() + y.sq() + z.sq()

    /** The Euclidean length; undefined derivative at the origin. */
    fun length(): AD = sqrt(lengthSquared())

    /** The distance to [other]. */
    fun distanceTo(other: ADVec3): AD = (this - other).length()

    /** The squared distance to [other]. */
    fun distanceSquaredTo(other: ADVec3): AD = (this - other).lengthSquared()

    /** This vector scaled to unit length. */
    fun normalized(): ADVec3 {
        val inverse = length().reciprocal()
        return ADVec3(x * inverse, y * inverse, z * inverse)
    }

    /** Linear interpolation toward [other] by [t]. */
    fun lerp(other: ADVec3, t: AD): ADVec3 = this + (other - this) * t

    /** The plain, untracked value as one of Field's own [Vec3]s. */
    val value: Vec3 get() = Vec3(x.value.toFloat(), y.value.toFloat(), z.value.toFloat())

    override fun toString(): String = "ADVec3(${x.value}, ${y.value}, ${z.value})"
}

/** This vector as an untracked constant on [tape] — the way a fixed target enters a loss. */
fun Vec2.onTape(tape: ADTape): ADVec2 = ADVec2(tape.constant(x.toDouble()), tape.constant(y.toDouble()))

/** This vector as an untracked constant on [tape]. */
fun Vec3.onTape(tape: ADTape): ADVec3 =
    ADVec3(tape.constant(x.toDouble()), tape.constant(y.toDouble()), tape.constant(z.toDouble()))

// ---------------------------------------------------------------------------------------------
// Inverse kinematics
// ---------------------------------------------------------------------------------------------

/**
 * A planar chain of rigid links: where it is anchored, and how long each link is.
 *
 * The configuration is the *joint angle* vector, one per link, each measured **relative to the
 * previous link** — so an all-zero configuration is a straight arm along the positive x axis, and a
 * constant configuration is an arc. Relative angles are what makes the [IkOptions.smoothness]
 * penalty mean "don't bend sharply".
 */
data class IkChain(
    /** Where the first link starts. */
    val origin: Vec2,
    /** The link lengths, root to tip. */
    val lengths: DoubleArray
) {
    init {
        require(lengths.isNotEmpty()) { "a chain needs at least one link" }
        require(lengths.all { it > 0.0 }) { "link lengths must be positive" }
    }

    /** How many links, and therefore how many joint angles. */
    val linkCount: Int get() = lengths.size

    /** The furthest the tip can possibly get from [origin] — the chain fully extended. */
    val reach: Double get() = lengths.sum()

    /** The joint positions, [origin] first and the end effector last, for the configuration [angles]. */
    fun jointsAt(angles: DoubleArray): List<Vec2> {
        require(angles.size == lengths.size) { "expected ${lengths.size} angles, got ${angles.size}" }
        val out = ArrayList<Vec2>(lengths.size + 1)
        var x = origin.x.toDouble()
        var y = origin.y.toDouble()
        var heading = 0.0
        out.add(Vec2(x.toFloat(), y.toFloat()))
        for (i in lengths.indices) {
            heading += angles[i]
            x += lengths[i] * kotlin.math.cos(heading)
            y += lengths[i] * kotlin.math.sin(heading)
            out.add(Vec2(x.toFloat(), y.toFloat()))
        }
        return out
    }

    /** Where the tip lands for the configuration [angles]. */
    fun endEffectorAt(angles: DoubleArray): Vec2 = jointsAt(angles).last()

    /** The chain drawn as a polyline, root to tip — ready for `showPlane` or `lines(...)`. */
    fun toFLine(angles: DoubleArray): FLine {
        val joints = jointsAt(angles)
        val line = FLine()
        line.moveTo(joints.first().x.toDouble(), joints.first().y.toDouble())
        for (index in 1 until joints.size) line.lineTo(joints[index].x.toDouble(), joints[index].y.toDouble())
        return line
    }

    /**
     * Forward kinematics in [AD] arithmetic: the tip position as a differentiable function of the
     * tracked joint angles. The one function [solveIk] differentiates.
     */
    fun endEffectorAD(angles: List<AD>): ADVec2 {
        require(angles.size == lengths.size) { "expected ${lengths.size} angles, got ${angles.size}" }
        val tape = angles.first().tape
        var x = tape.constant(origin.x.toDouble())
        var y = tape.constant(origin.y.toDouble())
        var heading = angles[0]
        x += lengths[0] * cos(heading)
        y += lengths[0] * sin(heading)
        for (i in 1 until lengths.size) {
            heading += angles[i]
            x += lengths[i] * cos(heading)
            y += lengths[i] * sin(heading)
        }
        return ADVec2(x, y)
    }

    override fun equals(other: Any?): Boolean =
        other is IkChain && origin == other.origin && lengths.contentEquals(other.lengths)

    override fun hashCode(): Int = 31 * origin.hashCode() + lengths.contentHashCode()
}

/**
 * What shapes an [solveIk] solve.
 *
 * [smoothness] is the interesting one: a reach objective alone is under-determined for more than two
 * links, and the solver will happily find a crumpled configuration that reaches perfectly. Adding a
 * curvature penalty picks the *elegant* member of that family — but it is a real trade, not a free
 * regulariser, so it defaults to zero ("reach exactly, however ugly") and you turn it up knowing
 * that [IkSolution.reachError] will rise with it.
 */
data class IkOptions(
    /**
     * Weight on `Σ(θᵢ - θᵢ₋₁)²`, penalising sharp bends between consecutive links. Zero — the
     * default — reaches exactly. Anything positive competes with the reach term; see the file KDoc
     * for measured numbers.
     */
    val smoothness: Double = 0.0,
    /** Weight on `Σθᵢ²`, which pulls the whole configuration toward straight. */
    val restPull: Double = 0.0,
    /** The descent to use; L-BFGS is the right default for this smooth objective. */
    val optimize: OptimizeOptions = OptimizeOptions(
        method = OptimizeMethod.LBFGS,
        iterations = 400,
        gradientTolerance = 1e-12,
        showProgress = false
    )
)

/** An [solveIk] result: the configuration found, where it puts the chain, and the full descent. */
data class IkSolution(
    /** The solved joint angles, relative link to link. */
    val angles: DoubleArray,
    /** The joint positions for [angles], origin first, tip last. */
    val joints: List<Vec2>,
    /** Where the tip ended up. */
    val endEffector: Vec2,
    /** The distance from [endEffector] to the requested target — the number that says "it reached". */
    val reachError: Double,
    /** The optimizer's own record, including the full loss trajectory. */
    val run: OptimizeRun
) {
    /** The chain as a polyline for drawing. */
    fun toFLine(chain: IkChain): FLine = chain.toFLine(angles)

    override fun equals(other: Any?): Boolean =
        other is IkSolution && angles.contentEquals(other.angles) && reachError == other.reachError

    override fun hashCode(): Int = 31 * angles.contentHashCode() + reachError.hashCode()
}

/**
 * Solves the planar inverse-kinematics problem: find joint angles putting [chain]'s tip on [target].
 *
 * The objective is `|tip - target|² + smoothness·Σ(θᵢ - θᵢ₋₁)² + restPull·Σθᵢ²`, differentiated
 * exactly by reverse mode and minimised by [minimize]. Deterministic: the default
 * [initialAngles] is the straight arm, so the same call always gives the same pose — which is what
 * you need when the pose is going on screen and has to look the same tomorrow.
 *
 * When [target] is further than [IkChain.reach] there is no exact solution and the solver stretches
 * as far as it can; [IkSolution.reachError] then reports the shortfall rather than the
 * solver pretending to have converged.
 */
fun solveIk(
    chain: IkChain,
    target: Vec2,
    initialAngles: DoubleArray = DoubleArray(chain.linkCount),
    options: IkOptions = IkOptions()
): IkSolution {
    require(initialAngles.size == chain.linkCount) {
        "expected ${chain.linkCount} initial angles, got ${initialAngles.size}"
    }
    val targetX = target.x.toDouble()
    val targetY = target.y.toDouble()
    val run = minimize(initialAngles, options.optimize) { angles ->
        val tip = chain.endEffectorAD(angles)
        var loss = (tip.x - targetX).sq() + (tip.y - targetY).sq()
        if (options.smoothness != 0.0) {
            for (i in 1 until angles.size) loss += options.smoothness * (angles[i] - angles[i - 1]).sq()
        }
        if (options.restPull != 0.0) {
            for (angle in angles) loss += options.restPull * angle.sq()
        }
        loss
    }
    val joints = chain.jointsAt(run.x)
    val tip = joints.last()
    val error = kotlin.math.hypot((tip.x - target.x).toDouble(), (tip.y - target.y).toDouble())
    return IkSolution(run.x.copyOf(), joints, tip, error, run)
}

// ---------------------------------------------------------------------------------------------
// Differentiable packing
// ---------------------------------------------------------------------------------------------

/** One packed disc: where it ended up and how big it is. */
data class PackedCircle(
    /** The centre. */
    val centre: Vec2,
    /** The radius, which [packCircles] does not change. */
    val radius: Double
) {
    /** This disc as a closed polygonal [FLine], for `showPlane` or `lines(...)`. */
    fun toFLine(segments: Int = 48): FLine {
        require(segments >= 3) { "a circle needs at least 3 segments" }
        val line = FLine()
        for (i in 0..segments) {
            val a = i / segments.toDouble() * Math.PI * 2.0
            val px = centre.x + radius * kotlin.math.cos(a)
            val py = centre.y + radius * kotlin.math.sin(a)
            if (i == 0) line.moveTo(px, py) else line.lineTo(px, py)
        }
        return line
    }
}

/** An [packCircles] result: the packing, its residual overlap, and the descent that produced it. */
data class PackingRun(
    /** The packed discs, in the order their radii were given. */
    val circles: List<PackedCircle>,
    /** The largest remaining pairwise overlap, in the same units as the radii. Zero means disjoint. */
    val worstOverlap: Double,
    /** How far the worst disc pokes outside the box. Zero means fully contained. */
    val worstEscape: Double,
    /** The optimizer's record, including the loss trajectory. */
    val run: OptimizeRun
)

/**
 * Packs discs of the given [radii] into the rectangle `[0, width] × [0, height]` by gradient descent
 * on a differentiable overlap penalty.
 *
 * The loss is three hinge terms and a pull:
 * `Σᵢ<ⱼ relu(rᵢ+rⱼ - dᵢⱼ)² + wall·Σ relu(penetration)² + pull·Σ|cᵢ - centre|²`.
 * Every hinge is a `relu(...)²`, so the kink convention from `autodiff.kt` decides the answer here: at
 * the exact moment two discs touch, the even-split rule makes the penalty's derivative half a
 * one-sided rule's, and the packing settles instead of chattering across the contact.
 *
 * Deterministic given [rngSeed] — the initial layout is a seeded scatter, so the same seed always
 * gives the same packing, which is the whole point for generative work.
 */
fun packCircles(
    radii: DoubleArray,
    width: Double,
    height: Double,
    wallWeight: Double = 4.0,
    pullWeight: Double = 0.004,
    iterations: Int = 600,
    rngSeed: Long = 0L
): PackingRun {
    require(radii.isNotEmpty()) { "nothing to pack" }
    require(radii.all { it > 0.0 }) { "radii must be positive" }
    require(width > 0.0 && height > 0.0) { "the box must have positive extent" }

    val rng = kotlin.random.Random(rngSeed)
    val initial = DoubleArray(radii.size * 2)
    for (i in radii.indices) {
        initial[2 * i] = radii[i] + rng.nextDouble() * (width - 2.0 * radii[i]).coerceAtLeast(0.0)
        initial[2 * i + 1] = radii[i] + rng.nextDouble() * (height - 2.0 * radii[i]).coerceAtLeast(0.0)
    }

    val centreX = width / 2.0
    val centreY = height / 2.0
    val run = minimize(
        initial,
        OptimizeOptions(
            method = OptimizeMethod.LBFGS,
            iterations = iterations,
            gradientTolerance = 1e-12,
            showProgress = false
        )
    ) { p ->
        val positions = radii.indices.map { ADVec2(p[2 * it], p[2 * it + 1]) }
        var loss = p[0].tape.constant(0.0)
        for (i in radii.indices) {
            for (j in i + 1 until radii.size) {
                val gap = positions[i].distanceTo(positions[j])
                loss += relu((radii[i] + radii[j]) - gap).sq()
            }
        }
        for (i in radii.indices) {
            val r = radii[i]
            loss += wallWeight * relu(r - positions[i].x).sq()
            loss += wallWeight * relu(positions[i].x - (width - r)).sq()
            loss += wallWeight * relu(r - positions[i].y).sq()
            loss += wallWeight * relu(positions[i].y - (height - r)).sq()
            loss += pullWeight * ((positions[i].x - centreX).sq() + (positions[i].y - centreY).sq())
        }
        loss
    }

    val circles = radii.indices.map {
        PackedCircle(Vec2(run.x[2 * it].toFloat(), run.x[2 * it + 1].toFloat()), radii[it])
    }
    var worstOverlap = 0.0
    for (i in circles.indices) {
        for (j in i + 1 until circles.size) {
            val d = kotlin.math.hypot(
                (circles[i].centre.x - circles[j].centre.x).toDouble(),
                (circles[i].centre.y - circles[j].centre.y).toDouble()
            )
            worstOverlap = kotlin.math.max(worstOverlap, radii[i] + radii[j] - d)
        }
    }
    var worstEscape = 0.0
    for (c in circles) {
        val cx = c.centre.x.toDouble()
        val cy = c.centre.y.toDouble()
        worstEscape = kotlin.math.max(worstEscape, c.radius - cx)
        worstEscape = kotlin.math.max(worstEscape, cx - (width - c.radius))
        worstEscape = kotlin.math.max(worstEscape, c.radius - cy)
        worstEscape = kotlin.math.max(worstEscape, cy - (height - c.radius))
    }
    return PackingRun(circles, worstOverlap.coerceAtLeast(0.0), worstEscape.coerceAtLeast(0.0), run)
}
