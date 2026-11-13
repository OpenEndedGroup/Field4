@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.geometry.kinematics

import afield.math.linalg.Matrix4d
import afield.math.linalg.Matrix4dc
import afield.math.linalg.Matrix4f
import afield.math.linalg.Vector3d
import afield.math.linalg.Vector3dc
import afield.math.linalg.Vector3fc
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.DoubleArraySerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Declared rigid motions: how a part moves, as data.
 *
 * A [RigidMotion] is either a [Rotation] about an axis through a point, between two angles, or a [Slide] along a
 * direction, between two distances. It answers one question — where is the part at this point of its motion? —
 * as a [Matrix4d] pose, and it can be sampled into [MotionStep]s and written to and read from JSON, so a viewer can
 * play the same motion that a clearance check measured.
 *
 * ```kotlin
 * // a door 900 wide, hinged on the z axis at the origin, opening to 95°
 * val swing = RigidMotion.rotation(point = vec(0.0, 0.0, 0.0), axis = vec(0.0, 0.0, 1.0), from = 0.0, to = 95.0)
 * val half = swing.pose(0.5)                     // the pose half-way through, at 47.5°
 * val edge = swing.pointAt(0.5, Vector3d(900.0, 0.0, 0.0))   // where the leading edge is then
 * val frames = swing.stepsBy(5.0)                // 19 steps of 5°, 20 poses
 * val json = swing.toJson()                      // {"type":"rotation","point":[0,0,0],"axis":[0,0,1],"from":0,"to":95}
 * ```
 *
 * Nothing here knows about meshes, renderers or units: the part's geometry is whatever you transform with the pose,
 * in whatever units you model in.
 */

/**
 * One rigid motion of a part, between two values of its own coordinate: an angle for a [Rotation], a distance for a
 * [Slide].
 *
 * The part is modelled at **value 0** (a door closed, a drawer shut): [poseAt] of 0 is the identity, and every other
 * pose maps those rest coordinates to where the part is at that value. The motion runs from [from] to [to]; the
 * normalised parameter `t` in `0..1` runs along it, so [pose] of 0 is the pose at [from] and [pose] of 1 the pose at
 * [to]. A range need not start at 0 (a door already ajar), and [to] may be less than [from] (a motion run backwards).
 *
 * Poses are column-major [Matrix4d]s with the translation in `m30`, `m31`, `m32`, as everywhere else in Field;
 * [poseArray] gives the same 16 numbers as a `DoubleArray` and [poseF] a float [Matrix4f] for a renderer.
 *
 * Motions are immutable; the vectors they hold are copies of the ones they were built from.
 */
@Serializable
sealed class RigidMotion {
    /** The value the motion starts at: degrees for a [Rotation], model units for a [Slide]. */
    abstract val from: Double

    /** The value the motion ends at, in the same units as [from]. */
    abstract val to: Double

    /** The pose at a value of the motion's coordinate (an angle in degrees, or a distance), whether or not it lies in the range. */
    abstract fun poseAt(value: Double): Matrix4d

    /**
     * An upper bound on how far any point within [radius] of [center] moves between the values [a] and [b].
     *
     * For a slide that is the distance travelled; for a rotation it is the arc length the farthest such point can
     * sweep, `r·|b − a|` in radians, where `r` bounds the point's distance from the axis. Clearance checks use it to
     * decide where a sweep between two samples needs a finer step.
     */
    abstract fun maxTravel(center: Vector3dc, radius: Double, a: Double, b: Double): Double

    /**
     * An upper bound on how far any point in the convex hull of [xyz] (flat `x, y, z` triples) moves between the
     * values [a] and [b]: the tight form of the ball bound above, for a part whose vertices you have.
     *
     * For a rotation it is the largest vertex distance from the axis times the angle in radians (distance from an
     * axis is convex, so no point of the hull lies farther out than its farthest vertex).
     */
    abstract fun maxTravel(xyz: DoubleArray, a: Double, b: Double): Double

    /** The same motion, run from [to] back to [from]. */
    abstract fun reversed(): RigidMotion

    /** The same motion over a different range. */
    abstract fun withRange(from: Double, to: Double): RigidMotion

    /** The value at the normalised parameter [t]: [from] at 0, [to] at 1, linear between (and beyond). */
    fun valueAt(t: Double): Double = from + (to - from) * t

