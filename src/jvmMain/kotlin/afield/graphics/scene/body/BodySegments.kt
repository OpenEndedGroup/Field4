package afield.graphics.scene.body

import afield.math.linalg.Vector3f
import afield.graphics.vulkan.targets.OpenXRHmdTarget
import afield.graphics.vulkan.targets.OpenXRHmdTarget.BodyJoint
import afield.graphics.vulkan.targets.OpenXRHmdTarget.BodyTrackingState
import afield.graphics.vulkan.targets.OpenXRHmdTarget.ControllerState
import afield.graphics.vulkan.targets.OpenXRHmdTarget.Hand
import afield.graphics.vulkan.targets.OpenXRHmdTarget.HandJoint
import afield.graphics.vulkan.targets.OpenXRHmdTarget.HandTrackingState
import afield.graphics.vulkan.targets.OpenXRHmdTarget.JointPose
import kotlin.math.max

/** One copied world-space centreline with a preferred capsule radius, in metres. */
data class BodySegment(
    val id: Int,
    val name: String,
    val start: Vector3f,
    val end: Vector3f,
    val radius: Float,
) {
    /** Capsule signed distance and outward direction; a collapsed segment is a sphere. */
    fun sample(position: Vector3f, gap: Float = 0f): BodySegmentHit {
        require(position.isFinite() && gap.isFinite())
        val axis = Vector3f(end).sub(start)
        val lengthSquared = axis.lengthSquared()
        val t = if (lengthSquared > 1e-12f) Vector3f(position).sub(start).dot(axis) / lengthSquared else 0f
        val centre = Vector3f(start).fma(t.coerceIn(0f, 1f), axis)
        val normal = Vector3f(position).sub(centre)
        val distance = normal.length()
        if (distance > 1e-6f) normal.div(distance)
        else if (lengthSquared > 1e-12f) {
            axis.normalize()
            val basis = if (kotlin.math.abs(axis.x) < .9f) Vector3f(1f, 0f, 0f) else Vector3f(0f, 1f, 0f)
            axis.cross(basis, normal).normalize()
        } else normal.set(1f, 0f, 0f)
        val target = max(radius + gap, 0f)
        return BodySegmentHit(id, Vector3f(centre).fma(target, normal), normal, distance - target)
    }
}

/** Closest tested capsule: negative [distance] is inside its preferred standoff surface. */
data class BodySegmentHit(val segment: Int, val point: Vector3f, val normal: Vector3f, val distance: Float)

/**
 * A small body/hand snapshot shared by CPU physics, GPU uploads and diagnostic drawing.
 *
 * IDs are fixed even when tracking disappears. [active] omits invalid observations rather than
 * holding old poses or inventing missing joints. Distances are world metres, with rounded capsule
 * ends so force direction changes continuously around joints. These are interaction envelopes,
 * rather than the flat-ended surface queries of [BodyGeometry].
 */
class BodySegments private constructor(private val slots: List<BodySegment?>) {
    val active: List<BodySegment> = slots.filterNotNull()
    operator fun get(id: Int): BodySegment? = slots.getOrNull(id)

    /** Full small-set search for CPU scenes. Overlaps choose the most penetrating capsule. */
    fun closest(position: Vector3f, gap: Float = 0f): BodySegmentHit? =
        active.map { it.sample(position, gap) }.minByOrNull { it.distance }

    /** Two-candidate reference for the GPU search; both distances use this frame's poses. */
    fun refine(position: Vector3f, previous: Int, candidate: Int, gap: Float = 0f): BodySegmentHit? {
        val old = get(previous)?.sample(position, gap)
        val next = if (candidate == previous) null else get(candidate)?.sample(position, gap)
        return when {
            old == null -> next
            next == null || old.distance <= next.distance -> old
            else -> next
        }
    }

