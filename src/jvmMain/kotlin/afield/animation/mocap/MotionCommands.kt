@file:afield.runtime.completion.CompletionCategory("Animation")

package afield.animation.mocap

import afield.math.linalg.Vec3
import org.json.JSONObject
import kotlin.math.min

enum class MotionOperation { PROMPT, ROOT_PLAN, JOINT_TARGETS, ATTACH, UPDATE_ATTACHMENT, DETACH, IMPULSE, ADVANCE, RESET }

data class MotionCapabilities(val backend: String, val operations: Set<MotionOperation>, val details: JSONObject) {
    fun require(operation: MotionOperation) {
        require(operation in operations) { "$backend does not support $operation. Supported: $operations" }
    }
    companion object {
        fun fromJson(j: JSONObject) = MotionCapabilities(j.getString("backend"),
            j.getJSONArray("operations").strings().map { MotionOperation.valueOf(it) }.toSet(), j)
    }
}

/** Kinematic conditioning, in world coordinates; orientation is optional and separately recorded. */
data class JointTarget(val joint: String, val time: Double, val position: List<Double>, val orientation: List<Double>? = null) {
    init {
        require(joint.isNotBlank() && time.isFinite() && time >= 0 && position.size == 3 && position.all(Double::isFinite))
        require(orientation == null || orientation.size == 4 && orientation.all(Double::isFinite) &&
            kotlin.math.abs(orientation.sumOf { it * it } - 1) < .002)
    }
    fun json() = JSONObject().put("joint", joint).put("time", time).put("position", position)
        .put("orientation", orientation ?: JSONObject.NULL)
}

/** Force capped spring/damper. A break requires a sustained criterion; saturation alone never detaches. */
data class MotionAttachment(
    val id: String, val body: String, val localPoint: List<Double>, val anchor: List<Double>,
    val stiffness: Double = 30.0, val damping: Double = 5.0, val maximumForce: Double = 50.0,
    val engagementSeconds: Double = .2, val breakForce: Double? = null, val breakError: Double? = null,
    val breakDuration: Double = .1,
    val anchorVelocity: List<Double> = listOf(0.0, 0.0, 0.0),
) {
    init {
        require(id.isNotBlank() && body.isNotBlank())
        require(localPoint.size == 3 && anchor.size == 3 && (localPoint + anchor).all(Double::isFinite))
        require(anchorVelocity.size == 3 && anchorVelocity.all(Double::isFinite))
        require(listOf(stiffness, damping, maximumForce, engagementSeconds, breakDuration).all { it.isFinite() && it >= 0 })
        require(maximumForce > 0 && breakDuration > 0)
        require(breakForce == null || breakForce.isFinite() && breakForce > 0)
        require(breakError == null || breakError.isFinite() && breakError > 0)
    }
    fun json() = JSONObject().put("id", id).put("body", body).put("localPoint", localPoint).put("anchor", anchor)
        .put("stiffness", stiffness).put("damping", damping).put("maximumForce", maximumForce)
        .put("engagementSeconds", engagementSeconds).put("breakForce", breakForce ?: JSONObject.NULL)
        .put("anchorVelocity", anchorVelocity)
        .put("breakError", breakError ?: JSONObject.NULL).put("breakDuration", breakDuration)
        .put("mode", if (breakForce == null && breakError == null) "saturating" else "breakable")
}

data class PointWrench(val force: Vec3, val torque: Vec3, val velocity: Vec3, val rawMagnitude: Double, val powerWatts: Double)

/**
 * Inspectable point-force math, independent of a simulator. Inputs are in one world frame, SI.
 * [point] is measured from the actual COM, not the display joint or rigid-body origin.
 * The simulator adapter must add this wrench to other forces and apply it for the documented step.
 */
fun pointTether(point: Vec3, centreOfMass: Vec3, comVelocity: Vec3, angularVelocity: Vec3,
    anchor: Vec3, anchorVelocity: Vec3 = Vec3(), stiffness: Double, damping: Double,
    maximumForce: Double, engagement: Double = 1.0): PointWrench {
    require(listOf(stiffness, damping, maximumForce, engagement).all(Double::isFinite))
    require(stiffness >= 0 && damping >= 0 && maximumForce > 0 && engagement in 0.0..1.0)
    listOf(point, centreOfMass, comVelocity, angularVelocity, anchor, anchorVelocity).forEach {
        require(it.x.isFinite() && it.y.isFinite() && it.z.isFinite())
    }
    val radius = Vec3(point).sub(centreOfMass)
    val velocity = Vec3(angularVelocity).cross(radius).add(comVelocity)
    val force = Vec3(anchor).sub(point).mul(stiffness.toFloat())
        .sub(Vec3(velocity).sub(anchorVelocity).mul(damping.toFloat())).mul(engagement.toFloat())
    val magnitude = force.length().toDouble()
    force.mul(min(1.0, maximumForce / magnitude.coerceAtLeast(1e-30)).toFloat())
    return PointWrench(force, Vec3(radius).cross(force), velocity, magnitude, force.dot(velocity).toDouble())
}