    /** The normalised parameter of [value]: 0 at [from], 1 at [to]. A zero-length range maps everything to 0. */
    fun parameterOf(value: Double): Double = if (to == from) 0.0 else (value - from) / (to - from)

    /** The pose at the normalised parameter [t] in `0..1`. */
    fun pose(t: Double): Matrix4d = poseAt(valueAt(t))

    /** The pose at [t] as 16 column-major doubles: the form a JSON viewer or a GPU buffer takes. */
    fun poseArray(t: Double): DoubleArray = pose(t).get(DoubleArray(16))

    /** The pose at [t] narrowed to a float [Matrix4f], for a renderer. */
    fun poseF(t: Double): Matrix4f = pose(t).toMatrix4f()

    /** Where the rest-pose point [p] is at the normalised parameter [t]. */
    fun pointAt(t: Double, p: Vector3dc): Vector3d = pose(t).transformPoint(p)

    /**
     * The motion sampled at [count] equal steps: `count + 1` [MotionStep]s from [from] to [to] inclusive.
     *
     * **Throws** `IllegalArgumentException` when [count] is less than 1.
     */
    fun steps(count: Int): List<MotionStep> {
        require(count >= 1) { "a motion needs at least one step, got $count" }
        return (0..count).map { i ->
            val t = i.toDouble() / count
            val value = if (i == count) to else valueAt(t)
            MotionStep(i, t, value, poseAt(value))
        }
    }

    /**
     * The motion sampled so no step is longer than [maxStep] in the motion's own units (degrees or distance): the
     * range is divided into `ceil(|to − from| / maxStep)` equal steps, at least one.
     *
     * `swing.stepsBy(1.0)` is "every degree"; a 95° swing gives 96 poses.
     */
    fun stepsBy(maxStep: Double): List<MotionStep> {
        require(maxStep > 0.0 && maxStep.isFinite()) { "maxStep must be positive and finite, got $maxStep" }
        return steps(max(1, ceil(abs(to - from) / maxStep - 1e-9).toInt()))
    }

    /** This motion as JSON, `{"type":"rotation"|"slide", ...}`; [fromJson] reads it back. */
    fun toJson(): String = motionJson.encodeToString(serializer(), this)

    companion object {
        /**
         * A rotation about the axis through [point] along [axis], between [from] and [to] degrees.
         *
         * Positive angles turn by the right-hand rule about [axis]: with the axis pointing up `+z`, a positive angle
         * turns `+x` towards `+y`. [axis] need not be unit length, and is normalised.
         */
        fun rotation(point: Vector3dc, axis: Vector3dc, from: Double, to: Double): Rotation =
            Rotation(Vector3d(point), unit(axis, "rotation axis"), from, to)

        /** A rotation with float vectors, as `vec(...)` builds them. */
        fun rotation(point: Vector3fc, axis: Vector3fc, from: Double, to: Double): Rotation =
            rotation(point.toDouble(), axis.toDouble(), from, to)

        /**
         * A slide along [direction], between the distances [from] and [to] (in model units).
         *
         * [direction] is normalised, so [from] and [to] are true distances along it.
         */
        fun slide(direction: Vector3dc, from: Double, to: Double): Slide =
            Slide(unit(direction, "slide direction"), from, to)

        /** A slide with a float direction, as `vec(...)` builds it. */
        fun slide(direction: Vector3fc, from: Double, to: Double): Slide = slide(direction.toDouble(), from, to)

        /** Read a motion written by [toJson]. */
        fun fromJson(json: String): RigidMotion = motionJson.decodeFromString(serializer(), json)

        private fun unit(v: Vector3dc, what: String): Vector3d {
            val n = sqrt(v.x() * v.x() + v.y() * v.y() + v.z() * v.z())
            require(n.isFinite() && n > 1e-300) { "$what must be a non-zero finite vector, got $v" }
            return Vector3d(v.x() / n, v.y() / n, v.z() / n)
        }
    }
}

/**
 * A rotation of a part about the axis through [point] along the unit vector [axis], from [from] to [to] degrees.
 *
 * Build one with [RigidMotion.rotation], which normalises the axis. JSON:
 * `{"type":"rotation","point":[x,y,z],"axis":[x,y,z],"from":deg,"to":deg}`.
 */
