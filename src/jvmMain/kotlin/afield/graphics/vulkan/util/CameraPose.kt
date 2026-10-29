package afield.graphics.vulkan.util

/**
 * Rigid camera poses for blending between viewpoints: [CameraPose] stores a [Camera]'s placement as a dual quaternion plus a focus distance.
 *
 * [pose] reads one from a camera and [applyPose] writes it back. [sclerpTo] moves a camera toward a target pose by screw interpolation and [nlerpTo] by normalised linear interpolation.
 */

import afield.math.linalg.DualQuaternionf
import afield.math.linalg.Matrix3f
import afield.math.linalg.Quaternionf
import afield.math.linalg.Vector3f
import afield.math.linalg.Vector3fc
import kotlin.math.abs

/**
 * A camera body transform plus the look-at focus distance needed to reconstruct
 * Field's traditional at/target/up camera state.
 */
data class CameraPose(
    /** Where the camera is and which way it faces, as one rigid transform — rotation and translation
     * together in a dual quaternion, which is what makes two poses interpolable as a unit rather
     * than axis by axis. */
    val transform: DualQuaternionf,
    /** How far down [forward] the look-at target sits, in world units. A pose carries this because
     * [transform] alone cannot say it, and Field's camera is steered by a target point rather than
     * by a rotation. */
    val focusDistance: Float
) {
    constructor(
        rotation: Quaternionf,
        position: Vector3fc,
        focusDistance: Float
    ) : this(DualQuaternionf(rotation, position), focusDistance)

    /** The eye point, read out of [transform] as its translation. */
    val position: Vector3f
        get() = transform.getTranslation()

    /** The camera's orientation, read out of [transform]. */
    val rotation: Quaternionf
        get() = transform.getRotation()

    /** The viewing direction: the OpenGL-style `-Z` axis turned by [rotation], and a unit vector. */
    val forward: Vector3f
        get() = rotation.transform(Vector3f(0f, 0f, -1f))

    /** The camera's up axis: `+Y` turned by [rotation]. Already perpendicular to [forward], since a
     * rotation is applied to both. */
    val up: Vector3f
        get() = rotation.transform(Vector3f(0f, 1f, 0f))

    /** The point the camera looks at, [focusDistance] along [forward] from [position]. This is what
     * a `Camera` is actually steered by, so it is the value [applyPose] writes. */
    val target: Vector3f
        get() = Vector3f(position).fma(focusDistance, forward)

    /**
     * Blends toward [other] by normalised linear interpolation of the two dual quaternions, with
     * [focusDistance] interpolated linearly alongside.
     *
     * Cheaper than [sclerp] and indistinguishable from it over the small steps a per-frame ease
     * takes. Over a large step it does not hold angular speed constant — both ends land exactly, and
     * the turn is fastest in the middle — so a slow deliberate move between two distant viewpoints
     * is the case for [sclerp].
     */
    fun nlerp(other: CameraPose, t: Float): CameraPose =
        CameraPose(
            transform.nlerp(other.transform, t),
            focusDistance + (other.focusDistance - focusDistance) * t
        )

    /**
     * Blends toward [other] by screw interpolation, so the camera turns and travels at a constant
     * rate about a single screw axis. [focusDistance] is interpolated linearly alongside.
     *
     * This is the one to reach for when the whole move is visible — a long dolly between two set-ups
     * reads as one motion rather than as a turn that speeds up halfway. At `t` of 0 and 1 it agrees
     * with [nlerp], so the two are interchangeable at the endpoints.
     */
    fun sclerp(other: CameraPose, t: Float): CameraPose =
        CameraPose(
            transform.sclerp(other.transform, t),
            focusDistance + (other.focusDistance - focusDistance) * t
        )

    companion object {
        fun lookAt(
            eye: Vector3fc,
            center: Vector3fc,
            up: Vector3fc = Vector3f(0f, 1f, 0f)
        ): CameraPose {
            val forward = Vector3f(center).sub(eye)
            val distance = forward.length()
            if (distance < 1e-6f) {
                return CameraPose(Quaternionf(), eye, 1f)
            }
            forward.normalize()
            val rotation = rotationFromForwardUp(forward, up)
            return CameraPose(rotation, eye, distance)
        }

        fun lookAlong(
            position: Vector3fc,
            direction: Vector3fc,
            up: Vector3fc = Vector3f(0f, 1f, 0f),
            focusDistance: Float = 1f
        ): CameraPose {
            val forward = Vector3f(direction)
            if (forward.lengthSquared() < 1e-12f) {
                return CameraPose(Quaternionf(), position, focusDistance)
            }
            forward.normalize()
            return CameraPose(rotationFromForwardUp(forward, up), position, focusDistance)
        }
    }
}

