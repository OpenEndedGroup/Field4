package afield.graphics.vulkan.targets

import afield.math.linalg.Vec3
import kotlin.math.acos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Estimates where the two eyes converge from a pair of per-eye gaze rays.
 *
 * [BinocularGazeIntersection] takes the closest approach of the left and right rays and rejects
 * depths it cannot trust, so a caller can place an object at the point the viewer is looking at.
 * `BinocularGazeIntersectionTest` covers it.
 */

/**
 * Closest approach of two forward eye rays, in metres, with explicit rejection of unreliable
 * depth. Real rays can be skew: [position] is the midpoint of [leftPoint] and [rightPoint], and
 * [separation] is their gap. Read [valid] before placing an object there.
 *
 * [update] consumes HTC poses already located in OpenXR LOCAL space. It rotates each eye's -Z
 * axis by that eye's orientation; it does NOT apply a head pose. Results stay in LOCAL space.
 * Apply your chosen worldFromLocal transform once to move them into application world space.
 * All vectors are reused; copy them when retaining a sample across frames.
 */
class BinocularGazeIntersection(
    /**
     * The smallest angle between the two rays that still gives a depth, in degrees. Nearer to
     * parallel than this the closest-approach solve is ill-conditioned, so [status] becomes
     * [Status.PARALLEL] and no point is written. Must be above 0 and below 90.
     */
    val minimumAngleDegrees: Double = 0.25,
    /**
     * How far [leftPoint] and [rightPoint] may be apart, in metres, before the rays count as
     * missing each other and [status] becomes [Status.RAYS_MISS]. Skew rays always leave some gap;
     * this is how much of it to forgive.
     */
    val maximumSeparation: Double = 0.03,
    /** Nearest accepted depth along either ray, in metres. Closer gives [Status.OUT_OF_RANGE]. */
    val minimumDistance: Double = 0.05,
    /** Farthest accepted depth along either ray, in metres. Beyond it, [Status.OUT_OF_RANGE]. */
    val maximumDistance: Double = 8.0,
) {
    enum class Status { NO_TRACKING, INVALID_RAY, PARALLEL, BEHIND_EYES, OUT_OF_RANGE, RAYS_MISS, VALID }

    init {
        require(minimumAngleDegrees > 0 && minimumAngleDegrees < 90)
        require(maximumSeparation.isFinite() && maximumSeparation >= 0)
        require(minimumDistance.isFinite() && minimumDistance >= 0)
        require(maximumDistance.isFinite() && maximumDistance > minimumDistance)
    }

    /** Why the last [update] or [solve] accepted or rejected a depth. [Status.NO_TRACKING] until the first call. */
    var status = Status.NO_TRACKING
        private set

    /** True only for [Status.VALID] — the one check to make before using [position]. */
    val valid: Boolean get() = status == Status.VALID

    /**
     * True when both rays were finite, long enough to normalize and had distinct origins, so
     * [leftDirection] and [rightDirection] are meaningful. The convergence tests come after this,
     * so a pair of usable rays that converge badly leaves this true while [valid] is false.
     */
    var raysValid = false
        private set

    /** Midpoint of [leftPoint] and [rightPoint] — where to put an object the viewer is looking at. */
    val position = Vec3()

    /** The point on the left eye's ray closest to the right eye's ray. */
    val leftPoint = Vec3()

    /** The point on the right eye's ray closest to the left eye's ray. */
    val rightPoint = Vec3()

    /** Unit forward direction of the left eye's ray, as the last solve normalized it. */
    val leftDirection = Vec3()

    /** Unit forward direction of the right eye's ray, as the last solve normalized it. */
    val rightDirection = Vec3()

    /** Distance from the left eye to [leftPoint], in metres. Negative means the convergence is behind the eye. */
    var leftDistance = Double.NaN
        private set

    /** Distance from the right eye to [rightPoint], in metres. Negative means behind the eye. */
    var rightDistance = Double.NaN
        private set

    /** Gap between [leftPoint] and [rightPoint], in metres — how far the two rays missed by. */
    var separation = Double.NaN
        private set

    /**
     * Angle between the two rays, in degrees. Written before the convergence tests, so it is
     * readable even when the rays were too close to parallel to intersect.
     */
    var angleDegrees = Double.NaN
        private set
    private val minimumSinSquared = sin(Math.toRadians(minimumAngleDegrees)).let { it * it }

    fun invalidate() {
        status = Status.NO_TRACKING
        raysValid = false
        leftDistance = Double.NaN
        rightDistance = Double.NaN
        separation = Double.NaN
        angleDegrees = Double.NaN
    }

    fun update(eyes: HtcEyeTrackingState?): Boolean {
        if (eyes == null || !eyes.left.gazeValid || !eyes.right.gazeValid) {
            invalidate()
            return false
        }
        eyes.left.orientation.transform(leftDirection.set(0f, 0f, -1f))
        eyes.right.orientation.transform(rightDirection.set(0f, 0f, -1f))
        return solve(eyes.left.position, leftDirection, eyes.right.position, rightDirection)
    }

    /** Both origins and directions must use the same coordinate system; directions may be unnormalized. */
    fun solve(leftOrigin: Vec3, leftRay: Vec3, rightOrigin: Vec3, rightRay: Vec3): Boolean {
        invalidate()
        status = Status.INVALID_RAY
        if (!finite(leftOrigin) || !finite(rightOrigin) || !finite(leftRay) || !finite(rightRay)) return false
        val leftLength = sqrt(lengthSquared(leftRay))
        val rightLength = sqrt(lengthSquared(rightRay))
        if (leftLength < 1e-12 || rightLength < 1e-12) return false
        val ux = leftRay.x / leftLength; val uy = leftRay.y / leftLength; val uz = leftRay.z / leftLength
        val vx = rightRay.x / rightLength; val vy = rightRay.y / rightLength; val vz = rightRay.z / rightLength
        val wx = leftOrigin.x.toDouble() - rightOrigin.x
        val wy = leftOrigin.y.toDouble() - rightOrigin.y
        val wz = leftOrigin.z.toDouble() - rightOrigin.z
        if (wx * wx + wy * wy + wz * wz < 1e-12) return false
        leftDirection.set(ux.toFloat(), uy.toFloat(), uz.toFloat())
        rightDirection.set(vx.toFloat(), vy.toFloat(), vz.toFloat())
        raysValid = true
        val b = (ux * vx + uy * vy + uz * vz).coerceIn(-1.0, 1.0)
        angleDegrees = Math.toDegrees(acos(b))
        val denominator = 1.0 - b * b
        if (denominator < minimumSinSquared) {
            status = Status.PARALLEL
            return false
        }
        val d = ux * wx + uy * wy + uz * wz
        val e = vx * wx + vy * wy + vz * wz
        leftDistance = (b * e - d) / denominator
        rightDistance = (e - b * d) / denominator
        val lx = leftOrigin.x + leftDistance * ux
        val ly = leftOrigin.y + leftDistance * uy
        val lz = leftOrigin.z + leftDistance * uz
        val rx = rightOrigin.x + rightDistance * vx
        val ry = rightOrigin.y + rightDistance * vy
        val rz = rightOrigin.z + rightDistance * vz
        leftPoint.set(lx.toFloat(), ly.toFloat(), lz.toFloat())
        rightPoint.set(rx.toFloat(), ry.toFloat(), rz.toFloat())
        position.set(((lx + rx) * 0.5).toFloat(), ((ly + ry) * 0.5).toFloat(), ((lz + rz) * 0.5).toFloat())
        separation = sqrt((lx - rx) * (lx - rx) + (ly - ry) * (ly - ry) + (lz - rz) * (lz - rz))
        status = when {
            leftDistance <= 0 || rightDistance <= 0 -> Status.BEHIND_EYES
            leftDistance < minimumDistance || rightDistance < minimumDistance ||
                leftDistance > maximumDistance || rightDistance > maximumDistance -> Status.OUT_OF_RANGE
            separation > maximumSeparation -> Status.RAYS_MISS
            else -> Status.VALID
        }
        return valid
    }

    private fun finite(v: Vec3) = v.x.isFinite() && v.y.isFinite() && v.z.isFinite()
    private fun lengthSquared(v: Vec3) = v.x.toDouble() * v.x + v.y.toDouble() * v.y + v.z.toDouble() * v.z
}
