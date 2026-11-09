package afield.graphics.scene.body

import afield.graphics.vulkan.targets.OpenXRHmdTarget
import afield.graphics.vulkan.targets.OpenXRHmdTarget.BodyJoint
import afield.graphics.vulkan.targets.OpenXRHmdTarget.BodyTrackingState
import afield.graphics.vulkan.targets.OpenXRHmdTarget.JointPose
import afield.math.linalg.Vector3f
import kotlin.math.abs
import kotlin.math.sqrt

/** World-space axis-aligned bounds in metres. Empty tracking produces null rather than infinite bounds. */
data class BodyBounds(val min: Vector3f, val max: Vector3f) {
    /** Centre of these bounds, returned as a new vector. */
    val center: Vector3f get() = Vector3f(min).add(max).mul(.5f)
    /** Extent of these bounds, returned as a new vector. */
    val size: Vector3f get() = Vector3f(max).sub(min)
}

/** A nearest-point result. Negative signed distance means inside the selected sphere or cylinder. */
data class BodyHit(
    val point: Vector3f,
    val normal: Vector3f,
    val distance: Float,
    val signedDistance: Float,
    val joint: BodyJoint? = null,
    val segment: Pair<BodyJoint, BodyJoint>? = null,
    val head: Boolean = false,
)

/**
 * A copied snapshot of valid body positions, with an optional head. No missing joints are inferred.
 * Zero radii query the skeleton's points and segments; positive radii query finite cylinders and
 * a head sphere. Surfaces are independent primitives, not a watertight Boolean union: internal
 * cylinder end caps remain queryable where shapes overlap. All arguments/results are in metres.
 */
class BodyGeometry(body: BodyTrackingState?, head: JointPose? = null) {
    /** Valid, finite body positions copied from the input state. */
    val joints: Map<BodyJoint, Vector3f> = BodyJoint.entries.mapNotNull { joint ->
        body?.get(joint)?.takeIf { it.positionValid && it.position.isFinite() }?.let {
            joint to Vector3f(it.position)
        }
    }.toMap()
    private val headPosition = head?.takeIf { it.positionValid && it.position.isFinite() }?.let { Vector3f(it.position) }
    private val segments = CONNECTIONS.filter { (a, b) -> a in joints && b in joints }

    /** Nearest reported body joint centre. Head and inferred/interpolated points are excluded. */
    fun closestJoint(query: Vector3f): BodyHit? {
        require(query.isFinite()) { "Query must be finite" }
        return joints.map { (joint, p) -> hit(query, p, joint = joint) }.minByOrNull { it.distance }
    }

    /**
     * Nearest surface/centreline point, including isolated valid joints. A zero-length limb becomes
     * a sphere of limbRadius (or a point at radius zero). Head can be excluded independently.
     */
    fun closestPoint(query: Vector3f, limbRadius: Float = 0f, headRadius: Float = 0f, includeHead: Boolean = true): BodyHit? {
        validate(query, limbRadius, headRadius)
        val hits = mutableListOf<BodyHit>()
        val connected = segments.map { it.first }.toSet() + segments.map { it.second }
        for (pair in segments) {
            hits += cylinderHit(query, joints.getValue(pair.first), joints.getValue(pair.second), limbRadius).copy(segment = pair)
        }
        for ((joint, p) in joints) if (joint !in connected) hits += sphereHit(query, p, limbRadius).copy(joint = joint)
        if (includeHead) headPosition?.let { hits += sphereHit(query, it, headRadius).copy(head = true) }
        return hits.minByOrNull { it.distance }
    }

    /** Tight AABB of the same independent primitives used by closestPoint, optionally padded. */
    fun bounds(limbRadius: Float = 0f, headRadius: Float = 0f, includeHead: Boolean = true, padding: Float = 0f): BodyBounds? {
        validate(Vector3f(), limbRadius, headRadius)
        require(padding.isFinite() && padding >= 0f) { "Padding must be finite and nonnegative" }
        val min = Vector3f(Float.POSITIVE_INFINITY)
        val max = Vector3f(Float.NEGATIVE_INFINITY)
        fun include(p: Vector3f, radius: Vector3f) {
            min.min(Vector3f(p).sub(radius))
            max.max(Vector3f(p).add(radius))
        }
        val connected = segments.map { it.first }.toSet() + segments.map { it.second }
        for ((a, b) in segments) {
            val p = joints.getValue(a)
            val q = joints.getValue(b)
            val d = Vector3f(q).sub(p)
            val radius = if (d.lengthSquared() <= 1e-12f) Vector3f(limbRadius) else {
                d.normalize()
                Vector3f(sqrt((1f-d.x*d.x).coerceAtLeast(0f)), sqrt((1f-d.y*d.y).coerceAtLeast(0f)), sqrt((1f-d.z*d.z).coerceAtLeast(0f))).mul(limbRadius)
            }
            include(p, radius)
            include(q, radius)
        }
        for ((joint, p) in joints) if (joint !in connected) include(p, Vector3f(limbRadius))
        if (includeHead) headPosition?.let { include(it, Vector3f(headRadius)) }
        if (!min.isFinite()) return null
        return BodyBounds(min.sub(Vector3f(padding)), max.add(Vector3f(padding)))
    }

