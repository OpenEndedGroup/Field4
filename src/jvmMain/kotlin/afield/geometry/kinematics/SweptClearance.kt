@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.geometry.kinematics

import afield.geometry.mesh.TriMesh
import afield.geometry.solid.manifold.Solid
import afield.math.linalg.Matrix4dc
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Swept clearance: a part, moving by a [RigidMotion], past obstacles — how close does it come, where, and when?
 *
 * [sweptClearance] samples the motion and, at every step, finds the exact gap between the posed part and the
 * nearest obstacle (double-precision triangle distances over box trees), with the two nearest points. Between steps
 * it uses the motion's own travel bound to say how much lower the gap could have dipped, and refines where that
 * matters, so the result carries a *certified* lower bound on the true minimum, not only the sampled one.
 *
 * ```kotlin
 * val door = Solid.cube(900.0, 40.0, 2000.0)                          // closed, along +x from the hinge
 * val swing = RigidMotion.rotation(vec(0.0, 0.0, 0.0), vec(0.0, 0.0, 1.0), 0.0, 100.0)
 * val wall = Solid.cube(40.0, 1200.0, 2000.0).translated(-80.0, 100.0, 0.0)
 * val c = sweptClearance(door, swing, listOf(wall), maxStep = 1.0, tolerance = 0.5)
 * println(c.minimum)                 // the smallest gap, the angle, the wall, the two points
 * println(c.firstBelow(12.7))        // the first angle closer than 12.7 mm, or null
 * ```
 */

/**
 * One sample of a [SweptClearance]: the motion [value] (degrees or distance) and parameter [t], the [gap] to the
 * nearest obstacle there and the [contact] that realises it (`null` when there are no obstacles).
 */
class ClearanceStep(
    /** The motion's coordinate here: degrees for a rotation, distance for a slide. */
    val value: Double,
    /** Normalised parameter along the motion, 0 at its start and 1 at its end. */
    val t: Double,
    /** The nearest approach here, or `null` when no obstacle was given. */
    val contact: Contact?,
) {
    /** The gap to the nearest obstacle, `+∞` when there is none. */
    val gap: Double get() = contact?.gap ?: Double.POSITIVE_INFINITY

    /** The nearest obstacle's name, or `null`. */
    val obstacle: String? get() = contact?.obstacle

    /** Value, gap and obstacle, for `println`. */
    override fun toString(): String = "ClearanceStep(value=%.4f, gap=%.6g%s)".format(value, gap, contact?.let { " to ${it.obstacle}" } ?: "")
}

/**
 * The result of [sweptClearance]: every evaluated step in motion order, the [minimum], and how sure it is.
 *
 * [lowerBound] is certified: no pose anywhere along the motion (not just at the samples) comes closer than it,
 * because between two samples the gap cannot fall faster than the part's points travel. When [sweptClearance] was
 * given a tolerance, `minimum.gap - lowerBound` is within it.
 */
class SweptClearance(
    /** The motion that was checked. */
    val motion: RigidMotion,
    /** Every evaluated step, ordered along the motion: the regular samples plus any refinement. */
    val steps: List<ClearanceStep>,
    /** A certified lower bound on the gap over the whole motion. */
    val lowerBound: Double,
    /** The threshold [firstBelow] was refined for, or `null`. */
    val threshold: Double?,
) {
    /** The step with the smallest gap (the first such, if several tie). */
    val minimum: ClearanceStep = steps.minByOrNull { it.gap } ?: error("no steps")

    /** The gaps, step by step: what a viewer tints a timeline with. */
    fun gaps(): DoubleArray = DoubleArray(steps.size) { steps[it].gap }

    /**
     * The first evaluated step whose gap is under [threshold], or `null` if none is.
     *
     * When this result was computed with that threshold and a tolerance, no earlier pose comes closer than
     * `threshold - tolerance`; without them it is only the first *sample* under, at the sampling step's resolution.
     */
    fun firstBelow(threshold: Double): ClearanceStep? = steps.firstOrNull { it.gap < threshold }

    /** Whether every pose, between the samples too, is certainly at least [clearance] clear: [lowerBound] `>=` [clearance]. */
    fun keeps(clearance: Double): Boolean = lowerBound >= clearance

    /** The motion and the per-step values as JSON, for a viewer: `{"motion":{...},"steps":[{"value":..,"gap":..,"obstacle":..}]}`. */
    fun toJson(): String = clearanceJson.encodeToString(
        ClearanceTrackJson.serializer(),
        ClearanceTrackJson(
            motion, lowerBound,
            steps.map { ClearanceStepJson(it.value, it.t, if (it.gap.isFinite()) it.gap else null, it.obstacle) },
        ),
    )

    /** The minimum and the bound, for `println`. */
    override fun toString(): String =
        "SweptClearance(min %.6g at %.4f to ${minimum.obstacle}, certified >= %.6g, ${steps.size} steps)".format(minimum.gap, minimum.value, lowerBound)
}