    companion object {
        const val CAPACITY = 61
        private val handChains: List<List<Int>> = [
            [1, 2, 3, 4, 5], [1, 6, 7, 8, 9, 10], [1, 11, 12, 13, 14, 15],
            [1, 16, 17, 18, 19, 20], [1, 21, 22, 23, 24, 25], [0, 1],
        ]

        /**
         * Copy valid observations, allowing independent per-segment radii via [radiusFor].
         * Hand IDs retain their slots while inactive. Forearms fall back to valid controller grips.
         * A default radius is supplied for torso, limbs, head and fingers; return a nonnegative
         * radius in metres, or null to exclude that segment entirely.
         */
        fun from(
            body: BodyTrackingState?,
            head: JointPose? = null,
            left: HandTrackingState? = null,
            right: HandTrackingState? = null,
            leftController: ControllerState? = null,
            rightController: ControllerState? = null,
            includeHands: Boolean = true,
            includeHead: Boolean = true,
            radiusFor: (String, Float) -> Float? = { _, radius -> radius },
        ): BodySegments {
            val slots = MutableList<BodySegment?>(CAPACITY) { null }
            fun valid(p: JointPose?) = p?.takeIf { it.positionValid && it.position.isFinite() }?.position
            fun insert(id: Int, name: String, a: Vector3f?, b: Vector3f?, defaultRadius: Float) {
                if (a == null || b == null) return
                val radius = radiusFor(name, defaultRadius) ?: return
                require(radius.isFinite() && radius >= 0f) { "Invalid body radius for $name: $radius" }
                slots[id] = BodySegment(id, name, Vector3f(a), Vector3f(b), radius)
            }
            for ((id, pair) in BodyGeometry.CONNECTIONS.withIndex()) {
                val (a, b) = pair
                insert(id, "${a.name}/${b.name}", valid(body?.get(a)), valid(body?.get(b)), if (id == 0) .18f else .07f)
            }
            if (includeHead) {
                insert(7, "CHEST/HEAD", valid(body?.get(BodyJoint.CHEST)), valid(head), .08f)
                insert(8, "HEAD", valid(head), valid(head), .10f)
            }
            fun addHand(side: Hand, hand: HandTrackingState?, grip: ControllerState?, arm: BodyJoint, base: Int) {
                val prefix = side.name
                val wrist = if (hand?.isActive == true) valid(hand[HandJoint.WRIST]) else null
                val endpoint = wrist ?: grip?.takeIf { it.poseValid && it.position.isFinite() }?.position
                insert(if (side == Hand.LEFT) 9 else 10, "$prefix/FOREARM", valid(body?.get(arm)), endpoint, .055f)
                var offset = 0
                for (chain in handChains) for ((a, b) in chain.zipWithNext()) {
                    if (includeHands && hand?.isActive == true)
                        insert(base + offset, "$prefix/${HandJoint.entries[a].name}/${HandJoint.entries[b].name}", valid(hand.joints[a]), valid(hand.joints[b]), .012f)
                    offset++
                }
            }
            addHand(Hand.LEFT, left, leftController, BodyJoint.LEFT_ARM_LOWER, 11)
            addHand(Hand.RIGHT, right, rightController, BodyJoint.RIGHT_ARM_LOWER, 36)
            return BodySegments(slots)
        }
    }
}

/** Copy this predicted frame's real tracking; no inspection-copy translation is applied. */
fun OpenXRHmdTarget.bodySegments(
    includeHands: Boolean = true,
    includeHead: Boolean = true,
    radiusFor: (String, Float) -> Float? = { _, radius -> radius },
): BodySegments {
    val head = JointPose()
    head.positionValid = headPose(head.position, head.orientation)
    return BodySegments.from(bodyState(), head, handState(Hand.LEFT), handState(Hand.RIGHT),
        controllerState(Hand.LEFT), controllerState(Hand.RIGHT), includeHands, includeHead, radiusFor)
}

/**
 * Spring towards a capsule's standoff surface. Independent positive gains pull from outside and
 * push from inside. [reach] is the outside influence band in metres; inside remains repulsive.
 * [insideReach] optionally limits penetration influence too; zero covers the entire interior.
 * The result is world acceleration; a physics adapter converts it to its integration convention.
 */
fun BodySegmentHit.force(pull: Float, push: Float, reach: Float, falloff: Float = 1f, maximum: Float = 0f, insideReach: Float = 0f): Vector3f {
    require(listOf(pull, push, reach, falloff, maximum, insideReach).all { it.isFinite() && it >= 0f })
    val depth = kotlin.math.abs(distance)
    val band = if (distance >= 0f) reach else insideReach
    val weight = if (depth == 0f || (distance < 0f && insideReach == 0f)) 1f
        else if (band <= 0f || depth >= band) 0f
        else Math.pow((1f - depth / band).toDouble(), falloff.toDouble()).toFloat()
    var magnitude = -distance * (if (distance > 0f) pull else push) * weight
    if (maximum > 0f) magnitude = magnitude.coerceIn(-maximum, maximum)
    return Vector3f(normal).mul(magnitude)
}