    companion object {
        /** Connections supported by WiVRn's sparse body joint set; no shoulder or knee inference. */
        val CONNECTIONS: List<Pair<BodyJoint, BodyJoint>> = [
            BodyJoint.HIPS to BodyJoint.CHEST,
            BodyJoint.CHEST to BodyJoint.LEFT_ARM_LOWER,
            BodyJoint.CHEST to BodyJoint.RIGHT_ARM_LOWER,
            BodyJoint.HIPS to BodyJoint.LEFT_LOWER_LEG,
            BodyJoint.HIPS to BodyJoint.RIGHT_LOWER_LEG,
            BodyJoint.LEFT_LOWER_LEG to BodyJoint.LEFT_FOOT,
            BodyJoint.RIGHT_LOWER_LEG to BodyJoint.RIGHT_FOOT,
        ]
    }
}

/** Copy this frame's body and head observations; the snapshot does not change on the next XR frame. */
fun OpenXRHmdTarget.bodyGeometry(): BodyGeometry {
    val head = JointPose()
    head.positionValid = headPose(head.position, head.orientation)
    head.orientationValid = head.positionValid
    return BodyGeometry(bodyState(), head)
}

private fun validate(query: Vector3f, limbRadius: Float, headRadius: Float) {
    require(query.isFinite()) { "Query must be finite" }
    require(limbRadius.isFinite() && limbRadius >= 0f && headRadius.isFinite() && headRadius >= 0f) {
        "Radii must be finite and nonnegative"
    }
}

private fun hit(query: Vector3f, point: Vector3f, joint: BodyJoint? = null): BodyHit {
    val normal = Vector3f(query).sub(point)
    val distance = normal.length()
    if (distance > 1e-6f) normal.div(distance) else normal.set(1f, 0f, 0f)
    return BodyHit(Vector3f(point), normal, distance, distance, joint = joint)
}

private fun sphereHit(query: Vector3f, center: Vector3f, radius: Float): BodyHit {
    val base = hit(query, center)
    val point = Vector3f(center).fma(radius, base.normal)
    val signed = base.distance - radius
    return BodyHit(point, base.normal, abs(signed), signed)
}

private fun cylinderHit(query: Vector3f, a: Vector3f, b: Vector3f, radius: Float): BodyHit {
    val axis = Vector3f(b).sub(a)
    val length = axis.length()
    if (length <= 1e-6f) return sphereHit(query, a, radius)
    axis.div(length)
    val delta = Vector3f(query).sub(a)
    val t = delta.dot(axis)
    val radial = Vector3f(delta).fma(-t, axis)
    val radialLength = radial.length()
    val direction = if (radialLength > 1e-6f) Vector3f(radial).div(radialLength) else {
        val basis = if (abs(axis.x) < .9f) Vector3f(1f, 0f, 0f) else Vector3f(0f, 1f, 0f)
        axis.cross(basis, Vector3f()).normalize()
    }
    if (radius == 0f) return hit(query, Vector3f(a).fma(t.coerceIn(0f, length), axis))
    val inside = t >= 0f && t <= length && radialLength <= radius
    val point: Vector3f
    val normal: Vector3f
    if (inside && minOf(t, length-t) < radius-radialLength) {
        val top = t > length*.5f
        point = Vector3f(a).fma(if (top) length else 0f, axis).add(radial)
        normal = Vector3f(axis).mul(if (top) 1f else -1f)
    } else if (!inside && (t < 0f || t > length) && radialLength < radius) {
        point = Vector3f(a).fma(t.coerceIn(0f, length), axis).add(radial)
        normal = Vector3f(axis).mul(if (t > length) 1f else -1f)
    } else {
        point = Vector3f(a).fma(t.coerceIn(0f, length), axis).fma(radius, direction)
        normal = if (inside) direction else hit(query, point).normal
    }
    val distance = query.distance(point)
    return BodyHit(point, normal, distance, if (inside) -distance else distance)
}
