package org.joml

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A mutable single-precision unit dual quaternion for rigid 3D transforms.
 *
 * This class intentionally follows JOML's mutable naming style while filling a
 * small gap in JOML itself. The real part stores rotation. The dual part stores
 * translation as `0.5 * translation * rotation`.
 */
class DualQuaternionf {
    val real: Quaternionf = Quaternionf()
    val dual: Quaternionf = Quaternionf(0f, 0f, 0f, 0f)

    constructor()

    constructor(other: DualQuaternionf) {
        set(other)
    }

    constructor(real: Quaternionfc, dual: Quaternionfc) {
        set(real, dual)
    }

    constructor(rotation: Quaternionfc, translation: Vector3fc) {
        set(rotation, translation)
    }

    fun identity(): DualQuaternionf {
        real.identity()
        dual.set(0f, 0f, 0f, 0f)
        return this
    }

    fun set(other: DualQuaternionf): DualQuaternionf =
        set(other.real, other.dual)

    fun set(real: Quaternionfc, dual: Quaternionfc): DualQuaternionf {
        this.real.set(real)
        this.dual.set(dual)
        return this
    }

    fun set(rotation: Quaternionfc, translation: Vector3fc): DualQuaternionf {
        real.set(rotation).normalize()
        setDualFromTranslation(translation)
        return normalize()
    }

    fun setTranslation(translation: Vector3fc): DualQuaternionf {
        real.normalize()
        setDualFromTranslation(translation)
        return normalize()
    }

    fun getRotation(dest: Quaternionf = Quaternionf()): Quaternionf =
        dest.set(real).normalize()

    fun getTranslation(dest: Vector3f = Vector3f()): Vector3f {
        val rInv = Quaternionf(real).conjugate()
        val t = Quaternionf(dual).mul(rInv)
        return dest.set(2f * t.x, 2f * t.y, 2f * t.z)
    }

    fun dot(other: DualQuaternionf): Float =
        quatDot(real, other.real)

    fun negate(): DualQuaternionf {
        real.negateComponents()
        dual.negateComponents()
        return this
    }

    fun normalize(): DualQuaternionf {
        val lenSq = quatDot(real, real)
        if (lenSq < EPSILON * EPSILON) {
            return identity()
        }

        val invLen = 1f / sqrt(lenSq)
        real.scaleComponents(invLen)
        dual.scaleComponents(invLen)

        // Unit dual quaternions satisfy dot(real, dual) == 0. Keep that
        // invariant after linear blends and accumulated floating-point error.
        val rd = quatDot(real, dual)
        dual.fmaComponents(-rd, real)
        return this
    }

    fun normalized(dest: DualQuaternionf = DualQuaternionf()): DualQuaternionf =
        dest.set(this).normalize()

    fun conjugate(): DualQuaternionf {
        real.conjugate()
        dual.conjugate()
        return this
    }

    fun invert(): DualQuaternionf =
        normalize().conjugate()

    fun inverted(dest: DualQuaternionf = DualQuaternionf()): DualQuaternionf =
        dest.set(this).invert()

    fun mul(right: DualQuaternionf): DualQuaternionf =
        mul(right, this)

    fun mul(right: DualQuaternionf, dest: DualQuaternionf): DualQuaternionf {
        val outReal = Quaternionf(real).mul(right.real)
        val outDual = Quaternionf(real).mul(right.dual)
        outDual.addComponents(Quaternionf(dual).mul(right.real))
        dest.real.set(outReal)
        dest.dual.set(outDual)
        return dest
    }

    operator fun times(right: DualQuaternionf): DualQuaternionf =
        mul(right, DualQuaternionf())

    operator fun times(position: Vector3fc): Vector3f =
        transform(position)

    operator fun unaryMinus(): DualQuaternionf =
        DualQuaternionf(this).negate()

    fun transform(position: Vector3fc, dest: Vector3f = Vector3f()): Vector3f {
        real.transform(position, dest)
        return dest.add(getTranslation(TEMP_VEC.get()))
    }

    fun toMatrix(dest: Matrix4f = Matrix4f()): Matrix4f {
        val translation = getTranslation(TEMP_VEC.get())
        return dest.identity().translate(translation).rotate(real)
    }

    fun nlerp(other: DualQuaternionf, t: Float, dest: DualQuaternionf = DualQuaternionf()): DualQuaternionf {
        val sign = if (dot(other) < 0f) -1f else 1f
        val oneMinusT = 1f - t
        dest.real.set(
            real.x * oneMinusT + other.real.x * sign * t,
            real.y * oneMinusT + other.real.y * sign * t,
            real.z * oneMinusT + other.real.z * sign * t,
            real.w * oneMinusT + other.real.w * sign * t
        )
        dest.dual.set(
            dual.x * oneMinusT + other.dual.x * sign * t,
            dual.y * oneMinusT + other.dual.y * sign * t,
            dual.z * oneMinusT + other.dual.z * sign * t,
            dual.w * oneMinusT + other.dual.w * sign * t
        )
        return dest.normalize()
    }