@Serializable
@SerialName("rotation")
class Rotation internal constructor(
    /** A point on the axis. */
    @Serializable(with = Vector3dArraySerializer::class) val point: Vector3dc,
    /** The axis direction: unit length when built by [RigidMotion.rotation]; poses use its unit direction either way. */
    @Serializable(with = Vector3dArraySerializer::class) val axis: Vector3dc,
    override val from: Double,
    override val to: Double,
) : RigidMotion() {
    init {
        require(from.isFinite() && to.isFinite()) { "rotation range must be finite, got $from..$to" }
        require(axis.x() * axis.x() + axis.y() * axis.y() + axis.z() * axis.z() > 1e-300) { "rotation axis must be non-zero" }
    }

    override fun poseAt(value: Double): Matrix4d {
        val a = Math.toRadians(value)
        val c = cos(a); val s = sin(a); val k = 1.0 - c
        val n = sqrt(axis.x() * axis.x() + axis.y() * axis.y() + axis.z() * axis.z())
        val x = axis.x() / n; val y = axis.y() / n; val z = axis.z() / n
        // Rodrigues: R = cI + s[axis]x + (1-c) axis axisᵀ; stored column-major (mCR = column C, row R).
        val r00 = c + x * x * k; val r01 = y * x * k + z * s; val r02 = z * x * k - y * s
        val r10 = x * y * k - z * s; val r11 = c + y * y * k; val r12 = z * y * k + x * s
        val r20 = x * z * k + y * s; val r21 = y * z * k - x * s; val r22 = c + z * z * k
        // p' = R (p - point) + point, so the translation is point - R point.
        val px = point.x(); val py = point.y(); val pz = point.z()
        val tx = px - (r00 * px + r10 * py + r20 * pz)
        val ty = py - (r01 * px + r11 * py + r21 * pz)
        val tz = pz - (r02 * px + r12 * py + r22 * pz)
        return Matrix4d(r00, r01, r02, 0.0, r10, r11, r12, 0.0, r20, r21, r22, 0.0, tx, ty, tz, 1.0)
    }

    override fun maxTravel(center: Vector3dc, radius: Double, a: Double, b: Double): Double {
        // Distance of the ball's centre from the axis, plus its radius, bounds every point's distance from the axis.
        val dx = center.x() - point.x(); val dy = center.y() - point.y(); val dz = center.z() - point.z()
        val n = sqrt(axis.x() * axis.x() + axis.y() * axis.y() + axis.z() * axis.z())
        val along = (dx * axis.x() + dy * axis.y() + dz * axis.z()) / n
        val off = sqrt(max(0.0, dx * dx + dy * dy + dz * dz - along * along))
        return (off + radius) * Math.toRadians(abs(b - a))
    }

    override fun maxTravel(xyz: DoubleArray, a: Double, b: Double): Double = radiusOf(xyz) * Math.toRadians(abs(b - a))

    /** The largest distance of any of the points [xyz] (flat triples) from this rotation's axis. */
    fun radiusOf(xyz: DoubleArray): Double {
        val n = sqrt(axis.x() * axis.x() + axis.y() * axis.y() + axis.z() * axis.z())
        val ux = axis.x() / n; val uy = axis.y() / n; val uz = axis.z() / n
        var best = 0.0
        var i = 0
        while (i + 2 < xyz.size) {
            val dx = xyz[i] - point.x(); val dy = xyz[i + 1] - point.y(); val dz = xyz[i + 2] - point.z()
            val along = dx * ux + dy * uy + dz * uz
            best = max(best, dx * dx + dy * dy + dz * dz - along * along)
            i += 3
        }
        return sqrt(best)
    }

    override fun reversed(): Rotation = Rotation(point, axis, to, from)

    override fun withRange(from: Double, to: Double): Rotation = Rotation(point, axis, from, to)

    override fun equals(other: Any?): Boolean =
        other is Rotation && point == other.point && axis == other.axis && from == other.from && to == other.to

    override fun hashCode(): Int = ((point.hashCode() * 31 + axis.hashCode()) * 31 + from.hashCode()) * 31 + to.hashCode()

    override fun toString(): String = "Rotation(about $axis through $point, $from° to $to°)"
}