/**
 * Reads this camera's current placement as a [CameraPose], turning its eye, target and up vectors
 * into a rotation, a translation and a focus distance.
 *
 * The distance between eye and target becomes [CameraPose.focusDistance], so a pose captured here
 * and applied later restores the target point and not only the direction. A camera whose eye and
 * target coincide has no direction to read, and comes back as an unrotated pose at a focus distance
 * of 1.
 */
fun Camera.pose(): CameraPose =
    CameraPose.lookAt(at, target, up)

/**
 * Moves this camera to [pose], writing its eye, target and up vectors in place.
 *
 * The up vector is re-squared against the new view direction afterwards, so a pose produced by
 * interpolation cannot leave the camera with a skewed basis. Nothing else about the camera — its
 * projection, its controls — is touched.
 */
fun Camera.applyPose(pose: CameraPose) {
    at.set(pose.position)
    target.set(pose.target)
    up.set(pose.up)
    reorthogonalizeUp()
}

/**
 * Moves this camera a fraction [t] of the way toward [targetPose] by screw interpolation, reading its
 * current pose and writing the blended one back.
 *
 * Called once a frame with a small `t` this is an exponential ease: the remaining distance is cut by
 * the same proportion every frame, so the camera approaches without ever overshooting and the
 * approach slows as it arrives. `t` of 1 jumps straight there.
 */
fun Camera.sclerpTo(targetPose: CameraPose, t: Float) {
    applyPose(pose().sclerp(targetPose, t))
}

/**
 * Moves this camera a fraction [t] of the way toward [targetPose] by normalised linear interpolation.
 *
 * The same per-frame ease as [sclerpTo] and cheaper; at the step sizes an ease uses, the two are
 * hard to tell apart.
 */
fun Camera.nlerpTo(targetPose: CameraPose, t: Float) {
    applyPose(pose().nlerp(targetPose, t))
}

internal fun rotationFromForwardUp(forward: Vector3fc, up: Vector3fc): Quaternionf {
    val f = Vector3f(forward)
    if (f.lengthSquared() < 1e-12f) {
        return Quaternionf()
    }
    f.normalize()

    val upHint = Vector3f(up)
    if (upHint.lengthSquared() < 1e-12f) {
        upHint.set(0f, 1f, 0f)
    }
    upHint.normalize()

    val right = Vector3f(f).cross(upHint)
    if (right.lengthSquared() < 1e-12f) {
        if (abs(f.y) > 0.9f) {
            right.set(1f, 0f, 0f)
        } else {
            right.set(Vector3f(f).cross(Vector3f(0f, 1f, 0f)))
        }
    }
    right.normalize()

    val trueUp = Vector3f(right).cross(f).normalize()
    val back = Vector3f(f).negate()

    val basis = Matrix3f()
    basis.m00(right.x)
    basis.m10(trueUp.x)
    basis.m20(back.x)
    basis.m01(right.y)
    basis.m11(trueUp.y)
    basis.m21(back.y)
    basis.m02(right.z)
    basis.m12(trueUp.z)
    basis.m22(back.z)
    return Quaternionf().setFromNormalized(basis).normalize()
}