    fun sclerp(other: DualQuaternionf, t: Float, dest: DualQuaternionf = DualQuaternionf()): DualQuaternionf {
        val alignedOther = if (dot(other) < 0f) DualQuaternionf(other).negate() else other
        val relative = inverted(TEMP_DQ_A.get()).mul(alignedOther, TEMP_DQ_B.get()).normalize()
        val step = relative.pow(t, TEMP_DQ_C.get())
        return mul(step, dest).normalize()
    }

    fun pow(exponent: Float, dest: DualQuaternionf = DualQuaternionf()): DualQuaternionf {
        val omega = TEMP_VEC.get()
        val velocity = TEMP_VEC_2.get()
        logTwist(omega, velocity)
        omega.mul(exponent)
        velocity.mul(exponent)
        return fromTwist(omega, velocity, dest)
    }

    private fun setDualFromTranslation(translation: Vector3fc) {
        val tx = translation.x()
        val ty = translation.y()
        val tz = translation.z()
        val rx = real.x
        val ry = real.y
        val rz = real.z
        val rw = real.w

        dual.set(
            0.5f * (tx * rw + ty * rz - tz * ry),
            0.5f * (-tx * rz + ty * rw + tz * rx),
            0.5f * (tx * ry - ty * rx + tz * rw),
            0.5f * (-tx * rx - ty * ry - tz * rz)
        )
    }

    private fun logTwist(omega: Vector3f, velocity: Vector3f) {
        val rotation = Quaternionf(real).normalize()
        if (rotation.w < 0f) {
            rotation.negateComponents()
        }

        val imagLen = sqrt(rotation.x * rotation.x + rotation.y * rotation.y + rotation.z * rotation.z)
        val translation = getTranslation(TEMP_VEC_3.get())
        if (imagLen < EPSILON) {
            omega.set(0f, 0f, 0f)
            velocity.set(translation)
            return
        }

        val theta = 2.0 * atan2(imagLen.toDouble(), rotation.w.toDouble())
        val thetaF = theta.toFloat()
        omega.set(rotation.x / imagLen, rotation.y / imagLen, rotation.z / imagLen).mul(thetaF)

        val omegaCrossP = Vector3f(omega).cross(translation)
        val omegaCrossOmegaCrossP = Vector3f(omega).cross(omegaCrossP)
        val coeff = ((1.0 - theta * rotation.w / (2.0 * imagLen)) / (theta * theta)).toFloat()

        velocity.set(translation)
            .fma(-0.5f, omegaCrossP)
            .fma(coeff, omegaCrossOmegaCrossP)
    }

    private fun fromTwist(omega: Vector3f, velocity: Vector3f, dest: DualQuaternionf): DualQuaternionf {
        val theta = omega.length()
        if (theta < EPSILON) {
            return dest.set(Quaternionf(), velocity)
        }

        val axis = Vector3f(omega).mul(1f / theta)
        val rotation = Quaternionf().fromAxisAngleRad(axis, theta)

        val omegaCrossV = Vector3f(omega).cross(velocity)
        val omegaCrossOmegaCrossV = Vector3f(omega).cross(omegaCrossV)
        val thetaD = theta.toDouble()
        val a = ((1.0 - cos(thetaD)) / (thetaD * thetaD)).toFloat()
        val b = ((thetaD - sin(thetaD)) / (thetaD * thetaD * thetaD)).toFloat()
        val translation = Vector3f(velocity)
            .fma(a, omegaCrossV)
            .fma(b, omegaCrossOmegaCrossV)

        return dest.set(rotation, translation)
    }

    override fun toString(): String =
        "DualQuaternionf[real=(${"%.4f".format(real.x)}, ${"%.4f".format(real.y)}, ${"%.4f".format(real.z)}, ${"%.4f".format(real.w)}), " +
            "dual=(${"%.4f".format(dual.x)}, ${"%.4f".format(dual.y)}, ${"%.4f".format(dual.z)}, ${"%.4f".format(dual.w)})]"

    companion object {
        private const val EPSILON = 1e-6f

        private val TEMP_VEC = ThreadLocal.withInitial { Vector3f() }
        private val TEMP_VEC_2 = ThreadLocal.withInitial { Vector3f() }
        private val TEMP_VEC_3 = ThreadLocal.withInitial { Vector3f() }
        private val TEMP_DQ_A = ThreadLocal.withInitial { DualQuaternionf() }
        private val TEMP_DQ_B = ThreadLocal.withInitial { DualQuaternionf() }
        private val TEMP_DQ_C = ThreadLocal.withInitial { DualQuaternionf() }
    }
}

private fun quatDot(a: Quaternionf, b: Quaternionf): Float =
    a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w

private fun Quaternionf.scaleComponents(scale: Float): Quaternionf {
    set(x * scale, y * scale, z * scale, w * scale)
    return this
}

private fun Quaternionf.fmaComponents(scale: Float, other: Quaternionf): Quaternionf {
    set(x + scale * other.x, y + scale * other.y, z + scale * other.z, w + scale * other.w)
    return this
}

private fun Quaternionf.addComponents(other: Quaternionf): Quaternionf {
    set(x + other.x, y + other.y, z + other.z, w + other.w)
    return this
}

private fun Quaternionf.negateComponents(): Quaternionf {
    set(-x, -y, -z, -w)
    return this
}