/**
 * A slide of a part along the unit vector [direction], from [from] to [to] (in model units).
 *
 * Build one with [RigidMotion.slide], which normalises the direction. JSON:
 * `{"type":"slide","direction":[x,y,z],"from":d,"to":d}`.
 */
@Serializable
@SerialName("slide")
class Slide internal constructor(
    /** The direction of travel: unit length when built by [RigidMotion.slide]; poses use its unit direction either way. */
    @Serializable(with = Vector3dArraySerializer::class) val direction: Vector3dc,
    override val from: Double,
    override val to: Double,
) : RigidMotion() {
    init {
        require(from.isFinite() && to.isFinite()) { "slide range must be finite, got $from..$to" }
        require(direction.x() * direction.x() + direction.y() * direction.y() + direction.z() * direction.z() > 1e-300) { "slide direction must be non-zero" }
    }

    override fun poseAt(value: Double): Matrix4d {
        val n = sqrt(direction.x() * direction.x() + direction.y() * direction.y() + direction.z() * direction.z())
        val d = value / n
        return Matrix4d().translation(direction.x() * d, direction.y() * d, direction.z() * d)
    }

    override fun maxTravel(center: Vector3dc, radius: Double, a: Double, b: Double): Double = abs(b - a)

    override fun maxTravel(xyz: DoubleArray, a: Double, b: Double): Double = abs(b - a)

    override fun reversed(): Slide = Slide(direction, to, from)

    override fun withRange(from: Double, to: Double): Slide = Slide(direction, from, to)

    override fun equals(other: Any?): Boolean =
        other is Slide && direction == other.direction && from == other.from && to == other.to

    override fun hashCode(): Int = (direction.hashCode() * 31 + from.hashCode()) * 31 + to.hashCode()

    override fun toString(): String = "Slide(along $direction, $from to $to)"
}

/** One sample of a motion: its [index], normalised parameter [t], the motion [value] there, and the [pose]. */
class MotionStep(
    /** Position in the sampled list, from 0. */
    val index: Int,
    /** Normalised parameter, 0 at the motion's start and 1 at its end. */
    val t: Double,
    /** The motion's own coordinate here: degrees for a rotation, distance for a slide. */
    val value: Double,
    /** The part's pose here, rest coordinates to world. */
    val pose: Matrix4dc,
) {
    /** The index, parameter and value, for `println`. */
    override fun toString(): String = "MotionStep(#$index, t=%.4f, value=%.4f)".format(t, value)
}

/** Apply this affine pose to the point [p], returning a new vector. */
fun Matrix4dc.transformPoint(p: Vector3dc): Vector3d = Vector3d(
    m00() * p.x() + m10() * p.y() + m20() * p.z() + m30(),
    m01() * p.x() + m11() * p.y() + m21() * p.z() + m31(),
    m02() * p.x() + m12() * p.y() + m22() * p.z() + m32(),
)

/** This double matrix narrowed to Field's float [Matrix4f]. */
fun Matrix4dc.toMatrix4f(): Matrix4f = Matrix4f(
    m00().toFloat(), m01().toFloat(), m02().toFloat(), m03().toFloat(),
    m10().toFloat(), m11().toFloat(), m12().toFloat(), m13().toFloat(),
    m20().toFloat(), m21().toFloat(), m22().toFloat(), m23().toFloat(),
    m30().toFloat(), m31().toFloat(), m32().toFloat(), m33().toFloat(),
)

private fun Vector3fc.toDouble(): Vector3d = Vector3d(x().toDouble(), y().toDouble(), z().toDouble())

/** The JSON settings motions are written with: a `"type"` discriminator, defaults written, unknown keys ignored. */
internal val motionJson = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/** Writes a [Vector3dc] as a three-number JSON array, `[x, y, z]`. */
internal object Vector3dArraySerializer : KSerializer<Vector3dc> {
    private val delegate = DoubleArraySerializer()
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: Vector3dc) =
        encoder.encodeSerializableValue(delegate, doubleArrayOf(value.x(), value.y(), value.z()))

    override fun deserialize(decoder: Decoder): Vector3dc {
        val a = decoder.decodeSerializableValue(delegate)
        require(a.size == 3) { "a vector needs three numbers, got ${a.size}" }
        return Vector3d(a[0], a[1], a[2])
    }
}