@Serializable
internal class ClearanceTrackJson(val motion: RigidMotion, val lowerBound: Double, val steps: List<ClearanceStepJson>)

@Serializable
internal class ClearanceStepJson(val value: Double, val t: Double, val gap: Double?, val obstacle: String?)

private val clearanceJson = Json { classDiscriminator = "type"; encodeDefaults = true }

/**
 * Sweep [part] through [motion] past [obstacles] and measure the gap at every step.
 *
 * The motion is sampled every [maxStep] (degrees for a rotation, distance for a slide). Between two samples the gap
 * can fall by at most the distance the part's farthest point travels, which bounds the gap from below everywhere;
 * [SweptClearance.lowerBound] is the least such bound.
 *
 * - With a [tolerance], intervals whose bound could hide a gap more than [tolerance] under the smallest one found are
 *   bisected until it cannot, so `minimum.gap - lowerBound <= tolerance`.
 * - With a [threshold] and a [tolerance], intervals before the first sample under [threshold] are refined too, so
 *   [SweptClearance.firstBelow] finds the first crossing: no earlier pose is closer than `threshold - tolerance`,
 *   and the crossing is located to within an interval over which no point of the part moves more than [tolerance].
 * - Without a tolerance it is plain sampling, and [SweptClearance.lowerBound] says how far off that could be.
 *
 * The part's rest coordinates are its pose at motion value 0 (see [RigidMotion]); obstacles stay where they are.
 * Refinement stops at [maxSteps] evaluated steps, after which the bound is still reported honestly.
 */
fun sweptClearance(
    part: ClearanceBody,
    motion: RigidMotion,
    obstacles: List<ClearanceBody>,
    maxStep: Double = defaultStep(motion),
    tolerance: Double? = null,
    threshold: Double? = null,
    maxSteps: Int = 100_000,
): SweptClearance {
    require(tolerance == null || tolerance > 0.0 && tolerance.isFinite()) { "tolerance must be positive, got $tolerance" }
    val sweep = ClearanceProbe(part, motion, obstacles)
    val values = motion.stepsBy(maxStep).map { it.value }.toMutableList()
    val samples = values.map { sweep.at(it) }.toMutableList()
    if (tolerance != null) {
        // Worklist of interval indices; each split inserts a midpoint sample.
        var changed = true
        while (changed && samples.size < maxSteps) {
            changed = false
            val minGap = samples.minOf { it.gap }
            val firstUnder = if (threshold == null) -1 else samples.indexOfFirst { it.gap < threshold }
            var i = 0
            while (i < samples.size - 1 && samples.size < maxSteps) {
                val a = samples[i]; val b = samples[i + 1]
                val travel = sweep.travel(a.value, b.value)
                val lb = intervalBound(a.gap, b.gap, travel)
                val forMin = minGap > 0.0 && lb < minGap - tolerance
                val beforeCrossing = threshold != null && (firstUnder < 0 || i < firstUnder) &&
                    (lb < threshold - tolerance || i + 1 == firstUnder && travel > tolerance)
                if ((forMin || beforeCrossing) && travel > 1e-12 * max(1.0, abs(a.value))) {
                    val mid = (a.value + b.value) / 2
                    samples.add(i + 1, sweep.at(mid))
                    changed = true
                    i += 2
                } else i++
            }
        }
    }
    var bound = samples.minOf { it.gap }
    for (i in 0 until samples.size - 1) {
        bound = min(bound, intervalBound(samples[i].gap, samples[i + 1].gap, sweep.travel(samples[i].value, samples[i + 1].value)))
    }
    return SweptClearance(motion, samples, max(0.0, bound), threshold)
}

