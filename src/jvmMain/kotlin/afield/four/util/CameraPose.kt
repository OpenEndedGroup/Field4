package afield.four.util

import org.joml.DualQuaternionf
import org.joml.Matrix3f
import org.joml.Quaternionf
import org.joml.Vector3f
import org.joml.Vector3fc
import kotlin.math.abs

/**
 * A camera body transform plus the look-at focus distance needed to reconstruct
 * Field's traditional at/target/up camera state.
 */
data class CameraPose(
    val transform: DualQuaternionf,
    val focusDistance: Float
) {
    constructor(
        rotation: Quaternionf,
        position: Vector3fc,
        focusDistance: Float
    ) : this(DualQuaternionf(rotation, position), focusDistance)

    val position: Vector3f
        get() = transform.getTranslation()

    val rotation: Quaternionf
        get() = transform.getRotation()

    val forward: Vector3f
        get() = rotation.transform(Vector3f(0f, 0f, -1f))

    val up: Vector3f
        get() = rotation.transform(Vector3f(0f, 1f, 0f))

    val target: Vector3f
        get() = Vector3f(position).fma(focusDistance, forward)

    fun nlerp(other: CameraPose, t: Float): CameraPose =
        CameraPose(
            transform.nlerp(other.transform, t),
            focusDistance + (other.focusDistance - focusDistance) * t
        )

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

fun Camera.pose(): CameraPose =
    CameraPose.lookAt(at, target, up)

fun Camera.applyPose(pose: CameraPose) {
    at.set(pose.position)
    target.set(pose.target)
    up.set(pose.up)
    reorthogonalizeUp()
}

fun Camera.sclerpTo(targetPose: CameraPose, t: Float) {
    applyPose(pose().sclerp(targetPose, t))
}

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