/** [sweptClearance] for a Manifold [Solid] part and obstacles. */
fun sweptClearance(
    part: Solid,
    motion: RigidMotion,
    obstacles: List<Solid>,
    maxStep: Double = defaultStep(motion),
    tolerance: Double? = null,
    threshold: Double? = null,
): SweptClearance = sweptClearance(
    ClearanceBody.of(part, "part"), motion,
    obstacles.mapIndexed { i, o -> ClearanceBody.of(o, "obstacle $i") }, maxStep, tolerance, threshold,
)

/** [sweptClearance] for [TriMesh] part and obstacles. */
@JvmName("sweptClearanceMeshes")
fun sweptClearance(
    part: TriMesh,
    motion: RigidMotion,
    obstacles: List<TriMesh>,
    maxStep: Double = defaultStep(motion),
    tolerance: Double? = null,
    threshold: Double? = null,
): SweptClearance = sweptClearance(
    ClearanceBody.of(part, "part"), motion,
    obstacles.mapIndexed { i, o -> ClearanceBody.of(o, "obstacle $i") }, maxStep, tolerance, threshold,
)

/** One degree for a rotation; a hundredth of the travel for a slide. */
fun defaultStep(motion: RigidMotion): Double = when (motion) {
    is Rotation -> 1.0
    is Slide -> max(abs(motion.to - motion.from) / 100, 1e-9)
}

/** The least the gap can be between two samples [ga] and [gb] when points move at most [travel] between them. */
internal fun intervalBound(ga: Double, gb: Double, travel: Double): Double {
    if (ga.isInfinite() || gb.isInfinite()) return min(ga, gb)
    return min(min(ga, gb), (ga + gb - travel) / 2)
}

/**
 * The gap at one value of a motion at a time: what [sweptClearance] samples with, for a caller that picks its own
 * values (a viewer following a drag, or a comparison at another engine's angles).
 *
 * Obstacles are prepared once, at construction; each [at] poses the part and searches nearest boxes first. Not
 * thread-safe: make one per thread.
 */
class ClearanceProbe(
    /** The moving part. */
    val part: ClearanceBody,
    /** How it moves. */
    val motion: RigidMotion,
    obstacles: List<ClearanceBody>,
) {
    private val posed = PosedBody(part)
    private val fixed = obstacles.map { PosedBody(it).also { p -> p.place(null) } }
    private val vertices = part.positions

    /** An upper bound on how far any point of the part moves between the values [a] and [b]. */
    fun travel(a: Double, b: Double): Double = motion.maxTravel(vertices, a, b)

    /** The nearest approach at the motion value [value] (degrees or distance). */
    fun at(value: Double): ClearanceStep = ClearanceStep(value, motion.parameterOf(value), nearest(motion.poseAt(value)))

    /** The nearest obstacle to the part at [pose], or `null` when there are no obstacles. */
    fun nearest(pose: Matrix4dc): Contact? {
        posed.place(pose)
        if (fixed.isEmpty()) return null
        val order = fixed.indices.map { it to boxDistance2(posed.boxes, 0, fixed[it].boxes, 0) }.sortedBy { it.second }
        var best: Contact? = null
        for ((i, lb2) in order) {
            val b = best
            if (b != null && lb2 >= b.gap * b.gap) break
            val c = bodyDistance(posed, fixed[i], b?.gap ?: Double.POSITIVE_INFINITY, i) ?: continue
            if (b == null || c.gap < b.gap) best = c
            if (c.gap == 0.0) break
        }
        return best
    }
}
